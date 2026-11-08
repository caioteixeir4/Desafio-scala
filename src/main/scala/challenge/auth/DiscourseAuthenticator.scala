package challenge.auth

import scala.collection.mutable

import challenge.auth.AuthError.*
import challenge.http.{Http, HttpFailure, Session}
import challenge.parse.LoginParser
import sttp.client3.*

/** Login do Discourse: csrf -> POST /session -> confirmacao em /session/current.json.
  *
  * Com `totpSecret`, o POST /session leva tambem `second_factor_method=1` (TOTP) e `second_factor_token`
  * (codigo de 6 digitos gerado na hora). Sem segredo, o login e o mesmo de sempre.
  */
final class DiscourseAuthenticator(
    username: String,
    password: String,
    http: Http,
    totpSecret: Option[String] = None,
    clock: () => Long = () => System.currentTimeMillis()
) extends Authenticator:

  private val base = http.baseUrl

  def login(): Either[AuthError, Session] =
    // Sessao do zero a cada execucao: cookie jar vazio.
    val jar = mutable.Map.empty[String, String]

    for
      csrfRes <- send(jar, basicRequest.get(uri"$base/session/csrf"))
      csrf    <- LoginParser.parseCsrf(csrfRes.code.code, csrfRes.body)
      // O codigo TOTP e gerado depois do csrf, o mais perto possivel do envio (a janela dura 30 s).
      form    <- loginForm()
      loginRes <- send(
        jar,
        basicRequest
          .post(uri"$base/session")
          .header("X-CSRF-Token", csrf)
          .body(form)
      )
      _     <- LoginParser.parseLogin(loginRes.code.code, loginRes.body).left.map(explainSecondFactor)
      // Evidencia positiva: so um usuario logado recebe 200 com current_user aqui.
      meRes <- send(jar, basicRequest.get(uri"$base/session/current.json"))
      user  <- LoginParser.parseCurrentUser(meRes.code.code, meRes.body)
    yield Session(user, jar.toMap)

  private def loginForm(): Either[AuthError, Map[String, String]] =
    val fields = Map("login" -> username, "password" -> password)
    totpSecret match
      case None => Right(fields)
      case Some(secret) =>
        Totp
          .generate(secret, clock())
          .map(code => fields ++ Map("second_factor_method" -> "1", "second_factor_token" -> code))
          .left
          .map(why => UnexpectedResponse(s"Segredo TOTP invalido: $why"))

  /** O Discourse responde igual a "faltou o codigo" e "codigo errado"; a mensagem diz qual dos dois e o nosso caso. */
  private def explainSecondFactor(err: AuthError): AuthError = err match
    case SecondFactorRequired(msg) if totpSecret.isDefined =>
      SecondFactorRequired(
        s"Codigo TOTP rejeitado ($msg). Confira DISCOURSE_TOTP_SECRET e o relogio da maquina; " +
          "o mesmo codigo nao vale duas vezes, entao espere cerca de 30 s se o robo rodou agora ha pouco"
      )
    case SecondFactorRequired(msg) =>
      SecondFactorRequired(
        s"$msg. A conta exige segundo fator: defina DISCOURSE_TOTP_SECRET (app autenticador). " +
          "Chave de seguranca e passkey nao sao suportadas"
      )
    case other => other

  /** csrf novo -> DELETE /session/{usuario} -> confirmacao: /session/current.json deve voltar a ser anonimo (404). */
  def logout(session: Session): Either[AuthError, Unit] =
    val jar = mutable.Map.from(session.cookies)

    for
      csrfRes <- send(jar, basicRequest.get(uri"$base/session/csrf"))
      csrf    <- LoginParser.parseCsrf(csrfRes.code.code, csrfRes.body)
      delRes <- send(
        jar,
        basicRequest.delete(uri"$base/session/${session.username}").header("X-CSRF-Token", csrf)
      )
      _     <- LoginParser.parseLogout(delRes.code.code)
      meRes <- send(jar, basicRequest.get(uri"$base/session/current.json"))
      _     <- confirmLoggedOut(meRes.code.code, meRes.body)
    yield ()

  private def confirmLoggedOut(status: Int, body: String): Either[AuthError, Unit] =
    LoginParser.parseCurrentUser(status, body) match
      case Left(_: NotAuthenticated) => Right(()) // evidencia positiva de que a sessao acabou
      case Left(other)               => Left(other)
      case Right(user)               => Left(UnexpectedResponse(s"Logout sem efeito: a sessao de $user continua ativa"))

  private def send(
      jar: mutable.Map[String, String],
      req: Request[Either[String, String], Any]
  ): Either[AuthError, Response[String]] =
    http.send(jar, req).left.map {
      case n: HttpFailure.Network => SiteUnavailable(n.message)
      case other                  => UnexpectedResponse(other.message)
    }

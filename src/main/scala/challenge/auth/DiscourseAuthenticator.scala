package challenge.auth

import scala.collection.mutable

import challenge.auth.AuthError.*
import challenge.http.{Http, HttpFailure, Session}
import challenge.parse.LoginParser
import sttp.client3.*

/** Login do Discourse: csrf -> POST /session -> confirmacao em /session/current.json. */
final class DiscourseAuthenticator(
    username: String,
    password: String,
    http: Http
) extends Authenticator:

  private val base = http.baseUrl

  def login(): Either[AuthError, Session] =
    // Sessao do zero a cada execucao: cookie jar vazio.
    val jar = mutable.Map.empty[String, String]

    for
      csrfRes <- send(jar, basicRequest.get(uri"$base/session/csrf"))
      csrf    <- LoginParser.parseCsrf(csrfRes.code.code, csrfRes.body)
      loginRes <- send(
        jar,
        basicRequest
          .post(uri"$base/session")
          .header("X-CSRF-Token", csrf)
          .body(Map("login" -> username, "password" -> password))
      )
      _     <- LoginParser.parseLogin(loginRes.code.code, loginRes.body)
      // Evidencia positiva: so um usuario logado recebe 200 com current_user aqui.
      meRes <- send(jar, basicRequest.get(uri"$base/session/current.json"))
      user  <- LoginParser.parseCurrentUser(meRes.code.code, meRes.body)
    yield Session(user, jar.toMap)

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

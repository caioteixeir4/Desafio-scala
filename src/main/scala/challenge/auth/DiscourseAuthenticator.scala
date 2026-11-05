package challenge.auth

import scala.collection.mutable

import challenge.auth.AuthError.*
import challenge.http.{Http, Session}
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

  private def send(
      jar: mutable.Map[String, String],
      req: Request[Either[String, String], Any]
  ): Either[AuthError, Response[String]] =
    http.send(jar, req).left.map(e => SiteUnavailable(s"Falha de rede: ${e.getMessage}"))

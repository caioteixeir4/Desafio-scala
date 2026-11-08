package challenge.auth

import scala.collection.mutable

import challenge.auth.AuthError.*
import challenge.config.Config
import challenge.http.{Http, RateLimiter}
import sttp.client3.{Response, StringBody}
import sttp.client3.testing.SttpBackendStub
import sttp.model.StatusCode

class DiscourseAuthenticatorSpec extends munit.FunSuite:

  private val config = Config(
    baseUrl = "https://forum.test",
    username = "login@exemplo.test",
    password = "senha-de-teste",
    userAgent = "test-bot/1.0 (teste)",
    minRequestIntervalMs = 0,
    outputPath = "unused.json",
    rawDumpDir = None
  )

  /** Servidor simulado com estado: so devolve `current_user` enquanto estiver logado. */
  private final class FakeForum(
      var loggedIn: Boolean = false,
      logoutWorks: Boolean = true,
      deleteStatus: Int = 200,
      totpSecret: Option[String] = None,
      rejectSecondFactor: Boolean = false // o POST /session responde "invalid_second_factor", como o Discourse
  ):
    val calls = mutable.ListBuffer.empty[String]
    var csrfSeenOnDelete: Option[String] = None
    var loginBody: String                = ""

    val backend = SttpBackendStub.synchronous.whenAnyRequest.thenRespondF { req =>
      val path = req.uri.toString.stripPrefix("https://forum.test")
      calls += s"${req.method.method} $path"
      (req.method.method, path) match
        case ("GET", "/session/csrf") => Response.ok("""{"csrf":"tok-123"}""")
        case ("POST", "/session") =>
          loginBody = req.body match
            case StringBody(s, _, _) => s
            case _                   => ""
          if rejectSecondFactor then
            Response.ok("""{"error":"Codigo invalido","reason":"invalid_second_factor","totp_enabled":true}""")
          else
            loggedIn = true
            Response.ok("""{"user":{"username":"Caio"}}""")
        case ("GET", "/session/current.json") =>
          if loggedIn then Response.ok("""{"current_user":{"username":"Caio"}}""")
          else Response("", StatusCode.NotFound)
        case ("DELETE", p) if p.startsWith("/session/") =>
          csrfSeenOnDelete = req.header("X-CSRF-Token")
          if deleteStatus < 400 && logoutWorks then loggedIn = false
          Response("{}", StatusCode.unsafeApply(deleteStatus))
        case _ => Response("", StatusCode.NotFound)
    }

    // Relogio fixo em T=59 s: com o segredo da RFC 6238 o codigo e 287082.
    val auth = DiscourseAuthenticator(
      config.username,
      config.password,
      Http(config, backend, RateLimiter(0)),
      totpSecret,
      clock = () => 59_000L
    )

  private val rfcSecret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"

  test("2FA: com segredo, o POST /session leva method=1 e o codigo TOTP; sem segredo, nao leva nada disso") {
    val with2fa = FakeForum(totpSecret = Some(rfcSecret))
    assertEquals(with2fa.auth.login().map(_.username), Right("Caio"))
    assert(with2fa.loginBody.contains("second_factor_method=1"), with2fa.loginBody)
    assert(with2fa.loginBody.contains("second_factor_token=287082"), with2fa.loginBody)

    val without = FakeForum()
    without.auth.login()
    assert(!without.loginBody.contains("second_factor"), without.loginBody)
  }

  test("2FA: sem segredo e a conta exige, o erro e SecondFactorRequired e diz o que fazer") {
    val forum = FakeForum(rejectSecondFactor = true)
    val err   = forum.auth.login().left.toOption.get
    assert(err.isInstanceOf[SecondFactorRequired])
    assert(err.message.contains("DISCOURSE_TOTP_SECRET"), err.message)
  }

  test("2FA: com segredo e o servidor rejeita o codigo, a mensagem diz que o codigo foi rejeitado (nao que faltou)") {
    val forum = FakeForum(totpSecret = Some(rfcSecret), rejectSecondFactor = true)
    val err   = forum.auth.login().left.toOption.get
    assert(err.isInstanceOf[SecondFactorRequired])
    assert(err.message.contains("rejeitado"), err.message)
    assert(!err.message.contains(rfcSecret))
    assert(!err.message.contains("287082"))
  }

  test("login: csrf -> POST /session -> confirmacao em /session/current.json") {
    val forum = FakeForum()
    assertEquals(forum.auth.login().map(_.username), Right("Caio"))
    assertEquals(forum.calls.toList, List("GET /session/csrf", "POST /session", "GET /session/current.json"))
  }

  test("logout: pega csrf novo, envia DELETE /session/{usuario} e confirma que a sessao acabou") {
    val forum   = FakeForum()
    val session = forum.auth.login().toOption.get
    forum.calls.clear()

    assertEquals(forum.auth.logout(session), Right(()))
    assertEquals(
      forum.calls.toList,
      List("GET /session/csrf", "DELETE /session/Caio", "GET /session/current.json")
    )
    assertEquals(forum.csrfSeenOnDelete, Some("tok-123"))
    assert(!forum.loggedIn)
  }

  test("logout: se o servidor ignora o DELETE, a evidencia nega e vira erro (nao finge sucesso)") {
    val forum   = FakeForum(logoutWorks = false)
    val session = forum.auth.login().toOption.get
    assert(forum.auth.logout(session).left.exists(_.isInstanceOf[UnexpectedResponse]))
  }

  test("logout: HTTP 500 no DELETE e SiteUnavailable") {
    val forum   = FakeForum(deleteStatus = 500)
    val session = forum.auth.login().toOption.get
    assert(forum.auth.logout(session).left.exists(_.isInstanceOf[SiteUnavailable]))
  }

  test("logout: HTTP 429 no DELETE e RateLimited") {
    val forum   = FakeForum(deleteStatus = 429)
    val session = forum.auth.login().toOption.get
    assert(forum.auth.logout(session).left.exists(_.isInstanceOf[RateLimited]))
  }

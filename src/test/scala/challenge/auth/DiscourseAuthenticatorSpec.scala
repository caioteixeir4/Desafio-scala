package challenge.auth

import scala.collection.mutable

import challenge.auth.AuthError.*
import challenge.config.Config
import challenge.http.{Http, RateLimiter}
import sttp.client3.Response
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
  private final class FakeForum(var loggedIn: Boolean = false, logoutWorks: Boolean = true, deleteStatus: Int = 200):
    val calls = mutable.ListBuffer.empty[String]
    var csrfSeenOnDelete: Option[String] = None

    val backend = SttpBackendStub.synchronous.whenAnyRequest.thenRespondF { req =>
      val path = req.uri.toString.stripPrefix("https://forum.test")
      calls += s"${req.method.method} $path"
      (req.method.method, path) match
        case ("GET", "/session/csrf") => Response.ok("""{"csrf":"tok-123"}""")
        case ("POST", "/session") =>
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

    val auth = DiscourseAuthenticator(config.username, config.password, Http(config, backend, RateLimiter(0)))

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

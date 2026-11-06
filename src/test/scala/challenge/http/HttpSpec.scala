package challenge.http

import scala.collection.mutable

import challenge.config.Config
import sttp.client3.*
import sttp.client3.testing.SttpBackendStub
import sttp.model.{StatusCode, Uri}

class HttpSpec extends munit.FunSuite:

  private val config = Config(
    baseUrl = "https://forum.test",
    username = "u",
    password = "p",
    userAgent = "test-bot/1.0 (teste)",
    minRequestIntervalMs = 0,
    outputPath = "unused.json",
    rawDumpDir = None
  )

  private val robotsBody = "User-agent: *\nDisallow: /session\nDisallow: /search\n"

  private def http(robotsStatus: Int = 200, robotsText: String = robotsBody, exceptions: Set[String] = Set("/session")) =
    val requested = mutable.ListBuffer.empty[String]
    val backend = SttpBackendStub.synchronous.whenAnyRequest.thenRespondF { req =>
      val path = req.uri.toString.stripPrefix("https://forum.test")
      requested += path
      if path == "/robots.txt" then Response(robotsText, StatusCode.unsafeApply(robotsStatus))
      else Response.ok("{}")
    }
    (Http(config, backend, RateLimiter(0), exceptions), requested)

  private def get(h: Http, path: String) = h.send(mutable.Map.empty, basicRequest.get(Uri.unsafeParse(s"https://forum.test$path")))

  test("caminho bloqueado nao chega a ser requisitado") {
    val (h, requested) = http()
    assert(h.loadRobots().isRight)
    assert(get(h, "/search?q=x").left.exists(_.isInstanceOf[HttpFailure.BlockedByRobots]))
    assertEquals(requested.toList, List("/robots.txt"))
  }

  test("caminho permitido segue normalmente") {
    val (h, _) = http()
    h.loadRobots()
    assert(get(h, "/u/x/bookmarks.json").isRight)
  }

  test("/session passa pela excecao deliberada do login") {
    val (h, _) = http()
    h.loadRobots()
    assert(get(h, "/session/csrf").isRight)
  }

  test("sem a excecao, /session e bloqueado") {
    val (h, _) = http(exceptions = Set.empty)
    h.loadRobots()
    assert(get(h, "/session/csrf").left.exists(_.isInstanceOf[HttpFailure.BlockedByRobots]))
  }

  test("robots.txt 404 libera tudo; 5xx falha por cautela") {
    val (notFound, _) = http(robotsStatus = 404, exceptions = Set.empty)
    assert(notFound.loadRobots().isRight)
    assert(get(notFound, "/session").isRight)

    val (down, _) = http(robotsStatus = 503)
    assert(down.loadRobots().left.exists(_.isInstanceOf[HttpFailure.RobotsUnavailable]))
  }

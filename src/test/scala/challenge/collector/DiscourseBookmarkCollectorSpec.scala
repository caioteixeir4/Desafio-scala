package challenge.collector

import challenge.config.Config
import challenge.http.{Http, RateLimiter, Session}
import sttp.client3.Response
import sttp.client3.testing.SttpBackendStub
import sttp.model.StatusCode

class DiscourseBookmarkCollectorSpec extends munit.FunSuite:

  private val config = Config(
    baseUrl = "https://forum.test",
    username = "u",
    password = "p",
    userAgent = "test-agent",
    minRequestIntervalMs = 0,
    outputPath = "unused.json",
    rawDumpDir = None
  )
  private val session = Session("u", Map("_forum_session" -> "x"))

  private def item(n: Int) =
    s"""{"title":"T$n","bookmarkable_url":"/t/t$n/$n","user":{"username":"a$n"}}"""

  private def page(items: List[Int], next: Option[String]) =
    val more = next.map(n => s""","more_bookmarks_url":"$n"""").getOrElse("")
    s"""{"user_bookmark_list":{"bookmarks":[${items.map(item).mkString(",")}]$more}}"""

  /** Servidor simulado: devolve as paginas na ordem em que sao pedidas e registra os caminhos. */
  private def collectWith(pages: Map[String, String]) =
    val requested = scala.collection.mutable.ListBuffer.empty[String]
    val backend = SttpBackendStub.synchronous.whenAnyRequest.thenRespondF { req =>
      val path = req.uri.toString.stripPrefix("https://forum.test")
      requested += path
      pages.get(path) match
        case Some(body) => Response.ok(body)
        case None       => Response("", StatusCode.NotFound)
    }
    val http      = Http(config, backend, RateLimiter(0))
    val collector = DiscourseBookmarkCollector(http)
    (collector.collectAll(session), requested.toList)

  test("segue as paginas ate a resposta nao indicar mais, sem numero fixo") {
    val first = "/u/u/bookmarks.json"
    val (result, requested) = collectWith(
      Map(
        first                       -> page(List(1, 2), Some("/u/u/bookmarks.json?page=1")),
        "/u/u/bookmarks.json?page=1" -> page(List(3, 4), Some("/u/u/bookmarks.json?page=2")),
        "/u/u/bookmarks.json?page=2" -> page(List(5), None)
      )
    )
    assertEquals(result.map(_.map(_.title)), Right(List("T1", "T2", "T3", "T4", "T5")))
    assertEquals(requested.size, 3)
  }

  test("uma unica pagina sem 'proxima' faz uma unica requisicao") {
    val (result, requested) = collectWith(Map("/u/u/bookmarks.json" -> page(List(1, 2), None)))
    assertEquals(result.map(_.size), Right(2))
    assertEquals(requested.size, 1)
  }

  test("pagina cheia seguida de pagina vazia termina sem erro") {
    val (result, _) = collectWith(
      Map(
        "/u/u/bookmarks.json"        -> page(List(1, 2), Some("/u/u/bookmarks.json?page=1")),
        "/u/u/bookmarks.json?page=1" -> page(Nil, Some("/u/u/bookmarks.json?page=2"))
      )
    )
    assertEquals(result.map(_.size), Right(2))
  }

  test("link de proxima pagina repetido nao causa loop infinito") {
    val loop = "/u/u/bookmarks.json"
    val (result, requested) = collectWith(Map(loop -> page(List(1), Some(loop))))
    assertEquals(result.map(_.size), Right(1))
    assertEquals(requested.size, 1)
  }

  test("HTTP 403 na coleta vira erro explicito") {
    val backend = SttpBackendStub.synchronous.whenAnyRequest.thenRespond("", StatusCode.Forbidden)
    val collector = DiscourseBookmarkCollector(Http(config, backend, RateLimiter(0)))
    assert(collector.collectAll(session).left.exists(_.contains("403")))
  }

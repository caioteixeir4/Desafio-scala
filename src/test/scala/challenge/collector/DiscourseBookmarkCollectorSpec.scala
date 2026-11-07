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

  test("paginacao com as respostas REAIS do site: segue ?page=1 e para na ultima (40 bookmarks)") {
    def res(name: String) =
      val src = scala.io.Source.fromResource(s"fixtures/$name")
      try src.mkString finally src.close()

    val (result, requested) = collectWith(
      Map(
        "/u/u/bookmarks.json"        -> res("bookmarks-page-real-p0.json").replace("/u/Caio_Teixeira/", "/u/u/"),
        "/u/u/bookmarks.json?page=1" -> res("bookmarks-page-real-p1.json")
      )
    )
    assertEquals(result.map(_.size), Right(40))
    assertEquals(requested, List("/u/u/bookmarks.json", "/u/u/bookmarks.json?page=1"))
  }

  // --- HTTP 429 / Retry-After ---

  private val firstPath = "/u/u/bookmarks.json"

  /** Servidor que responde `responses` na ordem; registra as esperas pedidas ao `sleep` injetado. */
  private def collectWithResponses(responses: List[Response[String]], maxWaitMs: Long = 60_000L) =
    val queue  = scala.collection.mutable.Queue.from(responses)
    val sleeps = scala.collection.mutable.ListBuffer.empty[Long]
    val backend = SttpBackendStub.synchronous.whenAnyRequest.thenRespondF(queue.dequeue())
    val collector = DiscourseBookmarkCollector(
      Http(config, backend, RateLimiter(0)),
      maxWaitMs = maxWaitMs,
      sleep = ms => sleeps += ms
    )
    (collector.collectAll(session), sleeps.toList, responses.size - queue.size)

  private def tooMany(retryAfter: Option[String]) =
    val base = Response("", StatusCode.TooManyRequests)
    retryAfter.fold(base)(v => base.copy(headers = Seq(sttp.model.Header("Retry-After", v))))

  test("429 com Retry-After: espera o tempo pedido e tenta de novo ate dar certo") {
    val (result, sleeps, used) = collectWithResponses(
      List(tooMany(Some("2")), tooMany(Some("3")), Response.ok(page(List(1, 2), None)))
    )
    assertEquals(result.map(_.size), Right(2))
    assertEquals(sleeps, List(2000L, 3000L))
    assertEquals(used, 3)
  }

  test("429 sem Retry-After usa a espera padrao") {
    val (result, sleeps, _) = collectWithResponses(List(tooMany(None), Response.ok(page(List(1), None))))
    assertEquals(result.map(_.size), Right(1))
    assertEquals(sleeps, List(5000L))
  }

  test("429 persistente desiste apos o maximo de tentativas, sem laco infinito") {
    val (result, sleeps, used) = collectWithResponses(List.fill(10)(tooMany(Some("1"))))
    assert(result.left.exists(_.contains("429")))
    assertEquals(sleeps.size, 3) // 3 novas tentativas
    assertEquals(used, 4)        // 1 original + 3 repeticoes
  }

  test("Retry-After maior que o maximo: desiste na hora, sem esperar") {
    val (result, sleeps, used) = collectWithResponses(List(tooMany(Some("3600"))), maxWaitMs = 60_000L)
    assert(result.left.exists(_.contains("acima do maximo")))
    assertEquals(sleeps, Nil)
    assertEquals(used, 1)
  }

  test("Retry-After em formato de data HTTP no passado: tenta de novo sem esperar") {
    val (result, sleeps, _) = collectWithResponses(
      List(tooMany(Some("Wed, 21 Oct 2015 07:28:00 GMT")), Response.ok(page(List(1), None)))
    )
    assertEquals(result.map(_.size), Right(1))
    assertEquals(sleeps, List(0L))
  }

  test("HTTP 403 na coleta vira erro explicito") {
    val backend = SttpBackendStub.synchronous.whenAnyRequest.thenRespond("", StatusCode.Forbidden)
    val collector = DiscourseBookmarkCollector(Http(config, backend, RateLimiter(0)))
    assert(collector.collectAll(session).left.exists(_.contains("403")))
  }

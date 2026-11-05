package challenge.parse

class BookmarkParserSpec extends munit.FunSuite:

  // Fixture sintetica baseada no formato esperado; substituir por resposta real (RAW_DUMP_DIR) quando disponivel.
  private val base = "https://meta.discourse.org"

  private def page(next: Option[String]) =
    val more = next.map(n => s""","more_bookmarks_url":"$n"""").getOrElse("")
    s"""{"user_bookmark_list":{"bookmarks":[
       |{"title":"Topico A","bookmarkable_url":"/t/topico-a/1/2","user":{"username":"alice"}},
       |{"title":"Topico B","bookmarkable_url":"https://meta.discourse.org/t/topico-b/3","user":{"username":"bob"}}
       |]$more}}""".stripMargin

  test("extrai titulo, url absoluta e autor") {
    val result = BookmarkParser.parse(page(None), base).toOption.get
    assertEquals(result.bookmarks.map(_.title), List("Topico A", "Topico B"))
    assertEquals(result.bookmarks.map(_.url), List(s"$base/t/topico-a/1/2", "https://meta.discourse.org/t/topico-b/3"))
    assertEquals(result.bookmarks.map(_.author), List("alice", "bob"))
  }

  test("proxima pagina vem da propria resposta") {
    val withNext = BookmarkParser.parse(page(Some("/u/x/bookmarks.json?page=1")), base).toOption.get
    assertEquals(withNext.next, Some("/u/x/bookmarks.json?page=1"))
    assertEquals(BookmarkParser.parse(page(None), base).toOption.get.next, None)
  }

  test("lista vazia e valida") {
    val result = BookmarkParser.parse("""{"user_bookmark_list":{"bookmarks":[]}}""", base)
    assertEquals(result.map(_.bookmarks), Right(Nil))
  }

  test("autor ausente falha explicitamente em vez de inventar valor") {
    val body = """{"user_bookmark_list":{"bookmarks":[{"title":"T","bookmarkable_url":"/t/x/1"}]}}"""
    assert(BookmarkParser.parse(body, base).isLeft)
  }

  test("JSON invalido vira erro") {
    assert(BookmarkParser.parse("<html>", base).isLeft)
  }

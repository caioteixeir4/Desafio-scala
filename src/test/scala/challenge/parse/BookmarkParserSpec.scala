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

  // --- Fixtures REAIS: GET /u/Caio_Teixeira/bookmarks.json (p0, mais novos) e ?page=1 (p1, mais antigos).
  //     p1 capturada em 2026-11-05 (quando a conta tinha 20); p0 em 2026-11-07 (40 no total). ---

  private def resource(name: String): String =
    val src = scala.io.Source.fromResource(s"fixtures/$name")
    try src.mkString finally src.close()

  private lazy val real   = BookmarkParser.parse(resource("bookmarks-page-real-p1.json"), base).fold(e => fail(e), identity)
  private lazy val realP0 = BookmarkParser.parse(resource("bookmarks-page-real-p0.json"), base).fold(e => fail(e), identity)

  test("resposta real, pagina 0: 20 bookmarks e proxima pagina vinda de more_bookmarks_url") {
    assertEquals(realP0.bookmarks.size, 20)
    assertEquals(realP0.next, Some("/u/Caio_Teixeira/bookmarks.json?page=1"))
  }

  test("resposta real, ultima pagina: 20 bookmarks e nenhuma proxima pagina (campo ausente)") {
    assertEquals(real.bookmarks.size, 20)
    assertEquals(real.next, None)
  }

  test("respostas reais: as duas paginas nao tem bookmarks repetidos") {
    val urls = (realP0.bookmarks ++ real.bookmarks).map(_.url)
    assertEquals(urls.distinct.size, 40)
  }

  test("resposta real: primeiro bookmark com titulo original, url absoluta e autor") {
    assertEquals(
      real.bookmarks.head,
      challenge.model.Bookmark(
        title = "Desktop PWA Navigation",
        url = "https://meta.discourse.org/t/desktop-pwa-navigation/241108",
        author = "Don"
      )
    )
  }

  test("resposta real: usa 'title' original, nao o 'fancy_title' traduzido") {
    // A resposta traz fancy_title localizado ("Navegacao PWA de Desktop"); o robo nao deve usa-lo.
    assert(!real.bookmarks.exists(_.title.startsWith("Navega")))
  }

  test("resposta real: todos tem autor e url do forum") {
    assert(real.bookmarks.forall(_.author.nonEmpty))
    assert(real.bookmarks.forall(_.url.startsWith(base + "/t/")))
    assert(real.bookmarks.exists(_.author == "Discourse")) // posts oficiais
  }

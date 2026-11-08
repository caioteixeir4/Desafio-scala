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
    assertEquals(result.bookmarks.map(_.title), List(Some("Topico A"), Some("Topico B")))
    assertEquals(result.bookmarks.map(_.url), List(s"$base/t/topico-a/1/2", "https://meta.discourse.org/t/topico-b/3"))
    assertEquals(result.bookmarks.map(_.author), List(Some("alice"), Some("bob")))
    assertEquals(result.warnings, Nil)
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

  // --- Dados incompletos: nao travam a coleta, nao inventam valor, e avisam ---

  private def bodyOf(items: String*) = s"""{"user_bookmark_list":{"bookmarks":[${items.mkString(",")}]}}"""

  test("conta SEM bookmarks: o site responde {\"bookmarks\":[]} na raiz, e isso e lista vazia valida (nao erro)") {
    // Formato real confirmado no users_controller.rb do Discourse e visto no site com uma conta sem favoritos.
    val result = BookmarkParser.parse("""{"bookmarks":[]}""", base)
    assertEquals(result.map(_.bookmarks), Right(Nil))
    assertEquals(result.map(_.next), Right(None))
    assertEquals(result.map(_.itemCount), Right(0))
    assertEquals(result.map(_.warnings), Right(Nil))
  }

  test("autor ausente: mantem o bookmark com autor null e avisa (nao inventa valor)") {
    val p = BookmarkParser.parse(bodyOf("""{"id":7,"title":"T","bookmarkable_url":"/t/x/1"}"""), base).toOption.get
    assertEquals(p.bookmarks, List(challenge.model.Bookmark(Some("T"), s"$base/t/x/1", None)))
    assertEquals(p.warnings.size, 1)
    assert(p.warnings.head.contains("id=7") && p.warnings.head.contains("user.username"))
  }

  test("autor com 'user' nulo (usuario apagado) tambem vira autor null com aviso") {
    val p = BookmarkParser.parse(bodyOf("""{"title":"T","bookmarkable_url":"/t/x/1","user":null}"""), base).toOption.get
    assertEquals(p.bookmarks.map(_.author), List(None))
    assertEquals(p.warnings.size, 1)
  }

  test("titulo ausente ou vazio: mantem o bookmark com titulo null e avisa") {
    val p = BookmarkParser.parse(
      bodyOf(
        """{"bookmarkable_url":"/t/a/1","user":{"username":"u"}}""",
        """{"title":"  ","bookmarkable_url":"/t/b/2","user":{"username":"u"}}"""
      ),
      base
    ).toOption.get
    assertEquals(p.bookmarks.map(_.title), List(None, None))
    assertEquals(p.warnings.size, 2)
  }

  test("sem URL o item e ignorado com aviso, e os demais da pagina continuam") {
    val p = BookmarkParser.parse(
      bodyOf(
        """{"title":"Sem url","user":{"username":"u"}}""",
        """{"title":"Bom","bookmarkable_url":"/t/ok/1","user":{"username":"u"}}"""
      ),
      base
    ).toOption.get
    assertEquals(p.bookmarks.map(_.title), List(Some("Bom")))
    assertEquals(p.itemCount, 2) // o item ignorado conta como recebido (a paginacao depende disso)
    assertEquals(p.warnings.size, 1)
    assert(p.warnings.head.startsWith("bookmark #1 ignorado"))
  }

  test("item que nem e objeto (ex.: null) e ignorado com aviso, sem quebrar") {
    val p = BookmarkParser.parse(bodyOf("null", """{"title":"Bom","bookmarkable_url":"/t/ok/1","user":{"username":"u"}}"""), base).toOption.get
    assertEquals(p.bookmarks.size, 1)
    assertEquals(p.warnings.size, 1)
  }

  test("bookmark do tipo Post (formato SINTETICO, nao capturado do site): url do post e autor do post") {
    val post =
      """{"id":99,"bookmarkable_type":"Post","title":"Topico do post","bookmarkable_url":"/t/topico-do-post/10/3","user":{"username":"carol"}}"""
    val p = BookmarkParser.parse(bodyOf(post), base).toOption.get
    assertEquals(p.bookmarks, List(challenge.model.Bookmark(Some("Topico do post"), s"$base/t/topico-do-post/10/3", Some("carol"))))
    assertEquals(p.warnings, Nil)
  }

  test("resposta que nao e a lista esperada continua sendo erro da pagina (nao ha o que aproveitar)") {
    assert(BookmarkParser.parse("""{"outra":"coisa"}""", base).isLeft)
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
        title = Some("Desktop PWA Navigation"),
        url = "https://meta.discourse.org/t/desktop-pwa-navigation/241108",
        author = Some("Don")
      )
    )
  }

  test("resposta real: usa 'title' original, nao o 'fancy_title' traduzido") {
    // A resposta traz fancy_title localizado ("Navegacao PWA de Desktop"); o robo nao deve usa-lo.
    assert(!real.bookmarks.exists(_.title.exists(_.startsWith("Navega"))))
  }

  test("resposta real: todos tem titulo, autor e url do forum, e nenhum aviso") {
    assert(real.bookmarks.forall(_.title.nonEmpty))
    assert(real.bookmarks.forall(_.author.nonEmpty))
    assert(real.bookmarks.forall(_.url.startsWith(base + "/t/")))
    assert(real.bookmarks.exists(_.author.contains("Discourse"))) // posts oficiais
    assertEquals(real.warnings, Nil)
    assertEquals(realP0.warnings, Nil)
  }

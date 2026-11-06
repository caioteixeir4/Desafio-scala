package challenge.parse

import scala.io.Source

class RobotsTxtSpec extends munit.FunSuite:

  private val ua = "oystr-code-challenge-bot/0.1 (candidato)"

  // robots.txt real do meta.discourse.org, salvo em 2026-11-05.
  private val real: String =
    val src = Source.fromResource("fixtures/robots.txt")
    try src.mkString finally src.close()

  private val rules = RobotsTxt.parse(real, ua)

  test("bloqueia os caminhos de User-agent: * do robots.txt real") {
    assert(!rules.isAllowed("/session"))
    assert(!rules.isAllowed("/session/csrf"))
    assert(!rules.isAllowed("/my"))
    assert(!rules.isAllowed("/search?q=x"))
    assert(!rules.isAllowed("/admin/users"))
    assert(!rules.isAllowed("/auth/github"))
  }

  test("permite o endpoint de bookmarks usado pelo robo") {
    assert(rules.isAllowed("/u/Caio_Teixeira/bookmarks.json"))
    assert(rules.isAllowed("/u/Caio_Teixeira/bookmarks.json?page=1"))
    assert(rules.isAllowed("/robots.txt"))
  }

  test("curinga e api_key na query") {
    assert(!rules.isAllowed("/t/foo/1.rss"))
    assert(!rules.isAllowed("/latest.json?api_key=abc"))
  }

  test("grupo especifico de outro bot nao se aplica a nos, mas se aplicaria a ele") {
    val mauibot = RobotsTxt.parse(real, "mauibot/1.0")
    assert(!mauibot.isAllowed("/u/x/bookmarks.json")) // Disallow: / para mauibot
    assert(rules.isAllowed("/u/x/bookmarks.json"))
  }

  test("mais longo vence e Allow ganha o empate") {
    val r = RobotsTxt.parse("User-agent: *\nDisallow: /a\nAllow: /a/b\nDisallow: /c\nAllow: /c\n", ua)
    assert(r.isAllowed("/a/b/x"))
    assert(!r.isAllowed("/a/z"))
    assert(r.isAllowed("/c"))
  }

  test("Disallow vazio nao bloqueia nada; sem robots tudo e permitido") {
    assert(RobotsTxt.parse("User-agent: *\nDisallow:\n", ua).isAllowed("/qualquer"))
    assert(RobotsRules.allowAll.isAllowed("/session"))
  }

  test("ancora $ casa so o fim") {
    val r = RobotsTxt.parse("User-agent: *\nDisallow: /*.pdf$\n", ua)
    assert(!r.isAllowed("/a/b.pdf"))
    assert(r.isAllowed("/a/b.pdf?x=1"))
  }

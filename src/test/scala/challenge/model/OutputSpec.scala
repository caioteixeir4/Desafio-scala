package challenge.model

import io.circe.syntax.*

class OutputSpec extends munit.FunSuite:

  test("Output serializa bookmarks com titulo, url e autor") {
    val out = Output(
      source = "https://meta.discourse.org",
      username = "fulano",
      collectedAt = "2026-11-05T00:00:00Z",
      total = 1,
      bookmarks = List(Bookmark(Some("Titulo"), "https://meta.discourse.org/t/x/1", Some("autor")))
    )
    val json = out.asJson
    assertEquals(json.hcursor.downField("total").as[Int], Right(1))
    assertEquals(
      json.hcursor.downField("bookmarks").downN(0).downField("author").as[String],
      Right("autor")
    )
    assertEquals(json.hcursor.downField("warnings").as[List[String]], Right(Nil))
  }

  test("campos ausentes saem como null e os avisos vao no proprio output.json") {
    val out = Output(
      source = "https://meta.discourse.org",
      username = "fulano",
      collectedAt = "2026-11-05T00:00:00Z",
      total = 1,
      bookmarks = List(Bookmark(None, "https://meta.discourse.org/t/x/1", None)),
      warnings = List("pagina 0: bookmark #1: sem 'user.username'; autor gravado como null")
    )
    val b = out.asJson.hcursor.downField("bookmarks").downN(0)
    assert(b.downField("author").focus.exists(_.isNull))
    assert(b.downField("title").focus.exists(_.isNull))
    assertEquals(out.asJson.hcursor.downField("warnings").as[List[String]].map(_.size), Right(1))
  }

package challenge.model

import io.circe.syntax.*

class OutputSpec extends munit.FunSuite:

  test("Output serializa bookmarks com titulo, url e autor") {
    val out = Output(
      source = "https://meta.discourse.org",
      username = "fulano",
      collectedAt = "2026-11-05T00:00:00Z",
      total = 1,
      bookmarks = List(Bookmark("Titulo", "https://meta.discourse.org/t/x/1", "autor"))
    )
    val json = out.asJson
    assertEquals(json.hcursor.downField("total").as[Int], Right(1))
    assertEquals(
      json.hcursor.downField("bookmarks").downN(0).downField("author").as[String],
      Right("autor")
    )
  }

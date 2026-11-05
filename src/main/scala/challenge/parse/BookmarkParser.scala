package challenge.parse

import challenge.model.Bookmark

/** Pagina de bookmarks ja interpretada. Parsing puro: sem rede, testavel com fixtures. */
final case class BookmarkPage(
    bookmarks: List[Bookmark],
    hasMore: Boolean
)

object BookmarkParser:
  // TODO: implementar apos o reconhecimento (RECON.md) do formato real da resposta.
  def parse(body: String, baseUrl: String): Either[String, BookmarkPage] = ???

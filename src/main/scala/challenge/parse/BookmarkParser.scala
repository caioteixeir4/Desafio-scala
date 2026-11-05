package challenge.parse

import challenge.model.Bookmark
import io.circe.{ACursor, Json}

/** Pagina de bookmarks ja interpretada. `next` e o caminho da proxima pagina, se a resposta indicar que ha mais. */
final case class BookmarkPage(
    bookmarks: List[Bookmark],
    next: Option[String]
)

/** Parsing puro de /u/{usuario}/bookmarks.json: sem rede, testavel com fixtures. */
object BookmarkParser:

  def parse(body: String, baseUrl: String): Either[String, BookmarkPage] =
    for
      json  <- io.circe.parser.parse(body).left.map(e => s"Corpo nao e JSON valido: ${e.message}")
      list   = json.hcursor.downField("user_bookmark_list")
      items <- list.downField("bookmarks").as[List[Json]].left.map(_ => "Resposta sem user_bookmark_list.bookmarks")
      marks <- sequence(items.map(item => bookmark(item.hcursor, baseUrl)))
    yield BookmarkPage(marks, list.get[String]("more_bookmarks_url").toOption.filter(_.nonEmpty))

  private def bookmark(c: ACursor, baseUrl: String): Either[String, Bookmark] =
    for
      title <- c.get[String]("title").left.map(_ => "bookmark sem 'title'")
      path  <- c.get[String]("bookmarkable_url").left.map(_ => s"bookmark '$title' sem 'bookmarkable_url'")
      // Autor da postagem marcada (nao o dono do bookmark).
      author <- c.downField("user").get[String]("username").left.map(_ => s"bookmark '$title' sem 'user.username' (autor)")
    yield Bookmark(title, absolute(path, baseUrl), author)

  private def absolute(path: String, baseUrl: String): String =
    if path.startsWith("http") then path else baseUrl.stripSuffix("/") + path

  private def sequence[A](xs: List[Either[String, A]]): Either[String, List[A]] =
    xs.foldRight(Right(Nil): Either[String, List[A]])((x, acc) => for a <- x; as <- acc yield a :: as)

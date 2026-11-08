package challenge.parse

import challenge.model.Bookmark
import io.circe.{ACursor, Json}

/** Pagina de bookmarks ja interpretada.
  *
  * @param next      caminho da proxima pagina, se a resposta indicar que ha mais
  * @param itemCount quantos itens vieram na resposta (inclui os ignorados); a paginacao usa isto, nao `bookmarks.size`
  * @param warnings  itens ignorados ou incompletos, em texto para o usuario
  */
final case class BookmarkPage(
    bookmarks: List[Bookmark],
    next: Option[String],
    itemCount: Int = 0,
    warnings: List[String] = Nil
)

/** Parsing puro de /u/{usuario}/bookmarks.json: sem rede, testavel com fixtures.
  *
  * Tolerante por item: um bookmark incompleto nao derruba a pagina. Sem `bookmarkable_url` o item e ignorado
  * (nao ha como identifica-lo); sem `title` ou autor, e mantido com `null` naquele campo. Em ambos os casos
  * gera aviso. So uma resposta que nao e a lista esperada (nao-JSON, sem `bookmarks`) e erro da pagina inteira.
  */
object BookmarkParser:

  private final case class Item(bookmark: Option[Bookmark], warnings: List[String])

  def parse(body: String, baseUrl: String): Either[String, BookmarkPage] =
    for
      json  <- io.circe.parser.parse(body).left.map(e => s"Corpo nao e JSON valido: ${e.message}")
      list   = json.hcursor.downField("user_bookmark_list")
      items <- itemsOf(json)
    yield
      val parsed = items.zipWithIndex.map((item, i) => bookmark(item.hcursor, i + 1, baseUrl))
      BookmarkPage(
        bookmarks = parsed.flatMap(_.bookmark),
        next = list.get[String]("more_bookmarks_url").toOption.filter(_.nonEmpty),
        itemCount = items.size,
        warnings = parsed.flatMap(_.warnings)
      )

  /** Lista de itens da resposta. Com bookmarks vem em `user_bookmark_list.bookmarks`; sem nenhum, o Discourse
    * responde `{"bookmarks": []}` na raiz (users_controller.rb, acao `bookmarks`), o que nao e erro.
    */
  private def itemsOf(json: Json): Either[String, List[Json]] =
    val c      = json.hcursor
    val nested = c.downField("user_bookmark_list").downField("bookmarks").as[List[Json]]
    val flat   = c.downField("bookmarks").as[List[Json]]
    (nested, flat) match
      case (Right(items), _) => Right(items)
      case (_, Right(items)) => Right(items)
      case _ => Left("Resposta sem user_bookmark_list.bookmarks (nem o formato de lista vazia, bookmarks na raiz)")

  private def bookmark(c: ACursor, position: Int, baseUrl: String): Item =
    val label  = describe(c, position)
    val url    = text(c.get[String]("bookmarkable_url"))
    val title  = text(c.get[String]("title"))
    // Autor da postagem marcada (nao o dono do bookmark).
    val author = text(c.downField("user").get[String]("username"))

    url match
      case None =>
        Item(None, List(s"$label ignorado: sem 'bookmarkable_url', nao ha como identificar o item"))
      case Some(path) =>
        val missing = List(
          Option.when(title.isEmpty)(s"$label: sem 'title'; titulo gravado como null"),
          Option.when(author.isEmpty)(s"$label: sem 'user.username'; autor gravado como null")
        ).flatten
        Item(Some(Bookmark(title, absolute(path, baseUrl), author)), missing)

  private def text(r: io.circe.Decoder.Result[String]): Option[String] = r.toOption.map(_.trim).filter(_.nonEmpty)

  /** "bookmark #3 (id=123, tipo=Post)": o que ajuda a achar o item no JSON bruto. */
  private def describe(c: ACursor, position: Int): String =
    val details = List(
      c.get[Long]("id").toOption.map(id => s"id=$id"),
      c.get[String]("bookmarkable_type").toOption.map(t => s"tipo=$t")
    ).flatten
    if details.isEmpty then s"bookmark #$position" else s"bookmark #$position (${details.mkString(", ")})"

  private def absolute(path: String, baseUrl: String): String =
    if path.startsWith("http") then path else baseUrl.stripSuffix("/") + path

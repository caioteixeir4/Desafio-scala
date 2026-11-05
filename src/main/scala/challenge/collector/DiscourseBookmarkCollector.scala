package challenge.collector

import java.nio.file.{Files, Path}
import scala.annotation.tailrec
import scala.collection.mutable

import challenge.http.{Http, Session}
import challenge.model.Bookmark
import challenge.parse.BookmarkParser
import sttp.client3.*
import sttp.model.Uri

/** Segue `more_bookmarks_url` ate a resposta nao indicar mais paginas. Nao ha contagem de paginas fixa. */
final class DiscourseBookmarkCollector(
    http: Http,
    rawDumpDir: Option[Path] = None,
    maxPages: Int = 1000 // trava de seguranca contra loop, nao um limite esperado
) extends BookmarkCollector:

  def collectAll(session: Session): Either[String, List[Bookmark]] =
    val jar = mutable.Map.from(session.cookies)
    rawDumpDir.foreach(Files.createDirectories(_))

    @tailrec
    def loop(path: String, page: Int, seen: Set[String], acc: List[Bookmark]): Either[String, List[Bookmark]] =
      if page >= maxPages then Left(s"Excedeu $maxPages paginas; abortando")
      else
        fetch(jar, path, page) match
          case Left(err) => Left(err)
          case Right(parsed) =>
            System.err.println(s"pagina $page: ${parsed.bookmarks.size} bookmarks, proxima=${parsed.next.getOrElse("(nenhuma)")}")
            val all = acc ++ parsed.bookmarks
            parsed.next match
              case Some(next) if parsed.bookmarks.nonEmpty && !seen.contains(next) =>
                loop(next, page + 1, seen + next, all)
              case _ => Right(all)

    val first = s"/u/${session.username}/bookmarks.json"
    loop(first, 0, Set(first), Nil)

  private def fetch(jar: mutable.Map[String, String], path: String, page: Int) =
    val uri = Uri.unsafeParse(http.baseUrl + path)
    for
      res <- http.send(jar, basicRequest.get(uri)).left.map(e => s"Falha de rede: ${e.getMessage}")
      _ <- res.code.code match
        case 401 | 403 => Left(s"Sessao nao autorizada (HTTP ${res.code.code}) em $path")
        case c if c >= 400 => Left(s"HTTP $c em $path")
        case _ => Right(())
      _      = rawDumpDir.foreach(d => Files.writeString(d.resolve(f"bookmarks-page-$page%03d.json"), res.body))
      parsed <- BookmarkParser.parse(res.body, http.baseUrl)
    yield parsed

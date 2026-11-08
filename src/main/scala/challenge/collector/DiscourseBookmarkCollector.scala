package challenge.collector

import java.nio.file.{Files, Path}
import java.time.{Duration, ZonedDateTime}
import java.time.format.DateTimeFormatter
import scala.annotation.tailrec
import scala.collection.mutable
import scala.util.Try

import challenge.http.{Http, Session}
import challenge.parse.BookmarkParser
import sttp.client3.*
import sttp.model.Uri

/** Segue `more_bookmarks_url` ate a resposta nao indicar mais paginas. Nao ha contagem de paginas fixa.
  *
  * Em HTTP 429 respeita `Retry-After`: espera e tenta de novo, no maximo `maxRetries` vezes por pagina.
  * Se o servidor pedir uma espera maior que `maxWaitMs`, desiste em vez de ficar parado ou insistir.
  */
final class DiscourseBookmarkCollector(
    http: Http,
    rawDumpDir: Option[Path] = None,
    maxPages: Int = 1000, // trava de seguranca contra loop, nao um limite esperado
    maxRetries: Int = 3,
    maxWaitMs: Long = 60_000L,
    defaultWaitMs: Long = 5_000L, // usado quando o 429 vem sem Retry-After
    sleep: Long => Unit = ms => Thread.sleep(ms)
) extends BookmarkCollector:

  def collectAll(session: Session): Either[String, Collected] =
    val jar = mutable.Map.from(session.cookies)
    rawDumpDir.foreach(Files.createDirectories(_))

    @tailrec
    def loop(path: String, page: Int, seen: Set[String], acc: Collected): Either[String, Collected] =
      if page >= maxPages then Left(s"Excedeu $maxPages paginas; abortando")
      else
        fetch(jar, path, page) match
          case Left(err) => Left(err)
          case Right(parsed) =>
            System.err.println(s"pagina $page: ${parsed.bookmarks.size} bookmarks, proxima=${parsed.next.getOrElse("(nenhuma)")}")
            val all = Collected(
              acc.bookmarks ++ parsed.bookmarks,
              acc.warnings ++ parsed.warnings.map(w => s"pagina $page: $w")
            )
            parsed.next match
              // itemCount (e nao bookmarks.size): uma pagina so com itens ignorados nao pode encerrar a paginacao.
              case Some(next) if parsed.itemCount > 0 && !seen.contains(next) =>
                loop(next, page + 1, seen + next, all)
              case _ => Right(all)

    val first = s"/u/${session.username}/bookmarks.json"
    loop(first, 0, Set(first), Collected(Nil, Nil))

  private def fetch(jar: mutable.Map[String, String], path: String, page: Int) =
    val uri = Uri.unsafeParse(http.baseUrl + path)
    for
      res <- getWithRetry(jar, uri, path, attempt = 0)
      _ <- res.code.code match
        case 401 | 403 => Left(s"Sessao nao autorizada (HTTP ${res.code.code}) em $path")
        case c if c >= 400 => Left(s"HTTP $c em $path")
        case _ => Right(())
      _      = rawDumpDir.foreach(d => Files.writeString(d.resolve(f"bookmarks-page-$page%03d.json"), res.body))
      parsed <- BookmarkParser.parse(res.body, http.baseUrl)
    yield parsed

  @tailrec
  private def getWithRetry(
      jar: mutable.Map[String, String],
      uri: Uri,
      path: String,
      attempt: Int
  ): Either[String, Response[String]] =
    http.send(jar, basicRequest.get(uri)) match
      case Left(failure) => Left(failure.message)
      case Right(res) if res.code.code == 429 =>
        val waitMs = retryAfterMs(res).getOrElse(defaultWaitMs)
        if attempt >= maxRetries then
          Left(s"Rate limit (HTTP 429) em $path persistiu apos $maxRetries novas tentativas")
        else if waitMs > maxWaitMs then
          Left(s"Rate limit (HTTP 429) em $path: servidor pediu ${waitMs / 1000}s de espera, acima do maximo de ${maxWaitMs / 1000}s")
        else
          System.err.println(s"HTTP 429 em $path; aguardando ${waitMs}ms (tentativa ${attempt + 1}/$maxRetries)")
          sleep(waitMs)
          getWithRetry(jar, uri, path, attempt + 1)
      case Right(res) => Right(res)

  /** Retry-After em segundos ("120") ou data HTTP ("Wed, 21 Oct 2026 07:28:00 GMT"). */
  private def retryAfterMs(res: Response[String]): Option[Long] =
    res.header("Retry-After").map(_.trim).flatMap { v =>
      v.toLongOption.map(_ * 1000).orElse {
        Try {
          val at = ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME)
          math.max(0L, Duration.between(ZonedDateTime.now(at.getZone), at).toMillis)
        }.toOption
      }
    }

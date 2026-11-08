package challenge.collector

import challenge.http.Session
import challenge.model.Bookmark

/** Resultado da coleta: os bookmarks e os avisos (itens ignorados ou incompletos, por pagina). */
final case class Collected(bookmarks: List[Bookmark], warnings: List[String])

/** Coleta todos os bookmarks paginando ate a propria resposta indicar o fim. */
trait BookmarkCollector:
  def collectAll(session: Session): Either[String, Collected]

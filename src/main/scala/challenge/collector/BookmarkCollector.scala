package challenge.collector

import challenge.http.Session
import challenge.model.Bookmark

/** Coleta todos os bookmarks paginando ate a propria resposta indicar o fim. */
trait BookmarkCollector:
  def collectAll(session: Session): Either[String, List[Bookmark]]

package challenge.model

import io.circe.Encoder
import io.circe.generic.semiauto.deriveEncoder

/** Conteudo do output.json. */
final case class Output(
    source: String,
    username: String,
    collectedAt: String,
    total: Int,
    bookmarks: List[Bookmark]
)

object Output:
  given Encoder[Output] = deriveEncoder

package challenge.model

import io.circe.Encoder
import io.circe.generic.semiauto.deriveEncoder

/** Um bookmark do usuario autenticado. */
final case class Bookmark(
    title: String,
    url: String,
    author: String
)

object Bookmark:
  given Encoder[Bookmark] = deriveEncoder

package challenge.model

import io.circe.Encoder
import io.circe.generic.semiauto.deriveEncoder

/** Conteudo do output.json. `warnings` lista o que veio incompleto ou foi ignorado (vazio quando esta tudo certo). */
final case class Output(
    source: String,
    username: String,
    collectedAt: String,
    total: Int,
    bookmarks: List[Bookmark],
    warnings: List[String] = Nil
)

object Output:
  given Encoder[Output] = deriveEncoder

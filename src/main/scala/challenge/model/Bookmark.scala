package challenge.model

import io.circe.Encoder
import io.circe.generic.semiauto.deriveEncoder

/** Um bookmark do usuario autenticado.
  *
  * `url` e obrigatoria (e a identidade do item). `title` e `author` ficam `null` no JSON quando a resposta do
  * site nao os traz (ex.: post de usuario apagado): o robo nao inventa valor, e avisa em `warnings`.
  */
final case class Bookmark(
    title: Option[String],
    url: String,
    author: Option[String]
)

object Bookmark:
  given Encoder[Bookmark] = deriveEncoder

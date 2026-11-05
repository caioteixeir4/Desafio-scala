package challenge.http

/** Sessao autenticada: guarda cookies e o usuario confirmado. */
final case class Session(
    username: String,
    cookies: Map[String, String]
)

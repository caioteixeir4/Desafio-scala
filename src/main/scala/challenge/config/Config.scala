package challenge.config

/** Configuracao lida do ambiente. Credenciais nunca ficam no codigo. */
final case class Config(
    baseUrl: String,
    username: String,
    password: String,
    userAgent: String,
    minRequestIntervalMs: Long,
    outputPath: String,
    rawDumpDir: Option[String]
)

object Config:

  /** Falha de configuracao (variavel obrigatoria ausente). */
  final case class MissingEnv(name: String) extends RuntimeException(s"Variavel de ambiente ausente: $name")

  def fromEnv(env: Map[String, String] = sys.env): Either[MissingEnv, Config] =
    def required(name: String): Either[MissingEnv, String] =
      env.get(name).filter(_.nonEmpty).toRight(MissingEnv(name))

    for
      user <- required("DISCOURSE_USERNAME")
      pass <- required("DISCOURSE_PASSWORD")
    yield Config(
      baseUrl = env.getOrElse("DISCOURSE_BASE_URL", "https://meta.discourse.org"),
      username = user,
      password = pass,
      userAgent = env.getOrElse("USER_AGENT", "oystr-code-challenge-bot/0.1 (candidato; contato: preencher)"),
      minRequestIntervalMs = env.get("MIN_REQUEST_INTERVAL_MS").flatMap(_.toLongOption).getOrElse(1000L),
      outputPath = env.getOrElse("OUTPUT_PATH", "output.json"),
      rawDumpDir = env.get("RAW_DUMP_DIR").filter(_.nonEmpty)
    )

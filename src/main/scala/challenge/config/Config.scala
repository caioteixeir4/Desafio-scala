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

  /** Falha de configuracao: o robo nao roda com configuracao errada, em vez de assumir um valor em silencio. */
  sealed abstract class ConfigError(message: String) extends RuntimeException(message)

  /** Variavel obrigatoria ausente ou vazia. */
  final case class MissingEnv(name: String) extends ConfigError(s"Variavel de ambiente ausente: $name")

  /** Variavel presente, mas com valor que nao serve (ex.: letras onde se espera numero). */
  final case class InvalidEnv(name: String, value: String, expected: String)
      extends ConfigError(s"Variavel de ambiente invalida: $name='$value' (esperado: $expected)")

  def fromEnv(env: Map[String, String] = sys.env): Either[ConfigError, Config] =
    def required(name: String): Either[ConfigError, String] =
      env.get(name).filter(_.nonEmpty).toRight(MissingEnv(name))

    /** Ausente -> usa o padrao. Presente mas invalido -> erro (nao cai no padrao em silencio). */
    def nonNegativeLong(name: String, default: Long): Either[ConfigError, Long] =
      env.get(name).filter(_.nonEmpty) match
        case None => Right(default)
        case Some(raw) =>
          raw.trim.toLongOption
            .filter(_ >= 0)
            .toRight(InvalidEnv(name, raw, "numero inteiro maior ou igual a 0, em milissegundos"))

    for
      user     <- required("DISCOURSE_USERNAME")
      pass     <- required("DISCOURSE_PASSWORD")
      interval <- nonNegativeLong("MIN_REQUEST_INTERVAL_MS", 1000L)
    yield Config(
      baseUrl = env.getOrElse("DISCOURSE_BASE_URL", "https://meta.discourse.org"),
      username = user,
      password = pass,
      userAgent = env.getOrElse("USER_AGENT", "oystr-code-challenge-bot/0.1 (Caio Teixeira; https://github.com/caioteixeir4)"),
      minRequestIntervalMs = interval,
      outputPath = env.getOrElse("OUTPUT_PATH", "output.json"),
      rawDumpDir = env.get("RAW_DUMP_DIR").filter(_.nonEmpty)
    )

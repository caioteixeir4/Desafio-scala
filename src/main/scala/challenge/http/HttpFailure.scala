package challenge.http

/** Falhas da camada HTTP, distintas entre si. */
sealed trait HttpFailure extends Product with Serializable:
  def message: String

object HttpFailure:
  /** Erro de rede, timeout ou conexao. */
  final case class Network(detail: String) extends HttpFailure:
    def message: String = s"Falha de rede: $detail"

  /** O robots.txt do alvo proibe este caminho para o nosso User-Agent. */
  final case class BlockedByRobots(path: String) extends HttpFailure:
    def message: String = s"Bloqueado pelo robots.txt: $path"

  /** Nao foi possivel obter o robots.txt (5xx); por cautela o robo nao segue. */
  final case class RobotsUnavailable(detail: String) extends HttpFailure:
    def message: String = s"robots.txt indisponivel: $detail"

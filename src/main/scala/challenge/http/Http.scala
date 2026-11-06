package challenge.http

import scala.collection.mutable
import scala.concurrent.duration.*

import challenge.config.Config
import challenge.http.HttpFailure.*
import challenge.parse.{RobotsRules, RobotsTxt}
import sttp.client3.*
import sttp.model.Uri

/** Cliente HTTP generico compartilhado: User-Agent identificavel, rate limit, cookie jar manual e robots.txt.
  *
  * @param robotsExceptions prefixos de caminho acessados mesmo se o robots.txt os bloquear. Por padrao
  *                         `/session`, necessario para o login (excecao deliberada, ver RECON.md).
  */
final class Http(
    config: Config,
    backend: SttpBackend[Identity, Any],
    limiter: RateLimiter,
    robotsExceptions: Set[String] = Set("/session")
):

  val baseUrl: String = config.baseUrl.stripSuffix("/")

  // Sem robots carregado (ex.: testes) nao ha restricao; o Main sempre chama loadRobots() antes.
  @volatile private var robots: Option[RobotsRules] = None

  /** Baixa e aplica o robots.txt. 4xx = sem restricoes; 5xx ou rede = falha (nao segue por cautela). */
  def loadRobots(): Either[HttpFailure, Unit] =
    execute(mutable.Map.empty, basicRequest.get(uri"$baseUrl/robots.txt")).flatMap { res =>
      val code = res.code.code
      if code >= 500 then Left(RobotsUnavailable(s"HTTP $code"))
      else
        robots = Some(if code >= 400 then RobotsRules.allowAll else RobotsTxt.parse(res.body, config.userAgent))
        Right(())
    }

  def send(
      jar: mutable.Map[String, String],
      req: Request[Either[String, String], Any]
  ): Either[HttpFailure, Response[String]] =
    val (path, pathAndQuery) = pathOf(req.uri)
    val blocked = robots.exists(!_.isAllowed(pathAndQuery)) && !robotsExceptions.exists(path.startsWith)
    if blocked then Left(BlockedByRobots(pathAndQuery)) else execute(jar, req)

  private def pathOf(uri: Uri): (String, String) =
    val j     = uri.toJavaUri
    val path  = Option(j.getRawPath).filter(_.nonEmpty).getOrElse("/")
    val query = Option(j.getRawQuery).map("?" + _).getOrElse("")
    (path, path + query)

  private def execute(
      jar: mutable.Map[String, String],
      req: Request[Either[String, String], Any]
  ): Either[HttpFailure, Response[String]] =
    limiter.await()
    val full = req
      .header("User-Agent", config.userAgent)
      .header("Accept", "application/json, text/plain")
      .header("X-Requested-With", "XMLHttpRequest")
      .cookies(jar.toSeq*)
      .readTimeout(30.seconds)
      .response(asStringAlways)
    try
      val res = full.send(backend)
      res.unsafeCookies.foreach(c => jar.update(c.name, c.value))
      Right(res)
    catch case e: SttpClientException => Left(Network(e.getMessage))

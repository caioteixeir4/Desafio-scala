package challenge.http

import scala.collection.mutable
import scala.concurrent.duration.*

import challenge.config.Config
import sttp.client3.*

/** Cliente HTTP generico compartilhado: User-Agent identificavel, rate limit e cookie jar manual. */
final class Http(config: Config, backend: SttpBackend[Identity, Any], limiter: RateLimiter):

  val baseUrl: String = config.baseUrl.stripSuffix("/")

  def send(
      jar: mutable.Map[String, String],
      req: Request[Either[String, String], Any]
  ): Either[SttpClientException, Response[String]] =
    limiter.await()
    val full = req
      .header("User-Agent", config.userAgent)
      .header("Accept", "application/json")
      .header("X-Requested-With", "XMLHttpRequest")
      .cookies(jar.toSeq*)
      .readTimeout(30.seconds)
      .response(asStringAlways)
    try
      val res = full.send(backend)
      res.unsafeCookies.foreach(c => jar.update(c.name, c.value))
      Right(res)
    catch case e: SttpClientException => Left(e)

package challenge.http

/** Garante intervalo minimo entre requisicoes (sem rajadas contra o site de terceiros). */
final class RateLimiter(minIntervalMs: Long):
  private var lastRequestAt = 0L

  def await(): Unit = synchronized {
    val wait = lastRequestAt + minIntervalMs - System.currentTimeMillis()
    if wait > 0 then Thread.sleep(wait)
    lastRequestAt = System.currentTimeMillis()
  }

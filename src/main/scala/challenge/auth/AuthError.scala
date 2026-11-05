package challenge.auth

/** Falhas de autenticacao, tipadas para distinguir a causa. */
sealed trait AuthError extends Product with Serializable:
  def message: String

object AuthError:
  /** Usuario ou senha rejeitados pelo servidor. */
  final case class InvalidCredentials(message: String) extends AuthError

  /** Conta existe, mas o email ainda nao foi confirmado/ativado. */
  final case class AccountNotActivated(message: String) extends AuthError

  /** A conta exige segundo fator (2FA) e o robo nao o forneceu. */
  final case class SecondFactorRequired(message: String) extends AuthError

  /** Servidor pediu para reduzir o ritmo (HTTP 429). */
  final case class RateLimited(message: String) extends AuthError

  /** Site fora do ar, timeout ou resposta 5xx. */
  final case class SiteUnavailable(message: String) extends AuthError

  /** O login pareceu funcionar, mas nao houve evidencia positiva de sessao autenticada. */
  final case class NotAuthenticated(message: String) extends AuthError

  /** Resposta em formato inesperado (o site pode ter mudado). */
  final case class UnexpectedResponse(message: String) extends AuthError

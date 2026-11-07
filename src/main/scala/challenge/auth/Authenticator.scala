package challenge.auth

import challenge.http.Session

/** Estabelece uma sessao autenticada do zero e a confirma por evidencia positiva. */
trait Authenticator:
  def login(): Either[AuthError, Session]

  /** Encerra a sessao no servidor, para nao deixar um token de login novo a cada execucao. */
  def logout(session: Session): Either[AuthError, Unit]

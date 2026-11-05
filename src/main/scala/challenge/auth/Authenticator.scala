package challenge.auth

import challenge.http.Session

/** Estabelece uma sessao autenticada do zero e a confirma por evidencia positiva. */
trait Authenticator:
  def login(): Either[AuthError, Session]

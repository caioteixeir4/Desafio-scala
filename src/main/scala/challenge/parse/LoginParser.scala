package challenge.parse

import challenge.auth.AuthError
import challenge.auth.AuthError.*
import io.circe.Json
import io.circe.parser.parse

/** Interpreta as respostas do fluxo de login. Puro: recebe status e corpo, sem rede. */
object LoginParser:

  private def json(body: String): Either[AuthError, Json] =
    parse(body).left.map(e => UnexpectedResponse(s"Corpo nao e JSON valido: ${e.message}"))

  /** GET /session/csrf -> {"csrf": "..."} */
  def parseCsrf(status: Int, body: String): Either[AuthError, String] =
    statusError(status).toLeft(()).flatMap(_ => json(body)).flatMap { j =>
      j.hcursor.get[String]("csrf").left.map(_ => UnexpectedResponse("Resposta de /session/csrf sem campo 'csrf'"))
    }

  /** POST /session. O Discourse responde 200 tanto no sucesso ({"user": ...}) quanto em varias falhas ({"error", "reason"}). */
  def parseLogin(status: Int, body: String): Either[AuthError, Unit] =
    statusError(status).toLeft(()).flatMap(_ => json(body)).flatMap { j =>
      val c      = j.hcursor
      val error  = c.get[String]("error").toOption
      val reason = c.get[String]("reason").toOption
      val needs2fa = c.get[Boolean]("second_factor_required").getOrElse(false) ||
        c.get[Boolean]("security_key_required").getOrElse(false) ||
        reason.exists(r => r == "invalid_second_factor" || r == "invalid_security_key")

      if needs2fa then Left(SecondFactorRequired(error.getOrElse("Segundo fator exigido")))
      else
        (reason, error) match
          case (Some("invalid_credentials"), e) => Left(InvalidCredentials(e.getOrElse("Credenciais invalidas")))
          case (Some("not_activated"), e)       => Left(AccountNotActivated(e.getOrElse("Conta nao ativada")))
          case (Some(r), e)                     => Left(UnexpectedResponse(s"Login recusado (reason=$r): ${e.getOrElse("")}"))
          case (None, Some(e))                  => Left(UnexpectedResponse(s"Login recusado: $e"))
          case (None, None) if c.downField("user").succeeded => Right(())
          case _ => Left(UnexpectedResponse("Resposta de login sem 'user' nem 'error'"))
    }

  /** GET /session/current.json. So 200 com current_user.username conta como evidencia positiva. */
  def parseCurrentUser(status: Int, body: String): Either[AuthError, String] =
    if status == 401 || status == 403 || status == 404 then
      Left(NotAuthenticated(s"/session/current.json respondeu $status: sessao nao autenticada"))
    else
      statusError(status).toLeft(()).flatMap(_ => json(body)).flatMap { j =>
        j.hcursor
          .downField("current_user")
          .get[String]("username")
          .left
          .map(_ => NotAuthenticated("Resposta sem current_user.username: sessao nao autenticada"))
      }

  private def statusError(status: Int): Option[AuthError] =
    if status == 429 then Some(RateLimited("HTTP 429"))
    else if status >= 500 then Some(SiteUnavailable(s"HTTP $status"))
    else if status >= 400 then Some(UnexpectedResponse(s"HTTP $status"))
    else None

package challenge.parse

import challenge.auth.AuthError.*

class LoginParserSpec extends munit.FunSuite:

  test("parseCsrf extrai o token") {
    assertEquals(LoginParser.parseCsrf(200, """{"csrf":"abc"}"""), Right("abc"))
  }

  test("parseCsrf com 5xx e SiteUnavailable") {
    assert(LoginParser.parseCsrf(503, "").left.exists(_.isInstanceOf[SiteUnavailable]))
  }

  test("parseLogin aceita resposta com user") {
    assertEquals(LoginParser.parseLogin(200, """{"user":{"id":1,"username":"x"}}"""), Right(()))
  }

  test("parseLogin: credencial errada e InvalidCredentials") {
    val body = """{"error":"Incorrect username, email or password","reason":"invalid_credentials"}"""
    assert(LoginParser.parseLogin(200, body).left.exists(_.isInstanceOf[InvalidCredentials]))
  }

  test("parseLogin: conta nao ativada") {
    val body = """{"error":"Voce precisa ativar","reason":"not_activated"}"""
    assert(LoginParser.parseLogin(200, body).left.exists(_.isInstanceOf[AccountNotActivated]))
  }

  test("parseLogin: segundo fator exigido") {
    assert(LoginParser.parseLogin(200, """{"second_factor_required":true}""").left.exists(_.isInstanceOf[SecondFactorRequired]))
  }

  test("parseLogin: 429 e RateLimited, 5xx e SiteUnavailable (falhas diferentes)") {
    assert(LoginParser.parseLogin(429, "{}").left.exists(_.isInstanceOf[RateLimited]))
    assert(LoginParser.parseLogin(502, "").left.exists(_.isInstanceOf[SiteUnavailable]))
  }

  test("parseLogin: corpo nao JSON e UnexpectedResponse") {
    assert(LoginParser.parseLogin(200, "<html>").left.exists(_.isInstanceOf[UnexpectedResponse]))
  }

  test("parseCurrentUser: 404 anonimo nao e autenticado") {
    assert(LoginParser.parseCurrentUser(404, "").left.exists(_.isInstanceOf[NotAuthenticated]))
  }

  test("parseCurrentUser: 200 sem current_user nao e autenticado") {
    assert(LoginParser.parseCurrentUser(200, "{}").left.exists(_.isInstanceOf[NotAuthenticated]))
  }

  test("parseCurrentUser: evidencia positiva devolve o username") {
    assertEquals(LoginParser.parseCurrentUser(200, """{"current_user":{"username":"caio"}}"""), Right("caio"))
  }

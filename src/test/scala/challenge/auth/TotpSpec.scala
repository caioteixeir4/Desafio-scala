package challenge.auth

class TotpSpec extends munit.FunSuite:

  // RFC 6238, Apendice B (SHA-1): segredo ASCII "12345678901234567890", em base32 abaixo.
  // A RFC lista 8 digitos; os 6 digitos sao os 6 ultimos (binario % 10^6).
  private val rfcSecret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"

  private def at(seconds: Long) = Totp.generate(rfcSecret, seconds * 1000)

  test("vetores oficiais da RFC 6238 (SHA-1)") {
    assertEquals(at(59L), Right("287082"))         // RFC: 94287082
    assertEquals(at(1111111109L), Right("081804"))  // RFC: 07081804 (zero a esquerda preservado)
    assertEquals(at(1111111111L), Right("050471"))  // RFC: 14050471
    assertEquals(at(1234567890L), Right("005924"))  // RFC: 89005924
    assertEquals(at(2000000000L), Right("279037"))  // RFC: 69279037
    assertEquals(at(20000000000L), Right("353130")) // RFC: 65353130
  }

  test("o codigo muda a cada janela de 30 s e e igual dentro dela") {
    assertEquals(at(30L), at(59L))
    assert(at(59L) != at(60L))
  }

  test("base32 aceita minusculas, espacos, hifens e '=' final") {
    val spaced = "gezd gnbv-gy3t qojq gezd gnbv gy3t qojq===="
    assertEquals(Totp.generate(spaced, 59_000L), Right("287082"))
  }

  test("segredo invalido e erro, sem excecao") {
    assert(Totp.generate("", 0L).isLeft)
    assert(Totp.generate("   ", 0L).isLeft)
    assert(Totp.generate("ABC1", 0L).isLeft) // '1' nao existe na base32
    assert(Totp.generate("A", 0L).isLeft)    // 5 bits: nao forma nenhum byte
  }

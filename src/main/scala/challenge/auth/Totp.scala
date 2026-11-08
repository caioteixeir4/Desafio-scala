package challenge.auth

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Codigo TOTP (RFC 6238) como o do app autenticador: HMAC-SHA1, 6 digitos, janela de 30 s.
  *
  * Sao os padroes do ROTP, que e o que o Discourse usa para validar. Puro: o horario entra como parametro.
  */
object Totp:

  private val Alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

  val PeriodSeconds: Long = 30L
  val Digits: Int         = 6

  /** Base32 (RFC 4648). Aceita minusculas, espacios e hifens (o site mostra a chave em grupos) e `=` final. */
  def decodeBase32(secret: String): Either[String, Array[Byte]] =
    val clean = secret.filterNot(c => c.isWhitespace || c == '-').toUpperCase.replaceAll("=+$", "")
    if clean.isEmpty then Left("segredo vazio")
    else if clean.exists(c => Alphabet.indexOf(c) < 0) then Left("segredo com caractere fora da base32 (A-Z, 2-7)")
    else
      val out        = ByteArrayOutputStream()
      var buffer     = 0
      var bitsInside = 0
      clean.foreach { c =>
        buffer = (buffer << 5) | Alphabet.indexOf(c)
        bitsInside += 5
        if bitsInside >= 8 then
          bitsInside -= 8
          out.write((buffer >> bitsInside) & 0xff)
      }
      val bytes = out.toByteArray
      if bytes.isEmpty then Left("segredo curto demais") else Right(bytes)

  /** Codigo de 6 digitos valido no instante `nowMillis`. */
  def generate(secret: String, nowMillis: Long): Either[String, String] =
    decodeBase32(secret).map { key =>
      val counter = nowMillis / 1000 / PeriodSeconds
      val message = ByteBuffer.allocate(8).putLong(counter).array()
      val mac     = Mac.getInstance("HmacSHA1")
      mac.init(SecretKeySpec(key, "HmacSHA1"))
      val hash   = mac.doFinal(message)
      val offset = hash(hash.length - 1) & 0x0f
      val binary =
        ((hash(offset) & 0x7f) << 24) | ((hash(offset + 1) & 0xff) << 16) |
          ((hash(offset + 2) & 0xff) << 8) | (hash(offset + 3) & 0xff)
      val code = binary % 1_000_000
      f"$code%06d"
    }

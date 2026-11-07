package challenge.config

import challenge.config.Config.*

class ConfigSpec extends munit.FunSuite:

  private val base = Map("DISCOURSE_USERNAME" -> "u", "DISCOURSE_PASSWORD" -> "p")

  test("so com usuario e senha, usa os padroes") {
    val c = Config.fromEnv(base).toOption.get
    assertEquals(c.baseUrl, "https://meta.discourse.org")
    assertEquals(c.minRequestIntervalMs, 1000L)
    assertEquals(c.outputPath, "output.json")
    assertEquals(c.rawDumpDir, None)
  }

  test("usuario ou senha ausentes ou vazios viram MissingEnv") {
    assertEquals(Config.fromEnv(Map("DISCOURSE_PASSWORD" -> "p")), Left(MissingEnv("DISCOURSE_USERNAME")))
    assertEquals(Config.fromEnv(base + ("DISCOURSE_PASSWORD" -> "")), Left(MissingEnv("DISCOURSE_PASSWORD")))
  }

  test("intervalo valido e usado, inclusive 0") {
    assertEquals(Config.fromEnv(base + ("MIN_REQUEST_INTERVAL_MS" -> "2500")).map(_.minRequestIntervalMs), Right(2500L))
    assertEquals(Config.fromEnv(base + ("MIN_REQUEST_INTERVAL_MS" -> "0")).map(_.minRequestIntervalMs), Right(0L))
  }

  test("intervalo com letras ou negativo e erro, nao cai no padrao em silencio") {
    val letras = Config.fromEnv(base + ("MIN_REQUEST_INTERVAL_MS" -> "abc"))
    assertEquals(letras, Left(InvalidEnv("MIN_REQUEST_INTERVAL_MS", "abc", "numero inteiro maior ou igual a 0, em milissegundos")))
    assert(Config.fromEnv(base + ("MIN_REQUEST_INTERVAL_MS" -> "-5")).left.exists(_.isInstanceOf[InvalidEnv]))
    assert(Config.fromEnv(base + ("MIN_REQUEST_INTERVAL_MS" -> "1.5")).left.exists(_.isInstanceOf[InvalidEnv]))
  }

  test("intervalo em branco conta como ausente (usa o padrao)") {
    assertEquals(Config.fromEnv(base + ("MIN_REQUEST_INTERVAL_MS" -> "")).map(_.minRequestIntervalMs), Right(1000L))
  }

  test("RAW_DUMP_DIR so liga o dump quando tem valor") {
    assertEquals(Config.fromEnv(base + ("RAW_DUMP_DIR" -> "raw")).map(_.rawDumpDir), Right(Some("raw")))
    assertEquals(Config.fromEnv(base + ("RAW_DUMP_DIR" -> "")).map(_.rawDumpDir), Right(None))
  }

package challenge

import java.nio.file.{Files, Paths}
import java.time.Instant

import challenge.auth.AuthError.*
import challenge.auth.DiscourseAuthenticator
import challenge.collector.DiscourseBookmarkCollector
import challenge.config.Config
import challenge.http.{Http, RateLimiter}
import challenge.model.Output
import io.circe.syntax.*
import sttp.client3.HttpClientSyncBackend

@main def run(): Unit =
  Config.fromEnv() match
    case Left(err) =>
      System.err.println(err.getMessage)
      sys.exit(2)
    case Right(config) =>
      val http = Http(config, HttpClientSyncBackend(), RateLimiter(config.minRequestIntervalMs))
      http.loadRobots() match
        case Left(err) =>
          System.err.println(err.message)
          sys.exit(3)
        case Right(_) => ()
      val auth = DiscourseAuthenticator(config.username, config.password, http)
      auth.login() match
        case Left(err) =>
          System.err.println(s"Falha de autenticacao: ${err.message}")
          // Codigos de saida distintos por causa da falha.
          sys.exit(err match
            case _: InvalidCredentials   => 10
            case _: AccountNotActivated  => 11
            case _: SecondFactorRequired => 12
            case _: SiteUnavailable      => 20
            case _: RateLimited          => 21
            case _: NotAuthenticated     => 30
            case _: UnexpectedResponse   => 31
          )
        case Right(session) =>
          println(s"Autenticado como ${session.username}")
          val collector = DiscourseBookmarkCollector(http, config.rawDumpDir.map(Paths.get(_)))
          val collected = collector.collectAll(session)

          // O resultado e gravado antes do logout: uma falha ao sair nunca pode custar a coleta.
          collected.foreach { result =>
            val output = Output(
              source = config.baseUrl,
              username = session.username,
              collectedAt = Instant.now().toString,
              total = result.bookmarks.size,
              bookmarks = result.bookmarks,
              warnings = result.warnings
            )
            Files.writeString(Paths.get(config.outputPath), output.asJson.spaces2)
            println(s"${result.bookmarks.size} bookmarks gravados em ${config.outputPath}")
            if result.warnings.nonEmpty then
              System.err.println(s"ATENCAO: ${result.warnings.size} aviso(s) na coleta; o resultado pode estar incompleto:")
              result.warnings.foreach(w => System.err.println(s"  - $w"))
          }

          // Encerra a sessao (com sucesso ou nao na coleta) para nao deixar token de login na conta.
          auth.logout(session) match
            case Right(_)  => System.err.println("Sessao encerrada (logout confirmado)")
            case Left(err) => System.err.println(s"Aviso: logout falhou, a sessao pode ter ficado ativa: ${err.message}")

          collected.left.foreach { err =>
            System.err.println(s"Falha na coleta: $err")
            sys.exit(40)
          }

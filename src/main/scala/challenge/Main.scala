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
      DiscourseAuthenticator(config.username, config.password, http).login() match
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
          collector.collectAll(session) match
            case Left(err) =>
              System.err.println(s"Falha na coleta: $err")
              sys.exit(40)
            case Right(bookmarks) =>
              val output = Output(
                source = config.baseUrl,
                username = session.username,
                collectedAt = Instant.now().toString,
                total = bookmarks.size,
                bookmarks = bookmarks
              )
              Files.writeString(Paths.get(config.outputPath), output.asJson.spaces2)
              println(s"${bookmarks.size} bookmarks gravados em ${config.outputPath}")

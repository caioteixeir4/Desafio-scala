package challenge

import challenge.config.Config

@main def run(): Unit =
  Config.fromEnv() match
    case Left(err) =>
      System.err.println(err.getMessage)
      sys.exit(2)
    case Right(config) =>
      // TODO: login -> coleta paginada -> output.json
      println(s"Config ok para ${config.baseUrl} (usuario: ${config.username})")

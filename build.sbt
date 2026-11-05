ThisBuild / scalaVersion := "3.3.4"
ThisBuild / version      := "0.1.0"

lazy val root = (project in file("."))
  .settings(
    name := "oystr-challenge",
    libraryDependencies ++= Seq(
      // HTTP generico (sem biblioteca especifica do alvo)
      "com.softwaremill.sttp.client3" %% "core"  % "3.9.8",
      // JSON
      "io.circe"                      %% "circe-core"    % "0.14.10",
      "io.circe"                      %% "circe-parser"  % "0.14.10",
      "io.circe"                      %% "circe-generic" % "0.14.10",
      // Testes
      "org.scalameta"                 %% "munit" % "1.0.2" % Test
    ),
    scalacOptions ++= Seq("-deprecation", "-feature", "-Wunused:imports")
  )

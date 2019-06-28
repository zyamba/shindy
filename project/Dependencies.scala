import sbt._

object Dependencies {

  object Versions {
    val cats = "2.0.0-M4"
    val fs2 = "1.1.0-M1"
    val circe = "0.12.0-M3"
    val pureconfig = "0.11.1"
    val doobie = "0.8.0-M1"
    val postgresqlJdbcDriver = "42.2.5"
    val hikariCp = "3.3.1"
  }

  lazy val postgresJdbcDriver = "org.postgresql" % "postgresql" % Versions.postgresqlJdbcDriver

  lazy val `doobie-postgres` = "org.tpolecat" %% "doobie-postgres" % Versions.doobie
  lazy val `doobie-hikari` = "org.tpolecat" %% "doobie-hikari" % Versions.doobie
  lazy val `doobie-scalatest` = "org.tpolecat" %% "doobie-scalatest" % Versions.doobie

  lazy val `cats-core` = "org.typelevel" %% "cats-core" % Versions.cats
  lazy val `cats-effect` = "org.typelevel" %% "cats-effect" % Versions.cats

  lazy val `fs2-core` = "co.fs2" %% "fs2-core" % Versions.fs2

  lazy val `circe-core` = "io.circe" %% "circe-core" % Versions.circe
  lazy val `circe-parser` = "io.circe" %% "circe-parser" % Versions.circe
  lazy val `circe-generic` = "io.circe" %% "circe-generic" % Versions.circe

  lazy val scalactic = "org.scalactic" %% "scalactic" % "3.0.8"
  lazy val scalatest = "org.scalatest" %% "scalatest" % "3.0.8"
  lazy val scalacheck = "org.scalacheck" %% "scalacheck" % "1.14.0"

  lazy val pureconfig = "com.github.pureconfig" %% "pureconfig" % Versions.pureconfig
  lazy val hikariCp = "com.zaxxer" % "HikariCP" % Versions.hikariCp

}

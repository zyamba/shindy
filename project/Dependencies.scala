import sbt._
object Dependencies {

  object Versions {
    val cats = "1.6.0"
    val catsEffect = "1.2.0"
    val fs2 = "1.0.4"
    val circe = "0.11.1"
  }

  lazy val `cats-core` = "org.typelevel" %% "cats-core" % Versions.cats
  lazy val `cats-effect` = "org.typelevel" %% "cats-effect" % Versions.catsEffect

  lazy val `fs2-core` = "co.fs2" %% "fs2-core" % Versions.fs2

  lazy val `circe-core` = "io.circe" %% "circe-core" % Versions.circe
  lazy val `circe-parser` = "io.circe" %% "circe-parser" % Versions.circe
  lazy val `circe-generic` = "io.circe" %% "circe-generic" % Versions.circe

  lazy val scalactic = "org.scalactic" %% "scalactic" % "3.0.5"
  lazy val scalatest = "org.scalatest" %% "scalatest" % "3.0.5"
  lazy val scalacheck = "org.scalacheck" %% "scalacheck" % "1.14.0"

  lazy val postgresJdbcDriver = "org.postgresql" % "postgresql" % "42.2.5"
  lazy val `doobie-postgres` = "org.tpolecat" %% "doobie-postgres" % "0.6.0"
  lazy val `doobie-hikari` = "org.tpolecat" %% "doobie-hikari" % "0.6.0"
  lazy val `doobie-scalatest` ="org.tpolecat" %% "doobie-scalatest" % "0.6.0"

}

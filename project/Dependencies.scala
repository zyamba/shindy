import sbt._

object Dependencies {

  object Versions {
    val cats = "2.13.0"
    val catsEffect = "3.7.0"
    val catsEffectTesting = "1.8.0"
    val circe = "0.14.15"
    val pureconfig = "0.17.10"
    val doobie = "1.0.0-RC12"
    val postgresqlJdbcDriver = "42.7.10"
    val hikariCp = "5.0.1"
    val fs2 = "3.12.2"
  }

  lazy val `scala-collection-compat` = "org.scala-lang.modules" %% "scala-collection-compat" % "2.14.0"

  lazy val postgresJdbcDriver = "org.postgresql" % "postgresql" % Versions.postgresqlJdbcDriver

  lazy val `doobie-postgres` = "org.tpolecat" %% "doobie-postgres" % Versions.doobie
  lazy val `doobie-hikari` = "org.tpolecat" %% "doobie-hikari" % Versions.doobie
  lazy val `doobie-scalatest` = "org.tpolecat" %% "doobie-scalatest" % Versions.doobie

  lazy val fs2 = "co.fs2" %% "fs2-core" % Versions.fs2

  lazy val `cats-core` = "org.typelevel" %% "cats-core" % Versions.cats
  lazy val `cats-effect` = "org.typelevel" %% "cats-effect" % Versions.catsEffect
  lazy val `cats-effect-testing` = "org.typelevel" %% "cats-effect-testing-scalatest" % Versions.catsEffectTesting

  lazy val `circe-core` = "io.circe" %% "circe-core" % Versions.circe
  lazy val `circe-parser` = "io.circe" %% "circe-parser" % Versions.circe
  lazy val `circe-generic` = "io.circe" %% "circe-generic" % Versions.circe

  lazy val scalactic = "org.scalactic" %% "scalactic" % "3.2.19"
  lazy val scalatest = "org.scalatest" %% "scalatest" % "3.2.19"
  lazy val scalacheck = "org.scalacheck" %% "scalacheck" % "1.19.0"
  lazy val scalatestplus = "org.scalatestplus" %% "scalacheck-1-19" % "3.2.19.0"

  lazy val pureconfig = "com.github.pureconfig" %% "pureconfig-core" % Versions.pureconfig
  lazy val hikariCp = "com.zaxxer" % "HikariCP" % Versions.hikariCp

}

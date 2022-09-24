import sbt._

object Dependencies {

  object Versions {
    val cats = "2.8.0"
    val catsEffect = "3.3.12"
    val catsEffectTesting = "1.4.0"
    val circe = "0.14.2"
    val pureconfig = "0.17.1"
    val doobie = "1.0.0-RC2"
    val postgresqlJdbcDriver = "42.3.3"
    val hikariCp = "5.0.1"
    val fs2 = "3.2.8"
  }

  lazy val `kind-projector` = "org.typelevel" % "kind-projector" % "0.10.3"
  
  lazy val `scala-collection-compat` = "org.scala-lang.modules" %% "scala-collection-compat" % "2.7.0"

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

  lazy val scalactic = "org.scalactic" %% "scalactic" % "3.2.12"
  lazy val scalatest = "org.scalatest" %% "scalatest" % "3.2.12"
  lazy val scalacheck = "org.scalacheck" %% "scalacheck" % "1.16.0"
  lazy val scalatestplus = "org.scalatestplus" %% "scalacheck-1-14" % "3.2.2.0"

  lazy val pureconfig = "com.github.pureconfig" %% "pureconfig" % Versions.pureconfig
  lazy val hikariCp = "com.zaxxer" % "HikariCP" % Versions.hikariCp

}

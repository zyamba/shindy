import sbt._

object Dependencies {

  object Versions {
    val cats = "2.1.1"
    val fs2 = "2.3.0"
    val circe = "0.13.0"
    val pureconfig = "0.12.3"
    val doobie = "0.8.8"
    val postgresqlJdbcDriver = "42.2.6"
    val hikariCp = "3.4.2"
    val zio = "1.0.0-RC18-2"
  }

  lazy val zio = "dev.zio" %% "zio" %  Versions.zio
  lazy val zioStreams = "dev.zio" %% "zio-streams" %  Versions.zio

  lazy val zioInteropCats = "dev.zio" %% "zio-interop-cats" % "2.0.0.0-RC12"

  lazy val `kind-projector` = "org.typelevel" % "kind-projector" % "0.10.3"
  
  lazy val `scala-collection-compat` = "org.scala-lang.modules" %% "scala-collection-compat" % "2.1.4"

  lazy val postgresJdbcDriver = "org.postgresql" % "postgresql" % Versions.postgresqlJdbcDriver

  lazy val `doobie-postgres` = "org.tpolecat" %% "doobie-postgres" % Versions.doobie
  lazy val `doobie-hikari` = "org.tpolecat" %% "doobie-hikari" % Versions.doobie
  lazy val `doobie-scalatest` = "org.tpolecat" %% "doobie-scalatest" % Versions.doobie

  lazy val `cats-core` = "org.typelevel" %% "cats-core" % Versions.cats
  lazy val `cats-effect` = "org.typelevel" %% "cats-effect" % Versions.cats
  lazy val `cats-macros` = "org.typelevel" %% "cats-macros" % Versions.cats

  lazy val `circe-core` = "io.circe" %% "circe-core" % Versions.circe
  lazy val `circe-parser` = "io.circe" %% "circe-parser" % Versions.circe
  lazy val `circe-generic` = "io.circe" %% "circe-generic" % Versions.circe

  lazy val scalactic = "org.scalactic" %% "scalactic" % "3.1.1"
  lazy val scalatest = "org.scalatest" %% "scalatest" % "3.1.1"
  lazy val scalacheck = "org.scalacheck" %% "scalacheck" % "1.14.0"

  lazy val pureconfig = "com.github.pureconfig" %% "pureconfig" % Versions.pureconfig
  lazy val hikariCp = "com.zaxxer" % "HikariCP" % Versions.hikariCp

}

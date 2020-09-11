import sbt._

object Dependencies {

  object Versions {
    val cats = "2.1.1"
    val circe = "0.13.0"
    val pureconfig = "0.13.0"
    val doobie = "0.9.2"
    val postgresqlJdbcDriver = "42.2.6"
    val hikariCp = "3.4.5"
    val zio = "1.0.1"
  }

  lazy val zio = "dev.zio" %% "zio" %  Versions.zio
  lazy val zioStreams = "dev.zio" %% "zio-streams" %  Versions.zio

  lazy val zioInteropCats = "dev.zio" %% "zio-interop-cats" % "2.1.4.0"

  lazy val `kind-projector` = "org.typelevel" % "kind-projector" % "0.10.3"
  
  lazy val `scala-collection-compat` = "org.scala-lang.modules" %% "scala-collection-compat" % "2.1.6"

  lazy val postgresJdbcDriver = "org.postgresql" % "postgresql" % Versions.postgresqlJdbcDriver

  lazy val `doobie-postgres` = "org.tpolecat" %% "doobie-postgres" % Versions.doobie
  lazy val `doobie-hikari` = "org.tpolecat" %% "doobie-hikari" % Versions.doobie
  lazy val `doobie-scalatest` = "org.tpolecat" %% "doobie-scalatest" % Versions.doobie

  lazy val `cats-core` = "org.typelevel" %% "cats-core" % Versions.cats

  lazy val `circe-core` = "io.circe" %% "circe-core" % Versions.circe
  lazy val `circe-parser` = "io.circe" %% "circe-parser" % Versions.circe
  lazy val `circe-generic` = "io.circe" %% "circe-generic" % Versions.circe

  lazy val scalactic = "org.scalactic" %% "scalactic" % "3.2.2"
  lazy val scalatest = "org.scalatest" %% "scalatest" % "3.2.2"
  lazy val scalacheck = "org.scalacheck" %% "scalacheck" % "1.14.3"
  lazy val scalatestplus = "org.scalatestplus" %% "scalacheck-1-14" % "3.2.2.0"

  lazy val pureconfig = "com.github.pureconfig" %% "pureconfig" % Versions.pureconfig
  lazy val hikariCp = "com.zaxxer" % "HikariCP" % Versions.hikariCp

}

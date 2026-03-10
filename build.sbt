import Dependencies.*
import sbt.Keys.*
import sbt.*

inThisBuild(
  List(
    organization := "io.github.zyamba",
    organizationName := "zyamba",
    organizationHomepage := Some(url("https://github.com/zyamba")),
    scalaVersion := "3.3.7",

    resolvers += Resolver.mavenCentral,

    scalacOptions ++= Seq("-deprecation", "-feature"),

    libraryDependencies ++= Seq(
      `scala-collection-compat`, // Scala 2.13 collection compatibility
      scalactic,
      scalatest % Test,
      scalatestplus % Test,
      scalacheck % Test
    ),

    dependencyOverrides ++= Seq(
      `cats-core`,
    ),

    scmInfo := Some(
      ScmInfo(
        url("https://github.com/zyamba/shindy"),
        "scm:git@github.com:zyamba/shindy.git"
      )
    ),

    developers := List(
      Developer(
        id    = "ivanobulo",
        name  = "Ivan Luzyanin",
        email = "ivanobulo@gmail.com",
        url   = url("https://twitter.com/ivanobulo")
      )
    ),

    description := "Lightweight Composable Event Sourcing library for Scala",
    licenses := List("MIT" -> new URL("https://opensource.org/licenses/MIT")),
    homepage := Some(url("https://github.com/zyamba/shindy")),

  )
)

// Remove all additional repository other than Maven Central from POM
ThisBuild / pomIncludeRepository := { _ => false }
ThisBuild / publishMavenStyle := true

// new setting for the Central Portal
ThisBuild / publishTo := {
  val centralSnapshots = "https://central.sonatype.com/repository/maven-snapshots/"
  if (isSnapshot.value) Some("central-snapshots" at centralSnapshots)
  else localStaging.value
}

val DbTests = config("db").extend(Test)
configs(DbTests)

lazy val dbTestsCommonSettings = inConfig(DbTests)(Defaults.testTasks) ++ Seq(
  Test / testOptions := Tests.Argument("-l", "DatabaseTest") :: Nil,
  DbTests / testOptions := Tests.Argument("-n", "DatabaseTest") :: Nil
)

lazy val `shindy-core` = project settings (
  libraryDependencies ++= Seq(
    `cats-core`
  )
)

lazy val examples = project.settings(
  publish / skip := true,
  coverageEnabled := false
).dependsOn(`shindy-core`)

lazy val `shindy-eventstore`  = project
  .settings(
    libraryDependencies ++= Seq(
      `cats-core`,
      `cats-effect`,
      fs2,
    ),
    coverageEnabled := false, // eventstore-spec has the test
  ).dependsOn(`shindy-core`)

lazy val `shindy-eventstore-spec`  = project.configs(DbTests)
  .settings(
    dbTestsCommonSettings,
    libraryDependencies ++= Seq(
      `cats-core`,
      `cats-effect`,
      `cats-effect-testing`,
      scalatest,
      scalatestplus,
      scalacheck,
    )
  )
  .dependsOn(`shindy-eventstore`, examples)

lazy val `shindy-eventstore-postgres` = project.configs(DbTests).settings(
  dbTestsCommonSettings,
  libraryDependencies ++= Seq(
    `circe-core`,
    `circe-parser`,
    `circe-generic`,
    postgresJdbcDriver,
    `doobie-postgres`,
    `doobie-hikari` % Test,
    `doobie-scalatest` % Test,
    pureconfig % Test
  ),
  dependencyOverrides += hikariCp
).dependsOn(`shindy-eventstore`, `shindy-eventstore-spec` % Test)

lazy val root = project in file(".") settings(
  name := "shindy",
  publish / skip := true,
) aggregate(
  `shindy-core`,
  `shindy-eventstore`,
  `shindy-eventstore-spec`,
  `shindy-eventstore-postgres`,
  examples
)

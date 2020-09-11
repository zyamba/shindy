import Dependencies._
import ReleaseTransformations._
import sbt.Keys.testOptions
import sbt.Tests

inThisBuild(
  List(
    organization := "io.github.zyamba",
    organizationName := "zyamba",
    organizationHomepage := Some(url("https://github.com/zyamba")),
    scalaVersion := "2.13.1",

    crossScalaVersions := Seq("2.13.1", "2.12.8"),

    resolvers += Resolver.sonatypeRepo("releases"),

    addCompilerPlugin(`kind-projector` cross CrossVersion.binary),

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
      `zio`,
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
        url   = url("http://twitter.com/ivanobulo")
      )
    ),

    pomIncludeRepository := { _ => false },
    description := "Lightweight Composible Event Sourcing library for Scala",
    licenses := List("MIT" -> new URL("https://opensource.org/licenses/MIT")),
    homepage := Some(url("https://github.com/zyamba/shindy")),

  )
)

ThisBuild / publishTo := sonatypePublishToBundle.value
ThisBuild / publishMavenStyle := true

ThisBuild / useGpg := true

ThisBuild / releasePublishArtifactsAction := PgpKeys.publishSigned.value

ThisBuild / releaseCrossBuild := true
ThisBuild / releaseProcess := Seq[ReleaseStep](
  checkSnapshotDependencies,
  inquireVersions,
  runClean,
  runTest,
  setReleaseVersion,
  commitReleaseVersion,
  tagRelease,
  // For non cross-build projects, use releaseStepCommand("publishSigned")
  releaseStepCommandAndRemaining("+publishSigned"),
  setNextVersion,
  commitNextVersion,
  releaseStepCommand("sonatypeReleaseAll"),
  pushChanges
)

val DbTests = config("db").extend(Test)
configs(DbTests)

lazy val dbTestsCommonSettings = inConfig(DbTests)(Defaults.testTasks) ++ Seq(
  testOptions in Test := Tests.Argument("-l", "DatabaseTest") :: Nil,
  testOptions in DbTests := Tests.Argument("-n", "DatabaseTest") :: Nil
)

lazy val `shindy-core` = project settings (
  libraryDependencies ++= Seq(
    `cats-core`
  )
)

lazy val examples = project.settings(
  skip in publish := true
).dependsOn(`shindy-core`)

lazy val `shindy-eventstore`  = project
  .settings(
    libraryDependencies ++= Seq(
      zio,
      zioStreams,
    ),
    coverageEnabled := false, // eventstore-spec has the test
  ).dependsOn(`shindy-core`)

lazy val `shindy-eventstore-spec`  = project.configs(DbTests)
  .settings(
    dbTestsCommonSettings,
    libraryDependencies ++= Seq(
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
    zioInteropCats,
    postgresJdbcDriver,
    `doobie-postgres`,
    `doobie-hikari`,
    `doobie-scalatest` % Test,
    pureconfig % Test
  ),
  dependencyOverrides += hikariCp
).dependsOn(`shindy-eventstore`, `shindy-eventstore-spec` % Test)

lazy val root = project in file(".") settings(
  name := "shindy",
  skip in publish := true,
) aggregate(
  `shindy-core`,
  `shindy-eventstore`,
  `shindy-eventstore-spec`,
  `shindy-eventstore-postgres`,
  examples
)
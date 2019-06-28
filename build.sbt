import Dependencies._
import sbt.Keys.testOptions
import sbt.Tests

inThisBuild(
  List(
    organization := "io.github.zyamba",
    organizationName := "zyamba",
    organizationHomepage := Some(url("https://github.com/zyamba")),
    scalaVersion := "2.13.0",

    crossScalaVersions := Seq("2.13.0", "2.12.8"),

    resolvers += Resolver.sonatypeRepo("releases"),

    addCompilerPlugin("org.typelevel" % "kind-projector" % "0.10.3" cross CrossVersion.binary),

    scalacOptions ++= Seq("-deprecation", "-feature"),

    libraryDependencies ++= Seq(
      "org.scala-lang.modules" %% "scala-collection-compat" % "2.0.0", // Scala 2.13 compatibility
      scalactic % Test,
      scalatest % Test,
      scalacheck % Test
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
ThisBuild / publishTo := {
  val nexus = "https://oss.sonatype.org/"
  if (isSnapshot.value) Some("snapshots" at nexus + "content/repositories/snapshots")
  else Some("releases" at nexus + "service/local/staging/deploy/maven2")
}
ThisBuild / publishMavenStyle := true

ThisBuild / releasePublishArtifactsAction := PgpKeys.publishSigned.value

val DbTests = config("db").extend(Test)
configs(DbTests)

lazy val dbTestsCommonSettings = inConfig(DbTests)(Defaults.testTasks) ++ Seq(
  testOptions in Test := Tests.Argument("-l", "DatabaseTest") :: Nil,
  testOptions in DbTests := Tests.Argument("-n", "DatabaseTest") :: Nil
)

lazy val `shindy-core` = project settings (
  libraryDependencies ++= Seq(
    `cats-core`,
    `fs2-core`
  )
)

lazy val examples = project settings(
  skip in publish := true
) dependsOn `shindy-core`

lazy val `shindy-hydrate` = project
  .dependsOn(`shindy-core`, `scala213-compat`, examples % Test)

lazy val `scala213-compat` = project.settings(
  coverageEnabled := false
)

lazy val `shindy-eventstore-postgres` = project.configs(DbTests).settings (
  dbTestsCommonSettings,
  libraryDependencies ++= Seq(
    `circe-core`,
    `circe-parser`,
    `circe-generic`,
    `fs2-core`,
    postgresJdbcDriver,
    `doobie-postgres`,
    `doobie-hikari`,
    `doobie-scalatest` % Test,
    pureconfig % Test
  ),
  dependencyOverrides += hikariCp
).dependsOn(`shindy-hydrate`, examples % Test)

lazy val root = project in file(".") settings(
  name := "shindy",
  skip in publish := true,
) aggregate(`shindy-core`, `shindy-hydrate`, `shindy-eventstore-postgres`, examples)
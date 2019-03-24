import Dependencies._

inThisBuild(
  List(
    organization := "io.github.zyamba",
    organizationName := "zyamba",
    organizationHomepage := Some(url("https://github.com/zyamba")),
    scalaVersion := "2.12.8",

    addCompilerPlugin("org.spire-math" %% "kind-projector" % "0.9.8"),

    scalacOptions ++= Seq("-deprecation", "-feature"),

    libraryDependencies ++= Seq(
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


lazy val `shindy-core` = project settings (
  libraryDependencies ++= Seq(
    `cats-core`,
    `fs2-core`
  )
)

lazy val examples = project settings(
  skip in publish := true,
) dependsOn `shindy-core`

lazy val `shindy-hydrate` = project settings (
  libraryDependencies ++= Seq()
) dependsOn (`shindy-core`, examples % Test)

lazy val `shindy-eventstore-postgres` = project settings (
  libraryDependencies ++= Seq(
    `circe-core`,
    `circe-parser`,
    `circe-generic`,
    `fs2-core`,
    postgresJdbcDriver,
    `doobie-postgres`,
    `doobie-hikari`,
    `doobie-scalatest` % Test
  ),
  dependencyOverrides += "com.zaxxer" % "HikariCP" % "3.3.1"
) dependsOn(`shindy-hydrate`, examples % Test)

lazy val root = project in file(".") settings(
  name := "shindy",
  skip in publish := true,
) aggregate(`shindy-core`, `shindy-hydrate`, `shindy-eventstore-postgres`, examples)
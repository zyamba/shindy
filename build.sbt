inThisBuild(
  List(
    organization := "io.github.zyamba",
    organizationName := "zyamba",
    organizationHomepage := Some(url("https://github.com/zyamba")),
    scalaVersion := "2.12.6",

    addCompilerPlugin("org.spire-math" %% "kind-projector" % "0.9.8"),

    scalacOptions ++= Seq("-deprecation", "-feature"),

    libraryDependencies ++= Seq(
      "org.scalactic" %% "scalactic" % "3.0.5" % Test,
      "org.scalatest" %% "scalatest" % "3.0.5" % Test
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

val examples = project settings(
  skip in publish := true,
)
val `shindy-core` = project settings (
  libraryDependencies ++= Seq(
    "org.typelevel" %% "cats-core" % "1.5.0-RC1",
    "co.fs2" %% "fs2-core" % "1.0.0",
    //  "co.fs2" %% "fs2-io" % "1.0.0",
    "org.typelevel" %% "cats-effect" % "1.0.0" % Test,
    "org.scalacheck" %% "scalacheck" % "1.14.0" % Test
  )
)

val `shindy-eventstore-postgres` = project settings (
  libraryDependencies ++= Seq(
    "org.typelevel" %% "cats-core" % "1.5.0-RC1",
    "co.fs2" %% "fs2-core" % "1.0.0",
    //  "co.fs2" %% "fs2-io" % "1.0.0",
    "org.typelevel" %% "cats-effect" % "1.0.0" % Test,
  )
) dependsOn `shindy-core`

val root = project in file(".") settings(
  name := "shindy",
  skip in publish := true,
) aggregate(`shindy-core`, examples)
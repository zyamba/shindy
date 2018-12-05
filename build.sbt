inThisBuild(
  List(
    organization := "zyamba",
    version := "0.1",
    scalaVersion := "2.12.6",

    addCompilerPlugin("org.spire-math" %% "kind-projector" % "0.9.8"),

    scalacOptions ++= Seq("-deprecation", "-feature"),

    libraryDependencies ++= Seq(
      "org.scalactic" %% "scalactic" % "3.0.5" % Test,
      "org.scalatest" %% "scalatest" % "3.0.5" % Test
    )
  )
)

val examples = project in file("examples") settings()
val `shindy-core` = project in file("shindy-core") settings (
  libraryDependencies ++= Seq(
    "org.typelevel" %% "cats-core" % "1.5.0-RC1",
    "co.fs2" %% "fs2-core" % "1.0.0",
    //  "co.fs2" %% "fs2-io" % "1.0.0",
    "org.typelevel" %% "cats-effect" % "1.0.0" % Test,
  )
)

val root = project in file(".") settings(
  name := "shindy",
  publishArtifact := false,
) aggregate(`shindy-core`, examples)
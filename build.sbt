name := "shindy"

organization := "zyamba"

version := "0.1"

scalaVersion := "2.12.6"

libraryDependencies ++= Seq(
  "org.typelevel" %% "cats-core" % "1.5.0-RC1",
  "org.typelevel" %% "cats-effect" % "1.0.0",
  "co.fs2" %% "fs2-core" % "1.0.0",
  "co.fs2" %% "fs2-io" % "1.0.0",
  "org.scalactic" %% "scalactic" % "3.0.5" % Test,
  "org.scalatest" %% "scalatest" % "3.0.5" % Test
)

addCompilerPlugin("org.spire-math" %% "kind-projector" % "0.9.8")
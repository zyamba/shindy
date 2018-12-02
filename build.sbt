name := "shindy"

organization := "zyamba"

version := "0.1"

scalaVersion := "2.12.6"

libraryDependencies ++= Seq(
  "org.typelevel" %% "cats-core" % "1.5.0-RC1",
  "org.scalactic" %% "scalactic" % "3.0.5",
  "org.scalatest" %% "scalatest" % "3.0.5" % Test
)

addCompilerPlugin("org.spire-math" %% "kind-projector" % "0.9.8")
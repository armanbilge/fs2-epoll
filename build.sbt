ThisBuild / tlBaseVersion := "0.0"

ThisBuild / organization := "com.armanbilge"
ThisBuild / organizationName := "Arman Bilge"
ThisBuild / developers += tlGitHubDev("armanbilge", "Arman Bilge")
ThisBuild / startYear := Some(2023)
ThisBuild / tlSonatypeUseLegacyHost := false

val scala213 = "2.13.11"
ThisBuild / crossScalaVersions := Seq("2.12.18", scala213, "3.3.1")
ThisBuild / scalaVersion := scala213

ThisBuild / githubWorkflowJavaVersions := Seq(
  JavaSpec.temurin("17"),
  JavaSpec.temurin("11"),
  JavaSpec.temurin("8")
)

ThisBuild / githubWorkflowPublishPreamble +=
  WorkflowStep.Use(
    UseRef.Public("typelevel", "await-cirrus", "main"),
    name = Some("Wait for Cirrus CI")
  )

val ceVersion = "3.6-e9aeb8c"
val fs2Version = "3.9.1"
val jnrFfiVersion = "2.2.14"
val munitCEVersion = "2.0.0-M3"

lazy val root = project.in(file(".")).aggregate(epoll).enablePlugins(NoPublishPlugin)

lazy val epoll = project
  .in(file("epoll"))
  .settings(
    name := "fs2-epoll",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % ceVersion,
      "co.fs2" %% "fs2-io" % fs2Version,
      "com.github.jnr" % "jnr-ffi" % jnrFfiVersion,
      "org.typelevel" %% "munit-cats-effect" % munitCEVersion % Test
    ),
    Test / testOptions += Tests.Argument("+l")
  )

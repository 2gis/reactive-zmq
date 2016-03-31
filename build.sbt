name := "reactive-zmq"

organization := "ru.dgis"

val akkaVersion = "2.4.2"

scalaVersion := "2.11.8"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % akkaVersion,
  "com.typesafe.akka" %% "akka-stream" % akkaVersion,
  "org.zeromq" % "jeromq" % "0.3.5",
  "org.mockito" % "mockito-core" % "1.10.19" % "test",
  "org.scalatest" %% "scalatest" % "2.2.6" % "test",
  "com.typesafe.akka" %% "akka-testkit" % akkaVersion % "test",
  "com.typesafe.akka" %% "akka-stream-testkit" % akkaVersion % "test",
  "org.scalacheck" %% "scalacheck" % "1.12.5" % "test"
)

releaseVersionBump := sbtrelease.Version.Bump.Minor

bintrayReleaseOnPublish in ThisBuild := false

bintrayOrganization := Some("2gis")

licenses += "MPL-2.0" -> url("https://www.mozilla.org/en-US/MPL/2.0/")

publishTo := {
  if (isSnapshot.value)
    Some("OSS JFrog Snapshots" at "https://oss.jfrog.org/artifactory/oss-snapshot-local")
  else publishTo.value
}

bintrayCredentialsFile := Path.userHome / ".sbt" / ".bintray"

credentials += Credentials(Path.userHome / ".sbt" / ".jfrog")

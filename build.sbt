organization := "io.github.2gis"

name := "reactive-zmq"

val akkaVersion = "2.5.4"

scalaVersion := "2.12.3"

crossScalaVersions := Seq("2.11.11", "2.12.3")

scalacOptions ++= Seq("-feature", "-deprecation", "-Xlint")

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor"          % akkaVersion,
  "com.typesafe.akka" %% "akka-stream"         % akkaVersion,
  "org.zeromq"        %  "jeromq"              % "0.4.3",
  "org.mockito"       %  "mockito-core"        % "2.8.47"     % "test",
  "org.scalatest"     %% "scalatest"           % "3.0.1"      % "test",
  "com.typesafe.akka" %% "akka-testkit"        % akkaVersion  % "test",
  "com.typesafe.akka" %% "akka-stream-testkit" % akkaVersion  % "test",
  "org.scalacheck"    %% "scalacheck"          % "1.13.5"     % "test"
)


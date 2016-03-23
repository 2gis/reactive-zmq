name := "reactive-zmq"

organization := "ru.dgis"

val akkaVersion = "2.4.2"

scalaVersion := "2.11.8"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % akkaVersion,
  "com.typesafe.akka" %% "akka-stream" % akkaVersion,
  "org.zeromq" % "jeromq" % "0.3.5"
)

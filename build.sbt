name := "http-wait"
version := "0.0.1-SNAPSHOT"

scalaVersion := "2.13.2"

val http4sVersion = "0.21.3"

libraryDependencies ++= Seq(
  "ch.qos.logback" % "logback-classic" % "1.2.3",
  "io.monix" %% "monix" % "3.2.2",
  "org.http4s" %% "http4s-dsl" % http4sVersion,
  "org.http4s" %% "http4s-blaze-server" % http4sVersion,
  "org.http4s" %% "http4s-blaze-client" % http4sVersion,
)

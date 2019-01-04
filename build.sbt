name := "vk-dumper"
version := "0.2"
scalaVersion := "2.12.8"

mainClass in assembly := Some("vkdumper.VkDumper")
assemblyJarName in assembly := "vkdumper.jar"

test in assembly := {}

assemblyMergeStrategy in assembly := {
  case nt if nt.endsWith("META-INF/io.netty.versions.properties") => MergeStrategy.first
  case x => MergeStrategy.defaultMergeStrategy(x)
}


val akkaV = "2.5.18"
libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-stream",
  "com.typesafe.akka" %% "akka-actor",
  "com.typesafe.akka" %% "akka-slf4j"
).map(_ % akkaV)

val sttpV = "1.5.0"
libraryDependencies ++= Seq(
  "com.softwaremill.sttp" %% "core",
  "com.softwaremill.sttp" %% "async-http-client-backend-monix"
).map(_ % sttpV)

libraryDependencies ++= Seq(
  "com.github.scopt" %% "scopt" % "4.0.0-RC2",
  "org.json4s" %% "json4s-jackson" % "3.6.2",
  "io.monix" %% "monix-eval" % "3.0.0-RC2",
  "org.mapdb" % "mapdb" % "3.0.7",
  "com.typesafe.scala-logging" %% "scala-logging" % "3.9.0",
  "ch.qos.logback" % "logback-classic" % "1.2.3",
  "org.scalatest" %% "scalatest" % "3.0.5" % Test
)

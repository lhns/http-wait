name := "http-wait"
version := "0.0.1-SNAPSHOT"

scalaVersion := "2.13.10"

val V = new {
  val catsEffect = "3.4.5"
  val http4s = "0.23.17"
  val http4sJdkHttpClient = "0.7.0"
  val http4sProxy = "0.4.0"
  val logbackClassic = "1.4.5"
  val nativeimage = "22.3.0"
}

libraryDependencies ++= Seq(
  "ch.qos.logback" % "logback-classic" % V.logbackClassic,
  "de.lolhens" %% "http4s-proxy" % V.http4sProxy,
  "org.graalvm.nativeimage" % "svm" % V.nativeimage % Provided,
  "org.http4s" %% "http4s-ember-server" % V.http4s,
  "org.http4s" %% "http4s-dsl" % V.http4s,
  "org.http4s" %% "http4s-jdk-http-client" % V.http4sJdkHttpClient,
  "org.typelevel" %% "cats-effect" % V.catsEffect,
)

addCompilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1")

Compile / doc / sources := Seq.empty

version := {
  val tagPrefix = "refs/tags/"
  sys.env.get("CI_VERSION").filter(_.startsWith(tagPrefix)).map(_.drop(tagPrefix.length)).getOrElse(version.value)
}

assembly / assemblyJarName := s"${name.value}-${version.value}.sh.bat"

assembly / assemblyOption := (assembly / assemblyOption).value
  .withPrependShellScript(Some(AssemblyPlugin.defaultUniversalScript(shebang = false)))

assembly / assemblyMergeStrategy := {
  case PathList("module-info.class") => MergeStrategy.discard
  case x =>
    val oldStrategy = (assembly / assemblyMergeStrategy).value
    oldStrategy(x)
}

enablePlugins(
  GraalVMNativeImagePlugin
)

GraalVMNativeImage / name := (GraalVMNativeImage / name).value + "-" + (GraalVMNativeImage / version).value
graalVMNativeImageOptions ++= Seq(
  //"--static",
  "--no-server",
  "--no-fallback",
  "--initialize-at-build-time",
  "--install-exit-handlers",
  "--enable-url-protocols=http,https",
  "--allow-incomplete-classpath" /*logback-classic*/
)

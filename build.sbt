import com.typesafe.sbt.packager.docker.DockerAlias
import org.scalajs.linker.interface.ModuleSplitStyle

val ScalaLtsVersion     = "3.3.8"
val CatsEffectVersion   = "3.7.0"
val Fs2Version          = "3.13.0"
val Http4sStableVersion = "0.23.34"
val Ip4sVersion         = "3.8.0"
val WeaverVersion       = "0.13.0"
val LogbackVersion      = "1.5.35"
val Log4catsVersion     = "2.8.0"
val SkunkVersion        = "1.0.0"
val Smithy4sVersion     = "0.19.8"
val Fs2KafkaVersion     = "4.0.0"
val CirisVersion        = "3.9.0"
val AwsSdkVersion       = "2.49.2"
val Password4jVersion   = "1.8.4"
val JavaJwtVersion      = "4.6.0"

ThisBuild / organization := "org.typelevel"
ThisBuild / scalaVersion := ScalaLtsVersion
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / tlJdkRelease := Some(17)
ThisBuild / semanticdbEnabled := true
ThisBuild / semanticdbVersion := scalafixSemanticdb.revision

addCommandAlias("fix", "; scalafixAll; scalafmtAll; scalafmtSbt")
addCommandAlias("lint", "; scalafixAll --check; scalafmtCheckAll; scalafmtSbtCheck")

def serviceSettings(serviceName: String, mainClassName: String, exposedPort: Int) =
  Seq(
    name := serviceName,
    Compile / mainClass := Some(mainClassName),
    Compile / run / fork := true,
    dockerAlias :=
      DockerAlias(
        registryHost = None,
        username     = Some("typelevel-video-streaming"),
        name         = serviceName,
        tag          = Some("local")
      ),
    dockerBaseImage := "eclipse-temurin:17-jre-noble",
    dockerExposedPorts := Seq(exposedPort),
    dockerUpdateLatest := false
  )

lazy val root = project
  .in(file("."))
  .aggregate(backend)
  .enablePlugins(NoPublishPlugin)
  .settings(
    name := "typelevel-video-streaming",
    publish / skip := true
  )

lazy val backend = project
  .in(file("backend"))
  .aggregate(
    runtime,
    events,
    statusService,
    identityService,
    catalogService,
    playbackService
  )
  .enablePlugins(NoPublishPlugin)
  .settings(
    name := "backend",
    publish / skip := true
  )

lazy val runtime = project
  .in(file("backend/modules/runtime"))
  .enablePlugins(NoPublishPlugin)
  .settings(
    name := "runtime",
    Compile / exportJars := true,
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % CatsEffectVersion,
      "com.comcast" %% "ip4s-core" % Ip4sVersion,
      "co.fs2" %% "fs2-io" % Fs2Version,
      "is.cir" %% "ciris" % CirisVersion,
      "org.http4s" %% "http4s-ember-server" % Http4sStableVersion,
      "com.disneystreaming.smithy4s" %% "smithy4s-http4s" % Smithy4sVersion,
      "org.tpolecat" %% "skunk-core" % SkunkVersion,
      "com.auth0" % "java-jwt" % JavaJwtVersion,
      "org.typelevel" %% "weaver-cats" % WeaverVersion % Test
    ),
    testFrameworks += new TestFramework("weaver.framework.CatsEffect")
  )

lazy val events = project
  .in(file("backend/modules/events"))
  .enablePlugins(NoPublishPlugin, Smithy4sCodegenPlugin)
  .settings(
    name := "events",
    Compile / exportJars := true,
    libraryDependencies +=
      "com.disneystreaming.smithy4s" %% "smithy4s-core" % Smithy4sVersion
  )

lazy val statusService = project
  .in(file("backend/services/status-service"))
  .enablePlugins(JavaAppPackaging, DockerPlugin, NoPublishPlugin)
  .dependsOn(runtime)
  .settings(
    serviceSettings(
      serviceName   = "status-service",
      mainClassName = "org.typelevel.video.streaming.backend.status.Main",
      exposedPort   = 8080
    )
  )
  .settings(
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % CatsEffectVersion,
      "com.comcast" %% "ip4s-core" % Ip4sVersion,
      "org.http4s" %% "http4s-dsl" % Http4sStableVersion,
      "ch.qos.logback" % "logback-classic" % LogbackVersion % Runtime,
      "org.typelevel" %% "weaver-cats" % WeaverVersion % Test
    ),
    testFrameworks += new TestFramework("weaver.framework.CatsEffect")
  )

lazy val identityService = project
  .in(file("backend/services/identity-service"))
  .enablePlugins(JavaAppPackaging, DockerPlugin, NoPublishPlugin, Smithy4sCodegenPlugin)
  .dependsOn(runtime, events)
  .settings(
    serviceSettings(
      serviceName   = "identity-service",
      mainClassName = "org.typelevel.video.streaming.backend.identity.Main",
      exposedPort   = 8081
    )
  )
  .settings(
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % CatsEffectVersion,
      "com.disneystreaming.smithy4s" %% "smithy4s-core" % Smithy4sVersion,
      "com.comcast" %% "ip4s-core" % Ip4sVersion,
      "org.http4s" %% "http4s-dsl" % Http4sStableVersion,
      "org.tpolecat" %% "skunk-core" % SkunkVersion,
      "com.password4j" % "password4j" % Password4jVersion,
      "ch.qos.logback" % "logback-classic" % LogbackVersion % Runtime,
      "org.typelevel" %% "weaver-cats" % WeaverVersion % Test
    ),
    testFrameworks += new TestFramework("weaver.framework.CatsEffect")
  )

lazy val catalogService = project
  .in(file("backend/services/catalog-service"))
  .enablePlugins(JavaAppPackaging, DockerPlugin, NoPublishPlugin, Smithy4sCodegenPlugin)
  .dependsOn(runtime)
  .settings(
    serviceSettings(
      serviceName   = "catalog-service",
      mainClassName = "org.typelevel.video.streaming.backend.catalog.Main",
      exposedPort   = 8082
    )
  )
  .settings(
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % CatsEffectVersion,
      "com.disneystreaming.smithy4s" %% "smithy4s-core" % Smithy4sVersion,
      "com.comcast" %% "ip4s-core" % Ip4sVersion,
      "org.http4s" %% "http4s-dsl" % Http4sStableVersion,
      "org.tpolecat" %% "skunk-core" % SkunkVersion,
      "ch.qos.logback" % "logback-classic" % LogbackVersion % Runtime,
      "org.typelevel" %% "weaver-cats" % WeaverVersion % Test
    ),
    testFrameworks += new TestFramework("weaver.framework.CatsEffect")
  )

lazy val playbackService = project
  .in(file("backend/services/playback-service"))
  .enablePlugins(JavaAppPackaging, DockerPlugin, NoPublishPlugin, Smithy4sCodegenPlugin)
  .dependsOn(runtime, events)
  .settings(
    serviceSettings(
      serviceName   = "playback-service",
      mainClassName = "org.typelevel.video.streaming.backend.playback.Main",
      exposedPort   = 8083
    )
  )
  .settings(
    libraryDependencies ++= Seq(
      "software.amazon.awssdk" % "s3" % AwsSdkVersion,
      "org.typelevel" %% "fs2-kafka" % Fs2KafkaVersion,
      "software.amazon.awssdk" % "url-connection-client" % AwsSdkVersion,
      "software.amazon.awssdk" % "sts" % AwsSdkVersion,
      "ch.qos.logback" % "logback-classic" % LogbackVersion % Runtime,
      "org.typelevel" %% "weaver-cats" % WeaverVersion % Test
    ),
    testFrameworks += new TestFramework("weaver.framework.CatsEffect")
  )

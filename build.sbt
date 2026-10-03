import com.typesafe.sbt.packager.docker.DockerAlias
import com.typesafe.sbt.packager.universal.UniversalPlugin.autoImport.{
  stagingDirectory => packagingStagingDirectory,
}
import org.scalajs.linker.interface.{ModuleKind, ModuleSplitStyle}

val ScalaLtsVersion            = "3.9.0"
val CatsEffectVersion          = "3.7.1"
val Fs2Version                 = "3.14.0"
val Http4sStableVersion        = "0.23.37"
val Http4sOtel4sVersion        = "0.19.0"
val Ip4sVersion                = "3.8.0"
val WeaverVersion              = "0.13.0"
val LogbackVersion             = "1.5.38"
val Log4catsVersion            = "2.8.0"
val SkunkVersion               = "2.0.0-RC3"
val Smithy4sVersion            = "0.19.12"
val Fs2KafkaVersion            = "4.1.0"
val Fs2KafkaOtel4sVersion      = "0.2.0"
val Otel4sVersion              = "1.1.0"
val OpenTelemetryVersion       = "1.66.0"
val CirisVersion               = "3.15.1"
val AwsSdkVersion              = "2.49.6"
val Password4jVersion          = "1.8.4"
val JavaJwtVersion             = "4.6.1"
val OtelInstrumentationVersion = "2.31.1-alpha"
val TestcontainersVersion      = "2.0.5"
val DeclineVersion             = "2.6.2"

organization := "org.typelevel.video.streaming"
scalaVersion := ScalaLtsVersion
version := "0.1.0-SNAPSHOT"
scalacOptions ++= Seq("-release", "17")
javacOptions ++= Seq("--release", "17")
semanticdbEnabled := true
semanticdbVersion := scalafixSemanticdb.revision

addCommandAlias("fix", "; scalafixAll; scalafmtAll; scalafmtSbt")
addCommandAlias("lint", "; scalafixAll --check; scalafmtCheckAll; scalafmtSbtCheck")

Global / lintUnusedKeysOnLoad := false

val serviceMainClass = settingKey[String]("Main class for this service's application launcher")

def noPublishSettings =
  Def.settings(
    publish := {},
    publishLocal := {},
    publishArtifact := false,
    publish / skip := true,
  )

def serviceSettings(serviceName: String, mainClassName: String, exposedPort: Int) =
  Seq(
    name := serviceName,
    serviceMainClass := mainClassName,
    Compile / mainClass := Some(serviceMainClass.value),
    Compile / run / fork := true,
    Compile / run / javaOptions += "-Dcats.effect.trackFiberContext=true",
    dockerAlias :=
      DockerAlias(
        registryHost = None,
        username     = Some("typelevel-video-streaming"),
        name         = serviceName,
        tag          = Some("local"),
      ),
    dockerBaseImage := "eclipse-temurin:17-jre-noble",
    Docker / packagingStagingDirectory := (LocalRootProject / baseDirectory).value / "target" / "docker" / serviceName,
    dockerExposedPorts := Seq(exposedPort),
    dockerUpdateLatest := false,
    Universal / javaOptions += "-Dcats.effect.trackFiberContext=true",
  )

lazy val root = project
  .in(file("."))
  .aggregate(backend, frontend, apiContractsJS, trafficGenerator)
  .settings(noPublishSettings)
  .settings(
    name := "typelevel-video-streaming",
    publish / skip := true,
  )

lazy val frontend = project
  .in(file("frontend"))
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(apiContractsJS)
  .settings(noPublishSettings)
  .settings(
    name := "frontend",
    scalaJSUseMainModuleInitializer := true,
    Compile / fastLinkJS / scalaJSLinkerOutputDirectory :=
      baseDirectory.value / "target" / "scalajs-fast",
    Compile / fullLinkJS / scalaJSLinkerOutputDirectory :=
      baseDirectory.value / "target" / "scalajs-full",
    scalaJSLinkerConfig ~= {
      _.withModuleKind(ModuleKind.ESModule)
        .withModuleSplitStyle(ModuleSplitStyle.SmallModulesFor(List("typelevel.courses")))
    },
    libraryDependencies ++= Seq(
      "com.armanbilge" %% "calico" % "0.2.3",
      "com.armanbilge" %% "calico-router" % "0.2.3",
      "com.armanbilge" %% "fs2-dom" % "0.2.1",
      "co.fs2" %% "fs2-core" % Fs2Version,
      "com.disneystreaming.smithy4s" %% "smithy4s-http4s" % Smithy4sVersion,
      "org.http4s" %% "http4s-dom" % "0.2.12",
      "org.scala-js" %% "scalajs-dom" % "2.8.1",
      "org.typelevel" %% "cats-core" % "2.13.0",
      "org.typelevel" %% "cats-effect" % CatsEffectVersion,
      "io.circe" %% "circe-core" % "0.14.16",
      "io.circe" %% "circe-parser" % "0.14.16",
      "org.typelevel" %% "munit-cats-effect" % "2.2.1" % Test,
    ),
  )

lazy val backend = project
  .in(file("backend"))
  .aggregate(
    runtime,
    events,
    apiContracts,
    statusService,
    gatewayService,
    identityService,
    catalogService,
    playbackService,
  )
  .settings(noPublishSettings)
  .settings(
    name := "backend",
    publish / skip := true,
  )

lazy val runtime = project
  .in(file("backend/modules/runtime"))
  .settings(noPublishSettings)
  .settings(
    name := "runtime",
    Compile / exportJars := true,
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % CatsEffectVersion,
      "org.typelevel" %% "otel4s-instrumentation-metrics" % Otel4sVersion,
      "org.typelevel" %% "otel4s-oteljava" % Otel4sVersion,
      "org.typelevel" %% "otel4s-oteljava-context-storage" % Otel4sVersion,
      "io.opentelemetry" % "opentelemetry-exporter-otlp" % OpenTelemetryVersion % Runtime,
      "io.opentelemetry.instrumentation" % "opentelemetry-runtime-telemetry" % OtelInstrumentationVersion,
      "io.opentelemetry.instrumentation" % "opentelemetry-logback-appender-1.0" % OtelInstrumentationVersion,
      "org.typelevel" %% "log4cats-slf4j" % Log4catsVersion,
      "com.comcast" %% "ip4s-core" % Ip4sVersion,
      "co.fs2" %% "fs2-io" % Fs2Version,
      "is.cir" %% "ciris" % CirisVersion,
      "org.http4s" %% "http4s-ember-server" % Http4sStableVersion,
      "org.http4s" %% "http4s-otel4s-middleware-metrics" % Http4sOtel4sVersion,
      "org.http4s" %% "http4s-otel4s-middleware-core-client" % Http4sOtel4sVersion,
      "org.http4s" %% "http4s-otel4s-middleware-trace-server" % Http4sOtel4sVersion,
      "com.disneystreaming.smithy4s" %% "smithy4s-http4s" % Smithy4sVersion,
      "org.tpolecat" %% "skunk-core" % SkunkVersion,
      "ch.qos.logback" % "logback-classic" % LogbackVersion,
      "com.auth0" % "java-jwt" % JavaJwtVersion,
      "org.testcontainers" % "testcontainers-postgresql" % TestcontainersVersion % Test,
      "org.typelevel" %% "weaver-cats" % WeaverVersion % Test,
    ),
    testFrameworks += new TestFramework("weaver.framework.CatsEffect"),
  )

lazy val events = project
  .in(file("backend/modules/events"))
  .enablePlugins(Smithy4sCodegenPlugin)
  .settings(noPublishSettings)
  .settings(
    name := "events",
    Compile / exportJars := true,
    libraryDependencies +=
      "com.disneystreaming.smithy4s" %% "smithy4s-core" % Smithy4sVersion,
  )

lazy val apiContractsMatrix = projectMatrix
  .withId("apiContracts")
  .in(file("shared/api-contracts"))
  .enablePlugins(Smithy4sCodegenPlugin)
  .settings(noPublishSettings)
  .settings(
    name := "api-contracts",
    Compile / exportJars := true,
    libraryDependencies +=
      "com.disneystreaming.smithy4s" %% "smithy4s-core" % Smithy4sVersion,
    Compile / scalacOptions += "-Wconf:id=E230&src=.*/src_managed/.*:s",
  )
  .jvmPlatform(scalaVersions = Seq(ScalaLtsVersion))
  .jsPlatform(scalaVersions = Seq(ScalaLtsVersion))

lazy val apiContracts   = apiContractsMatrix.jvm(ScalaLtsVersion)
lazy val apiContractsJS = apiContractsMatrix.js(ScalaLtsVersion)

lazy val trafficGenerator = project
  .in(file("tools/traffic-generator"))
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .dependsOn(apiContracts)
  .settings(noPublishSettings)
  .settings(
    name := "traffic-generator",
    Compile / mainClass := Some("org.typelevel.video.streaming.traffic.TrafficGeneratorMain"),
    Compile / run / fork := true,
    Compile / run / javaOptions += "-Dcats.effect.trackFiberContext=true",
    Universal / javaOptions += "-Dcats.effect.trackFiberContext=true",
    dockerAlias := DockerAlias(
      None,
      Some("typelevel-video-streaming"),
      "traffic-generator",
      Some("local"),
    ),
    dockerBaseImage := "eclipse-temurin:17-jre-noble",
    Docker / packagingStagingDirectory := (LocalRootProject / baseDirectory).value / "target" / "docker" / "traffic-generator",
    dockerUpdateLatest := false,
    libraryDependencies ++= Seq(
      "com.monovore" %% "decline" % DeclineVersion,
      "org.typelevel" %% "cats-effect" % CatsEffectVersion,
      "co.fs2" %% "fs2-core" % Fs2Version,
      "org.http4s" %% "http4s-ember-client" % Http4sStableVersion,
      "com.disneystreaming.smithy4s" %% "smithy4s-http4s" % Smithy4sVersion,
      "io.circe" %% "circe-core" % "0.14.16",
      "io.circe" %% "circe-parser" % "0.14.16",
      "org.typelevel" %% "otel4s-oteljava" % Otel4sVersion,
      "org.typelevel" %% "otel4s-oteljava-context-storage" % Otel4sVersion,
      "io.opentelemetry" % "opentelemetry-exporter-otlp" % OpenTelemetryVersion % Runtime,
      "org.slf4j" % "slf4j-nop" % "2.0.17",
      "org.typelevel" %% "cats-effect-testkit" % CatsEffectVersion % Test,
      "org.typelevel" %% "weaver-cats" % WeaverVersion % Test,
    ),
    testFrameworks += new TestFramework("weaver.framework.CatsEffect"),
  )

lazy val statusService = project
  .in(file("backend/services/status-service"))
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .settings(noPublishSettings)
  .dependsOn(runtime)
  .settings(
    serviceSettings(
      serviceName   = "status-service",
      mainClassName = "org.typelevel.video.streaming.backend.status.Main",
      exposedPort   = 8080,
    ),
  )
  .settings(
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % CatsEffectVersion,
      "com.comcast" %% "ip4s-core" % Ip4sVersion,
      "org.http4s" %% "http4s-dsl" % Http4sStableVersion,
      "org.typelevel" %% "weaver-cats" % WeaverVersion % Test,
    ),
    testFrameworks += new TestFramework("weaver.framework.CatsEffect"),
  )

lazy val gatewayService = project
  .in(file("backend/services/gateway-service"))
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .settings(noPublishSettings)
  .dependsOn(runtime, apiContracts)
  .settings(
    serviceSettings(
      serviceName   = "gateway-service",
      mainClassName = "org.typelevel.video.streaming.backend.gateway.Main",
      exposedPort   = 8084,
    ),
  )
  .settings(
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % CatsEffectVersion,
      "org.http4s" %% "http4s-ember-client" % Http4sStableVersion,
      "org.http4s" %% "http4s-server" % Http4sStableVersion,
      "org.http4s" %% "http4s-otel4s-middleware-trace-client" % Http4sOtel4sVersion,
      "org.typelevel" %% "weaver-cats" % WeaverVersion % Test,
    ),
    testFrameworks += new TestFramework("weaver.framework.CatsEffect"),
  )

lazy val identityService = project
  .in(file("backend/services/identity-service"))
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .settings(noPublishSettings)
  .dependsOn(runtime % "compile->compile;runtime->runtime;test->test", events, apiContracts)
  .settings(
    serviceSettings(
      serviceName   = "identity-service",
      mainClassName = "org.typelevel.video.streaming.backend.identity.Main",
      exposedPort   = 8081,
    ),
  )
  .settings(
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % CatsEffectVersion,
      "com.disneystreaming.smithy4s" %% "smithy4s-core" % Smithy4sVersion,
      "com.comcast" %% "ip4s-core" % Ip4sVersion,
      "org.http4s" %% "http4s-dsl" % Http4sStableVersion,
      "org.tpolecat" %% "skunk-core" % SkunkVersion,
      "com.password4j" % "password4j" % Password4jVersion,
      "org.typelevel" %% "weaver-cats" % WeaverVersion % Test,
    ),
    testFrameworks += new TestFramework("weaver.framework.CatsEffect"),
  )

lazy val catalogService = project
  .in(file("backend/services/catalog-service"))
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .settings(noPublishSettings)
  .dependsOn(runtime % "compile->compile;runtime->runtime;test->test", apiContracts)
  .settings(
    serviceSettings(
      serviceName   = "catalog-service",
      mainClassName = "org.typelevel.video.streaming.backend.catalog.Main",
      exposedPort   = 8082,
    ),
  )
  .settings(
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % CatsEffectVersion,
      "com.disneystreaming.smithy4s" %% "smithy4s-core" % Smithy4sVersion,
      "com.comcast" %% "ip4s-core" % Ip4sVersion,
      "org.http4s" %% "http4s-dsl" % Http4sStableVersion,
      "org.tpolecat" %% "skunk-core" % SkunkVersion,
      "org.typelevel" %% "weaver-cats" % WeaverVersion % Test,
    ),
    testFrameworks += new TestFramework("weaver.framework.CatsEffect"),
  )

lazy val playbackService = project
  .in(file("backend/services/playback-service"))
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .settings(noPublishSettings)
  .dependsOn(runtime % "compile->compile;runtime->runtime;test->test", events, apiContracts)
  .settings(
    serviceSettings(
      serviceName   = "playback-service",
      mainClassName = "org.typelevel.video.streaming.backend.playback.Main",
      exposedPort   = 8083,
    ),
  )
  .settings(
    libraryDependencies ++= Seq(
      "software.amazon.awssdk" % "s3" % AwsSdkVersion,
      "org.typelevel" %% "fs2-kafka" % Fs2KafkaVersion,
      "io.github.irevive" %% "fs2-kafka-otel4s-trace" % Fs2KafkaOtel4sVersion,
      "software.amazon.awssdk" % "sts" % AwsSdkVersion,
      "org.typelevel" %% "weaver-cats" % WeaverVersion % Test,
    ),
    testFrameworks += new TestFramework("weaver.framework.CatsEffect"),
  )

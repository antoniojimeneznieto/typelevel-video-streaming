import com.typesafe.sbt.packager.docker.DockerAlias
import org.scalajs.linker.interface.ModuleKind

val ScalaLtsVersion = "3.3.8"
val CatsEffectVersion = "3.7.0"
val Fs2Version = "3.13.0"
val Http4sStableVersion = "0.23.34"
val Http4sDomVersion = "0.2.12"
val CalicoVersion = "0.2.3"
val Ip4sVersion = "3.8.0"
val ScalaJsDomVersion = "2.8.1"
val CirceVersion = "0.14.16"
val WeaverVersion = "0.13.0"
val LogbackVersion = "1.5.35"
val Log4catsVersion = "2.8.0"
val SkunkVersion = "1.0.0"
val Smithy4sVersion = "0.19.8"
val Fs2KafkaVersion = "4.0.0"
val JavaJwtVersion = "4.5.0"
val JwksRsaVersion = "0.22.1"
val CirisVersion = "3.9.0"
val FlywayVersion = "12.9.0"
val PostgresJdbcVersion = "42.7.11"

ThisBuild / organization := "org.typelevel.video.streaming"
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
        username = Some("typelevel-video-streaming"),
        name = serviceName,
        tag = Some("local")
      ),
    dockerBaseImage := "eclipse-temurin:17-jre-noble",
    dockerExposedPorts := Seq(exposedPort),
    dockerUpdateLatest := false
  )

lazy val root = project
  .in(file("."))
  .aggregate(backend, frontend)
  .enablePlugins(NoPublishPlugin)
  .settings(
    name := "typelevel-video-streaming",
    publish / skip := true
  )

lazy val backend = project
  .in(file("backend"))
  .aggregate(common, statusService, userService)
  .enablePlugins(NoPublishPlugin)
  .settings(
    name := "backend",
    publish / skip := true
  )

lazy val common = project
  .in(file("backend/modules/common"))
  .enablePlugins(NoPublishPlugin)
  .settings(
    name := "common",
    Compile / exportJars := true,
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % CatsEffectVersion,
      "com.comcast" %% "ip4s-core" % Ip4sVersion,
      "org.http4s" %% "http4s-core" % Http4sStableVersion,
      "org.typelevel" %% "log4cats-core" % Log4catsVersion,
      "is.cir" %% "ciris" % CirisVersion,
      "org.tpolecat" %% "skunk-core" % SkunkVersion,
      "com.auth0" % "java-jwt" % JavaJwtVersion,
      "com.auth0" % "jwks-rsa" % JwksRsaVersion,
      "org.typelevel" %% "log4cats-noop" % Log4catsVersion % Test,
      "org.typelevel" %% "weaver-cats" % WeaverVersion % Test
    ),
    testFrameworks += new TestFramework("weaver.framework.CatsEffect")
  )

lazy val statusService = project
  .in(file("backend/services/status-service"))
  .enablePlugins(JavaAppPackaging, DockerPlugin, NoPublishPlugin)
  .dependsOn(common)
  .settings(
    serviceSettings(
      serviceName = "status-service",
      mainClassName = "org.typelevel.video.streaming.backend.status.Main",
      exposedPort = 8080
    )
  )
  .settings(
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % CatsEffectVersion,
      "co.fs2" %% "fs2-core" % Fs2Version,
      "org.http4s" %% "http4s-dsl" % Http4sStableVersion,
      "org.http4s" %% "http4s-ember-server" % Http4sStableVersion,
      "ch.qos.logback" % "logback-classic" % LogbackVersion % Runtime,
      "org.typelevel" %% "weaver-cats" % WeaverVersion % Test
    ),
    testFrameworks += new TestFramework("weaver.framework.CatsEffect")
  )

lazy val userService = project
  .in(file("backend/services/user-service"))
  .enablePlugins(JavaAppPackaging, DockerPlugin, NoPublishPlugin, Smithy4sCodegenPlugin)
  .dependsOn(common)
  .settings(
    serviceSettings(
      serviceName = "user-service",
      mainClassName = "org.typelevel.video.streaming.backend.user.Main",
      exposedPort = 8080
    )
  )
  .settings(
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % CatsEffectVersion,
      "org.http4s" %% "http4s-ember-server" % Http4sStableVersion,
      "org.http4s" %% "http4s-ember-client" % Http4sStableVersion,
      "org.typelevel" %% "log4cats-slf4j" % Log4catsVersion,
      "org.tpolecat" %% "skunk-core" % SkunkVersion,
      "com.disneystreaming.smithy4s" %% "smithy4s-http4s" % Smithy4sVersion,
      "com.disneystreaming.smithy4s" %% "smithy4s-http4s-swagger" % Smithy4sVersion,
      "org.typelevel" %% "fs2-kafka" % Fs2KafkaVersion,
      "org.flywaydb" % "flyway-core" % FlywayVersion,
      "org.flywaydb" % "flyway-database-postgresql" % FlywayVersion,
      "org.postgresql" % "postgresql" % PostgresJdbcVersion % Runtime,
      "ch.qos.logback" % "logback-classic" % LogbackVersion % Runtime,
      "org.typelevel" %% "weaver-cats" % WeaverVersion % Test
    ),
    testFrameworks += new TestFramework("weaver.framework.CatsEffect")
  )

lazy val frontend = project
  .in(file("frontend"))
  .enablePlugins(ScalaJSPlugin, NoPublishPlugin)
  .settings(
    name := "frontend",
    scalaJSUseMainModuleInitializer := true,
    Compile / fastLinkJS / scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.ESModule)),
    Compile / fullLinkJS / scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.ESModule)),
    Compile / fastLinkJS / scalaJSLinkerOutputDirectory :=
      baseDirectory.value / "target" / "site",
    Compile / fullLinkJS / scalaJSLinkerOutputDirectory :=
      baseDirectory.value / "target" / "site",
    libraryDependencies ++= Seq(
      "com.armanbilge" %%% "calico" % CalicoVersion,
      "org.typelevel" %%% "cats-effect" % CatsEffectVersion,
      "co.fs2" %%% "fs2-core" % Fs2Version,
      "org.http4s" %%% "http4s-dom" % Http4sDomVersion,
      "io.circe" %%% "circe-parser" % CirceVersion,
      "org.scala-js" %%% "scalajs-dom" % ScalaJsDomVersion
    )
  )

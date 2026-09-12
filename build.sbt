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
  .enablePlugins(NoPublishPlugin)
  .settings(
    name := "typelevel-video-streaming",
    publish / skip := true
  )

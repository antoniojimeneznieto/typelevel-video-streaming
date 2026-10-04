package org.typelevel.video.streaming.lab

import cats.effect.IO
import fs2.Stream
import fs2.io.file.{CopyFlag, CopyFlags, Files, Path as Fs2Path}
import fs2.io.process.{ProcessBuilder, Redirect}
import fs2.text

import java.nio.file.Path

private[lab] object LabIo {
  private def builder(root: Path, args: Seq[String], environment: Map[String, String]) =
    ProcessBuilder(args.head, args.tail.toList)
      .withWorkingDirectory(Fs2Path.fromNioPath(root))
      .withExtraEnv(environment)

  def run(
      root: Path,
      args: Seq[String],
      environment: Map[String, String] = Map.empty,
      capture: Boolean                 = false,
      allowFailure: Boolean            = false,
  ): IO[String] = {
    val processBuilder = builder(root, args, environment)
    val configured     = if capture then
      processBuilder.withStdin(Redirect.Inherit).withRedirectErrorStream(true)
    else processBuilder.inheritStdio
    configured.spawn[IO].use { process =>
      for {
        output <-
          if capture then process.stdout.through(text.utf8.decode).compile.string
          else IO.pure("")
        code <- process.exitValue
        _    <- IO.raiseWhen(code != 0 && !allowFailure)(
               new IllegalStateException(
                 s"${args.head} exited with $code${if capture then s": ${output.trim}" else ""}",
               ),
             )
      } yield output
    }
  }

  def probe(
      root: Path,
      args: Seq[String],
      environment: Map[String, String] = Map.empty,
  ): IO[Option[String]] =
    builder(root, args, environment)
      .withStdin(Redirect.Inherit)
      .withStderr(Redirect.Discard)
      .spawn[IO]
      .use { process =>
        for {
          output <- process.stdout.through(text.utf8.decode).compile.string
          code   <- process.exitValue
        } yield Option.when(code == 0)(output)
      }

  def exists(path: Path): IO[Boolean] = Files[IO].exists(Fs2Path.fromNioPath(path))

  def isRegularFile(path: Path): IO[Boolean] =
    Files[IO].isRegularFile(Fs2Path.fromNioPath(path))

  def createDirectories(path: Path): IO[Unit] =
    Files[IO].createDirectories(Fs2Path.fromNioPath(path))

  def read(path: Path): IO[String] =
    Files[IO].readUtf8(Fs2Path.fromNioPath(path)).compile.string

  def writeAtomic(path: Path, contents: String): IO[Unit] = {
    val target    = Fs2Path.fromNioPath(path)
    val temporary = Fs2Path.fromNioPath(path.resolveSibling(path.getFileName.toString + ".tmp"))
    Stream.emit(contents).through(Files[IO].writeUtf8(temporary)).compile.drain *>
      Files[IO].move(temporary, target, CopyFlags(CopyFlag.ReplaceExisting, CopyFlag.AtomicMove))
  }
}

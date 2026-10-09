package org.typelevel.video.streaming.lab

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import fs2.Stream
import fs2.io.file.{CopyFlag, CopyFlags, Files, Flag, Flags, Path as Fs2Path}
import fs2.io.process.{ProcessBuilder, Redirect}
import fs2.text
import java.nio.file.Path
import java.nio.channels.OverlappingFileLockException
import scala.concurrent.duration.*

private[lab] object LabIo:
  final case class ProcessResult(exitCode: Int, stdout: String, stderr: String):
    def checked(command: String): IO[String] =
      IO.raiseWhen(exitCode != 0)(
        new IllegalStateException(s"$command exited with $exitCode: ${stderr.trim}"),
      ).as(stdout)

  private def builder(root: Path, args: Seq[String], environment: Map[String, String]) =
    ProcessBuilder(args.head, args.tail.toList)
      .withWorkingDirectory(Fs2Path.fromNioPath(root))
      .withExtraEnv(environment)

  def run(root: Path, args: Seq[String], environment: Map[String, String] = Map.empty): IO[Unit] =
    builder(root, args, environment).inheritStdio.spawn[IO].use { process =>
      for
        code <- process.exitValue
        _ <- IO.raiseWhen(code != 0)(new IllegalStateException(s"${args.head} exited with $code"))
      yield ()
    }

  def capture(
      root: Path,
      args: Seq[String],
      environment: Map[String, String] = Map.empty,
  ): IO[ProcessResult] =
    builder(root, args, environment).withStdin(Redirect.Inherit).spawn[IO].use { process =>
      for
        streams <- (
                     process.stdout.through(text.utf8.decode).compile.string,
                     process.stderr.through(text.utf8.decode).compile.string,
                   ).parTupled
        code <- process.exitValue
      yield ProcessResult(code, streams._1, streams._2)
    }

  def output(
      root: Path,
      args: Seq[String],
      environment: Map[String, String] = Map.empty,
  ): IO[String] =
    capture(root, args, environment).flatMap(_.checked(args.head))

  def probe(
      root: Path,
      args: Seq[String],
      environment: Map[String, String] = Map.empty,
  ): IO[Option[String]] =
    capture(root, args, environment).map(result => Option.when(result.exitCode == 0)(result.stdout))

  def exists(path: Path): IO[Boolean]         = Files[IO].exists(Fs2Path.fromNioPath(path))
  def isRegularFile(path: Path): IO[Boolean]  = Files[IO].isRegularFile(Fs2Path.fromNioPath(path))
  def createDirectories(path: Path): IO[Unit] =
    Files[IO].createDirectories(Fs2Path.fromNioPath(path))
  def read(path: Path): IO[String] = Files[IO].readUtf8(Fs2Path.fromNioPath(path)).compile.string

  /** Retry nonblocking lock acquisition so a queued CLI can still be canceled. */
  def exclusive(path: Path): Resource[IO, Unit] =
    Files[IO].open(Fs2Path.fromNioPath(path), Flags(Flag.Create, Flag.Write)).flatMap { handle =>
      Resource
        .makeFull[IO, handle.Lock] { poll =>
          def acquire: IO[handle.Lock] =
            handle.tryLock.recover { case _: OverlappingFileLockException => None }.flatMap {
              case Some(lock) => IO.pure(lock)
              case None => poll(IO.sleep(100.millis)) *> acquire
            }
          acquire
        }(handle.unlock)
        .void
    }

  def writeAtomic(path: Path, contents: String): IO[Unit] =
    Resource
      .make(
        IO.blocking(
          java.nio.file.Files.createTempFile(path.toAbsolutePath.getParent, ".lab-", ".tmp"),
        ),
      ) { temporary =>
        Files[IO].deleteIfExists(Fs2Path.fromNioPath(temporary)).void
      }
      .use { temporary =>
        val source = Fs2Path.fromNioPath(temporary)
        for
          _ <- Stream.emit(contents).through(Files[IO].writeUtf8(source)).compile.drain
          _ <- Files[IO].move(
                 source,
                 Fs2Path.fromNioPath(path),
                 CopyFlags(CopyFlag.ReplaceExisting, CopyFlag.AtomicMove),
               )
        yield ()
      }

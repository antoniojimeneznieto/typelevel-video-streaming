# Typelevel video streaming platform

One repository, two Scala runtimes — a JVM backend and a Scala.js frontend sharing the same build.

## Stack

**Backend** (JVM)
- [Cats Effect](https://typelevel.org/cats-effect/) — async runtime
- [FS2](https://fs2.io/) — streaming
- [http4s](https://http4s.org/) + Ember — HTTP server
- [Skunk](https://tpolecat.github.io/skunk/) — PostgreSQL client
- [Smithy4s](https://disneystreaming.github.io/smithy4s/) — API code generation
- [fs2-kafka](https://fd4s.github.io/fs2-kafka/) — Kafka client
- [Log4Cats](https://typelevel.org/log4cats/) + Logback — logging

**Frontend** (Scala.js)
- [Calico](https://www.armanbilge.com/calico/) — reactive UI
- [http4s-dom](https://http4s.org/v0.23/docs/dom.html) — browser HTTP client via Fetch
- [FS2](https://fs2.io/) — streaming

**Dev script** (`scripts/dev.sh`)
- Written in Bash
- Builds the backend Docker images, starts services via Docker Compose, watches frontend sources, and serves the app with Python's built-in HTTP server

## Requirements

| Tool | Version |
|---|---|
| Bash | 4.3+ |
| Java | 17+ |
| sbt | 1.12+ |
| Docker | with Compose plugin |
| Python | 3 |

## Local development

```bash
./scripts/dev.sh
```

Open <http://localhost:4500/>.

Press `Ctrl+C` to stop all processes and Docker Compose services.

Ports can be overridden:

```bash
FRONTEND_PORT=3001 STATUS_SERVICE_PORT=18082 USER_SERVICE_PORT=18083 ./scripts/dev.sh
```

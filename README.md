# Typelevel Video Streaming

A demo video streaming platform for the Scala Days 2026 interactive lab:
**A Distributed System On Fire: Diagnosing Failures with otel4s**.

## Stack

- Backend: [Cats Effect](https://typelevel.org/cats-effect/), [FS2](https://fs2.io/),
  [http4s](https://http4s.org/), [Skunk](https://tpolecat.github.io/skunk/),
  and [Smithy4s](https://disneystreaming.github.io/smithy4s/).
- Frontend: [Calico](https://www.armanbilge.com/calico/) on Scala.js.
- Telemetry: [otel4s](https://typelevel.org/otel4s/) with Grafana's OpenTelemetry LGTM stack.

## Requirements

- Java 17+ (JDK)
- sbt
- Docker with Compose v2, running

### Install missing tools

On macOS or Ubuntu/Debian Linux (including WSL):

```bash
bash scripts/setup.sh
```

## Run locally

From the repository root:

```bash
./scripts/start.sh
```

The script builds the images, initializes the databases, uploads the bundled demo videos to MinIO,
and starts the frontend, backend services, and infrastructure. The first run can take several minutes.

Open [http://localhost:3000](http://localhost:3000).

Grafana is available at [http://localhost:3001](http://localhost:3001) (login: `admin` / `admin`).
The four backend services export HTTP traces and metrics to the bundled collector over the Docker network.

The stack runs in the background. To stop it:

```bash
docker compose down
```

Runtime data is ephemeral: stopping the PostgreSQL, Kafka, or MinIO containers clears their data.

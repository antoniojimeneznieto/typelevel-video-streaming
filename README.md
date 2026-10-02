# Typelevel Video Streaming

A demo video streaming platform for the Scala Days 2026 interactive lab:
**A Distributed System On Fire: Diagnosing Failures with otel4s**.

Facilitators: use the [lab playbook](playbook.md) for setup, delivery, and reset.
It contains the hidden incident diagnosis; share only its participant brief.

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
./scripts/lab.sh start
```

The script builds the images, initializes the databases, uploads the bundled demo videos to SeaweedFS,
and starts the frontend, backend services, and infrastructure. The first run can take several minutes.

Open [http://localhost:8000](http://localhost:8000).

Grafana is available at [http://localhost:3000](http://localhost:3000) (login: `admin` / `admin`).
The four backend services export HTTP traces and metrics to the bundled collector over the Docker network.

The stack runs in the background. To stop it:

```bash
./scripts/lab.sh status
./scripts/lab.sh stop
```

Runtime data is ephemeral: stopping the PostgreSQL, Kafka, or SeaweedFS containers clears their data.

## Generate workshop traffic

With the application running:

```bash
./scripts/lab.sh traffic build
./scripts/lab.sh traffic run --rate 5 --duration 3m
```

The FS2 generator calls the gateway using shared Smithy4s contracts and reports
offered load, completions, failures, missed arrivals, and latency independently of
application telemetry. See [traffic generator usage](docs/observability/traffic-generator.md)
for continuous traffic, configuration, and accounting semantics.

## Run a lab incident

With the stack and traffic running, start the incident using the exercise code
provided by the facilitator:

```bash
./scripts/lab.sh incident start CODE
./bin/platform changes
./bin/platform inspect CHANGE_ID
./bin/platform rollback CHANGE_ID
```

The change list and inspection command give participants a reviewable route to
remediation. Run traffic separately with `./scripts/lab.sh traffic start --rate 5`
and stop it with `./scripts/lab.sh traffic stop`. Facilitator setup and reset
controls are documented in [Scenario 1](docs/observability/scenario-1.md).

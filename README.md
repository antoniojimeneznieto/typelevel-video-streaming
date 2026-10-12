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

With Nix, `nix develop` provides JDK 21, sbt, Node.js 22, and the command line
tools used by the lab scripts. Docker must be installed and running on the host,
with Compose v2 and Buildx available. Check it with `bash scripts/setup.sh --check`
inside the development shell. The sbt version is selected by
`project/build.properties`.

## Run locally

From the repository root:

```bash
./scripts/lab.sh start
```

The script pulls the published images, initializes the databases, uploads the bundled demo videos to SeaweedFS,
and starts the frontend, backend services, and infrastructure. The first run can take several minutes.

To build images from your local checkout instead of pulling published images:

```bash
./scripts/lab.sh start --build
```

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
application telemetry. Use `./scripts/lab.sh traffic --help` for available commands.

## Run a lab incident

Use the incident ID provided by the facilitator (`1`, `2`, `3`, or `4`):

```bash
./scripts/lab.sh incident start ID
```

This prepares the services and scenario data, starts healthy traffic, and waits
for a valid 60-second baseline with fresh metrics and traces. It prints the
Grafana URL. The first run downloads the release images and warms the Scala
compiler for later source edits. Complete setup before the workshop when possible.

For an unpublished checkout or local image build, use:

```bash
./scripts/lab.sh incident start ID --build
```

Inspect the baseline, then activate when instructed:

```bash
./scripts/lab.sh incident activate ID
```

For source-editing rounds, deploy your diagnostics or repair with:

```bash
./scripts/lab.sh incident rebuild
```

Rebuild preserves the workload and scenario settings. Readiness confirms the
new deployment emits telemetry; verify the repair with fresh evidence under
the same workload.
Repeated setup or activation commands preserve an already-running scenario.
Use `incident status` to inspect its phase and `incident restart ID` for a
fresh baseline using the saved exercise images. Restart preserves local source
edits and telemetry history; rebuilding those edits deploys them again.

If a step fails, fix the reported problem and retry the same command. Process
output is saved in `.lab/commands.log`, and lifecycle timestamps in
`.lab/incident-history.jsonl`. Initial exercise image IDs are saved per checkout;
use a fresh checkout when changing workshop releases.

Platform changes remain available for investigation:

```bash
./scripts/lab.sh platform changes
./scripts/lab.sh platform inspect CHANGE_ID
./scripts/lab.sh platform rollback CHANGE_ID
```

## Help and troubleshooting

```bash
./scripts/lab.sh help
./scripts/lab.sh incident status
./scripts/lab.sh traffic status
```

Keep Grafana on the same service, route, and time window when comparing
metrics, traces, and logs. After a change, check fresh requests and sustained
recovery; a ready deployment alone does not confirm a repair.

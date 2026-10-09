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

Use the incident ID provided by the facilitator (`1`, `3`, `4`, or `5`):

```bash
./scripts/lab.sh incident start ID
```

This prepares the services and scenario data, starts healthy traffic, and waits
for a valid 60-second baseline with fresh metrics and traces. It prints the
Grafana URL. The first run downloads the release images and warms the Scala
compiler for later source edits; run it before the session. For an unpublished
checkout, use `incident start ID --build` on the first run.

Inspect the baseline, then activate when instructed:

```bash
./scripts/lab.sh incident activate ID
```

For source-editing rounds, deploy your diagnostics or repair with:

```bash
./scripts/lab.sh incident rebuild
```

Rebuild preserves the workload and scenario settings. Readiness confirms the
new deployment emits telemetry; participants must still verify the repair.
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

See [the facilitator playbook](playbook.md) for scenario evidence, source edits,
recovery checks, and rehearsal commands. Low-level preparation and traffic
commands remain available for facilitator diagnostics.

The scenario controller is a packaged JVM command. Build its ZIP with
`sbt --batch 'labCli/Universal/packageBin'`. The publishing workflow provides a
CLI ZIP and checksum as a workflow artifact for every run and as release assets
for `v*` tags. After unpacking, run
`bin/lab-cli --root /path/to/typelevel-video-streaming platform changes`.
The command needs Java 17+ and a checkout containing the lab scripts and Compose
configuration. In a checkout, `./scripts/lab.sh` stages the Scala CLI on first use
and runs all lab commands.

To test publishing from a private repository, push the branch and run
`./scripts/publish.sh branch-name`. The workflow publishes versioned images to
GHCR and uploads the CLI ZIP as a workflow artifact. New GHCR packages are
private by default; grant Codespaces access to private packages or change each
package's visibility to public for anonymous pulls. A `v*` tag also publishes
the CLI ZIP and checksum as GitHub Release assets. Release assets in a private
repository require repository access. PostgreSQL, Kafka, and Debezium are pulled
from their upstream registries.

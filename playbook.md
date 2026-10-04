# Observability lab facilitator playbook

This is the operating guide for a facilitator running **A Distributed System On
Fire: Diagnosing Failures with otel4s**. It contains the hidden setup and
diagnosis. Share only the **participant brief** in the round section with
attendees. [lab-scenarios.md](lab-scenarios.md) is the scenario specification;
this file is the sequence of actions to run the workshop.

## Current readiness

Scenario 1 is rehearsed on the full stack. Scenario 3 has a prebuilt exercise
source, Identity traffic, and a workload check; its timing remains sensitive to
host capacity. Scenario 4 has a rehearsed mixed-actor workload and seeded
Playback projections; recheck it on the workshop host before presenting it.
The other live rounds in
[lab-scenarios.md](lab-scenarios.md) are designs, not runnable playbook steps yet.
Do not advertise the complete two-hour sequence until those rounds and their
reset paths have been rehearsed. Scenario 1 has not yet been calibrated on the
minimum supported laptop and Codespaces environments.

The platform change list currently contains the relevant change and any
changes from previous runs on that machine. Plausible unrelated changes have
not been added yet; do not rely on the change list as a difficult search task.

## How to use this playbook

Keep the same rhythm for every round as more scenarios become runnable:

1. **Prepare:** Start the stack, check telemetry, and establish a healthy
   traffic window.
2. **Present:** Give only the user symptom. Keep the hidden mechanism and
   expected diagnosis in facilitator notes.
3. **Investigate:** Ask for the affected population and start time, then for
   rate, error, latency, trace, and adjacent-layer evidence.
4. **Remediate:** Have participants apply the smallest reviewable correction.
   Keep the triggering workload unchanged.
5. **Verify:** Require a fresh sustained healthy window and new traces. Record
   the lesson that transfers to participants' own services.
6. **Reset:** Leave fault controls healthy before the next group or round.

Use a shared worksheet with these fields: symptom and affected users; first
abnormal signal; adjacent layers ruled out; decisive trace or log evidence;
change inspected; fix; recovery evidence; and transfer lesson.

### Delivery formats

| Format | Who types the incident command? | What attendees need |
| --- | --- | --- |
| Projector/shared stack | Facilitator, from the repository root | The symptom brief, Grafana, and a view of the participant commands when relevant. |
| Individual laptops | Each attendee, after the facilitator announces the exercise code | A running local stack, the repository, and local Grafana. Each laptop has its own traffic stream, change ledger, and fault state. |

The neutral incident command is safe to project. The `proxy` commands and the
diagnostic sections below are facilitator controls. The source and facilitator
documents are in the repository, so the exercise code is a presentation aid,
not a secrecy mechanism.

## Before the session

From the repository root, on every machine that will run the lab:

```bash
bash scripts/setup.sh --check
./scripts/lab.sh start
./scripts/lab.sh status
./scripts/lab.sh proxy check
```

The setup check expects a JDK 17+, sbt, Docker Compose v2, Docker Buildx, and a
running Docker daemon. `start` launches the full application, including
PostgreSQL, Kafka, Debezium, SeaweedFS, frontend, Toxiproxy, and LGTM. It
normally pulls images tagged for the current Git commit. If that tag is not
available or cannot be accessed, build this checkout locally with
`./scripts/lab.sh start --build`; allow substantially more preparation time.
The startup log path is printed by the script. Do not start a group exercise
until startup and readiness checks complete.
Traffic commands use the running Gateway container's image prefix and tag by
default, so a locally built stack uses its local generator image in a new shell.
All lab commands use the Scala lab CLI. `./scripts/lab.sh` stages it with sbt on
first use. CI also provides a `lab-cli-jvm` ZIP artifact;
its `bin/lab-cli --root /path/to/checkout` launcher can run from outside the
checkout, with Java 17+ installed.

Open the application at `http://localhost:8000` and Grafana at
`http://localhost:3000` (`admin` / `admin` in the local stack). In Codespaces,
use the forwarded URLs printed by startup. Open the **Workshop Overview**,
**Workshop Traffic**, and **Gateway and Catalog Boundary** dashboards. Confirm
they load. The Scenario 1 dashboard is linked from Workshop Overview.

Before admitting participants, run the automated rehearsal on the same image
set they will use:

```bash
./scripts/lab.sh verify scenario1
```

It takes roughly two minutes at its default observation windows, starts and
stops its own traffic, and resets the proxy on exit. It verifies aligned rates,
the latency symptom, a faulted distributed trace, participant rollback, and a
fresh recovery trace. A failure means the round needs investigation before it
is presented. The script prints the metric values and trace IDs it observed.
The independent generator summary is available with
`docker logs typelevel-video-streaming-lab-traffic` after the run.
Use `--grafana URL`, `--rate N`, and `--window SECONDS` when the local defaults
do not match the rehearsal environment; the window must be at least 35 seconds.

## Round 1: slow Catalog-facing requests

### Participant brief — share this paragraph only

> Catalog-facing requests became slow shortly after a routine platform change.
> Locate the delay and restore normal response time. Use telemetry to explain
> where the added time occurs, then show that new requests recover while traffic
> continues.

### Facilitator preparation

First restore a healthy proxy and start one continuous Catalog-read stream:

```bash
./scripts/lab.sh proxy reset
./scripts/lab.sh traffic start --rate 5
./scripts/lab.sh traffic status
```

Allow **at least 40 seconds** for JVM warmup and a healthy metrics window.
Record the time, Gateway server p95, Gateway Catalog client p95, Catalog server
p95, and the four rates on the dashboard: offered, Gateway inbound, Gateway
Catalog client, and Catalog server. Check the generator report for
`load_valid=true`, no dropped arrivals, and no failures. Do not start a second
traffic process. Keep this process running through fault and recovery.

Then activate the incident. On a projector, the facilitator types it; on
individual laptops, announce the code and have attendees type it:

```bash
./scripts/lab.sh incident start 8f27
```

The command prints a neutral change ID. It does not print the proxy setting.
Note the activation time privately. Allow about 40 seconds before comparing a
clean fault window; dashboard rate queries need time to accumulate data.

### Investigation prompts

Ask these in order, without naming the fault:

1. Which requests are affected? Is the offered rate steady, and are requests
   failing or only slowing?
2. Which service or dependency call first accounts for the added latency?
3. Does the downstream server spend the same amount of time as its caller?
4. In one trace begun **after** activation, where is the time between the
   Gateway Catalog client span and the Catalog server child span? Are the SQL
   spans also slow?
5. Which recent platform change could explain that boundary evidence?

Use the **Gateway and Catalog Boundary** dashboard for aligned rates and p95
latencies. Click a latency exemplar to open a Tempo trace; select a trace from
the incident period. The **Workshop Traffic** dashboard and generator JSON
reports provide independent offered-load and drop accounting. An aggregate
p95 difference points to a boundary; compare individual spans for the actual
per-request gap.

Participants inspect and remediate through:

```bash
./scripts/lab.sh platform changes
./scripts/lab.sh platform inspect CHANGE_ID
./scripts/lab.sh platform rollback CHANGE_ID
```

`CHANGE_ID` is the value printed at activation or listed by `changes`.
Rollback reconciles the proxy to the healthy mapping and can be repeated.
Participant remediation is the rollback, not a direct proxy reset.

### Expected evidence — facilitator only

- Gateway server and Gateway Catalog client duration rise together; Catalog
  server and SQL spans remain comparatively short.
- Offered, Gateway inbound, client-attempt, and Catalog server rates stay
  aligned. The generator reports no dropped arrivals or failed requests.
- A faulted trace contains a long Gateway Catalog client span with a short
  Catalog server child span. The request reached Catalog.
- The inspected east-west traffic-policy change shows a non-default Catalog
  egress delay. Rolling it back removes the delay.

On one local run at five requests per second, Gateway client p95 moved from
about 23 ms to 988 ms and back to 23 ms. Catalog server p95 stayed around
10–12 ms. These figures are examples, not thresholds to promise across
machines. The automated check uses broad smoke bounds; calibrate the live
timebox on the machines used for the workshop.

### Recovery and group walkthrough

Keep the same traffic running for **at least 40 seconds** after rollback.
Compare a recent recovery window with the recorded baseline: Gateway server
and client latency return near their healthy range, Catalog remains healthy,
rates remain aligned, and a **new** trace has a short client/server gap. Old
faulted traces remain in history and are not evidence of a failed rollback.

Have participants explain the cause using a trace and at least one rate
comparison. Close with the lesson: a slow dependency call does not establish
that the dependency's own work is slow; adjacent client and server spans locate
time at their boundary.

### Reset for the next group

After recovery has been demonstrated:

```bash
./scripts/lab.sh traffic stop
./scripts/lab.sh proxy status
```

`proxy status` must show `enabled: true` and an empty `toxics` list. If a
participant did not complete rollback, use their change ID with
`./scripts/lab.sh platform rollback CHANGE_ID` first. `./scripts/lab.sh proxy reset` is a
facilitator escape hatch; follow it with the participant rollback command so
the local change ledger also records the repair. Let a new healthy telemetry
window accumulate before repeating the round. Do not clear Grafana history to
make the next run look clean.

## Round 3: Identity compute starvation

### Participant brief — share this paragraph only

> Authentication activity increased. Logins and unrelated lightweight Identity
> requests became slow, while PostgreSQL appears healthy. Explain where the
> delay occurs and make lightweight requests responsive under the same load.

### Facilitator preparation

Build the full stack from this checkout before the workshop so the checked-in
Identity exercise source is in the image. It runs synchronous password
verification in `IO.delay` from startup. Build the generator and start baseline:

```bash
./scripts/lab.sh traffic build
./scripts/lab.sh scenario3 baseline
./scripts/lab.sh traffic status
```

The baseline sends 30 requests per second through Gateway, with 5% logins and
95% authenticated `GET /users/me` requests from one synthetic actor. The
generator registers that actor once, then obtains a token. Keep its JSON
reports; the `operations` field separates login and current-user duration.
Allow at least 30 seconds for warmup. `load_valid` covers the entire generator
run; `window.load_valid` covers the latest reporting interval. Record any
earlier dropped arrivals, then use clean recent windows to assess ongoing load.
The Compose Identity service is limited
to four CPUs and configured with two Cats Effect compute workers for this
exercise. Calibrate on the actual laptop before presenting it.

Activate the login-heavy mix with the neutral command:

```bash
./scripts/lab.sh incident start 3c91
```

This stops the baseline generator and starts a new one at the **same total rate**,
now 70% logins. Record the workload change time. Wait at least 25 seconds and
inspect `./scripts/lab.sh traffic status`. Require clean recent windows with
`window.load_valid=true`, no failures, and no new dropped arrivals before
interpreting latency. This is a
workload activation, so a brief gap between the two generator containers is
expected.

### Investigation prompts

1. Are both `POST /auth/login` and `GET /users/me` affected? Does the latter
   perform password hashing?
2. Where do Gateway and Identity server spans spend time? Are SQL and session
   acquisition spans large enough to account for it?
3. Are Identity's CPU, JVM thread, and Cats Effect starvation signals elevated?
4. Inspect `PasswordHasher.scala`: on which Cats Effect executor does the
   synchronous Argon2 verification run?

The generator summary gives independent offered, completed, dropped, and
per-operation latency evidence. Filter HTTP dashboard panels by Identity route
and compare a recent window. Use fresh traces from both Identity operations;
traces locate the service, and source/runtime evidence explains the scheduler
pressure. Do not put the lab actor, token, or password in telemetry.

### Participant remediation and proof

The core round ends when participants identify the `IO.delay` boundary and
explain the unrelated request slowdown with telemetry and source evidence.
For groups with time to implement the fix, change verification to `IO.blocking`
in `backend/services/identity-service/src/main/scala/org/typelevel/video/streaming/backend/identity/PasswordHasher.scala`. Keep the existing
`permits.permit.use` around both hash and verify. The semaphore limits
concurrent Argon2 operations to four. Review the source diff, then deploy it:

```bash
./scripts/lab.sh rebuild identity-service
```

Keep the 30 requests per second, 70% login workload running across the rebuild.
After warmup, require a fresh sustained window with `load_valid=true`, no
drops, and a lower `identity-current-user` latency. Check fresh traces and SQL
timing. In one local rehearsal of the checked-in exercise image, current-user
mean latency moved from 9.6 ms in the light mix to 61.5 ms in the heavy mix;
both samples used valid load. These are local calibration examples, not
universal thresholds.

The facilitator can run `./scripts/lab.sh verify scenario3` before the session. It
runs light and heavy samples against the prebuilt exercise image and checks that
the lightweight operation slows under valid load. It does not edit source.

### Reset for the next group

```bash
./scripts/lab.sh scenario3 restore
```

This stops the generator. If participants changed source and rebuilt Identity,
restore the exercise checkout or redeploy its prebuilt image before the next
group. Do not clear telemetry history.

## Round 4: stable minority-user Playback 401

### Participant brief — share this paragraph only

> A small, repeatable share of authenticated Playback requests returns `401`.
> Those users can log in, and most users are unaffected. Find the compatibility
> boundary without weakening authentication.

### Facilitator preparation

Build the current checkout so Identity can issue both documented subject
formats. Playback intentionally accepts only raw UUID subjects. Its workshop
read mode skips Kafka projection consumption; the setup command enables it, registers ten
legitimate actors and seeds their Playback user rows directly. It assumes the
default local Gateway port `8085`.

```bash
./scripts/lab.sh rebuild identity-service
./scripts/lab.sh traffic build
./scripts/lab.sh scenario4 prepare
./scripts/lab.sh scenario4 baseline
./scripts/lab.sh traffic status
```

The baseline sends 10 authenticated `GET /api/playback/favorites` requests per
second from eight old-format actors. Identity authenticates all ten accounts
during generator setup, including the two newer-format accounts, before
starting traffic. Verify clean recent windows and `200` responses.

Start the mixed workload with the neutral participant command:

```bash
./scripts/lab.sh incident start 7b42
```

The same ten requests per second now use the two newer-format actors for 20%
of slots. Actor identities and routing remain stable. Observe roughly 20%
`401` responses while `load_valid` or recent `window.load_valid` is true and
there are no dropped arrivals. Do not publish token contents, subjects, user
IDs, or actor labels as telemetry attributes.

### Investigation prompts

1. Does Identity login work for both groups? Is Gateway reaching Playback?
2. Where does the Playback trace end? Do rejected requests reach repository
   spans or SQL?
3. Which stage of token validation differs: signature and standard claims, or
   subject decoding? Add bounded stage reasons or small spans if needed.
4. Inspect `AccessTokenVerifier.userId` and Playback's wiring in `Main.scala`.

### Remediation and proof

Change Playback's verifier wiring to `AccessTokenVerifier.userIdCompatible`.
That decoder accepts exactly a canonical UUID or `user:` followed by a
canonical UUID. It retains RS256 signature, issuer, audience, expiry, issued-at,
and JWT ID checks. Rebuild Playback while keeping the mixed workload at the
same rate:

```bash
./scripts/lab.sh rebuild playback-service
./scripts/lab.sh traffic status
```

The rebuild can briefly produce `500` responses while the service restarts;
judge recovery from a later fresh window. Require that window to be valid with
all Playback requests returning `200`. Run
the verifier suite to check malformed, expired, incorrectly signed, and
unsupported tokens remain rejected:

```bash
sbt --batch 'runtime/testOnly *AccessTokenVerifierSuite'
```

Restore the original Playback verifier wiring before running
`./scripts/lab.sh scenario4 restore`. It stops traffic and rebuilds Playback
in normal projection mode. Leave the seeded accounts and projection rows in
place; preparation is idempotent.

## If something goes wrong

| Symptom | Facilitator action |
| --- | --- |
| Startup cannot pull the commit-tagged images | Check registry access and the printed startup log; use `./scripts/lab.sh start --build` before the session if the tag is unavailable. |
| No traffic series | Check `./scripts/lab.sh traffic status` and `docker logs typelevel-video-streaming-lab-traffic`; confirm the generator is running and `load_valid=true`. |
| Traffic drops or failures | Stop the round, inspect the generator JSON report and service health, and reduce the offered rate only for a new baseline/fault/recovery run. Do not change it mid-round. |
| Metrics are absent but requests complete | Check Grafana freshness and the generator JSON independently. Missing telemetry is unknown, not zero requests. |
| Fault command says a policy is already active | Inspect `./scripts/lab.sh platform changes`; roll back the active change before retrying activation. |
| Rollback succeeded but old slow traces remain | Select traces whose start times are after rollback and compare a fresh metrics window. |
| Proxy state and change ledger disagree | Use `./scripts/lab.sh platform rollback CHANGE_ID` to reconcile both. Reserve direct `proxy reset` for facilitator recovery. |

Use `./scripts/lab.sh stop` only after the session or when intentionally
clearing the full ephemeral stack. It stops the application and its data
containers, so it is not a between-round reset.

## Handoff record for the next facilitator

Record the repository commit or image tag, delivery format, host/Codespaces
size, setup time, fault activation time, rollback time, and recovery window.
Keep one baseline, fault, and recovery trace ID plus the generator summary.
Note any changed rate or timing and why. Before the next workshop, verify that
the attendee-facing brief still matches the commands and dashboard shown here.

## Adding later rounds

Add each round with the same headings used above: participant brief; facilitator
preparation; investigation prompts; expected evidence; participant remediation;
recovery proof; and reset. Keep exact commands and the workload rate beside the
step where they are used. Move a round from planned to runnable only after its
automated acceptance check and a human rehearsal pass on the delivery formats
the company will use.

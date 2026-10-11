---
marp: true
theme: default
size: 16:9
paginate: true
header: A distributed system on fire · otel4s lab
style: |
  section {
    background: #f7f8fa;
    color: #18243a;
    font-family: Inter, Avenir, Helvetica, Arial, sans-serif;
    font-size: 29px;
    padding: 62px 76px;
  }
  section.lead { background: #12233e; color: #f8fafc; }
  section.lead h1, section.lead h2 { color: #f8fafc; }
  h1 { color: #12233e; font-size: 1.85em; margin-bottom: 0.5em; }
  h2 { color: #176b83; font-size: 1.15em; }
  strong { color: #176b83; }
  section.lead strong { color: #67d3df; }
  blockquote { border-left: 6px solid #28a6b8; padding-left: 0.75em; margin-left: 0; }
  table { font-size: 0.83em; }
  th { background: #deedf1; }
  code { font-size: 0.87em; }
  pre { background: #e7edf2; padding: 0.6em; border-radius: 8px; }
  section.small { font-size: 24px; }
  section.image { font-size: 23px; }
  header, footer { color: #738093; font-size: 15px; }
---

<!-- _class: lead -->
<!-- _paginate: false -->

# A distributed system on fire

## Diagnosing failures with otel4s

**An interactive Typelevel lab**

<!-- Presenter: Welcome. Antonio introduces the platform; Maksim introduces the investigation approach. Environments should already be running. -->

---

# Your mission

> Investigate an unfamiliar system from evidence emitted by the running system.

**Find the affected users → locate the failure → explain the cause → verify recovery**

Write down your hypothesis before changing anything.

<!-- Presenter: Tell participants they can work in pairs. Ask for both a claim and the observation supporting it. Avoid showing source before the runtime evidence narrows the search. -->

---

# The platform you will investigate

```text
                  ┌─────────────┐
User / FS2 load ──▶   Gateway   ├─────▶ Catalog ────▶ PostgreSQL
                  └──────┬──────┘
                         ├────────────▶ Identity ────▶ PostgreSQL
                         └────────────▶ Playback ────▶ PostgreSQL

                  Services ── telemetry ──▶ Grafana LGTM
```

Browse courses. Log in. Read Playback data.

<!-- Presenter: Antonio gives the 5-minute platform overview. These are the HTTP request paths used in the lab. Name the stack by role: Cats Effect for effects, FS2 for workload, http4s for HTTP, Smithy4s for contracts, Skunk for PostgreSQL, otel4s for telemetry. The full application contains more infrastructure; do not spend time on it here. -->

---

# Three kinds of evidence

| Signal | First question |
| --- | --- |
| **Metrics** | What changed, when, and for whom? |
| **Traces** | Which operations did a request reach, and where did time go? |
| **Logs** | What did one component observe or decide? |

Runtime metrics and source may answer questions the request trace cannot.

<!-- Presenter: Adapted from the observability-with-otel4s talk. Use one lab request as the running example. Logs are useful when they answer a specific question; not every round needs a logging step. -->

---

# One request, several boundaries

```text
Gateway server span
  └─ Catalog client span
       └─ Catalog server span
            └─ SQL span
```

**Propagation** connects the spans. A latency exemplar opens one measured trace;
its trace ID also lets us find correlated logs.

<!-- Presenter: Show the lab's configured metric-to-trace and trace-to-log links. An exemplar is a sampled measurement, not necessarily the p95 request. Open a healthy request in Tempo. Show the trace ID, parent and child spans, and the time axis. Nested span durations cannot simply be added. Mention context propagation across fibers briefly; no API details yet. -->

---

<!-- _class: small -->

# The approach extends beyond this lab

| Boundary | Available integration |
| --- | --- |
| HTTP | http4s + otel4s middleware |
| Database | Skunk; doobie + otel4s |
| gRPC | fs2-grpc + otel4s |
| Kafka | fs2-kafka + otel4s |
| Other HTTP clients | sttp otel4s backends |

Cats Effect runtime metrics help reveal scheduler pressure.

**Instrument boundaries first. Add observations where a question remains unanswered.**

<!-- Presenter: This lab exercises HTTP and Skunk. Integration availability and setup vary by library. References: https://typelevel.org/otel4s/ecosystem.html ; https://typelevel.org/doobie/docs/20-Otel4s-Tracing.html ; https://sttp.softwaremill.com/en/stable/backends/wrappers/opentelemetry.html -->

---

# Our investigation loop

**Observe → hypothesize → seek distinguishing evidence → correct → verify**

1. Compare the healthy and changed windows.
2. Check offered load, errors, and latency by route.
3. Follow a representative request through a trace.
4. Inspect logs, runtime signals, or source when the evidence points there.
5. Test the correction under the same triggering workload.

<!-- Presenter: Live tool tour now: Workshop Overview, time picker, route link, healthy trace, and participant command card. Show `./scripts/lab.sh help` and `platform changes`; do not demonstrate a rollback. Give tool help without revealing the coming cause. -->

---

<!-- _class: small -->

# The next two hours

| Time | Activity |
| --- | --- |
| 00–18 | Platform, telemetry, and tool tour |
| 18–36 | Incident 1 · Catalog slowdown |
| 36–55 | Incident 3 · Identity slowdown |
| 55–83 | Incident 4 · Playback 401s |
| 83–105 | Incident 5 · Catalog depletion |
| 105–120 | Lessons, AI-assisted investigation, questions |

The live system and your evidence drive the pace.

<!-- Presenter: These are provisional targets. Rehearse preparation, source rebuilds, and sustained recovery windows on the actual workshop host before treating this as a commitment. Scenario 2 is planned but not in this live sequence. -->

---

# Incident 1 · Healthy Catalog traffic

Run the baseline:

```bash
./scripts/lab.sh incident start 1
```

Users browse courses through Gateway and Catalog. Traffic is steady and
course pages load quickly.

Open the dashboard URL printed by the command:
**Gateway–Catalog: Request Path**.

Watch the healthy latency and request rate while the baseline settles.

<!-- Presenter: Allow 2–5 minutes for setup and baseline observation. Start waits for a healthy 60-second generator window, fresh metrics, and a trace. Use this time to explain course browsing and help participants open the printed Grafana URL. Record the baseline time. -->

---

# Incident 1 · Slow course browsing

Activate the incident:

```bash
./scripts/lab.sh incident activate 1
```

> Users report that course pages load slowly after a routine platform change.

Find where the requests spend the extra time.

<!-- Presenter: Record activation time. Allow 2–5 minutes to observe the changed window and gather hypotheses. Keep traffic running. Reveal each following hint separately as groups need it. -->

---

# Incident 1 · Hint 1: request latency

Compare Gateway and Catalog latency before and after activation.

**Which latency series changed?**

<!-- Presenter: Use aligned windows on Gateway–Catalog: Request Path. Explain p95 only if needed: the estimated duration below which 95% of observations fall in the selected window. Ask participants to name the affected boundary before advancing. -->

---

# Incident 1 · Hint 2: traffic and failures

Compare the offered request rate and HTTP outcomes with the baseline.

**Did more traffic or more failures accompany the slowdown?**

<!-- Presenter: Check offered, Gateway inbound, Gateway Catalog attempt, and Catalog server rates. Use Workshop Traffic or traffic status to rule out dropped arrivals. Keep this as a distinct check before opening a trace. -->

---

# Incident 1 · Hint 3: one slow request

Open a latency exemplar from the incident window in Tempo.

Follow the request from Gateway to Catalog and SQL.

**Which span contains the added time?**

<!-- Presenter: An exemplar links an individual measurement to a trace, which may differ from the p95 request. Choose a fresh trace from the fault window. Give navigation help without identifying the cause. -->

---

# Incident 1 · Hint 4: client and server time

Compare Gateway's Catalog client span with Catalog's server span.

**Does Catalog spend as long processing the request as Gateway spends
waiting for it?**

<!-- Presenter: Ask participants to explain the gap using the span timeline. The expected evidence is a long client call with short Catalog server and SQL work. Recorded fallback: docs/scenario1-gateway-catalog-boundary.png. -->

---

# Incident 1 · Hint 5: platform changes

List the recorded changes, then inspect a candidate:

```bash
./scripts/lab.sh platform changes
./scripts/lab.sh platform inspect CHANGE_ID
```

**Which change fits both the time window and the boundary evidence?**

<!-- Presenter: Onboard participants to the platform commands here. Let them find the change ID and read its details. Require a trace-based explanation for the selected change before showing rollback. -->

---

# Incident 1 · Hint 6: recovery

Roll back the change supported by your evidence:

```bash
./scripts/lab.sh platform rollback CHANGE_ID
```

Keep traffic running. Compare a fresh trace and latency with the baseline.

**Did the extra client wait disappear?**

<!-- Presenter: Fresh traces can show recovery before the rolling latency graph clears. Keep the offered load unchanged and allow export and refresh delay. Ask groups to explain their recovery evidence. -->

---

# Incident 1 · Takeaway

**Boundary telemetry was enough to locate this latency problem.**

- Metrics showed when request latency changed and how broadly it changed.
- An exemplar connected the graph to an individual request trace.
- Comparing client and server spans located the delay between services.

The same workload confirmed recovery after the platform rollback.

<!-- Presenter: Ask a group to summarize the investigation in order: latency change, load comparison, exemplar, client/server gap, matching platform change, recovery. Mention that boundary instrumentation is the starting point and later incidents add observations where it leaves questions open. -->

---

# Incident 3 · Healthy Identity traffic

Run the baseline:

```bash
./scripts/lab.sh incident start 3
```

Users occasionally log in and regularly check their accounts with
`GET /users/me`. Both operations respond quickly.

Open the printed dashboard URL: **Identity: Login Latency**.

Observe both routes while the baseline settles.

<!-- Presenter: Allow 2–5 minutes for preparation and healthy observation. Baseline is 30 requests/s with 5% login in this checkout. Check valid recent generator windows. The exercise defect already exists; the next phase changes the workload. -->

---

# Incident 3 · Slow account operations

Activate the incident:

```bash
./scripts/lab.sh incident activate 3
```

> More users are logging in. Logins and lightweight account requests
> both become slow.

Explain why work without password verification also slows down.

<!-- Presenter: Activation switches to 70% login at the same total rate, with a brief generator transition. Record the time and allow 2–5 minutes to observe the degradation and gather hypotheses. Reveal hints one at a time. -->

---

# Incident 3 · Hint 1: affected routes

Compare login and `GET /users/me` latency in the same time window.

**Does the slowdown affect only requests that verify a password?**

<!-- Presenter: Use the lightweight route as a control. Confirm offered and completed load using the generator before attributing the change to the application. -->

---

# Incident 3 · Hint 2: request traces

Open a fresh login trace and a fresh current-user trace.

Compare their SQL spans and the time spent outside SQL.

**Which work appears only in the login request?**

<!-- Presenter: Look for password permit-wait and verification spans. Short SQL spans help exclude slow queries but cannot explain all time outside SQL. Avoid showing PasswordHasher.scala yet. -->

---

# Incident 3 · Hint 3: password work

Compare password verification duration, permit wait, and active verifications.

**Do those signals explain the delay in the lightweight route?**

<!-- Presenter: Keep password work, semaphore contention, and runtime pressure distinct. The lightweight route does not verify a password, so participants need runtime evidence for the effect that spreads to it. -->

---

# Incident 3 · Hint 4: runtime pressure

Follow the dashboard links to **Cats Effect Runtime Metrics** and
**JVM Runtime Metrics**. Select Identity and its current instance.

Compare scheduler drift and starvation with CPU activity.

**Can compute workers keep up with unrelated requests?**

<!-- Presenter: otel4s provides Cats Effect runtime instrumentation, and this lab provisions its dashboard. Compare runtime signals with route latency in the same interval. If telemetry becomes unresponsive, use generator output and container health. Missing traces are not evidence of recovery. -->

---

# Incident 3 · Hint 5: executor and repair

Inspect password verification in `PasswordHasher.scala`.

**Which Cats Effect executor runs the synchronous operation?**

After your correction, deploy it:

```bash
./scripts/lab.sh incident rebuild
```

Keep the login-heavy workload and check fresh current-user latency.

<!-- Presenter: Expected repair: IO.blocking instead of IO.delay, preserving the existing semaphore around Argon2 work. Moving work off compute does not add CPU capacity. Require a fresh valid sustained window and responsive lightweight requests after warmup. -->

---

# Incident 3 · Takeaway

**Runtime telemetry explained why expensive work delayed unrelated requests.**

- Request traces identified the password operation.
- A lightweight route provided a control for the slowdown.
- Cats Effect scheduling metrics exposed compute pressure.

The repair restored lightweight requests under the same login-heavy workload.

<!-- Presenter: Connect the request-level evidence with the runtime evidence. Remind participants that runtime instrumentation and its dashboard are available for their own Cats Effect services. -->

---

# Incident 4 · Healthy Playback traffic

Run the baseline:

```bash
./scripts/lab.sh incident start 4
```

Users log in and open their favorites in Playback. Traffic is steady and
Playback requests succeed.

Open the printed dashboard URL: **Playback: Favorites Authentication**.

Observe the response rates and a successful request trace.

<!-- Presenter: Allow 2–5 minutes for setup and baseline observation. Setup authenticates ten synthetic accounts, and baseline measured traffic uses the original group. The printed dashboard has outcome rates and separate successful/rejected trace tables. -->

---

# Incident 4 · Some users receive 401

Activate the incident:

```bash
./scripts/lab.sh incident activate 4
```

A new authentication token model adds a namespace to the subject.

> Some users can log in but consistently receive `401` from Playback.
> Most users remain unaffected.

Find the incompatible step while preserving authentication checks.

<!-- Presenter: The token-model rollout is known briefing context. Activation introduces users with that model into measured Playback traffic; it does not deploy a new decoder. Keep the same total rate. Allow 2–5 minutes to observe the stable minority failure. Reveal the following hints separately. -->

---

# Incident 4 · Hint 1: failure rate

Compare Playback's `200` and `401` rates with the baseline.

**Is the failing share stable or growing?**

<!-- Presenter: Expect a stable minority near 20% in valid recent workload windows. Distinguish a consistent user-group problem from random or accumulating failures. -->

---

# Incident 4 · Hint 2: Gateway and Playback

Open a fresh rejected Playback trace and a successful control.

**Does Gateway reach Playback in both requests?**

<!-- Presenter: Use the two outcome-specific trace tables in the dashboard. Select requests from the same deployment and fault window. Login succeeds for both groups during workload preparation. -->

---

# Incident 4 · Hint 3: repository work

Follow the rejected request inside Playback.

**Does it reach repository spans or SQL?**

<!-- Presenter: Compare the endpoint's successful and rejected paths. Let participants identify where the rejected path ends before naming the decoder implementation. -->

---

# Incident 4 · Hint 4: subject decoding

The `auth.subject.decode` span surrounds conversion of a verified token
subject into a user UUID.

Both successful and rejected requests have this span.

**What does the existing span leave unexplained?**

<!-- Presenter: Explain the observation boundary: JWT validation has already succeeded, and the decoder returns an optional user UUID. PlaybackAuthTelemetry wraps this supplied effect without changing validation behavior. The span locates the stage but does not report input shape or decoding outcome. -->

---

# Incident 4 · Hint 5: diagnostic attributes

Open `PlaybackAuthTelemetry.decodeSubject`.

Add span attributes that describe the decoding **result** and subject **shape**.

**Which bounded values would distinguish successful and rejected requests?**

Keep the decoder behavior during this diagnostic step.

<!-- Presenter: A contextual Tracer[IO] and prepared logger already exist. Expected attributes: auth.result=accepted|rejected and auth.subject.shape=bare_uuid|namespaced_uuid|other. Offer the API scaffolding or fallback patch only if needed. Do not change the decoder yet. -->

---

# Incident 4 · Hint 6: a correlated log

Log the decoding outcome and verified subject for the synthetic accounts
inside the active span. Deploy the diagnostics:

```bash
./scripts/lab.sh incident rebuild
```

Compare fresh `200` and `401` spans, then find the rejected request's log
by trace ID in Loki.

**What does the log add to the trace?**

<!-- Presenter: Never log a JWT or a real user's subject. Require rejected + namespaced_uuid on a 401 trace and accepted + bare_uuid on a 200 control, while the failure share remains near 20%. Query Loki with {service_name="playback-service"} |= "Playback subject decoding rejected" | trace_id="<trace-id>". Require this span-and-log checkpoint before repair. -->

---

# Incident 4 · Hint 7: compatibility and repair

Compare the formats that Identity emits with the formats Playback accepts.

**Can both legitimate groups succeed while invalid tokens remain rejected?**

Deploy your correction:

```bash
./scripts/lab.sh incident rebuild
```

Verify fresh requests under the same mixed workload.

<!-- Presenter: Inspect AccessTokenVerifier.userId and Playback Main.scala after the diagnostic checkpoint. Expected repair: wire AccessTokenVerifier.userIdCompatible. Preserve signature, issuer, audience, expiry, issued-at, and JWT ID checks. Require a valid fresh all-200 window and negative verifier tests. Exclude rebuild interruptions. -->

---

# Incident 4 · Takeaway

**Extra context explained a failure affecting a small share of users.**

- Comparing successful and rejected traces located the divergent step.
- Bounded span attributes described the decoding outcome and input shape.
- The trace ID linked the request to a more detailed diagnostic log.

Identifiers in metric labels create cardinality growth. Choose span attributes deliberately
and keep sensitive details out of telemetry.

<!-- Presenter: In this lab the exact subject belongs only in a controlled synthetic-account log. Avoid the blanket rule that all dynamic values belong in logs: privacy and usefulness still matter, and trace attributes are not metric series labels. Summarize the evidence before discussing the parser repair. -->

---

# Incident 5 · Healthy Catalog traffic

Run the baseline:

```bash
./scripts/lab.sh incident start 5
```

Users browse video courses. Traffic is steady, requests succeed, and database
sessions become available again after each request.

Open the printed dashboard URL: **Catalog: Session Pool**.

Observe active sessions and acquisition wait while the baseline settles.

<!-- Presenter: Allow 2–5 minutes for preparation and baseline observation. Start configures the six-session Catalog pool and waits for a healthy baseline. Check successful valid recent traffic and occupancy returning to zero. -->

---

# Incident 5 · Catalog gradually slows

Activate the incident:

```bash
./scripts/lab.sh incident activate 5
```

> Course browsing starts normally, then becomes progressively slower.
> Other Catalog reads eventually wait or time out too.

Find what consumes capacity and verify a sustained recovery.

<!-- Presenter: Activation switches from course browsing to mixed Catalog traffic at the same rate. Allow 2–5 minutes to observe degradation. Keep the triggering search pattern private until participants find it. Reveal hints one at a time. -->

---

# Incident 5 · Hint 1: traffic mix

Compare the request mix before and after activation.

**Which routes now receive traffic? Are LearningPath reads affected too?**

<!-- Presenter: This checkout's baseline is catalog-courses. The active catalog-soak workload adds LearningPath reads and changes course search patterns. Inspect Workshop Overview route rates or generator operations if the pool dashboard does not expose the mix. Do not reveal empty-result searches yet. -->

---

# Incident 5 · Hint 2: acquisition time

Compare time spent acquiring a database session with SQL execution time.

**Where does a slow request wait?**

<!-- Presenter: Use acquisition timing and fresh traces. Distinguish pool contention from a slow query. The acquisition histogram records waits when they finish, so the next hint checks current waiters. -->

---

# Incident 5 · Hint 3: waiting requests

Watch active sessions and requests waiting for a session over time.

**Does the pool return to its healthy state between requests?**

<!-- Presenter: Compare catalog.session.active and catalog.session.waiting. Ask participants to describe the progressive loss of capacity before inspecting its implementation. -->

---

# Incident 5 · Hint 4: the oldest wait

Check the age of the oldest request waiting for a database session.

**Does its wait keep growing while traffic continues?**

<!-- Presenter: The dashboard measures catalog.session.wait.max_age, which is the oldest queued acquisition, not the age of an active session. A completed-duration histogram cannot describe an acquisition still waiting. -->

---

# Incident 5 · Hint 5: healthy and slow traces

Compare a healthy Catalog trace with a fresh slow or timed-out request.

**Which operation stops reaching SQL?**

<!-- Presenter: Include a LearningPath request as an unrelated control. A long acquisition span without SQL children supports waiting for pool capacity. Select the correct fault window and service instance. -->

---

# Incident 5 · Hint 6: shared repository resource

Find the LearningPath handler and follow its repository dependency.

**Which database resource does it share with course browsing?**

Trace the course request path and check how it releases that resource.

<!-- Presenter: LearningPath is an affected control, not the leaking operation. Follow both paths to the shared Catalog session pool. Then inspect CatalogRepositoryImpl.listCourses and consider every successful, empty, failed, and cancelled path. Ask which request pattern precedes each occupancy step. -->

---

# Incident 5 · Hint 7: sustained recovery

Correct the resource lifetime and deploy the change:

```bash
./scripts/lab.sh incident rebuild
```

Keep the same mixed workload. Watch at least two minutes of fresh requests.

**Do sessions return and both course and LearningPath reads stay responsive?**

<!-- Presenter: Expected repair: replace manual allocated/release handling with sessions.use, preserving queries and results. Check finalization on empty results, failures, and cancellation. Rebuild briefly resets pool state, so temporary recovery alone does not prove the leak is fixed. Use the new instance's series and valid recent workload windows. -->

---

# Incident 5 · Takeaway

**Metrics can reveal accumulating resource loss before every request fails.**

- Active sessions, waiting requests, and oldest-wait age describe current state.
- Traces show requests waiting before they can execute SQL.
- An unrelated route exposes the effect of a shared depleted pool.

Sustained recovery under the same workload proves the resource repair.

<!-- Presenter: Ask participants to connect resource-state changes to the triggering request pattern. Contrast fresh recovery evidence with old instance series when needed. Keep the takeaway focused on resource state over time and request lifecycle. -->

---

# Incident recap

| Problem | Evidence that narrowed the cause |
| --- | --- |
| Network delay | Gateway client time grows while Catalog handling stays fast |
| Compute thread starvation | Unrelated routes slow down alongside Cats Effect runtime pressure |
| Playback rejection | Subject-shape attributes and correlated logs explain the affected requests |
| Database session leak | Occupancy and waiting requests grow while traces stop before SQL |

<!-- Presenter: Ask participants which observation changed their hypothesis in each round. Boundary telemetry located the network delay. Runtime metrics explained why password verification affected unrelated requests. Playback needed observations inside subject decoding. Pool metrics showed resource loss accumulating over time. Each repair required fresh recovery evidence under the triggering workload. -->

---

# What this lab demonstrated

**Telemetry lets you investigate a service before you know its code or APIs.**

- Boundary instrumentation can locate many failures across services.
- Typelevel integrations provide boundary telemetry and Cats Effect runtime metrics.
- Domain observations explain decisions and resource state inside the application.
- Correlated telemetry supports the same investigation loop with an AI assistant.

<!-- Presenter: Familiarity with the code was not a prerequisite for locating the failures. Source inspection became useful after the evidence narrowed the search, especially for the Identity, Playback, and Catalog repairs. otel4s and the Typelevel integrations shown earlier reduce the setup work for boundary and runtime telemetry. Propagation and log correlation connect observations across components. Domain instrumentation answers questions those integrations cannot infer automatically. -->

---

# Apply this to your service

1. Instrument incoming requests, outgoing calls, and database access.
2. Confirm trace propagation and log correlation.
3. Enable runtime metrics and observe shared resource capacity.
4. Add domain observations where a question remains unanswered.
5. Exercise a failure and verify the fix under the same workload.

<!-- Presenter: Refer back to the otel4s integration slide and the Cats Effect runtime dashboard. Choose one real request path as a starting point. Ask a colleague unfamiliar with that path to explain a controlled failure using its telemetry. Use their unanswered questions to guide the next instrumentation change. -->

---

<!-- _class: lead -->

# Questions?

<!-- Presenter: Leave the final five minutes for questions. -->

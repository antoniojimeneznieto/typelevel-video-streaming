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

**Propagation** connects services. The trace ID connects observations about the same request.

<!-- Presenter: Open a healthy request in Tempo. Show the trace ID, parent and child spans, and the time axis. Explain that nested span durations cannot simply be added. Mention context propagation across fibers briefly; no API details yet. -->

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

**Instrument boundaries first. Add domain observations where a question remains unanswered.**

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

# Incident 1 · Before the slowdown

Course browsing is responsive. Requests pass through Gateway to Catalog.

**Observe the healthy traffic:**

- Offered requests and completed requests.
- Gateway and Catalog latency.
- One Gateway → Catalog trace.

<!-- Presenter: Begin the 18-minute round. `./scripts/lab.sh prepare 1`; `./scripts/lab.sh traffic start --rate 5`; `./scripts/lab.sh traffic status`. Allow at least 75 seconds for warmup and a full metrics window. Check `load_valid`, drops, and failures. If already prepared, keep the existing healthy generator running. -->

---

# User report

> Catalog-facing requests became slow shortly after a routine platform change.

Find where the added time occurs. Restore normal response time while traffic continues.

**Start with the change in user experience. Do not assume the changed component is the cause.**

<!-- Presenter: Activate with `./scripts/lab.sh incident start 8f27`. Record activation time privately. Allow a complete fresh metrics window. The neutral command output should only say `Applied traffic policy`. -->

---

# Investigate the delay

- Did offered load change? Did failures increase?
- Which span accounts for the added time?
- Does Catalog spend as long handling the request as Gateway spends calling it?
- Which recent change fits the boundary evidence?

**Bring one trace and one rate comparison to the discussion.**

<!-- Presenter: First explain p95: the estimated duration below which 95% of observations fall within the selected window. An exemplar links a measurement to a trace; it is not necessarily the p95 request. Progressive hints: compare Gateway Catalog client and Catalog server spans; inspect `./scripts/lab.sh platform changes` and `platform inspect CHANGE_ID`. Let participants discover the change ID. -->

---

<!-- _class: image -->

# Verdict · Time at the service boundary

![bg right:57% contain](docs/scenario1-gateway-catalog-boundary.png)

Gateway's Catalog call is slow. Catalog's server and SQL work remain short.

**Resolution:** Roll back the relevant traffic policy.

**Proof:** Under the same load, fresh traces lose the client/server gap; the latency window recovers.

<!-- Presenter: Reveal only after groups explain their evidence. The screenshot is recorded rehearsal evidence; prefer a current trace. Participant command: `./scripts/lab.sh platform rollback CHANGE_ID`. Keep traffic running. Fresh traces can show recovery before the 60-second p95 window clears; allow export/refresh delay. Lesson: a slow dependency call does not prove slow dependency execution. -->

---

# Incident 3 · Before authentication activity increased

Light login traffic and authenticated current-user requests are responsive.

**Observe the healthy control:**

- Login latency.
- `GET /users/me` latency.
- Identity runtime signals.

<!-- Presenter: Begin the 19-minute round. The checked-in Identity exercise has a latent defect; workload activation exposes it. Before the session, build the exercise image and generator. `./scripts/lab.sh prepare 3`; `./scripts/lab.sh scenario3 baseline`. Baseline is 30 requests/s with 5% login. Give it at least 30 seconds. If telemetry slows or disappears during the incident, consult generator output and container health. -->

---

# User report

> More users are logging in. Logins and unrelated lightweight Identity requests are now slow. PostgreSQL appears healthy.

Explain why work with no password verification also slows down.

<!-- Presenter: Activate with `./scripts/lab.sh incident start 3c91`. It switches to 70% login at the same aggregate offered rate, with a brief generator transition. Record the time. -->

---

# Investigate Identity

- Are the login and current-user routes both affected?
- What differs in their traces?
- Are SQL time, password work, and permit wait enough to explain the slowdown?
- What do Cats Effect scheduling and CPU signals show?

**Find the operation before opening its implementation.**

<!-- Presenter: Use Identity Investigation, then the linked Cats Effect and JVM views. A short SQL span does not explain the time outside it. Missing traces during saturation are not proof of health. If needed, ask which operation runs only for login, then which executor carries that work. -->

---

# Verdict · Compute workers are occupied

Synchronous password verification runs on Cats Effect compute workers.

**Resolution:** Move verification from `IO.delay` to `IO.blocking`; keep concurrency bounded.

**Proof:** Under the same login-heavy mix, lightweight requests become responsive and verification concurrency stays bounded.

<!-- Presenter: Reveal after participants compare the control operation and runtime signals. Inspect PasswordHasher.scala only after the telemetry diagnosis. Deploy with `./scripts/lab.sh rebuild identity-service`. The existing semaphore still bounds hashing. Moving work off compute does not add CPU capacity; a host-sensitive workload may need calibration. -->

---

# Incident 4 · Before Playback failures

Authenticated Playback reads work for the initial group of users.

**Observe:** successful logins, Playback `200` responses, and a successful request trace.

<!-- Presenter: Begin the 28-minute round. This is the central otel4s exercise. Require the diagnostic telemetry checkpoint before a decoder change. Prepare before the session: build Identity and generator, `./scripts/lab.sh prepare 4`, then `./scripts/lab.sh scenario4 baseline`. Setup registers ten synthetic accounts; baseline measured traffic uses the initial eight actors. Keep account and token values out of slides. -->

---

# User report

> Some users can log in but consistently receive `401` from Playback. Most users are unaffected.

Find the compatibility boundary without weakening authentication.

**Which comparison would separate a user-specific problem from random request failures?**

<!-- Presenter: Activate with `./scripts/lab.sh incident start 7b42`. It starts a stable mixed-actor workload at the same 10 requests/s; roughly 20% should return 401. Do not reveal token subject formats yet. -->

---

# Existing telemetry narrows the search

Compare a fresh `200` trace with a fresh `401` trace.

- Does Identity authenticate both users?
- Does Gateway reach Playback?
- Does the rejected request reach repository or SQL work?
- What does the existing `auth.subject.decode` span still leave unexplained?

<!-- Presenter: Use Playback Investigation's recent success/rejection trace tables. Ask participants to identify the missing observation. Explain that a span can locate a validation stage without explaining why that stage rejected a request. -->

---

# Add one diagnostic observation

In `PlaybackAuthTelemetry.decodeSubject`, keep the decoder behavior as it is.

Add bounded span attributes:

```text
auth.result         = accepted | rejected
auth.subject.shape  = bare_uuid | namespaced_uuid | other
```

**Capture a fresh successful trace and a fresh rejected trace before repairing the decoder.**

<!-- Presenter: A contextual `Tracer[IO]` and public logger already exist. Participants edit the prepared method and rebuild Playback. Do not put raw subjects, tokens, or IDs in core span or metric attributes. The required checkpoint is `rejected + namespaced_uuid` for a 401 and `accepted + bare_uuid` for a 200, while the 401 share remains. Use the scenario4-diagnostics.patch only as facilitator fallback. -->

---

# Verdict · Two valid subject formats

The successful and rejected traces differ at subject decoding.

**Resolution:** Accept the two documented subject formats while retaining JWT signature, issuer, audience, and expiry checks.

**Proof:** Both legitimate groups succeed under the same mix; malformed and unsupported tokens still fail.

<!-- Presenter: Reveal after the diagnostic checkpoint. Source inspection confirms the parser mismatch. Change Playback wiring to `AccessTokenVerifier.userIdCompatible`, then `./scripts/lab.sh rebuild playback-service`. Verify a fresh window after the restart, plus negative verifier tests. Optional trace-to-logs correlation can follow the core exercise using seeded synthetic accounts; it is not required for repair. -->

---

# Incident 5 · Before Catalog slows

Course reads complete normally. Database sessions become available again after requests.

**Observe:** request outcomes, acquisition wait, active sessions, and a healthy course trace.

<!-- Presenter: Begin the 22-minute round. This one tests resource lifetime and a failure that spreads from a minority request pattern to unrelated reads. Before the session, build the generator and verify the exercise image. `./scripts/lab.sh prepare 5`; `./scripts/lab.sh scenario5 baseline`. Preparation sets a six-session Catalog pool. Allow 30–40 seconds and confirm recent valid load, no failures, near-zero wait, and occupancy returning to zero. -->

---

# User report

> Catalog starts healthy, then gradually slows. Eventually unrelated reads also wait. A restart helps only briefly.

Find the request pattern that consumes capacity and prove a durable repair.

<!-- Presenter: Activate with `./scripts/lab.sh incident start d5e0`. It switches to the mixed search workload at the same 5 requests/s, with a brief generator gap. Do not announce the empty-result trigger. Watch long enough for multiple pattern cycles; time to depletion varies by host. -->

---

# Investigate the growing queue

- Do slow requests wait **for a session** or execute **slow SQL**?
- Which successful request pattern precedes each loss of available capacity?
- Do LearningPath reads slow even though they are not the trigger?
- What happens to sessions on every exit path?

**A completed wait histogram cannot time requests still queued. Inspect active, waiting, and oldest-wait gauges.**

<!-- Presenter: Use Catalog Pool Investigation. A trace without a SQL child may be waiting for a session. Compare `catalog.session.active`, `catalog.session.waiting`, and `catalog.session.wait.max_age`. Ask for a triggering response and a slow unrelated LearningPath trace before revealing source. -->

---

# Verdict · A successful request can leak a session

An empty-result course search returns `200` but leaves its allocated session checked out.

**Resolution:** Scope the Skunk session with `Resource.use` on every outcome.

**Proof:** Under the same mixed search load, sessions return, wait stays bounded, and course and LearningPath reads recover.

<!-- Presenter: Reveal after participants connect a successful empty search with an occupancy step. Review CatalogRepositoryImpl.listCourses and replace manual allocated/release handling with `sessions.use`. Deploy with `./scripts/lab.sh scenario5 rebuild`, preserving the six-session pool. Judge a fresh sustained post-rebuild window; a restart alone temporarily clears the leak. Include error and cancellation finalization checks in the repair review. -->

---

# Four incidents, four transferable lessons

| Incident | What the evidence taught us |
| --- | --- |
| Boundary delay | Compare client and server time before assigning blame |
| Identity starvation | Use unrelated operations as controls and inspect runtime signals |
| Playback rejection | Add a bounded observation where existing spans stop explaining outcomes |
| Session depletion | Separate resource acquisition from execution; verify finalization |

<!-- Presenter: Ask participants which signal made the largest difference in each round. Do not force a logs example into an incident where logs were not decisive. -->

---

# Apply this to your service

1. Instrument incoming requests, outgoing calls, and database access.
2. Confirm trace propagation and log correlation.
3. Add domain observations where generic instrumentation leaves a question open.
4. Exercise a failure and see whether someone else can explain it.
5. Verify a fix using the workload that exposed the failure.

<!-- Presenter: Refer back to the otel4s integration slide. This is a short practical checklist, not another setup tutorial. -->

---

# Investigate with AI, grounded in evidence

Provide: **symptom + time window + healthy/failing metrics + example traces + relevant logs or changes**.

Ask:

> Rank plausible causes. Cite the observations for each. What remains unknown? Which observation should we collect next?

**Begin without knowing the codebase. Use evidence to decide where deeper inspection is necessary.**

<!-- Presenter: AI can help navigate and compare telemetry, but its explanation remains a hypothesis until checked against a new observation and recovery. Playback is the example where existing instrumentation needed to be extended. Live AI demonstration is optional. Ask what participants would instrument next; leave the final five minutes for questions. -->

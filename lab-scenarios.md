# Observability lab scenarios

## Purpose and format

This is the facilitator and implementation specification for a two-hour lab on
telemetry-driven debugging in a distributed Typelevel video-learning application.
It defines **five live incidents**, a short comparison, and optional take-home
exercises. The older
[scenario bank](docs/observability/gateway-dependencies-lab-scenarios.md) retains
other ideas; it is not the live agenda. Participant briefs must omit the hidden
setup and expected diagnosis below.

The recurring participant workflow is:

1. **Identify:** State the user symptom, affected population, and start time.
   Compare request rate, errors, and latency with a healthy window.
2. **Investigate:** Use traces to locate the boundary or operation. Use logs,
   runtime evidence, source, or database tools to explain the remaining gap.
3. **Fix and verify:** Apply the smallest durable correction. Repeat the same
   triggering workload and compare a sustained recovery window with baseline.
4. **Transfer:** Name the signal, instrumentation, and safeguard that would help
   diagnose or prevent the same failure in the participant's own service.

Each round ends with a brief group walkthrough. Slides introduce the workflow
and tools, but the hands-on investigation should reveal the cause before the
walkthrough does. A shared worksheet records the symptom, first abnormal
signal, adjacent layers ruled out, decisive evidence, fix, recovery evidence,
and transfer lesson.

## Common lab contract

The default lab runs Gateway, Catalog, Identity, Playback, one PostgreSQL
instance, Toxiproxy on the Gateway–Catalog path, LGTM, and an FS2 traffic
generator. All inter-service calls used here are HTTP. The frontend, Kafka,
Debezium, outbox setup, SeaweedFS, video upload, and pgAdmin are outside the
default workshop mode. Playback needs a read-only workshop mode and directly
seeded projection rows so authenticated reads work without Kafka or object
storage. The full application can remain available separately.

The generator selects real API operations through Gateway. Its profiles cover
Catalog reads with both matching and empty-result searches; logins mixed with
lightweight Identity requests; and authenticated Playback reads from a fixed
set of synthetic actors. It reports offered, sent,
completed, failed, timed-out, and dropped arrivals. Within each incident, keep
the offered rate and actor mix fixed through the fault and recovery; a slower
system must not silently receive less traffic. For the code-baked incidents,
activation changes the workload or actors to expose an already present defect;
verification repeats that exact triggering workload after the fix. Calibrate
all rates and observation windows on the minimum supported local and Codespaces
environments.

Facilitators activate faults and restore the lab between rounds. Participants
see a symptom brief, telemetry, source, and a plausible recent-change list.
Infrastructure/configuration incidents are fixed by inspecting and rolling back
the relevant change; code incidents require a reviewable source correction. A
facilitator reset is an escape hatch, not participant remediation. Incident IDs,
fault names, proxy state, and synthetic actor/cohort identifiers must not appear
in application telemetry. Use bounded service, operation, outcome, and failure
reason attributes; never log raw tokens or user identifiers.

Keep telemetry history between rounds. Use per-operation dashboard filters,
recent rate and latency windows, and timeline annotations for workload change,
incident activation, and remediation. Restore fault state between rounds and
allow a fresh healthy window before the next activation. Do not erase metrics to
make a new scenario look clean. A round is complete only when new requests are
healthy under continuing traffic; old in-flight traces may retain the fault.

## Live sequence

| Order | Incident | Main diagnostic lesson |
| --- | --- | --- |
| 1 | Gateway–Catalog network delay | Compare client and server time to locate a boundary. |
| 2 | Retry amplification from a low Catalog admission limit | Compare logical requests with physical downstream attempts. |
| 3 | Identity compute starvation | Move from service traces to runtime and source evidence. |
| 4 | Stable minority-user Playback failure | Add manual otel4s instrumentation where default spans stop answering the question. |
| 5 | PostgreSQL session leak in Catalog | Distinguish session-acquisition wait from SQL execution and repair resource lifetime. |

Three rounds now involve source fixes. Prepare faulty and corrected images with
reviewable diffs, timebox each investigation and walkthrough, and calibrate the
session-leak soak so round 5 has time to show both depletion and sustained
recovery. Local compilation remains available, but should not be required to
keep the two-hour session on schedule.

### Round 1 — Gateway–Catalog network delay

**Participant brief:** Catalog-facing requests became slow shortly after a
routine platform change. Locate the delay and restore normal response time.

**Facilitator setup:** Run a fixed Catalog-read profile to establish a healthy
window. Add stable latency below the Gateway timeout to the permanent
Gateway–Catalog Toxiproxy path and record a plausible traffic-policy change.
Catalog and PostgreSQL remain healthy. Keep the same traffic running.

**How participants identify it:** Gateway server and Catalog client latency rise
together, while Catalog server and SQL duration remain near baseline. A
representative trace shows substantial time in the Catalog client span outside
the Catalog server child span. Offered, inbound, client-attempt, and Catalog
server rates remain aligned.

**Fix and proof:** Inspect the recent traffic-policy change and roll it back.
The reconciler removes the proxy latency. Under unchanged traffic, the
client-minus-server duration shrinks, new traces regain the healthy shape, and
latency returns to the recorded range.

**Takeaway:** A slow dependency call is not proof that the dependency's own work
is slow. Compare adjacent spans and rates to locate time at a service boundary.

### Round 2 — Retry amplification

**Participant brief:** User traffic is steady, yet Catalog is receiving more
requests and some reads take several times longer. Explain the extra work and
restore normal behavior.

**Facilitator setup:** The single prebuilt Catalog image has an ordinary,
configurable maximum number of in-flight requests. At the healthy setting it
admits the fixed Catalog-read workload. A recent configuration rollout lowers
the limit, so Catalog returns retryable `503`s when full. Gateway already has a
bounded retry policy for safe reads. Calibrate the limit and load so some
requests succeed, some retry, and the system remains responsive. Recreate only
Catalog to apply the changed setting; no canary routing or faulty image is
needed. Ensure rejected requests are counted by Catalog server telemetry.

**How participants identify it:** Gateway inbound and generator offered rates
stay steady, but Gateway outbound attempts and Catalog server requests increase.
The attempt-to-logical-request ratio exceeds one. Traces show more than one
Catalog client span for a Gateway request, including rejected attempts and
possibly a later success. Catalog admission-rejection evidence points to the
recent limit change.

**Fix and proof:** Roll back the admission-limit change, not the Gateway retry
policy. Keep traffic running until rejections and retries stop, attempt ratio
returns near one, and new traces show one successful attempt. Discuss retry
eligibility, attempt count, backoff, and total deadline after the repair.

**Takeaway:** Count physical attempts separately from logical requests. A retry
policy can amplify a downstream capacity mistake even when many user requests
eventually succeed.

### Round 3 — Cats Effect compute starvation in Identity

**Participant brief:** A rise in authentication activity is followed by slow
logins and slow lightweight Identity operations. PostgreSQL appears healthy.
Explain why unrelated work in the Identity process is delayed.

**Facilitator setup:** The exercise release contains a latent defect: synchronous
Password4j Argon2 verification runs in `IO.delay` on Cats Effect compute workers.
The initial traffic mix uses few logins. Activation changes the generator to a
calibrated login-heavy mix while retaining lightweight Identity calls and a
stable aggregate offered rate. Use a consistent CPU limit and hashing cost;
avoid synthetic sleeps or a failure-named flag. The current application already
uses `IO.blocking`, so preparing this exercise requires a reviewable scenario
source version.

**How participants identify it:** Login latency rises first and unrelated
Identity requests slow as well. SQL execution and session acquisition remain
comparatively healthy. Runtime scheduling metrics and starvation logs point
inside the process; a thread dump or profile places compute workers in Argon2.
Source inspection reveals the `IO.delay` boundary.

**Diagnosis and optional fix:** The core round ends when participants identify
the synchronous password operation inside `IO.delay` and explain why unrelated
Identity calls slow under login load. If time and skill permit, move verification
to `IO.blocking` while keeping hashing concurrency bounded. Review the patch,
deploy the corrected version, and repeat the same login-heavy workload long
enough to rule out temporary relief from a restart. Lightweight requests should
remain responsive and hashing concurrency must stay bounded.

**Takeaway:** Traces identify the service; runtime evidence explains scheduler
pressure within it. Moving work off compute threads does not add CPU capacity,
so the scenario must be calibrated and concurrency bounded.

### Round 4 — Stable minority-user Playback failure

**Participant brief:** A small, repeatable share of authenticated Playback
requests returns `401`. Those users can log in, and most users are unaffected.
Find the compatibility boundary without weakening authentication.

**Facilitator setup:** Seed legitimate accounts with two token subject formats:
an older raw UUID and a newer `user:<uuid>` form. Identity issues both; the
exercise Playback verifier accepts only the older form. Start with old-format
actors, then add a fixed minority of newer-format actors. Each actor keeps its
identity across requests, so failures follow users rather than random attempts.
Playback's workshop read mode and seeded projection rows avoid Kafka and S3.

**How participants identify it:** Identity login succeeds for both groups.
Playback rejects the same minority before repository work while accepted
requests, network timing, and SQL remain healthy. Standard HTTP telemetry
locates the rejection but does not identify the validation stage. Participants
inspect the verifier and add small otel4s spans or bounded failure reasons around
signature/claims validation and subject decoding. Compare failing and healthy
traces. Do not add token contents, subject values, user IDs, or cohort flags to
telemetry.

**Fix and proof:** Deploy a bounded subject decoder accepting exactly the two
documented forms. Keep signature, issuer, audience, and expiry checks unchanged.
Repeat the identical mixed-actor workload: both legitimate groups succeed and
aggregate `401` rate returns to baseline. Negative checks still reject
malformed, expired, incorrectly signed, and unsupported tokens.

**Takeaway:** Instrument the point where existing spans stop distinguishing
plausible causes. Stable synthetic actors expose minority-user failures without
turning identities into high-cardinality telemetry labels.

### Round 5 — PostgreSQL session leak in Catalog

**Participant brief:** Catalog starts healthy, then gradually slows until
unrelated reads also wait for database access. Restarting Catalog gives only
temporary relief. Find the request pattern that consumes capacity.

**Facilitator setup:** Prepare a reviewable Catalog exercise version with a
real resource-lifetime defect. In `ListCourses`, a manually allocated Skunk
session is released when a search returns courses but not when a valid search
returns an empty page. This uses the existing API's normal `200` empty-result
path; Catalog has no course-by-ID `404` operation today. Most synthetic searches
return results, while a fixed minority use a filter that returns none. Hold the
total offered rate and a small session-pool limit constant. Do not inject SQL
latency or reduce the pool after startup. Seed enough results for the matching
searches and make the empty-result filter deterministic.

**How participants identify it:** The first empty searches complete normally,
but each leaves one session unavailable. Available capacity falls step by
step; session-acquisition wait and queued requests rise. SQL execution stays
relatively quick after acquisition. Eventually normal `ListCourses` and
`ListLearningPaths` reads degrade too. A restart briefly restores pool capacity,
but the identical workload drains it again. Source inspection finds the
unbalanced allocation on the empty-result branch.

**Fix and proof:** Replace manual allocation with `sessions.use` or another
correctly scoped `Resource` so nonempty results, empty results, errors, and
cancellation all release the session. Add a focused test of those exit paths.
Repeat the same mixed-search soak: pool capacity returns after each request,
acquisition wait stays bounded, and unrelated reads remain responsive. A larger
pool or a restart does not count as a durable fix. Allow enough time for the
faulty version to show progressive depletion and for the fixed version to
sustain the same workload; a brief restart-based recovery is insufficient.

**Takeaway:** Separate waiting for a database session from time spent executing
SQL. A minority of requests that appear successful can gradually degrade every
user when resource finalization is wrong. This round needs observable pool
acquisition and capacity evidence before release.

## Additional scenarios kept on the list

These exercises do not create additional mandatory live rounds. Connection
refusal is a short facilitated comparison; the SQL regression and telemetry
failure are take-home exercises.

### Short comparison — Catalog connection refusal

**Participant brief:** Catalog-facing reads started failing, although Catalog
appears healthy. Determine whether the requests reach it.

**Facilitator setup:** Reuse round 1's Catalog-read workload and healthy trace.
Disable the Toxiproxy listener with `./scripts/lab.sh proxy down`, leaving
Catalog itself running. The command is available now; a participant-facing
routing-change record is still planned. Keep traffic running during the brief
comparison.

**How participants identify it:** Gateway Catalog client calls fail while
Catalog server request rate falls or disappears. Failed client spans lack a
Catalog server child span, unlike round 1's slow-but-complete trace. A direct
Catalog health or read check confirms that the service still works. The missing
child span alone is not decisive because sampling or broken context propagation
can also produce an incomplete trace; corroborate it with rates and a direct
check. Verify the actual Gateway response mapping before promising a specific
HTTP status in the attendee brief.

**Fix and proof:** Restore routing with `./scripts/lab.sh proxy reset` or, once
the participant platform interface exists, roll back the relevant routing
change. Under unchanged traffic, Catalog server rate returns and fresh traces
contain a Catalog server span. The facilitator restores the proxy immediately
after the comparison.

**Takeaway:** A slow dependency call that reached its server and a failed call
that never reached it point to different boundaries. Compare client spans with
downstream rates rather than relying on a single trace.

### Take-home — Catalog SQL regression

**Participant brief:** Catalog reads slow after service and database changes.
Locate the added time within Catalog and restore performance.

**Facilitator setup:** Use a seeded Catalog dataset large enough to make a
reversible index or query regression measurable. Run the same fixed Catalog-read
profile before, during, and after the change. Do not inject database network
latency and call it SQL regression.

**How participants identify it:** Gateway client and Catalog server duration
rise together. SQL execution spans account for most added time; boundary
overhead stays near baseline. Session acquisition remains healthy unless pool
pressure becomes a secondary effect. A query plan shows the changed execution
path.

**Fix and proof:** Restore the index or query through a reviewable database
change. SQL duration falls first; Catalog and Gateway latency follow under the
same offered rate.

**Takeaway:** Once traces locate time in PostgreSQL, compare query execution
with session acquisition and use a plan to explain the execution cost.

### Take-home — Missing or stale telemetry

**Participant brief:** Gateway dashboard panels stopped updating, but users may
still be receiving responses. Determine whether Gateway is down, traffic is
idle, or telemetry is broken.

**Facilitator setup:** Use a bad Gateway OTLP endpoint configuration rollout as
the lean first version. Gateway keeps serving requests, but its metrics, traces,
and logs stop reaching LGTM. Catalog and traffic-generator telemetry can remain
healthy. A harder later variant can break collection more broadly. Do not
replace missing series with synthetic zeros. Provide dashboard freshness and
exporter or collector diagnostics before making this a take-home exercise.

**How participants identify it:** Consult the generator's Docker JSON reports,
direct Gateway requests, and service health to establish that traffic still
completes. Compare those independent observations with the timestamps of the
last Gateway metrics, traces, and logs. Generator metrics in LGTM help with a
Gateway-only export fault, but they are not independent evidence if LGTM itself
fails; the JSON output remains available outside the telemetry pipeline.
Exporter errors or collector receive/export diagnostics then locate the broken
telemetry path. Treat an absent or stale series as unknown, not as zero traffic
or zero errors.

**Fix and proof:** Roll back the endpoint or pipeline configuration without
restarting a healthy Gateway. Confirm that fresh metrics, traces, and logs
resume within the documented export delay while requests continue to complete.
The historical gap must remain visible as missing data.

**Takeaway:** Establish measurement freshness before interpreting a dashboard.
Keep an independent account of user-facing behavior when the telemetry system
itself is under investigation.

Kafka poison records and other event-driven incidents belong to a separate
optional track that starts Kafka, Debezium, and the Playback projection worker.

## Implementation and acceptance

Build the selected rounds before broadening the old scenario bank. Rounds 1, 3,
and 4 have runnable workflows. Round 5 now has a leaky exercise source, a
deterministic Catalog operation mix, session acquisition and oldest-wait
metrics, checked-out/waiting/capacity metrics, a dashboard, facilitator
controls, and an automated fault rehearsal. Its fault and minimal repair passed one local
full-stack rehearsal. It still needs calibration on the minimum supported
workshop hosts, a distributable corrected image, and focused error/cancellation
finalization tests for the participant repair. Round 2 still needs
Gateway bounded retry behavior, per-attempt evidence, and Catalog admission
limits. The additional exercises remain outside the live implementation scope.

The additional exercises have their own prerequisites: confirm Gateway's
connection-error response mapping for the short comparison, and expose
telemetry freshness and exporter or collector diagnostics for the
missing-telemetry exercise. The generator's JSON reports must stay usable when
LGTM is unavailable.

For every selected incident, automate a run that establishes baseline, activates
the documented fault, checks the diagnostic evidence, applies the same repair a
participant would, and proves sustained recovery at unchanged offered traffic.
Check dropped arrivals and trace availability as well as service metrics. Verify
the one-command local setup and Codespaces environment with prebuilt images
before promising them in workshop material.

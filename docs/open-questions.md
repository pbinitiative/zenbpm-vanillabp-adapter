# Open questions and recommendations

Two audiences. Questions 1-7 go to the ZenBPM maintainers as the ENGINE's owners, because the engine's
shape decides how much of the VanillaBP contract the adapter can serve; since 2026-09-13 the same
maintainers own the adapter as well, so the answers written below each question are decisions, not
requests. Questions 8-17 are decisions the adapter's owners take themselves; where the VanillaBP
project is affected (its skills, its wiki, its blueprints, its packages) the question says what to ask
that project for. Each question states what was found, what the plan assumes meanwhile, and the
recommendation. Nothing in the plan is blocked on an answer: every gated story ships a fallback.

## For the ZenBPM maintainers

### 1. Can a message-started instance carry a business key, or can instances be found by a variable?

**Found.** `PublishMessageRequest` has no `businessKey`; the only place a business key is set is
`POST /v1/process-instances`; `GET /v1/process-instances` filters by `businessKey` but never by a
variable (`internal/sql/queries/process_instance.sql`). An instance started through a message start
event is therefore not findable by the aggregate id the application gave it, and every later
`correlateMessage`, `completeTask`, `aggregateChanged` on that workflow fails with "not found".

**Plan meanwhile.** `startWorkflowByMessage` is refused by default with a message naming this gap,
and `message-start-lookup: scan` enables a bounded client-side scan (E7 S7.1.2).

**Recommendation.** Add `businessKey` to `POST /v1/messages`, applied to the instance a message start
creates (smallest change), and consider `PATCH /v1/process-instances/{key}` accepting `businessKey`
(the SQL upsert already writes the column) plus a `variables[<name>]=<value>` filter with a
`json_extract` index for the general case. E13.4.

**ZenBPM maintainers answer:** Nice to have.

### 2. Can the job lock be configured per subscription, and can a worker extend or acknowledge it?

**Found.** `jobLockDuration = 30 s` and `maxActiveJobsPerClient = 10` are constants
(`internal/cluster/jobmanager/server.go:27-29`); the stream request has `lock_duration` and
`max_active_jobs` commented out. A `@WorkflowTask` handler longer than 30 s meets a second delivery of
its own job; an asynchronous task which the application completes days later is handed out every
30 s and occupies the client's ten slots.

**Plan meanwhile.** Handlers are documented to finish within 30 s; redeliveries are answered from the
platform's delivery record; the dispatcher never queues (E6).

**Recommendation.** Implement the two commented fields and add a lock extension (stream message or
`POST /v1/jobs/{key}/extend-lock`). E13.1. This is the change with the largest effect on adapter
simplicity and on what a user may do inside a handler.

**ZenBPM maintainers answer:** Hi priority.

**Story:** [`engine-enablement/E13.1-configurable-job-lock-and-lock-extension.md`](engine-enablement/E13.1-configurable-job-lock-and-lock-extension.md) (2026-09-19).

**Implemented (2026-09-23):** engine commit `071460cc` (pull request #842). The adapter plan was
rewritten to build on it directly: the "Plan meanwhile" above no longer applies, see the story's
section 0 and `plan/epic-13-engine-enablement.md` E13.1.

### 3. Are job retries planned?

**Found.** `zenbpm:taskDefinition retries` is parsed and ignored (`pkg/bpmn/engine.go` TODO); a
`fail` without an error code is an incident at once.

**Plan meanwhile.** A thrown handler is not reported; the adapter backs off locally and files an
incident after `max-redeliveries` (E6 S6.2.1, decision 8).

**Recommendation.** Retries with an optional backoff on the fail request. E13.3.

**ZenBPM maintainers answer:** Medium priority. Retry strategy?

**Story:** [`engine-enablement/E13.3-job-retries-with-backoff.md`](engine-enablement/E13.3-job-retries-with-backoff.md) (2026-09-19); the strategy is its section 4.1, the one default to decide its section 9.1.

### 4. Is there a way to propagate job outputs without output mappings?

**Found.** `PropagateOnlyMappedOutputs` drops every completion variable of a task without
`zenbpm:output` mappings (`pkg/bpmn/runtime/varholder.go:107`), while catching events propagate all.
VanillaBP pushes the shared aggregate values at every completion so a gateway behind the task decides
on them; the adapter therefore has to `PATCH` variables before it completes (two calls).

**Recommendation.** A `propagateOutputs="all"` attribute on `taskDefinition`, or a per-engine default,
routing through `PropagateMappedOutputsOrAll`. E13.2. Also: why do tasks and catching events differ?

**ZenBPM maintainers answer:** Maybe leave as is so not to pollute the variables' context?

### 5. Are listeners, an event stream, or user-task lifecycle events planned?

**Found.** No execution or task listeners are parsed; the in-process `EventExporter` is not wired to
any API; cancellation of an instance notifies nobody; a user task is a job with no created/terminated
event other than the job itself.

**Plan meanwhile.** Marker service tasks inserted before end events and after timer start events
(decision 5); user tasks polled (decision 12); `TERMINATED` never reported.

**Recommendation.** A subscribable, acknowledged notification stream for instance start, instance
end (with the kind), job termination and user-task lifecycle, sourced from the exporter; and a richer
`WaitingJob` (element instance key, process id, version, business key) so a worker needs no lookup per
instance. E13.5, E13.6.

**ZenBPM maintainers answer:** Low priority.

### 6. Can a message publication refuse the fallback to a start subscription, and can messages be buffered?

**Found.** `publishCorrelatedMessageByName` falls back from an unmatched correlation key to a
definition-level subscription with a warning, so a correlation may START a workflow where the message
name also has a start event; nothing waiting is a 404 and the message is lost.

**Plan meanwhile.** A message name used by a start event and a catch element in one module is refused
while deploying; a 404 is retried from the outbox for the visibility window.

**Recommendation.** A `strict` flag on publish; buffering with a time-to-live and a message id for
deduplication, as Camunda 8 offers. E13.8.

**ZenBPM maintainers answer:** Low priority.

### 7. Stability, versioning, authentication

- Which release to pin for the first adapter release? The adapter needs E13.1 (commit `071460cc`,
  2026-09-23), and `v1.8.0` (2026-09-14) does not carry it. During development the adapter pins a
  `main` build (question 9); before its first release it needs a tagged engine release containing
  that build (S12.4.1). When will the next release be tagged? Three smaller asks follow from pinning
  `main` builds: (a) keep the untagged `dev` image versions in `ghcr.io/pbinitiative/zenbpm` (the
  adapter pins them by digest), or better, also tag each `dev` push with its commit (`sha-<commit>`)
  so the pin needs no digest; (b) `VERSION` on `main` still says `v1.8.0` after `v1.8.0` was
  released, so `/system/status` cannot tell a `main` build from the release - a bump right after
  each release (`v1.9.0-dev`) would; (c) does the engine serve its OpenAPI document at runtime
  (`/v1/openapi` or similar), so the adapter can diff its pinned copy against the running engine?
  At the pinned commit `bdd795a3` it does not: the generated `public.GetSpec()` only feeds the
  request validator. Until it does, decision 18 checks the copies against the engine's files of
  the pinned commit instead.
- Is an API stability statement planned (which endpoints are stable, which are tooling)? The adapter
  pins one engine version per release (decision 15) until there is one.
- Is authentication or TLS on the public ports planned? The wiki will say "put a proxy in front" until
  then (E13.9).
- Should "job in the wrong state" be a `409` rather than a `500`? (E13.10)
- Are `loopCounter`/`nrOfInstances` variables planned for multi-instance iterations? (E13.7)
- Is the Java client (`zenbpm-java-client`) meant to follow every engine release? If so, and if it
  gains a Quarkus-neutral core, the adapter could adopt it later; today it trails by two minors.

## For the adapter's owners (with what to ask the VanillaBP project)

### 8. Native adapter on the Camunda 8 template instead of "built on the PEA adapter"

The skill `vanillabp-bpms-characteristics` records the opposite decision. `analysis/03-template-assessment.md`
gives the evidence: no PEA implementation for ZenBPM exists, and the PEA layer would hide what ZenBPM's
REST API offers. **Decided (2026-09-13):** the adapter is largely based on `camunda8-adapter`; draft
decision 1 stands. **To ask the VanillaBP project:** accept the two skill changes drafted in S1.1.2, so
the workspace stops saying the adapter is built on the PEA adapter. **Decided (2026-10-04):** not
asked. The skills live in the workspace superproject `vanillabp/development-workspace`, which is
not pbinitiative's, and no pull request to it is planned; the two skill edits stay local in the
workspace (S1.1.2), where they correct what agents read.

### 9. Which engine version to pin, and no release lines

**Recommendation:** pin the newest RELEASED tag when S1.2.1 runs; write "tested, not newer" into the
README; no `line-*` profiles (draft decision 15). Revisit if an engine minor breaks the used surface.

**Decided (2026-10-04):** the pin is the newest commit of the engine's `main` when S1.2.1 runs
(E13.3, job retries, is expected on `main` by then), written as `zenbpm.commit` plus `zenbpm.image`
= the digest of the `dev` image built from it; before the adapter's first release it moves to an
engine release tag (S12.4.1). No `line-*` profiles. Decision 15 and S1.2.1 are written that way.

### 10. Default `use-prefix` instead of `by-adapter`

The SPI says overriding the default is a last resort because of upgrading version-1 applications.
There are none on ZenBPM, and `by-adapter` cannot be served at all. **Recommendation:** confirm draft
decision 2; alternatively keep `by-adapter` as the default and let every ZenBPM application fail its
first boot with the two ways out, as the PEA adapter does.

### 11. Marker service tasks which change the model's topology

Decision 5 inserts service tasks before end events and after timer start events. It changes what a
viewer shows (extra elements) and produces a new process version on the first deployment after
upgrading. Alternatives rejected: polling instance states (scales with instances, no end event id) and
a CDC consumer (reads another product's tables). **Recommendation:** accept for the first release,
mark decision 5 as superseded by E13.6 when the engine offers notifications, and name the markers on
the wiki's Deviations page.

### 12. `message-start-lookup: scan` as an opt-in

A client-side scan of active instances is the kind of thing VanillaBP avoids (decision 19 of the
platform: a start asks for numbers). Here it runs per probe, not per boot, and is bounded.
**Recommendation:** ship it opt-in with a WARN naming the bound, or refuse `startWorkflowByMessage`
entirely until E13.4 lands. The plan ships the opt-in.

### 13. Repository, organisation, coordinates and licence

**Decided (2026-09-13):** the repository is `github.com/pbinitiative/zenbpm-vanillabp-adapter`
(exists, `main`, MIT), packages start with `org.pbinitiative.zenbpmadapter`. **Still to confirm:**
the groupId. The plan uses `org.pbinitiative.zenbpmadapter`, equal to the root package; the
alternative is the Java client's `org.pbinitiative.zenbpm` with artifact ids
`zenbpm-vanillabp-adapter-*`, which puts engine client and adapter under one group. Either works;
the choice has to be made before S1.1.1 and never changed afterwards.

**Decided (2026-10-04):** groupId `org.pbinitiative.zenbpm`, artifact ids as listed below, packages
stay `org.pbinitiative.zenbpmadapter`. Artifact ids in any case:
`zenbpm-vanillabp-adapter` (core), `zenbpm-vanillabp-adapter-spring-boot`,
`zenbpm-vanillabp-adapter-quarkus`, `zenbpm-vanillabp-adapter-quarkus-deployment`,
`zenbpm-vanillabp-adapter-engine-test-support`. The wiki lives at
`pbinitiative/zenbpm-vanillabp-adapter.wiki`.

**Licence.** MIT is compatible with what the plan does: the adapter links no engine code (network
protocol only, so the engine's AGPL does not reach it), and the code adapted from the Apache-2.0
VanillaBP adapters may live in an MIT project as long as the Apache licence text and the attributions
travel with it - draft decision 17 and the `NOTICE` / `LICENSE-APACHE-2.0` files of S1.1.1. Worth a
quick legal check by whoever signs off licences at pbinitiative.
Since S1.2.1 the repository also holds copies of two engine files, `openapi/api.yaml` and
`zenbpm.proto`, and since S1.2.2 the core jar ships classes generated from the proto. Those are
copies of AGPL-licensed engine files, not just protocol use, so the check has to say under which
licence they travel with the adapter; `NOTICE` leaves it open until then. The engine already has a
place for such an answer: its `LICENSING.md` lists files under another licence than the default
(the rqlite files under MIT). Listing `openapi/api.yaml` and `pkg/zenclient/proto/zenbpm.proto`
there, under MIT or Apache 2.0, would settle it for the copies and for the code generated from them.

### 14. New adapter keys

`shared-engine`, `message-start-lookup`, `message-start-lookup-max-instances`,
`async-task-max-age-action`, `max-redeliveries`, `user-task-poll-interval`, `history-time-to-live`,
`instance-cache-max-entries`, `client-id`, `grpc-address`, `grpc-plaintext`, `startup-wait`,
`workflow-visibility-timeout` - all under `vanillabp.adapters.<id>.*`, scoped ones at four levels.
**Recommendation:** review the names once against the Camunda 8 keys so a user moving between the two
adapters recognises them (`request-timeout`, `startup-wait`, `worker-threads`, `shutdown-grace`,
`retry-backoff` are identical on purpose).

### 15. Two things the core could offer every remote adapter

Not needed for this plan, but seen while mapping: (a) the instance-facts cache with a bounded LRU and
a "miss is not cached" rule, and (b) the local retry with backoff and a maximum before escalation,
are written in Camunda 8 and again here. If a third remote adapter comes, both are candidates for
`migration-adapter` support classes. **Recommendation:** note it in the platform's backlog, do nothing
now.

### 16. How the CI reads VanillaBP's snapshot artifacts

`io.vanillabp:*` is `2.0.0-SNAPSHOT` and lives in VanillaBP's GitHub Packages, which requires a
token even for public packages. The plan (S1.3.1) stores a maintainer's classic PAT with
`read:packages` as two repository secrets (done 2026-10-04; the Maven registry refuses fine-grained
tokens). **To ask the VanillaBP project:** whether a dedicated read-only machine account (or a
published release on Maven Central) is planned, so a foreign organisation's CI does not depend on
one person's token. Until then, the secrets are rotated when that person leaves, and the README's
"Contributing" section names them.

**Answered 2026-10-15 by the VanillaBP project's own change:** since 2026-10-09 both repositories
publish their snapshots to the snapshot repository of Maven Central, which anybody reads without a
login. The root POM names that repository, and the PAT and the two secrets are no longer used.

### 17. Where releases are published

The Java client of the engine is on Maven Central under `org.pbinitiative.zenbpm`, so the namespace and
the signing process exist at pbinitiative. **Recommendation:** snapshots to `pbinitiative`'s GitHub
Packages from `main` (S1.3.2), releases to Maven Central from a tag (S12.4.1), GitHub Packages as the
fallback if Central is not wanted for this artifact. Consumers of the blueprints profile (S12.2.1)
need Central or a token, which is one more reason for Central.

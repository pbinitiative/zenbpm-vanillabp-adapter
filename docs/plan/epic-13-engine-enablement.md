# E13 - Engine enablement (changes to `zenbpm`, each with its adapter twin)

**Goal.** Turn the `PARTIAL` rows of the mapping into `SUPPORTED` by small, well-scoped changes to the
engine, contributed upstream (`pbinitiative/zenbpm`, Go, follow `zenbpm/AGENTS.md`: `make generate`
after touching SQL, OpenAPI or protobuf; run `make test`, `make test-e2e`, `make go-static-analysis`;
review with the `zenbpm-code-review` skill; tests per the `zenbpm-e2e-tests` skill).

**Ordering.** By value for the adapter: E13.1 (done 2026-09-23) and E13.4 remove the two limits a
user meets first (the 30-second lock, the unfindable message-started workflow); E13.2, E13.3, E13.5, E13.6 make the
adapter simpler; E13.7 to E13.10 are quality. Each feature names the adapter story it supersedes; the
adapter keeps its fallback until the engine version carrying the change is the pinned one, then
switches by version (read from `/system/status`) or drops the fallback with a `UPGRADE.md` entry.

Every engine feature here is also an open question to the ZenBPM maintainers (open-questions 1-7),
because a different shape may already be planned. Since the same organisation owns the engine and the
adapter, the priorities the maintainers wrote next to those questions decide the order here, and the
answers already given are: question 2 (lock and acknowledgement, E13.1) high, question 3 (retries,
E13.3) medium with the retry strategy still open, question 1 (business key on message start, E13.4)
nice to have, questions 5 and 6 (events, strict publish, E13.5/6/8) low, question 4 (propagate all,
E13.2) leaning towards leaving the engine as it is.

**CI across the two repositories.** An engine change which serves the adapter is proven twice: by the
engine's own E2E test in `test/e2e/`, and by the adapter's nightly `latest-engine` job (S11.6.1)
turning green for the feature's adapter twin. Where an engine pull request would break the adapter,
the engine's CI can trigger the adapter's workflow with a `repository_dispatch` (or `workflow_call`)
carrying the freshly built image tag, so the adapter's ITs run against it before the engine merges.
This is set up as part of the first E13 feature which lands, and documented in both repositories.

---

## E13.1 Configurable job lock and acknowledgement

Full story with analysis, design, tests and acceptance criteria:
[`../engine-enablement/E13.1-configurable-job-lock-and-lock-extension.md`](../engine-enablement/E13.1-configurable-job-lock-and-lock-extension.md).

- [x] **Engine:** done - commit `071460cc` ("#841: Implement job locking capability per
  subscription", pull request #842, 2026-09-23). What shipped and where it differs from the story:
  the story's section 0.
- [x] **Adapter twin:** folded into the main plan instead of superseding stories later, because no
  adapter code existed yet. The adapter requires an engine carrying the feature (decision 15) and
  uses it from the first story: S2.2.1 (REST `extendJobLock`), S2.2.2 (its codes), S2.3.1
  (subscription settings, `lock_until`), S6.1.1 (`job-timeout`, renewal while a handler runs),
  S6.1.2 (parking asynchronous tasks by `async-task-lock-renewal`), S6.2.1 (the backoff as a lock
  extension), S6.2.2 (a closed stream releases the locks). Decision 12 (user tasks are polled) was
  re-examined and stands, for a reason other than slots: a terminated job is never delivered.

## E13.2 Propagate job outputs without mappings

- [ ] **Engine:** a `zenbpm:taskDefinition propagateOutputs="all"` attribute (or an engine setting)
  making `JobCompleteByKey` use `PropagateMappedOutputsOrAll` for a task without output mappings.
  The maintainers lean towards leaving the engine as it is so a task cannot pollute the variable
  context (open question 4); if that stands, this feature is dropped and the adapter keeps the
  `PATCH`-then-`complete` of decision 7 as its final shape.
- [ ] **Adapter twin (simplifies S6.1.1, S6.3.1, S8.2.1):** drop the `PATCH` before `complete` and
  set the attribute while rewriting; decision 7 superseded.

## E13.3 Job retries

Full story with analysis, design, tests and acceptance criteria:
[`../engine-enablement/E13.3-job-retries-with-backoff.md`](../engine-enablement/E13.3-job-retries-with-backoff.md).

- [ ] **Engine:** implement `zenbpm:taskDefinition retries` (the TODO in `pkg/bpmn/engine.go`): a
  `fail` without an error code decrements retries and re-activates the job after an optional backoff
  (`retryBackoff` in the fail request); an incident only at zero.
- [ ] **Adapter twin (supersedes S6.2.1):** `fail` with `retries` and `retryBackoff` like Camunda 8;
  `ZenBpmLocalRetry` removed; decision 8 superseded.

## E13.4 Findable message-started workflows

- [ ] **Engine:** one of (a) `businessKey` on `POST /v1/messages` applied to the instance a message
  start creates, (b) `PATCH /v1/process-instances/{key}` accepting `businessKey` (the SQL upsert already
  carries the column), (c) a variable filter `variables[name]=value` on `GET /v1/process-instances`
  (a `json_extract` index). (a) is the smallest; (c) the most general. Optionally a uniqueness option
  for the business key per definition (idempotent start).
- [ ] **Adapter twin (supersedes S7.1.2):** `startWorkflowByMessage` sends the business key; the scan
  mode and `message-start-lookup` are removed; GAPS 1 closed.

## E13.5 User-task lifecycle events

- [ ] **Engine:** a stream subscription kind (or a dedicated RPC) delivering `created`, `assigned`,
  `completed`, `terminated` events for user-task jobs, acknowledged individually and not counted as
  active jobs.
- [ ] **Adapter twin (supersedes S8.1.1):** the poller becomes a subscription; `user-task-poll-interval`
  removed; decision 12 superseded.

## E13.6 Lifecycle notifications and a richer job

- [ ] **Engine:** (a) `WaitingJob` carries `element_instance_key`, `bpmn_process_id`,
  `process_definition_key`, `version`, `business_key`; (b) an end-of-instance and a start-of-instance
  notification a client can subscribe to (job-like, acknowledged), including `terminated`; (c) a
  cancellation notification for active jobs. The `EventExporter` in `pkg/bpmn/exporter` is the
  natural source.
- [ ] **Adapter twin (supersedes S9.1.1, S9.2.1, S9.3.1 partly, S6.1.1's instance lookup):** activation
  id = element instance key; no instance GET per job; markers replaced by subscriptions; `TERMINATED`
  reported; `@TaskEvent CANCELED` for service tasks. Decision 5 superseded.

## E13.7 Multi-instance variables

- [ ] **Engine:** every iteration's child instance gets `loopCounter` (1-based, as Camunda) and
  `nrOfInstances`; documented in `docs/reference/bpmn/`.
- [ ] **Adapter twin (supersedes S9.4.1):** read the two variables, drop the child-list walk.

## E13.8 Strict message publication and buffering

- [ ] **Engine:** (a) a `strict: true` flag on `POST /v1/messages` refusing the fallback from an
  unmatched correlation key to a definition-level subscription; (b) optional buffering with a
  `timeToLive` and a `messageId` for deduplication.
- [ ] **Adapter twin (supersedes parts of S4.2.2, S7.1.1):** the start/catch collision check becomes
  unnecessary with (a); `PhaseTwoRetryLater` becomes unnecessary with (b) and the outbox key doubles as
  the message id.

## E13.9 Authentication and TLS on the public ports

- [ ] **Engine:** bearer-token (or API-key) authentication middleware for REST and gRPC, TLS
  configuration for both listeners.
- [ ] **Adapter twin:** `vanillabp.adapters.<id>.auth.*` (method, token or credentials, never echoed),
  `grpc-plaintext` becomes the TLS switch it already is; GAPS 12 closed.

## E13.10 Job-state errors as 409

- [ ] **Engine:** map "job already completed/terminated" and "instance not active" engine errors to
  `409 Conflict` instead of `500` in the REST handlers (`internal/rest/server.go`).
- [ ] **Adapter twin (refines S2.2.2):** `ZenBpmErrors` treats 409 on a job as "gone" (final) instead
  of repeating a 500.

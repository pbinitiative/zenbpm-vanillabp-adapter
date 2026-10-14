# E13 - Engine enablement (changes to `zenbpm`, each with its adapter twin)

**Goal.** Turn the `PARTIAL` rows of the mapping into `SUPPORTED` by small, well-scoped changes to the
engine, contributed upstream (`pbinitiative/zenbpm`, Go, follow `zenbpm/AGENTS.md`: `make generate`
after touching SQL, OpenAPI or protobuf; run `make test`, `make test-e2e`, `make go-static-analysis`;
review with the `zenbpm-code-review` skill; tests per the `zenbpm-e2e-tests` skill).

**Ordering.** By value for the adapter: E13.1 (done 2026-09-23) and E13.4 remove the two limits a
user meets first (the 30-second lock, the unfindable message-started workflow); E13.2, E13.3, E13.5, E13.6 make the
adapter simpler; E13.7 to E13.10 are quality. Each feature names the adapter story it supersedes; the
adapter keeps its fallback until the engine build carrying the change is the pinned one, then
drops the fallback with an `UPGRADE.md` entry. It does not switch by the version `/system/status`
reports: while the pin is a `main` build that version names the last release (decision 15).

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
- [x] **Adapter twin (refines S2.2.2):** `ZenBpmErrors` treats 409 on a job as "gone" (final) instead
  of repeating a 500.

*Found by S2.2.2, 2026-10-14.* At the pinned commit `bdd795a3` the engine already answers `409` to
a completion and a failure of a job which is completed, terminated or failed (`ErrJobInTerminalState`;
a completion of a completed job is answered `201`), and to a variable update of an instance which is
neither active nor failed. `ZenBpmErrorsIT` measures the first for a job which failed with an
incident. So `ZenBpmErrors` reads a `409` of a job's completion or failure as a gone job from the
start, and nothing waits for the engine half of this item for those calls.

*Found by a review of S2.2.2, 2026-10-14.* The adapter's reading holds only while the engine answers
`409` to a completion or a failure for a command the job no longer takes (decision 10). An instance
which is not active, for example one with an incident on another branch, is a `TECHNICAL_ERROR`
there today (`validateExternalTriggerInstanceState` in `pkg/bpmn/engine_api.go`, read in the code,
not measured), so the completion stays repeatable. If the engine half of this item maps "instance
not active" to `409` for these two calls as well, the adapter would drop a completion of a job which
still waits. Keep that case off `409` for `complete` and `fail`, or supersede decision 10 and split
it in `ZenBpmErrors` first.

## E13.11 Answered subscriptions

- [ ] **Engine:** answer every `StreamSubscriptionRequest` on the job stream, subscriptions and
  unsubscriptions, the ones it takes as well as the ones it refuses, with a frame naming the job
  type, and give a refusal a `JobStreamErrorCode`. Today a request it takes gets no answer, and one
  it refuses gets an `ErrorResult` without a code whose message alone names the job type
  (`recvClientRequests` in `internal/grpc/server.go`; `unknownRequestError` has the same shape).
- [ ] **Adapter twin (refines S2.3.1):** a refused subscription fails that subscription and names
  its job type, instead of the whole call being opened again; a stream counts as back once every
  subscription was answered, instead of after a frame or two seconds without a failure.

*Found by S2.3.1, 2026-10-14.* At the pinned commit `bdd795a3` the engine refuses a subscription
only where the client is not registered (`addJobSub` in `internal/cluster/jobmanager/client.go`),
and the engine's handler registers the client before it reads the first request, so a refusal is not
expected in practice. The adapter
cannot tell which subscription an error frame answers, because nothing reads the engine's prose
(decision 10), so it logs the error with every subscribed job type and opens the call again.

## E13.12 Server keepalive on the job stream

- [ ] **Engine:** set `keepalive.ServerParameters` on the public gRPC server (`NewServer` in
  `internal/grpc/server.go`), so that the engine notices a job stream whose client is gone within
  minutes. Today it keeps grpc-go's default of a ping after two hours of silence, and until the
  connection fails it goes on handing jobs of the client's types to it, round robin; each such job
  waits for its lock to lapse.
- [ ] **Adapter twin:** none; the adapter pings from its side already (S2.3.1).

*Found by a review of S2.3.1, 2026-10-14.*

## E13.13 Variables above 4 MiB

- [ ] **Engine:** either carry what REST accepts through the gRPC between the nodes (raise
  `MaxRecvMsgSize`/`MaxCallRecvMsgSize` there to at least `httpServer.maxRequestBodyBytes`), or answer
  a request whose variables are too large with `413` instead of `500 TECHNICAL_ERROR`.
- [ ] **Adapter twin (refines S5.1.1 and `ZenBpmErrors`):** with `413`, a start or a variable update
  which is too large is permanent at once (decision 10 already reads `413` so).

*Found by a review of S2.3.1, 2026-10-14.* At the pinned commit `bdd795a3` a start with a 5 MiB
variable and a variable update which brings an instance to 6 MiB are both answered `500` with
"grpc: received message larger than max (... vs. 4194304)". The gRPC between the nodes keeps its
default limit of 4 MiB, since neither `Server.Open` in `internal/cluster/server/server.go` nor
`ClientManager.newClient` in `internal/cluster/client/client_manager.go` sets another.
`ZenNode.CreateInstance` and `ZenNode.ModifyProcessInstance` in `internal/cluster/node.go` turn the
failed call into `zenerr.TechnicalError`. REST takes bodies up to 10 MiB
(`HttpServer.MaxRequestBodyBytes` in `internal/config/config.go`) and answers a larger one with `413`
(`internal/rest/middleware/openapi.go`). So no instance gets more than 4 MiB of variables through
the API, and no job above that size reaches the job stream today. The stream takes jobs of any size
all the same (S2.3.1).

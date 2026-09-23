# ZenBPM capabilities relevant to a VanillaBP adapter

Source: `zenbpm/` at commit `b22f12c2` (`VERSION` = `v1.8.0`, unreleased; releases every 2-6 weeks,
last `v1.7.0`). Paths are relative to `zenbpm/`. Facts marked **(verified)** were re-read in the source
after the first survey because the design depends on them; everything else was read once.

## 1. Client-facing surface

| Port | Purpose | Client of the adapter? |
|---|---|---|
| `:8080` | REST `/v1/**` generated from `openapi/api.yaml`; operational plane `/system/**` (`internal/rest/server.go`) | yes, for every command and query |
| `:9090` | gRPC `grpc.ZenBpm` with ONE rpc `JobStream` (`pkg/zenclient/proto/zenbpm.proto`) | yes, for job delivery |
| `localhost:8090` | Raft, rqlite replication, internal `ZenService` | no |

- Every `/v1` request is validated against the embedded OpenAPI spec; bodies above 10 MiB get `413`.
- Errors are `{code, message}`; engine errors are mapped per handler (`internal/cluster/zenerr`):
  `NotFound -> 404`, `BadRequest -> 400`, `ClusterError -> 502`, everything else `500`. A job command
  against a job in the wrong state is an untyped engine error and surfaces as **500**, not 409.
- **No authentication and no TLS** on the public ports (middleware chain in `internal/rest/server.go`;
  `grpc.NewServer` without credentials). Production setups need a proxy.
- **No tenant, namespace or partition key** in the API. Partitions are internal; list endpoints return
  `partitions[]` and page per partition.

### Client libraries

- Go: `pkg/zenclient` (oapi-codegen REST client + gRPC worker with reconnect/backoff).
- Java: separate repo `pbinitiative/zenbpm-java-client`, `org.pbinitiative.zenbpm:zenbpm-client-core`
  1.5.0 plus a Spring Boot starter, Java 8/17, MIT. It trails the engine (1.8 changed the deploy
  content type to `application/octet-stream`, old clients get `415`) and has no Quarkus support
  (`docs/static/client-libraries.md`).
- No Process-Engine-API (bpm-crafters) implementation for ZenBPM exists anywhere in the workspace.

## 2. Deployment and definitions

- `POST /v1/process-definitions`, `Content-Type: application/octet-stream`, body = ONE `.bpmn` file.
  `201 {processDefinitionKey}` for a new version, `200` with the existing key when the latest version
  of the same `bpmnProcessId` is identical after XML normalisation (`pkg/bpmn/xml_loader.go`,
  `pkg/xmlutil/content.go`). A different content makes `version = latest + 1` and deletes the
  previous version's definition-level message and timer subscriptions (only the newest version starts
  by message or timer).
- Only one `<bpmn:process>` per file is modelled (`pkg/bpmn/model/bpmn20/core.go`). Unsupported
  elements are rejected at deployment with `400` (`pkg/bpmn/unsupported_elements_test.go`).
- `zenbpm:versionTag` on the process element (`extractProcessVersionTag`); deploying an existing tag
  again is an error.
- `GET /v1/process-definitions?bpmnProcessId=&onlyLatest=` lists versions; `GET .../{key}` returns
  `bpmnData` (XML), `version`, `versionTag`; `GET /v1/process-definitions/statistics` counts instances
  per definition (with `withIncident`); `GET .../{key}/statistics` per element.
- DMN: `POST /v1/dmn-resource-definitions`, evaluate, decision instances. Business rule tasks call a
  decision locally (`zenbpm:calledDecision`) or as a job (`zenbpm:taskDefinition`).

## 3. Instances

- `POST /v1/process-instances {processDefinitionKey | bpmnProcessId, variables, businessKey,
  historyTimeToLive}` -> `201 ProcessInstance{key, processDefinitionKey, bpmnProcessId, version,
  versionTag, businessKey, state, variables, activeElementInstances[]}`. **Synchronous**: the engine
  runs until every token waits or the instance ended. **No idempotency**: no request id, `businessKey`
  is not unique (no unique index, `internal/sql/migrations/0001_schema.up.sql`).
- `GET /v1/process-instances?businessKey=&bpmnProcessId=&state=&processDefinitionKey=&createdFrom=`
  **(verified: `business_key` filter in `internal/sql/queries/process_instance.sql`)**. There is **no
  variable filter**; variables are one JSON column and list rows carry them in full.
- `GET /v1/process-instances/{key}` -> state in `active | completed | terminated | failed` (`failed` =
  unresolved incident, instance still exists), `activeElementInstances`, `variables`, `processType`
  in `default | multiInstance | subprocess | callActivity`, `parentProcessInstanceKey`.
  **Sub-processes, call activities and multi-instance bodies are child process instances.**
- `PATCH /v1/process-instances/{key}/variables` merges variables (204; 409 in a forbidden state);
  `DELETE .../variables/{name}`. No element-local variable API.
- `POST /v1/process-instances/{key}/cancel` (root instances, `active|failed`) -> jobs `terminated`,
  **workers are not told**.
- History: `GET .../history` (flow element instances with in/out variables, page up to 1000),
  `.../jobs`, `.../incidents`, `.../child-processes`, `.../event-subscriptions/{messages|timers|errors}`.
  Kept until `historyTimeToLive` (default `cluster.persistence.instanceHistoryTTL`, 0 = forever).
- Consistency: each partition is rqlite over Raft; commands run on the partition leader, which is the
  only node executing the engine; **no separate exporter or read model**. Internal reads use
  `ConsistencyLevel_NONE` (`internal/cluster/partition/partition_persistence.go`), so on a single node
  a read after a write sees the write; in a multi-node cluster a follower may lag by replication time.
  Clients cannot choose a consistency level.

## 4. Jobs (service, send, user, external business-rule tasks, message throw/end events)

- Job rows: `key`, `type`, `state` (`active|completed|terminated|failed`), `element_id`,
  `element_type`, `element_instance_key`, `process_instance_key`, `input_variables`,
  `output_variables`, `assignee`.
- REST `GET /v1/jobs?jobType=&state=&processInstanceKey=&assignee=` is a plain query: **no lock, no
  state change, no long poll**. REST `Job` carries no `bpmnProcessId`, definition key or version, no
  headers, no deadline, no business key; `retries` is in the schema but never populated.
- gRPC `JobStream`: subscribe per `job_type`; the partition leader polls
  `GetWaitingJobs` (`state = 1 AND type IN (...) AND key NOT IN (distributed)`,
  `internal/sql/queries/job.sql`) and round-robins to subscribed clients. Payload
  `WaitingJob{key, instance_key, input_variables, type, element_id, created_at, element_type}`.
  Client identity = gRPC metadata `client_id` (UUID generated if absent; a second stream with the same
  id is rejected).
- **Lock (since commit `071460cc`, 2026-09-23, E13.1)**: each subscription names its own
  `lock_duration_ms` and `max_active_jobs` per job type (`0` = engine defaults
  `jobManager.defaultLockDurationMs` = 30 s and `jobManager.defaultMaxActiveJobs` = 10; capped at
  `maxLockDurationMs` = 24 h and `maxActiveJobsCap` = 1000, silently). Every `WaitingJob` carries
  `lock_until` (leader clock, conservative). A holder extends the lock to "now plus a duration" with
  `JobExtendLockRequest` on the stream (answer `LockExtended` or `ErrorResult` codes 1 lock not held,
  2 held by another client, 3 leader unavailable - outcome unknown) or `POST /v1/jobs/{key}/extend-lock
  {clientId, lockDuration}` (`200 {lockUntil}`, `409`, `404`, `502`). Any positive duration is accepted,
  so an extension by a short duration makes a job deliverable again at a chosen moment. The cap is
  counted per (client, job type). The lock is still in memory on the partition leader: a leader change
  forgets every lock and the new leader redelivers at once; closing a stream releases all locks of
  its client at once; a leader holds at most about 32,700 locked jobs. **No ownership check**: any
  client may complete any active job. **An assignee does not stop a job from being handed out** (the
  waiting query ignores it).
- Before that commit (up to `v1.7.0`): a fixed 30 s lock, no extension, ten active jobs per CLIENT
  across all types. The adapter does not support those engine versions (decision 15).
- `POST /v1/jobs/{key}/complete {variables}` -> 201. **(verified)** Already completed -> engine logs
  and returns `nil` (201). Terminated/failed job or an instance not `active` -> 500.
  **(verified)** The variables reach the process scope ONLY through `zenbpm:output` mappings of the
  task: `PropagateOnlyMappedOutputs` returns an empty map where the task has no output mapping
  (`pkg/bpmn/runtime/varholder.go:107`, `pkg/bpmn/jobs_api.go:285`). Catching events use
  `PropagateMappedOutputsOrAll` instead and do propagate everything.
- `POST /v1/jobs/{key}/fail {errorCode?, variables?}` -> 204. With an `errorCode` the engine searches
  boundary error events and error event sub-processes up the scope chain (a catch without `errorRef`
  catches all) and continues there; without one, or with no catcher, the job becomes `failed`, an
  **incident** is created and the instance turns `failed`. There is no `message` field on REST.
- **Retries are not implemented (verified: `pkg/bpmn/engine.go` "TODO: Implement Headers as worker
  parameters and Retries")**. A technical failure the worker cannot recover from is either left to the
  lock's redelivery (or brought back earlier by extending the lock by a short duration) or escalated
  by `fail`, which is an incident. `POST /v1/incidents/{key}/resolve`
  re-activates the job.
- `POST /v1/jobs/{key}/assign {assignee}` for user tasks; REST cannot unassign.

## 5. BPMN dialect

- Job type = `zenbpm:taskDefinition type`; `retries` parsed and ignored. User tasks default to type
  `user-task-type` (`pkg/bpmn/model/bpmn20/activities.go`).
- Two extension namespaces are accepted, `http://zenbpm.pbinitiative.org/1.0` and Camunda's
  `http://camunda.org/schema/zeebe/1.0` (`pkg/xmlutil/content.go`); parsing goes by local name, so
  models from the Camunda Modeler deploy. Camunda 7 local names are ignored.
- Parsed extensions: `taskDefinition`, `ioMapping/input|output`, `taskHeaders` (external business
  rule task only, not exposed to workers), `assignmentDefinition`, `calledElement` (processId,
  version, bindingType, versionTag), `calledDecision`, `loopCharacteristics` (inputCollection,
  inputElement, outputCollection, outputElement), `in@businessKey` (call activities),
  `subscription@correlationKey` on `bpmn:message`, `versionTag`. **Not parsed**: `formDefinition`,
  `userTaskForm`, `taskSchedule`, `priorityDefinition`, `executionListeners`, `taskListeners`,
  `properties`, `script`.
- Expressions: FEEL (`pkg/script/feel`); a value starting with `=` is evaluated, anything else is a
  literal; variables by bare name. Input variables of a job are EMPTY unless the task has input
  mappings (`pkg/bpmn/runtime/varholder.go`).

### Element support

| Supported | Not supported (rejected at deployment or absent) |
|---|---|
| service, send, user, business-rule (local DMN or job), receive (incl. instantiating) tasks | script, manual, abstract tasks |
| call activity (latest, version, versionTag; business key via `zenbpm:in`), embedded sub-process, event sub-process (message, timer, error; interrupting and not) | loop marker, compensation, ad-hoc |
| multi-instance parallel and sequential (`pkg/bpmn/multi_instance.go`; each iteration is a child process instance carrying the input element; index is read from history, no total variable) | |
| boundary timer (both kinds, cycles), boundary message (both kinds), boundary error (interrupting, catch-all) | boundary escalation, signal, conditional, compensation, cancel |
| intermediate catch timer, message, link; intermediate throw message (job), link | intermediate throw none, signal, escalation, compensation |
| start none, message, timer (duration, date, cycle incl. cron) | start signal, conditional |
| end none, message (job), error, terminate | end signal, escalation, compensation, cancel |
| exclusive, inclusive, parallel, event-based gateways; conditional and default flows from gateways | complex gateway; conditional flow leaving an activity |
| | **signals in general, execution and task listeners** |

## 6. Messages, variables, incidents, timers

- `POST /v1/messages {messageName, correlationKey?, variables}` -> 201. A correlation key is declared
  as `zenbpm:subscription correlationKey="=expr"` on the `bpmn:message`, evaluated when the
  subscription is created; one active subscription per `(name, key)` cluster-wide (partial unique
  index). **(verified)** Nothing waiting -> **404** (`internal/cluster/node.go:844,867`). **No
  buffering, no TTL, no message id, no deduplication.** With a key which matches nothing the engine
  **falls back to a definition-level subscription** (message start event or instantiating receive
  task) with a warning - a correlation can therefore start a new instance.
- Variables: a JSON object per instance; numbers decode as float64 in Go.
- Incidents: created on `fail` without a catcher, engine errors, guard limits, message publication
  failures. Per instance `GET .../incidents`, `POST /v1/incidents/{key}/resolve`. No global list; use
  `GET /v1/process-definitions/statistics` with `withIncident`.
- Timers: `timer` table polled by the partition leader every `POLL_TIMER_DELAY_SECONDS` (default 10,
  env only); a new leader re-polls after failover.

## 7. Operations

- Docker image `ghcr.io/pbinitiative/zenbpm:<tag>` (`v1.7.0`, `latest`; amd64 + arm64), no config
  baked; env defaults form a working single node (`REST_API_ADDR`, `GRPC_API_ADDR`,
  `CLUSTER_RAFT_BOOTSTRAP_EXPECT`, `POLL_TIMER_DELAY_SECONDS`, `PERSISTENCE_INSTANCE_HISTORY_TTL`).
- Health: `GET /system/health/live` (always 200), `GET /system/health/ready` (200/503 with
  `reasons`), `GET /system/status` (build + topology, always 200), `GET /system/metrics` (Prometheus).
- No written API stability promise; breaking changes shipped in a minor (1.8 deploy content type).
  `/modify/*` and `/tests/*` carry correctness TODOs. Licence AGPL-3.0 plus enterprise.

## 8. Limits which shape the adapter (all verified in source)

1. ~~Job lock 30 s, not extendable, 10 active jobs per client~~ - solved by E13.1 (commit `071460cc`):
   the lock is configurable per subscription and extendable, the cap is per job type. What remains:
   the lock lives in memory on the partition leader, so a leader change redelivers every open job.
2. No retries: `fail` without an error code is an incident at once.
3. Job completion variables reach the process scope only through output mappings.
4. A message nobody waits for is refused with 404 and lost; an unmatched correlation key may start a
   new instance where the same message name has a start event.
5. Instances are findable by business key, never by variable; message-started and timer-started
   instances carry no business key.
6. No listeners and no event stream a client could subscribe to; cancellation notifies nobody.
7. No tenant, no authentication, no TLS on the public ports.
8. No signals, no script tasks, no conditional events.

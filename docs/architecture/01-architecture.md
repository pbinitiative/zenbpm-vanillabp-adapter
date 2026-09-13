# Architecture of `zenbpm-adapter`

## 1. Repository and coordinates

- Repository `github.com/pbinitiative/zenbpm-adapter` (exists, default branch `main`, MIT licence,
  owned by the ZenBPM maintainers), checked out as a sibling of the other adapters in the workspace and
  registered in the superproject's `.gitmodules` as a LOCAL-ONLY member like `zenbpm` (the
  superproject's own CI never sees either); plus `pbinitiative/zenbpm-adapter.wiki`. What the
  workspace still needs is in S1.1.2.
- groupId `org.pbinitiative.zenbpmadapter` (equal to the root package, so a class name tells the
  artifact), artifacts `zenbpm-adapter` (core), `zenbpm-adapter-spring-boot`, `zenbpm-adapter-quarkus`,
  `zenbpm-adapter-quarkus-deployment`, `zenbpm-adapter-engine-test-support`; version `${revision}` =
  `2.0.0-SNAPSHOT`, Java 21, Spring Boot 4.1.x and Quarkus 3.39.x as managed by
  `adapter-platform-integration`. The VanillaBP platform artifacts (`io.vanillabp:*`, still
  `2.0.0-SNAPSHOT`) are read from VanillaBP's GitHub Packages, which needs a token in CI (S1.3.1).
- Licence: MIT for the repository. Code copied and adapted from `camunda8-adapter` and
  `process-engine-api-adapter` is Apache 2.0 and keeps its licence: the copied files carry their
  original header, `NOTICE` names both origins with a pointer to the Apache licence text kept as
  `LICENSE-APACHE-2.0`, and the README's licence section says so (draft decision 17).
- Conventions: the repository follows the VanillaBP adapter conventions (module split, Spotless with
  the platform's `formatting_conventions.xml`, per-platform coverage with the 85/90 gate, `test-utils`,
  `DECISIONS.md` as the only citation target, wiki user-facing and README contributor-facing), so the
  four VanillaBP adapters read alike although this one has other owners (draft decision 16). The Go
  conventions of `zenbpm/AGENTS.md` do not apply here.
- Adapter type constant `zenbpm` (`ZenBpmAdapter.ADAPTER_TYPE`), Quarkus capability
  `io.vanillabp.adapter.zenbpm`, extension name `vanillabp-zenbpm`.
- Engine pin: one property `zenbpm.version` (initially `v1.8.0`, or `v1.7.0` if 1.8 is not
  released when the first story runs, see open question 9), filtered into
  `core/src/main/resources/META-INF/vanillabp/adapter-zenbpm.properties` (`adapter.version`,
  `platform.version`, `zenbpm.engine`) and into the test resource `zenbpm-engine.properties`
  (`engine.image=ghcr.io/pbinitiative/zenbpm:${zenbpm.version}`). The API contract the adapter is
  written against is the copy `core/src/main/zenbpm/api.yaml` and `core/src/main/proto/zenbpm.proto`
  taken from the engine tag; a test diffs the shipped copies against the pinned image's
  `/v1/openapi` where the engine serves it, otherwise the copy is the contract.

```
zenbpm-adapter/
  pom.xml                         parent: modules, BOM imports, Spotless, JaCoCo, flatten, engine pin
  formatting_conventions.xml      copied from adapter-platform-integration
  AGENTS.md  DECISIONS.md  GAPS.md  UPGRADE.md  README.md  LICENSE  NOTICE  readme/
  core/                           plain Java: SPI implementations, REST client, job stream, model handling
  spring-boot/                    auto-configuration, BeanRegistrar, overlay properties, Spring ITs (Docker)
  smoke-test/                     Spring application booting without an engine: discovery, validation, health
  quarkus/runtime/                producers, overlay @ConfigMapping, quarkus-extension.yaml, startup observer
  quarkus/deployment/             build steps, build-time config root, extension tests (no Docker)
  quarkus/integration-tests/      QuarkusProdModeTest against the engine container, introspection endpoints
  quarkus/native-image-tests/     later (E11); a native build against the engine container
  election-integration-test/      two adapter ids on one engine, and zenbpm next to the BPMS double
  test-coverage-report/           spring-boot, quarkus, coverage-gate (85 floor, 90 rule)
  .github/workflows/              checks.yaml (PR + main: Spotless, build, unit and Docker tests, report upload)
                                  publish-snapshots.yaml (main: snapshots to pbinitiative's GitHub Packages,
                                    coverage reports to GitHub Pages)
                                  nightly.yaml (E11: pinned engine plus the engine's latest image, native image)
                                  release.yaml (E12: tag -> release build, publication, wiki version table)
                                  settings.xml for reading io.vanillabp snapshots from VanillaBP's GitHub Packages
  renovate.json                   own configuration (config:recommended); the engine image and pin move together
  NOTICE, LICENSE-APACHE-2.0      attribution of the code adapted from the Apache-2.0 VanillaBP adapters
```

`core` depends on `io.vanillabp:vanillabp-adapter-spi`, `io.vanillabp:spi-for-java`, Jackson
(`jackson-databind`, both platforms manage it), `io.grpc:grpc-netty-shaded`, `grpc-protobuf`,
`grpc-stub`, `protobuf-java` (versions from the Quarkus BOM, which is the stricter of the two for
native images), and `org.slf4j:slf4j-api`. No Spring, no Quarkus, no Camunda artifact.

## 2. The engine client (`core`, package `org.pbinitiative.zenbpmadapter.client`)

Hand-written and small on purpose (draft decision 3): the adapter uses about twenty endpoints, the
official Java client trails the engine and has no Quarkus story, and a generated client would put a
generator plus its reflection needs into every build. The gRPC stubs ARE generated (protobuf-maven-plugin
from the pinned proto), because a hand-written gRPC client is not a reasonable thing to write.

| Class | Responsibility |
|---|---|
| `ZenBpmAdapterConfiguration` | the per-id keys (section 6), their validation with the three outcomes, `instanceIdentity()` (normalised `rest-address`), never echoing values |
| `ZenBpmClientFactory` | builds ONE `ZenBpmClient` per adapter id eagerly; owns its lifecycle; closes what a module never stopped |
| `ZenBpmClientRegistry` | id -> factory, the platform's lookup; knows whether two ids address one engine |
| `ZenBpmRestClient` | `java.net.http.HttpClient` + Jackson; one method per used endpoint returning typed records (`ProcessDefinitionRef`, `ProcessInstanceRef`, `JobRef`, `HistoryPage`, ...); `request-timeout` on every call; maps `{code,message}` into `ZenBpmApiException(status, code, message, path)` |
| `ZenBpmJobStream` | one bidirectional `JobStream` per adapter id and node, metadata `client_id`; subscribe/unsubscribe per job type with reference counting across modules; reconnect with backoff; hands `WaitingJob`s to a `JobDispatcher` |
| `ZenBpmErrors` | the one classification: `permanentFailure(Throwable)` (400, 413, 415, key not a number), `isGone(Throwable)` (404 on a job), `retryLater(Throwable)` (404 on a message), `unavailable(Throwable)` (I/O, timeout, 502, 503, 5xx); read from status codes, never from text (Camunda 8 decision 16 applies verbatim) |
| `ZenBpmEngineWait` | before the first deployment round per adapter id: poll `GET /system/health/ready` until 200, `startup-wait` runs out, or a permanent answer; log the address and the last answer every few seconds |
| `ZenBpmHealth` | `checkHealth()`: READY -> UP, 503 -> DOWN with `reasons`, unreachable -> DOWN, unconfigured -> UNKNOWN |
| `ZenBpmInstanceCache` | bounded LRU `instanceKey -> InstanceFacts(bpmnProcessId scoped, version, processDefinitionKey, businessKey, parentKey, processType)`; filled by `GET /v1/process-instances/{key}`; per adapter id; `instance-cache-max-entries` (10 000) |
| `ZenBpmExecutor`, `ZenBpmExecutionModel` | the handler slots: `worker-threads` platform threads (4) or `virtual` bounded by `worker-threads-bound`; two platform threads for timing (reconnect, pollers, backoff) which no handler can occupy |
| `ZenBpmEnvironmentInfo` | logs engine version and node from `GET /system/status` once per adapter id at startup |

Transport rule: **the stream delivers, REST does everything else.** Completing and failing a job goes
over REST although the stream offers it, so that every command answers with an HTTP status the
classification can read, and so that a completion survives a stream reconnect.

## 3. Model handling (`core`, package `org.pbinitiative.zenbpmadapter.model`)

The `BPMN` type parameter is `ZenBpmBpmnModel`: a record holding the `org.w3c.dom.Document`, the
filename, the plain `bpmnProcessId`, and what was read off it (job-producing tasks as `BpmnTaskSpec`,
user tasks, message names with and without a start event, timer start events, multi-instance elements
with their nesting, end events, concurrent-token elements, FEEL identifiers). Reading is by local
name in either extension namespace (`zeebe:` or `zenbpm:`); writing uses the `zenbpm` namespace,
declared on the root once.

`prepareBpmn` rewrites the document ONCE per file (the context remembers the filename) and every
rewrite is idempotent and never overwrites what the modeller wrote (draft decision 4):

1. **Scoping** (`ZenBpmScoping`, from `PeaScoping`): under `use-prefix` rewrite `process@id`,
   `message@name`, `error@errorCode`, `escalation@escalationCode`, `taskDefinition@type` (scoped per
   process by default), `calledElement@processId`, `calledDecision@decisionId`; unchanged in `none`.
2. **Correlation keys**: every `bpmn:message` used by a catch element, boundary event or receive
   task without `zenbpm:subscription` gets `correlationKey="=<aggregate-id variable>"`. A message
   used ONLY by a start event gets none. A message a start event AND a catch element share is
   refused with a guiding message (the engine's fallback from an unmatched key to the start
   subscription would start a second workflow on a correlation, see GAPS 4).
3. **Task parameters**: for every job-producing task, one `zenbpm:ioMapping input` per name
   `taskParameterNames` reports (`source="=<name>" target="<name>"`), so the job carries exactly what a
   `@TaskParam` reads; nothing else, because the aggregate is loaded from the application's database.
4. **Marker tasks** (draft decision 5): where `workflowEndedHandlerExists` for the process, a service
   task `id="vanillabp__ended__<endEventId>"`, `zenbpm:taskDefinition type="<scoped
   vanillabp__ended>"`, is inserted in front of every end event of the TOP-LEVEL process (none, error,
   message, terminate), redirecting the end event's incoming flows to the task and adding one flow
   task -> end event. Where a timer start event exists and the module declares a
   `@WorkflowStartedByBpms` method or the core wants the aggregate built, a service task
   `vanillabp__started__<startEventId>` with type `<scoped vanillabp__started>` is inserted right
   after it. Both types are per PROCESS (they carry the process id, so one worker per process).
   Marker elements are drawn with a `bpmndi:BPMNShape` copied from their neighbour so the engine's
   diagram interchange stays valid.
5. **Unsupported constructs** are refused BEFORE deployment with a message naming file, process and
   element: signal events, conditional events, escalation events, script tasks, an instantiating
   receive task, a file with more than one executable process.

`wireBpmn` makes the core calls: `validateTaskWiring` (service, send, business-rule-as-job tasks and
message throw events as mandatory specs; user tasks as optional specs; a business rule task with
`calledDecision` is engine-served and skipped), `reportConcurrentTokenElements`,
`registerProcessVersions` (catalog per process), `unsharedWorkflowAggregatePaths` (FEEL identifiers of
conditions, timers, collections, assignees), `workflowsShareTheWorkflowAggregate` for call activities
(a called process on the same aggregate gets `zenbpm:in businessKey="=<id var>"` and an input mapping
of the id variable injected; one on its own aggregate does not).

`deployResources`: `ZenBpmEngineWait`, then `validateNoCollidingProcessIds`, then one `POST
/v1/process-definitions` per file and one `POST /v1/dmn-resource-definitions` per DMN, then `GET
/v1/process-definitions/{key}` per process to learn the version, `registerDeployedVersion`, and the
`ZenBpmDeployedProcesses` record (scoped id, key, version, model as deployed) which serves the viewer
and the ownership checks.

## 4. Runtime flows

### 4.1 Starting a workflow

Phase one: nothing but resolving the aggregate id and asserting the client is configured. Phase two:
`POST /v1/process-instances {bpmnProcessId: scoped, businessKey: aggregateId, variables: shared values
+ {<idName>: aggregateId}, historyTimeToLive: history-time-to-live if configured}`. The engine runs the
instance synchronously, so the response may already say `completed`; nothing to do about it. A
repeated dispatch is caught by `awarenessOfWorkflowForRedispatch` (business-key search); the residual
duplicate window is the outbox's documented at-least-once.

### 4.2 Locating a workflow (the probes)

`awarenessOfWorkflow(scope, persistence, id)`:

1. for each plain process id of the scope: `GET /v1/process-instances?businessKey=<id>&bpmnProcessId=<scoped>&size=1`;
   an item -> `active|failed` = ACTIVE, `completed|terminated` = COMPLETED;
2. if nothing and `<id>` parses as a long: `GET /v1/process-instances/<id>`; found and its
   `bpmnProcessId` is one of the scope's scoped ids -> as above (this is how a BPMS-initiated
   workflow, whose aggregate id IS its instance key, is found);
3. nothing -> UNKNOWN_TO_BPMS; any I/O failure, timeout or 5xx -> BPMS_UNAVAILABLE.

`awarenessOfTask` / `awarenessOfUserTask`: `GET /v1/jobs/{key}`; 404 -> UNKNOWN; then the job's
instance through the cache; instance not in scope -> UNKNOWN; job `active` -> ACTIVE, `completed` or
`terminated` -> COMPLETED; failure -> BPMS_UNAVAILABLE. `workflowVisibilityDelay()` = the configured
`workflow-visibility-timeout` (default `PT2S`, poll `PT0.25S`).

### 4.3 Delivering a task

```
JobStream ──WaitingJob──▶ JobDispatcher ──(slot free?)──▶ ZenBpmExecutor ──▶ ZenBpmJobHandler
                                          │ no slot: drop it, the lock lapses in 30 s
ZenBpmJobHandler:
  facts   = instanceCache.get(job.instance_key)              (GET /process-instances/{key} once)
  context = TaskInvocationContext{ taskDefinition = plain(job.type), aggregateId = facts.businessKey
            ?? facts.variables[idName], taskId = job.key, deliveryId = job.key, activationId = job.key,
            processVersion = facts.version, adapterId, taskParameters = job.input_variables,
            multiInstances = from parent chain, predatesDeployedVersion = facts.version < deployed }
  outcome = workflowTaskInvoker.invokeWorkflowTask(module, plainProcess, context)   (own transaction)
  COMPLETED           → PATCH /process-instances/{facts.key}/variables {shared values + id var}
                        POST /jobs/{key}/complete {same values}
  BPMN_ERROR          → PATCH variables; POST /jobs/{key}/fail {errorCode: scoped code}
  COMPLETION_PENDING  → nothing (the job stays; every 30 s redelivery is answered from the record)
  exception           → nothing sent; local backoff per job key (retry-backoff, x2, max 5 min);
                        after max-redeliveries: POST /jobs/{key}/fail {} → incident naming the aggregate
```

The `PATCH` before `complete` is what makes a gateway behind the task see the values (job outputs
propagate only through output mappings, section 4 of the capability analysis). Both calls are
idempotent: a repeated PATCH writes the same values, a repeated complete is answered 201.

Every handler runs `load -> invoke -> save -> commit -> report`, at least once. `deliversTasksAtLeastOnce()`
is `true`, and the delivery id is the job key. `Concurrent deliveries` happen when a handler outlives
the 30-second lock: documented, warned by the core, and the reason `worker-threads` is sized against the
connection pool AND the lock.

### 4.4 Messages

`CORRELATE_MESSAGE` phase one: the message is declared by a deployed model of the module and is not a
start message (else: guiding failure where the application called). Phase two: `POST /v1/messages
{messageName: scoped, correlationKey: correlationId ?? aggregateId, variables: shared values + id var}`.
404 -> `PhaseTwoRetryLater(workflow-visibility-timeout)`: the outbox is the buffer the engine does
not have, bounded by `vanillabp.outbox.block-after-attempts`. `START_WORKFLOW_BY_MESSAGE` publishes
without a key and with the id variable; the instance has no business key (open question 1).

### 4.5 Aggregate push

`AGGREGATE_CHANGED` without a task: locate the workflow (probe), `PATCH .../{key}/variables`. With a
task: `GET /v1/jobs/{taskId}` -> its `processInstanceKey` IS the scope the task runs in (a sub-process
or multi-instance body is a child instance) -> `PATCH` that instance. No idempotency key, as the core
defines it.

### 4.6 User tasks

A ZenBPM user task is a job of type `zenbpm:taskDefinition type` (default `user-task-type`), which is
the VanillaBP task definition. Notifications come from `ZenBpmUserTaskPoller` (one per adapter id,
timing thread): every `user-task-poll-interval` it lists `GET /v1/jobs?jobType=<scoped>&state=active`
and `state=terminated` per user-task type of the deployed modules, keeps a bounded seen-set per node,
and invokes the core with `TaskEvent CREATED` resp. `CANCELED`, delivery id = `job.key + ":created"`
resp. `":canceled"`. The delivery record makes a restarted node harmless. `COMPLETE_USER_TASK` /
`CANCEL_USER_TASK` are the job commands. The stream is deliberately NOT subscribed to user-task types:
an open user task would be re-sent every 30 s and occupy the client's ten slots.

### 4.7 Workflow ended and BPMS-initiated start

Both arrive as ordinary jobs of the marker tasks (section 3, step 4), served by two dedicated handlers:
`ZenBpmWorkflowEndedHandler` (kind COMPLETED, `endEventId` from the marker's id, then complete the job
so the instance ends) and `ZenBpmBpmsInitiatedStartHandler` (kind TIMER, `naturalIdentity` = instance
key, `startInstant` = job `created_at`, variables = job input variables which the marker's input
mappings copy from the start event's outputs; complete the job with the id variable plus the shared
values of the built aggregate). A cancelled instance runs no marker: kind TERMINATED is never reported.

### 4.8 Shutdown

`stopWorkflowProcessing(module)`: unsubscribe the module's job types, stop the module's pollers, wait
`shutdown-grace` (default `PT20S`) for handlers still inside the application, never `fail` a job while
shutting down (its lock lapses in 30 s and the delivery record answers the redelivery). The client
factory closes the stream and the HTTP client last, for every module which never reached the stop.

## 5. Threading

- Handler slots: `worker-threads` (default 4) or `virtual` with `worker-threads-bound`; sized against
  the database connection pool and against the 30-second lock (a queue in front of the slots would
  spend the lock waiting, so at most `worker-threads` jobs are accepted from the stream and the rest is
  left to lapse and be redelivered elsewhere).
- Timing threads: two per adapter id, for the stream's reconnect and heartbeat, the user-task poller,
  the local retry backoff and the engine wait. No handler runs on them.
- The core may call probes from any thread and from the outbox dispatcher concurrently; the REST
  client is thread-safe (`HttpClient` is), the caches are concurrent.

## 6. Configuration (`vanillabp.adapters.<id>.*`)

| Key | Level | Default | Meaning |
|---|---|---|---|
| `type` | adapter | (the id) | `zenbpm` |
| `rest-address` | adapter | required | `http://host:8080`; `/v1` is appended by the adapter |
| `grpc-address` | adapter | host of `rest-address`, port 9090 | `host:port` of the job stream |
| `grpc-plaintext` | adapter | `true` | `false` for TLS through a proxy |
| `client-id` | adapter | `<application name>-<id>-<random>` | gRPC `client_id`; must be unique per node |
| `request-timeout` | adapter | `PT10S` | every REST call |
| `startup-wait` | adapter | `PT10M` | how long the first deployment waits for `/system/health/ready` |
| `worker-threads` / `worker-threads-bound` | adapter | `4` / same | handler slots, `virtual` allowed |
| `retry-backoff` | adapter, module, workflow, task | `PT10S` | first local backoff after a failed handler; doubles up to `PT5M` |
| `max-redeliveries` | adapter, module, workflow, task | `10` | after which a failing job is failed into an incident |
| `shutdown-grace` | adapter | `PT20S` | |
| `workflow-visibility-timeout` | adapter | `PT2S` | `workflowVisibilityDelay()` and the retry-later window of a message |
| `user-task-poll-interval` | adapter | `PT5S` | `PT0S` switches user-task notifications off with a WARN |
| `history-time-to-live` | adapter, module, workflow | none (engine default) | sent with every start |
| `instance-cache-max-entries` | adapter | `10000` | |
| `name-clash-avoidance` | adapter, module, workflow | `use-prefix` (adapter default) | `by-adapter` is refused, `none` warns |
| `accept-unscoped-identifiers` | adapter, module | `false` | silences the `none` warning |
| `shared-engine` | adapter | `false` | says that this id deliberately shares its engine with another id (a prefix migration); without it two ids on one `rest-address` end the boot |
| `message-start-lookup` | adapter | `refuse` | `scan` enables the bounded client-side lookup of message-started workflows (GAPS 1) |
| `message-start-lookup-max-instances` | adapter | `1000` | the bound of that scan |
| `async-task-max-age-action` | adapter | `report` | `incident` fails an overdue asynchronous task's job so the engine raises an incident |
| `deployment-failure` | adapter | `fail` | core key |

Scoped keys resolve through the four levels of the configuration model, most specific first, with
the same resolver shape on both platforms (`PeaFetchVariables` pattern). Every key is modelled in
the Quarkus overlay, or the startup fails on it.

Startup validation, per configured id of type `zenbpm`: no `rest-address` -> WARN naming
`vanillabp.adapters.<id>.rest-address`, the application boots, the adapter refuses work with the same
message; a malformed address or a `grpc-address` without a port -> the boot ends naming the key, unless
the id is nowhere first and `deployment-failure: warn`; complete -> the client is built without
contacting the engine, and one INFO line names address, gRPC address, client id and the engine version
once the wait answered.

## 7. Messages and observability

- Every message names module, process, aggregate id, the attempted operation and the way out, keys in
  full (`vanillabp.adapters.<id>.<key>`), following the config-validation skill.
- Meters where Micrometer is present (`ZenBpmMetrics`, optional on both platforms):
  `vanillabp.zenbpm.jobs.received`, `.completed`, `.failed`, `.dropped` (no slot),
  `.redelivered` (a delivery id seen before), `vanillabp.zenbpm.stream.reconnects`,
  `vanillabp.zenbpm.slots.busy` / `.free`, `vanillabp.zenbpm.usertasks.polls`.
- Health contributes the engine's readiness per adapter id.

## 8. Test infrastructure

- `EngineUnderTest` (Spring ITs) and `EngineImage` (Quarkus) start
  `ghcr.io/pbinitiative/zenbpm:${zenbpm.version}` with `REST_API_ADDR=:8080`, `GRPC_API_ADDR=:9090`,
  `CLUSTER_RAFT_BOOTSTRAP_EXPECT=1`, `POLL_TIMER_DELAY_SECONDS=1`, waiting for
  `GET /system/health/ready` = 200; ports from `FreePortUtil` where fixed ports are needed.
- One container per IT class with a real engine, `@DirtiesContext`, own workflow module id, own
  resources location (`vanillabp-testing` skill).
- `SuppressOutputExtension` first on every class, `logback-test.xml` quieting Testcontainers.
- Quarkus: `QuarkusExtensionTest` for wiring and validation without Docker;
  `QuarkusProdModeTest` with `testCoverageJavaAgent(quarkusProdModeTestDefaults())` and introspection
  endpoints for the E2E flow.
- Coverage per platform, gate 85, rule 90; the Quarkus number is expected to be lower and is the
  work order for the Quarkus twins of every feature.

## 9. Class map (core)

```
org.pbinitiative.zenbpmadapter
  ZenBpmAdapter                        ADAPTER_TYPE
  ZenBpmProcessingContext              PC: module id, models per file, DMN bytes, deployed keys, opened job types
  client/                              section 2
  model/ZenBpmBpmnModel                BPMN
  model/ZenBpmModelReader              DOM parse hardened, local-name reads, both namespaces
  model/ZenBpmScoping                  use-prefix rewrite (from PeaScoping)
  model/ZenBpmCorrelationKeys          subscription injection, start/catch collision check
  model/ZenBpmTaskParameters           input-mapping injection from taskParameterNames
  model/ZenBpmMarkerTasks              ended/started marker insertion incl. DI shapes
  model/ZenBpmUnsupportedConstructs    refusals before deployment
  model/ZenBpmFeelIdentifiers          identifiers read by expressions, for the unshared-path check
  deployment/ZenBpmDeploymentService   AdapterDeploymentService<ZenBpmBpmnModel, ZenBpmProcessingContext>
  deployment/ZenBpmDeployedProcesses   what this boot deployed per adapter id
  deployment/ZenBpmProcessVersions     CachingProcessVersionCatalog over GET /process-definitions
  deployment/ZenBpmDeclaredProcessWorkers  job types composed for renamed processes
  processservice/ZenBpmProcessService  MigratableProcessService<A>: handler map, probes, viewer
  processservice/ZenBpmWorkflowViewer  definitions, XML, history
  processservice/ZenBpmSharedValues    shared values + id variable for every command
  wiring/JobDispatcher                 stream -> slots, drop when full
  wiring/ZenBpmJobHandler              section 4.3
  wiring/ZenBpmLocalRetry              per-job backoff and max-redeliveries
  wiring/ZenBpmUserTaskPoller          section 4.6
  wiring/ZenBpmWorkflowEndedHandler    marker job -> WorkflowEndedInvoker
  wiring/ZenBpmBpmsInitiatedStartHandler marker job -> BpmsInitiatedStartInvoker
  wiring/ZenBpmMultiInstance           iteration facts from the instance cache and child lists
  wiring/ZenBpmDrain                   handlers in flight per module
  health/ZenBpmHealth
  observability/ZenBpmMetrics, MicrometerZenBpmMetrics
```

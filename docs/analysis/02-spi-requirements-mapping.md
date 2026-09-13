# The adapter SPI held against ZenBPM

Every promise the migration adapter asks of an adapter (`ADAPTER-AUTHORS.md`, `MigratableProcessService`,
`AdapterDeploymentService`, the wiring callbacks and the inbound contexts) is listed once, with what
ZenBPM offers for it, the verdict and the design the adapter takes. The verdicts feed the roadmap: a
`SUPPORTED` row is an ordinary story, a `PARTIAL` row has a fallback and an engine-enablement twin, a
`MISSING` row is a documented deviation.

## A. Registration and configuration

| Requirement | ZenBPM | Verdict | Design |
|---|---|---|---|
| One `MigratableProcessService` and one `AdapterDeploymentService` per configured adapter id | a plain REST + gRPC address per engine | SUPPORTED | one `ZenBpmClient` (REST + one job stream) per adapter id, built eagerly at startup |
| `validateDistinctAdapterInstances` | no cluster identity in the API; `/system/status` reports node ids | SUPPORTED | identity = normalised `rest-address`; two ids with the same address end the boot naming the key |
| Configuration under `vanillabp.adapters.<id>.*`, validated at startup, unconfigured still boots | - | SUPPORTED | overlay classes on both platforms; `rest-address` required, `grpc-address` derived from it by default |
| Version guard descriptor | - | SUPPORTED | `META-INF/vanillabp/adapter-zenbpm.properties` with the engine version the tests ran against |
| `checkHealth()` | `GET /system/health/ready` | SUPPORTED | READY -> UP, 503 -> DOWN with reasons, unconfigured -> UNKNOWN |
| Authentication of the adapter against the engine | none exists | MISSING | no `auth.*` keys in the first release; the wiki says a proxy has to do it (engine-enablement E13.9) |

## B. Deployment pipeline

| Requirement | ZenBPM | Verdict | Design |
|---|---|---|---|
| `readBpmn` -> one entry per executable process | one process per file; parsing by local name | SUPPORTED | DOM parse (borrowed from PEA), refuse a file with more than one executable process with a guiding message |
| `prepareBpmn` rewrites the model once per FILE | XML in, XML out | SUPPORTED | context remembers rewritten filenames |
| Scoping (`use-prefix`) of process ids, messages, errors, task types, decision ids, `calledElement` | identifiers are plain strings | SUPPORTED | `ZenBpmScoping` = PEA's DOM rewrite plus `zenbpm:calledDecision` and `zenbpm:calledElement@processId` |
| `by-adapter` scoping | no tenant | MISSING | refused via `validateNativeIsolationSupported`; adapter default is `use-prefix` (draft decision 2) |
| `deployResources` one deployment per module | one file per request; `200` = unchanged | SUPPORTED | one POST per BPMN file, one per DMN file, all before `startWorkflowProcessing`; a partial failure ends the boot naming the file |
| `registerDeployedVersion` also when nothing changed | `200` returns the existing key; `GET .../{key}` returns `version` | SUPPORTED | one GET per file after deployment |
| Wiring validation (`validateTaskWiring`) for service, send, business-rule (job), receive? | `zenbpm:taskDefinition type`; a business rule task with `calledDecision` is engine-served | SUPPORTED | spec per job-producing task; user tasks as optional specs |
| `workflowTaskCompletesAsynchronously` refusal | every job stays open until completed | SUPPORTED | nothing to refuse |
| `reportConcurrentTokenElements` | model readable | SUPPORTED | parallel/inclusive forks, parallel multi-instance, non-interrupting boundary and event sub-processes |
| `unsharedWorkflowAggregatePaths` | FEEL expressions on flows, timers, collections, assignees | SUPPORTED | extract identifiers with a conservative FEEL name scanner |
| DMN deployment (`readDmn`) with scoped decision ids | `POST /v1/dmn-resource-definitions`; `zenbpm:calledDecision decisionId` | SUPPORTED | `DmnDecisionIds` from the core for the file, own rewrite for the task |
| `startWorkflowProcessing` / `stopWorkflowProcessing` | one `JobStream` per client id, subscribe/unsubscribe per job type | SUPPORTED | one stream per adapter id shared by all modules; per module a set of job types |

## C. Outbound operations (`phaseOperations()`)

| Operation | Phase one (asks) | Phase two (acts) | Verdict |
|---|---|---|---|
| `START_WORKFLOW` | nothing (a start has nothing to check) | `POST /v1/process-instances {bpmnProcessId (scoped), businessKey = aggregate id, variables = shared values + id variable, historyTimeToLive}` | SUPPORTED; idempotency only through the redispatch probe (business-key search) |
| `START_WORKFLOW_BY_MESSAGE` | model check: the message is a start event of a deployed model of the module | `POST /v1/messages` without key, variables incl. id variable | PARTIAL: the instance gets no business key, so it is not findable by the probe (engine-gated, E13.4). Fallback: client-side scan of active instances of the process by the id variable, bounded and warned |
| `COMPLETE_TASK` / `CANCEL_TASK` | pre-commit `GET /v1/jobs/{key}` = active, scope check through the instance | `PATCH variables` then `POST .../complete`, resp. `POST .../fail {errorCode}` | SUPPORTED; already completed -> 201 = success; 404 = gone = success with WARN |
| `COMPLETE_USER_TASK` / `CANCEL_USER_TASK` | same, the user task IS a job | same | SUPPORTED (ZenBPM can cancel a user task by BPMN error, unlike Camunda 8) |
| `CORRELATE_MESSAGE` | model check like Camunda 8 (declared message, and NOT also a start message - see gap) | `POST /v1/messages {name scoped, correlationKey = correlationId ?? aggregate id, variables}`; 404 -> `PhaseTwoRetryLater` | SUPPORTED with a deviation: no buffering, the outbox is the buffer |
| `SEND_SIGNAL` | - | - | MISSING: not in the map (`PhaseOperationNotSupported`) |
| `AGGREGATE_CHANGED` | probe | global: `PATCH /v1/process-instances/{key}/variables`; task-scoped: the same on the job's own `processInstanceKey` (sub-process and multi-instance bodies ARE child instances) | SUPPORTED |
| `isPhaseTwoFailureRepeatable` | - | permanent: HTTP 400, 413, 415, `NumberFormatException` on a key; repeated: 404 (retry-later), 409, 500, 502, timeouts | SUPPORTED |

## D. Probes (the election)

| Probe | ZenBPM | Verdict | Design |
|---|---|---|---|
| `awarenessOfWorkflow` | `GET /v1/process-instances?businessKey=<id>&bpmnProcessId=<scoped>` (+ `GET /v1/process-instances/{key}` where the id is a snowflake key) | SUPPORTED | ACTIVE for `active\|failed`, COMPLETED for `completed\|terminated`, UNKNOWN for an empty page, BPMS_UNAVAILABLE for I/O and 5xx; scope = the processes of the `WorkflowScope`, scoped |
| `awarenessOfWorkflowForRedispatch` | same query | SUPPORTED | honest query, so the default delegation holds; a failed query is BPMS_UNAVAILABLE, never ACTIVE |
| `awarenessOfTask` / `awarenessOfUserTask` | `GET /v1/jobs/{key}` then the instance for the scope | SUPPORTED | non-advancing read; a job of another scope is UNKNOWN |
| `canLocateWorkflows` | true | SUPPORTED | - |
| `workflowVisibilityDelay` | leader reads its own writes; followers lag by replication | PARTIAL | `workflow-visibility-timeout`, default `PT2S`, polled every 250 ms |
| `deliversTasksAtLeastOnce` | redelivery after 30 s | SUPPORTED | `true`; delivery id = job key |
| `openTaskCount` | `GET /v1/jobs?...` has `totalCount`, no filter by process id | PARTIAL | answered through `GET /v1/process-definitions/{key}/statistics` per known definition key, `null` where the module deployed nothing |

## E. Inbound: task delivery

| Requirement | ZenBPM | Verdict | Design |
|---|---|---|---|
| `getTaskDefinition` | `WaitingJob.type` | SUPPORTED | unscoped through `plainTaskDefinition` |
| `getWorkflowAggregateId` | not on the job | PARTIAL | `GET /v1/process-instances/{instance_key}` once per instance key, cached (`ZenBpmInstanceCache`); business key first, id variable second |
| `getTaskId` | `WaitingJob.key` | SUPPORTED | |
| `getDeliveryId` / `getActivationId` | job key / `element_instance_key` (REST only, not on the stream) | SUPPORTED / PARTIAL | delivery = job key; activation = job key until the stream carries the element instance key (E13.6) |
| `getProcessVersion`, `predatesDeployedVersion` | instance carries `version` | SUPPORTED | from the instance cache |
| `getTaskParameter` | job input variables exist only with input mappings | PARTIAL | the adapter injects `zenbpm:ioMapping input` per `@TaskParam` name reported by `taskParameterNames`, so the job carries exactly what is read |
| `getMultiInstances` | iteration = child instance; input element as variable; index from history; no total | PARTIAL | element from the child instance's variables; index and total from `GET .../child-processes` of the parent, cached per parent (engine-gated E13.7 for a cheaper answer) |
| Complete with shared values | variables reach the scope only via output mappings | SUPPORTED with a twist | `PATCH variables` on the job's instance, then `complete` with the same values; both idempotent |
| BPMN error | `fail {errorCode}` | SUPPORTED | unmatched code = incident, documented |
| Technical failure | no retries | PARTIAL | do NOT `fail`; leave the job to the 30-second redelivery, back off locally per job key (`retry-backoff`, exponential, bounded); after `max-redeliveries` fail the job so an incident names it (engine-gated E13.3) |
| Handler longer than the lock | second delivery runs concurrently | PARTIAL | documented; the core warns on concurrent deliveries; `worker-threads` sizing note; engine-gated E13.1 |
| Asynchronous task (`@TaskId`) staying open | redelivered every 30 s for as long as it is open, occupying the client's slots | PARTIAL | answered from the delivery record (COMPLETION_PENDING) at once; `async-task-` messages; engine-gated E13.1 |
| `stopWorkflowProcessing` drains | stream close is immediate; the lock lapses after 30 s | SUPPORTED | unsubscribe, wait `shutdown-grace` for handlers, never `fail` while shutting down |

## F. Inbound: notifications

| Requirement | ZenBPM | Verdict | Design |
|---|---|---|---|
| `@TaskEvent CREATED` for user tasks | a job of the user task's type; a stream subscription would re-send it every 30 s and count against the 10-slot cap | PARTIAL | poll `GET /v1/jobs?jobType=&state=active` at `user-task-poll-interval` (default `PT5S`), seen-set per node, delivery record as the net; engine-gated E13.5 |
| `@TaskEvent CANCELED` for user tasks | poll `state=terminated` | PARTIAL | same poller, `terminated` jobs |
| `@TaskEvent CANCELED` for service tasks | nobody is told | MISSING | deviation, like Camunda 8 |
| `@WorkflowEnded` | no listener, no event stream | MISSING natively | model rewrite: a marker service task (`vanillabp__ended`) is inserted before every end event of the top-level process where a handler exists; kind COMPLETED only, end event id known, terminated instances report nothing; engine-gated E13.6 |
| BPMS-initiated start (timer) | no listener | MISSING natively | model rewrite: a marker service task after every timer start event; natural identity = instance key, so the aggregate id IS the instance key and the probe finds it by key; variables of the start reach the core through injected input mappings |
| BPMS-initiated start (signal, conditional) | do not exist | n/a | rejected by the engine at deployment; the adapter says so before deploying |
| Instantiating receive task | exists | MISSING | refused at deployment: an instance without an aggregate |

## G. Versions and the viewer

| Requirement | ZenBPM | Verdict | Design |
|---|---|---|---|
| `registerProcessVersions` (tags) | `GET /v1/process-definitions?bpmnProcessId=` returns every version with `versionTag` | SUPPORTED | `CachingProcessVersionCatalog` subclass |
| `tasksOfVersion` | `GET .../{key}` returns `bpmnData` | SUPPORTED | same extraction as `wireBpmn` |
| `activeInstanceCountOf` | `GET /v1/process-definitions/statistics` or instance search `totalCount` | SUPPORTED | one request per version, never a page transfer |
| `processVersionCatalogOf` (renamed process) | search by the scoped old id | SUPPORTED | |
| `taskWiringOfProcessesNobodyDeployed` | job types are plain strings; under `use-prefix` they carry the process id | SUPPORTED | subscribe the composed types (Camunda 8 decision 19 shape) |
| `whatOlderVersionsMiss` | the adapter writes into the model | SUPPORTED | names the injected markers and mappings older versions lack |
| `getProcessDefinitions` / `getBpmnXml` / `getWorkflowHistory` | deployment memory + `GET /v1/process-definitions/{key}` + `GET .../history` + `.../child-processes` | SUPPORTED | native definition id = `processDefinitionKey`; history context = child instance key |

## H. Cross-cutting

| Requirement | ZenBPM | Verdict | Design |
|---|---|---|---|
| Scoped identifiers on the way back | job type, `bpmnProcessId` of the instance | SUPPORTED | every inbound path unscopes |
| Messages naming module, process, aggregate and the fix | - | SUPPORTED | convention of every message, reviewed |
| Both platforms | REST via `java.net.http`, gRPC via `grpc-java` | SUPPORTED | Quarkus native needs reflection configuration for the gRPC stubs (E11) |
| Release lines | engine has no stability promise, client is the adapter's own | not needed initially | one pinned engine version per adapter release, documented range; a line scheme only if a minor breaks the surface the adapter uses |

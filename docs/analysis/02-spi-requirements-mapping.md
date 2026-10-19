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
| `validateDistinctAdapterInstances` | no cluster identity in the API; `/system/status` reports node ids | SUPPORTED | identity = normalised `rest-address` and the `name-clash-avoidance` of the adapter id, as on Camunda 8; two ids with the same of both end the boot naming the keys |
| Configuration under `vanillabp.adapters.<id>.*`, validated at startup, unconfigured still boots | - | SUPPORTED | overlay classes on both platforms; `rest-address` required, `grpc-address` derived from it by default |
| Version guard descriptor | - | SUPPORTED | `META-INF/vanillabp/adapter-zenbpm.properties` with `part.version`, `part.artifact` and `platform.version`, which the platform's `VanillaBpParts` reads, plus the engine commit the tests ran against |
| `checkHealth()` | `GET /system/health/ready` | SUPPORTED | READY -> UP, 503 -> DOWN with reasons, unconfigured -> UNKNOWN |
| Authentication of the adapter against the engine | none exists | MISSING | no `auth.*` keys in the first release; the wiki says a proxy has to do it (engine-enablement E13.9) |
| `StartupReport` (what a start found, platform decisions 96, 97) | - | SUPPORTED | the bean is injected on both platforms; configuration findings go through it with `StartupTopic.CONFIGURATION` and the adapter id as scope, not into the log |

## B. Deployment pipeline

| Requirement | ZenBPM | Verdict | Design |
|---|---|---|---|
| `readBpmn` -> one entry per executable process | one process per file; parsing by local name | SUPPORTED | DOM parse (borrowed from PEA), refuse a file with more than one executable process with a guiding message |
| `prepareBpmn` rewrites the model once per FILE | XML in, XML out | SUPPORTED | context remembers rewritten filenames |
| Scoping (`use-prefix`) of process ids, messages, errors, task types, decision ids, `calledElement` | identifiers are plain strings | SUPPORTED | `ZenBpmScoping` = PEA's DOM rewrite plus `zenbpm:calledDecision` and `zenbpm:calledElement@processId` |
| `by-adapter` scoping | no tenant | MISSING | stays the default, as the contract asks of a BPMS without isolation, and is refused while deploying via `validateNativeIsolationSupported`, whose message names `use-prefix` and `none`; every application configures one of them (decision 24) |
| `deployResources` one deployment per module | one file per request; `200` = unchanged | SUPPORTED | one POST per BPMN file, one per DMN file, all before `startWorkflowProcessing`; a partial failure ends the boot naming the file |
| `registerDeployedVersion` also when nothing changed | `200` returns the existing key; `GET .../{key}` returns `version` | SUPPORTED | one GET per file after deployment |
| Wiring validation (`validateTaskWiring`, four arguments) for service, send, business-rule (job), receive? | `zenbpm:taskDefinition type`; a business rule task with `calledDecision` is engine-served | SUPPORTED | for claimed processes only (`isClaimedByAWorkflowService`, platform decision 120); spec per job-producing task with its `name`; user tasks as optional specs |
| `reportStartMessages`, `validateBpmsInitiatedStarts` | message and timer start events are in the model | SUPPORTED | the plain start-message names once per claimed process (no call where a name is an expression); EVERY start event, of called processes too, to `validateBpmsInitiatedStarts` |
| `reportModelExpressions` | FEEL expressions on flows, timers, multi-instance elements, correlation keys, mappings, decision inputs | SUPPORTED | every `=` expression with its `ExpressionPlace`, read before the adapter's own rewrites |
| `multiInstanceElementNames`, `reportCompensation` | `inputElement` on multi-instance elements; no compensation | SUPPORTED | asked for every multi-instance element; compensation is refused, so nothing to report |
| `workflowTaskCompletesAsynchronously` refusal | every job stays open until completed | SUPPORTED | nothing to refuse |
| `reportConcurrentTokenElements` | model readable | SUPPORTED | parallel/inclusive forks, parallel multi-instance, non-interrupting boundary and event sub-processes |
| `unsharedWorkflowAggregatePaths`, `declaredTypesOfWorkflowAggregatePaths` | FEEL expressions on flows, timers, collections, assignees | NOT USED | not called, as in the Camunda 8 adapter; an expression reading an attribute the aggregate does not share fails at runtime (deviation 14) |
| Identifier reports (`reportIdentifiersTheBpmsAlreadyHolds`, `reportIdentifiersTheModelsDeclare`, `identifiersOfVersion`) | `GET /v1/process-definitions?bpmnProcessId=`; no resource name or owner on a definition; the DMN list has no decision-id filter | PARTIAL | after the deploy; what counts as "held elsewhere" is settled in S4.3.2; a failed lookup never fails the deployment (debug only) |
| `whatThisBpmsDoesWith(Class, ValueDirection)` | numbers are float64 in the engine | SUPPORTED | `ZenBpmValueTypes`: Strings, Booleans, enums, `Integer`, `Short` and `Byte` survive; `Long`, `BigInteger` and `BigDecimal` are `changed` (S2.2.1: `2^53+1` comes back as `2^53`); `Double` and `Float` are decided against S2.2.1's `1000.0 -> 1000`; the rest `cannotSay` |
| DMN deployment (`readDmn`) with scoped decision ids | `POST /v1/dmn-resource-definitions`; `zenbpm:calledDecision decisionId` | SUPPORTED | `DmnDecisionIds` from the core for the file, own rewrite for the task |
| `startWorkflowProcessing` / `stopWorkflowProcessing` | one `JobStream` per client id, subscribe/unsubscribe per job type | SUPPORTED | one stream per adapter id shared by all modules; per module a set of job types |

## C. Outbound operations (`phaseOperations()`)

| Operation | Phase one (asks) | Phase two (acts) | Verdict |
|---|---|---|---|
| `START_WORKFLOW` | nothing (a start has nothing to check) | `POST /v1/process-instances {bpmnProcessId (scoped), businessKey = aggregate id, variables = shared values + id variable, historyTimeToLive}`; `reportStartedWorkflow(key, version)` | SUPPORTED; idempotency only through the redispatch probe (business-key search) |
| `START_WORKFLOW_BY_MESSAGE` | the core refuses a message which starts no process of the service (`reportStartMessages`); the adapter refuses every start under `message-start-lookup: refuse`, and a name the newest version of no process of the module starts with, read again before a refusal (decision 42) | `POST /v1/messages` without key, variables = shared values + id variable, one at a time per name on each node; the engine answers no instance key, so nothing is reported here; 404 -> an ordinary, counted failure (decision 47) | PARTIAL (S7.1.2, 2026-10-19): the instance gets no business key (engine-gated, E13.4). The start marker reports the instance key, which the core writes into the start row, and the probe finds the workflow through it (decision 25, `message-start-lookup: start-row`, the default). `scan` stays an opt-in for what the start row cannot cover, bounded by `message-start-lookup-max-instances` (GAPS 1); starts of one name on several nodes at the same moment may be lost (GAPS 30) |
| `COMPLETE_TASK` / `CANCEL_TASK` | pre-commit `GET /v1/jobs/{key}` = active, asked again within `workflow-visibility-timeout` where the node does not know it yet; the process of the job's instance must be one the module serves (the dispatcher's rule); a completed, terminated or unknown task throws `TaskNotFoundException`, a task of another module and one with an incident an `IllegalStateException` | `PATCH variables` into the job's instance and every instance up to the root (decision 35), then `POST .../complete`, resp. `POST .../fail {errorCode scoped, variables, clientId}` without a delivery token; this node's parked count of the job ends (decision 40) | SUPPORTED; a job which ended, a `404`/`409` of the command and a `409` of the `PATCH` are a WARN and the entry is done; a job or instance the node does not know yet and a `404` of the `PATCH` are repeated |
| `COMPLETE_USER_TASK` / `CANCEL_USER_TASK` | same, the user task IS a job | same | SUPPORTED (ZenBPM can cancel a user task by BPMN error, unlike Camunda 8) |
| `CORRELATE_MESSAGE` | model check like Camunda 8, never asking the engine whether something waits: a version the engine holds of the module's deployed processes waits for the message and the newest version does not start with it (decision 42); a start message fails naming `startWorkflowByMessage`; the versions are read again before a refusal; silent where not every model is known | `POST /v1/messages {name scoped, correlationKey = correlationId ?? aggregate id, variables = shared values + id variable}`; 404 -> `PhaseTwoRetryLater(workflow-visibility-timeout)` | SUPPORTED with a deviation (S7.1.1, 2026-10-18): no buffering, the outbox is the buffer, bounded by `vanillabp.outbox.wait-for-visibility-at-most` (GAPS 5); the engine deduplicates nothing, the outbox only while an entry with the same correlation id waits; a `404` after a publication which correlated is repeated as well (GAPS 29, decision 43) |
| `SEND_SIGNAL` | - | - | MISSING: not in the map (`PhaseOperationNotSupported`) |
| `AGGREGATE_CHANGED` | probe | global: the instance of `PhaseTwoRequest#workflowId()` first, else the business-key search (and the scan under `message-start-lookup: scan`), then `PATCH /v1/process-instances/{key}/variables` of the root and of every running instance below it, a called instance of a process the wiring puts on another aggregate left out (decision 49); task-scoped: the same on the job's own `processInstanceKey` alone (sub-process and multi-instance bodies ARE child instances) | SUPPORTED (built by S7.2.1) |
| `isPhaseTwoFailureRepeatable` | - | permanent: HTTP 400, 413, 415, `NumberFormatException` on a key; repeated: 404 (retry-later), 409, 500, 502, timeouts | SUPPORTED |

## D. Probes (the election)

| Probe | ZenBPM | Verdict | Design |
|---|---|---|---|
| `awarenessOfWorkflow` (four arguments) | `GET /v1/process-instances/{workflowId}` where the core hands one; `GET /v1/process-instances?businessKey=<id>&bpmnProcessId=<scoped>` | SUPPORTED | the key answers ACTIVE only; any other answer, 404 and 502 fall through to the business-key search (decision 25). ACTIVE for `active\|failed`, COMPLETED for `completed\|terminated`, UNKNOWN for an empty page, BPMS_UNAVAILABLE for I/O and 5xx; scope = the processes of the `WorkflowScope`, scoped |
| `awarenessOfWorkflowForRedispatch` (with `plannedAt`) | business-key search with `createdFrom` (inclusive) | SUPPORTED | the four-argument form is implemented: instances created at or after `plannedAt`, no state filter; a failed query is BPMS_UNAVAILABLE, never ACTIVE |
| `awarenessOfTask` / `awarenessOfUserTask` | `GET /v1/jobs/{key}` then the instance for the scope | SUPPORTED | non-advancing read; a job of another scope is UNKNOWN |
| `canLocateWorkflows` | true | SUPPORTED | a workflow without a business key is located only while the core knows its instance key (GAPS 1) |
| `workflowVisibilityDelay`, `workflowVisibilityDelay(workflowId)` | leader reads its own writes; followers lag by replication | PARTIAL | `workflow-visibility-timeout`, default `PT2S`, polled every 250 ms, for both forms |
| `OpenTaskProbe` (`reportTasksTheBpmsNoLongerHas`, platform decision 74) | `GET /v1/jobs/{key}` | SUPPORTED | after every delivery whose outcome reached the engine (S6.1.3): `completed`/`terminated` = GONE, `active`/`failed` = STILL_THERE, a `404` (a lagging node, GAPS 20), a job of another workflow and anything else CANNOT_SAY; only a task definition the process serves by a job is asked about, so user-task definitions are CANNOT_SAY while the poller reports their cancellation |
| `deliversTasksAtLeastOnce` | redelivery after the lock lapses, on a leader change, after a stream reconnect | SUPPORTED | `true`; delivery id = job key |
| `openTaskCount` | `GET /v1/jobs?...` has `totalCount`, no filter by process id | PARTIAL | answered through `GET /v1/process-definitions/{key}/statistics` per known definition key, `null` where the module deployed nothing |

## E. Inbound: task delivery

| Requirement | ZenBPM | Verdict | Design |
|---|---|---|---|
| `getTaskDefinition` | `WaitingJob.type` | SUPPORTED | unscoped through `plainTaskDefinition` |
| `getWorkflowAggregateId` | not on the job | PARTIAL | `GET /v1/process-instances/{instance_key}` once per instance key, cached (`ZenBpmInstanceCache`); business key first, id variable second |
| `getTaskId` | `WaitingJob.key` | SUPPORTED | |
| `getBpmnElementId` | `WaitingJob.element_id` | SUPPORTED | the core routes by it (platform decision 67) |
| `getWorkflowId` | `WaitingJob.process_instance_key`; no root key on the job | PARTIAL | the root instance key of the task's own process, walked up the parents and stopping at a call activity (decision 28); engine-gated E13.14 for the root key on the job |
| `getTaskKind`, `getBusinessKey` | the instance's business key | SUPPORTED | `TASK` for jobs, `USER_TASK` for user tasks; the business key from the instance cache (the core refuses a key which is not the aggregate id, platform decision 69) |
| `DeliveryOfAnUnknownWorkflowException` (platform decision 99) | `fail` with `retries` and `message` | SUPPORTED | the job is failed with `retries: 0` and the core's message, so an incident names it; WARN without stack trace (decision 27) |
| `getDeliveryId` / `getActivationId` | job key / `element_instance_key` (REST only, not on the stream) | SUPPORTED / PARTIAL | delivery = job key; activation = job key until the stream carries the element instance key (E13.6) |
| `getProcessVersion`, `predatesDeployedVersion` | instance carries `version` | SUPPORTED | from the instance cache |
| `getTaskParameter` | job input variables exist only with input mappings | PARTIAL | the adapter injects `zenbpm:ioMapping input` per `@TaskParam` name reported by `taskParameterNames`, so the job carries exactly what is read |
| `getMultiInstances` | iteration = child instance; input element as variable; index from history; no total | PARTIAL | element from the child instance's variables; index and total from `GET .../child-processes` of the parent, cached per parent (engine-gated E13.7 for a cheaper answer) |
| Complete with shared values | variables reach the scope only via output mappings | SUPPORTED with a twist | `PATCH variables` on the job's instance, then `complete` with the same values; both idempotent |
| BPMN error | `fail {errorCode}` | SUPPORTED | the values written into every instance up to the root first, as for a completion (decision 35), then `fail` with the scoped code, the same values, `deliveryToken` and `clientId`; an unmatched code is an incident, which a WARN names |
| Technical failure | job retries with backoff (E13.3, in the pinned engine since `bc3384b3`) | SUPPORTED | `fail` without an error code, with `message`, `retryBackoff` (`retry-backoff`), `deliveryToken` and `clientId`: the engine spends one attempt, hands the job out again after the backoff and files an incident when none is left (decision 27) |
| Handler longer than the lock | lock per subscription (`lock_duration_ms`), extension relative to now (E13.1, shipped) | SUPPORTED | subscription lock = `job-timeout` (default `PT5M`, four levels); a timing thread extends the lock of a running handler at half the job timeout; a leader change still redelivers (documented residual) |
| Asynchronous task (`@TaskId`) staying open | lock extension up to `jobManager.maxLockDurationMs` (24 h by default) | SUPPORTED | after COMPLETION_PENDING extend the lock by `async-task-lock-renewal` (default `PT1H`); the next delivery after it lapses is answered from the delivery record and renews again (Camunda 8's shape) |
| `stopWorkflowProcessing` drains | closing the stream releases the client's locks at once | SUPPORTED | unsubscribe, wait `shutdown-grace` for handlers, never `fail` while shutting down; the factory closes the stream last, which hands the open jobs back to the engine |

## F. Inbound: notifications

| Requirement | ZenBPM | Verdict | Design |
|---|---|---|---|
| `@TaskEvent CREATED` for user tasks | a job of the user task's type; a stream subscription would deliver it once per lock and cannot report its termination | PARTIAL | poll `GET /v1/jobs?jobType=&state=active` at `user-task-poll-interval` (default `PT5S`), seen-set per node, delivery record as the net; engine-gated E13.5 |
| `@TaskEvent CANCELED` for user tasks | poll `state=terminated` | PARTIAL | same poller, `terminated` jobs |
| `@TaskEvent CANCELED` for service tasks | nobody is told | PARTIAL | derived by the core: the open-task probe after every delivery (S6.1.3), and the end marker, after which the core cancels the open tasks of the workflow (platform decision 73); a task canceled while nothing else of its workflow is delivered is not reported |
| `@WorkflowEnded` | no listener, no event stream | MISSING natively | model rewrite: a marker service task (`vanillabp__ended`) is inserted before every end event of the top-level process where `workflowEndedHandlerExists`; kind COMPLETED only, end event id known, canceled instances report nothing; `workflowId` and business key reported; engine-gated E13.6 |
| BPMS-initiated start (none, message, timer) | no listener | SUPPORTED by model rewrite | built (S9.1.1, S9.3.1): a marker service task after every start event of the top-level process of a claimed process which starts a workflow of its own, written while wiring under the type `vanillabp__started__<scoped process id>` (decisions 26 and 44). The application's `@WorkflowStartedByBpms` method builds and names the aggregate; the adapter writes the name into the instance where it does not carry it (decision 46) and reports the instance key, which the core keeps in the start row. Such a workflow has no business key, so the probe finds it through that instance key (decision 25); the start of a workflow the application started itself answers `created() == false` and writes nothing. A process called on an aggregate of its own starts without the caller's name (decision 45) |
| BPMS-initiated start (signal, conditional) | do not exist | n/a | rejected by the engine at deployment; the adapter says so before deploying |
| Instantiating receive task | exists | MISSING | refused at deployment: an instance without an aggregate |

## G. Versions and the viewer

| Requirement | ZenBPM | Verdict | Design |
|---|---|---|---|
| `registerProcessVersions` (tags) | `GET /v1/process-definitions?bpmnProcessId=` returns every version with `versionTag` | SUPPORTED | `CachingProcessVersionCatalog` subclass |
| `tasksOfVersion` | `GET .../{key}` returns `bpmnData` | SUPPORTED | same extraction as `wireBpmn` |
| `activeInstanceCountOf` | `GET /v1/process-definitions/statistics` or instance search `totalCount` | SUPPORTED | one request per version, never a page transfer |
| `processVersionCatalogOf` (renamed process) | search by the scoped old id | SUPPORTED | |
| `taskWiringOfProcessesNobodyDeployed` | job types are plain strings; under `use-prefix` they carry the process id; a user task is a job of the same kind | SUPPORTED | subscribe the composed types (Camunda 8 decision 19 shape) which a model the engine holds under the id gives to a task, never a user task's (decision 41); a method wired by element id named at startup |
| `whatOlderVersionsMiss` | the adapter writes into the model | SUPPORTED | names the injected markers and mappings older versions lack |
| `getProcessDefinitions` / `getBpmnXml` / `getWorkflowHistory` | deployment memory + `GET /v1/process-definitions/{key}` + `GET .../history` + `.../child-processes` | SUPPORTED | native definition id = `processDefinitionKey`; history context = child instance key |

## H. Cross-cutting

| Requirement | ZenBPM | Verdict | Design |
|---|---|---|---|
| Scoped identifiers on the way back | job type, `bpmnProcessId` of the instance | SUPPORTED | every inbound path unscopes |
| Messages naming module, process, aggregate and the fix | - | SUPPORTED | convention of every message, reviewed |
| Both platforms | REST via `java.net.http`, gRPC via `grpc-java` | SUPPORTED | Quarkus native needs reflection configuration for the gRPC stubs (E11) |
| Release lines | engine has no stability promise, client is the adapter's own | not needed initially | one pinned engine version per adapter release, documented range; a line scheme only if a minor breaks the surface the adapter uses |

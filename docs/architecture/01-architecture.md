# Architecture of `zenbpm-vanillabp-adapter`

## 1. Repository and coordinates

- Repository `github.com/pbinitiative/zenbpm-vanillabp-adapter` (exists, default branch `main`, MIT
  licence, owned by the ZenBPM maintainers), checked out as a sibling of the other adapters in the
  workspace as a LOCAL-ONLY member like `zenbpm`: a plain clone, ignored by the superproject, with
  nothing committed to `vanillabp/development-workspace` (the superproject's own CI never sees
  either, and no pull request to it is planned); plus `pbinitiative/zenbpm-vanillabp-adapter.wiki`.
  S1.1.2 finishes the local setup.
- groupId `org.pbinitiative.zenbpm` (the group of the engine's Java client, so engine client and
  adapter are found under one group; the root package stays `org.pbinitiative.zenbpmadapter`),
  artifacts `zenbpm-vanillabp-adapter` (core), `zenbpm-vanillabp-adapter-spring-boot`,
  `zenbpm-vanillabp-adapter-quarkus`, `zenbpm-vanillabp-adapter-quarkus-deployment`,
  `zenbpm-vanillabp-adapter-engine-test-support`; version `${revision}` = `2.0.0-SNAPSHOT`, Java 21,
  Spring Boot 4.1.x and Quarkus 3.39.x as managed by `adapter-platform-integration`. The VanillaBP
  platform artifacts (`io.vanillabp:*`, still `2.0.0-SNAPSHOT`) are read from the snapshot
  repository of Maven Central, which the root POM names and which needs no login (S1.3.1).
- Licence: MIT for the repository. Code copied and adapted from `camunda8-adapter` and
  `process-engine-api-adapter` is Apache 2.0 and keeps its licence: the copied files carry their
  original header, `NOTICE` names both origins with a pointer to the Apache licence text kept as
  `LICENSE-APACHE-2.0`, and the README's licence section says so (draft decision 17).
- Conventions: the repository follows the VanillaBP adapter conventions (module split, Spotless with
  the platform's `formatting_conventions.xml` indenting by four spaces (decisions 20 and 22),
  per-platform coverage with the 85/90 gate, `test-utils`, `DECISIONS.md` as the only citation
  target, wiki user-facing and README contributor-facing), so the four VanillaBP adapters read alike
  although this one has other owners (draft decision 16). The Go conventions of `zenbpm/AGENTS.md`
  do not apply here.
- Adapter type constant `zenbpm` (`ZenBpmAdapter.ADAPTER_TYPE`), Quarkus capability
  `io.vanillabp.adapter.zenbpm`, extension name `vanillabp-zenbpm`.
- Engine pin: the newest engine `main` commit when S1.2.1 runs (it must carry E13.1, commit
  `071460cc`, which the task delivery requires; decision 15, open question 9), written as two
  properties: `zenbpm.commit` and `zenbpm.image` = the digest of the `dev` image built from that
  commit (`ghcr.io/pbinitiative/zenbpm@sha256:...`, because `dev` moves with every push). Filtered
  into `core/src/main/resources/META-INF/vanillabp/adapter-zenbpm.properties` (`part.version`,
  `part.artifact`, `platform.version` for the platform's `VanillaBpParts`, `zenbpm.engine` = the
  commit) and into the test resource
  `zenbpm-engine.properties` (`engine.image=${zenbpm.image}`). Before the first release the pin
  becomes an engine release tag (S12.4.1). The API contract the adapter is written against is the
  copy `core/src/main/zenbpm/api.yaml` and `core/src/main/proto/zenbpm.proto` taken from the pinned
  commit. The pull-request check compares the copies with the engine's files of that commit, and an
  integration test proves that the pinned image reports that commit (decision 18, which replaced a
  diff against `/v1/openapi`: the engine does not serve that document).

```
zenbpm-vanillabp-adapter/
  pom.xml                         parent: modules, BOM imports, Spotless, JaCoCo, flatten, engine pin
  formatting_conventions.xml      copied from adapter-platform-integration, indentation 4 (decisions 20, 22)
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
                                  settings.xml with the server publish-snapshots.yaml deploys to
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
| `ZenBpmJobStream` | one bidirectional `JobStream` per adapter id and node, metadata `client_id`; subscribe/unsubscribe per job type with reference counting across modules, each subscription carrying `lock_duration_ms` (the resolved `job-timeout`) and `max_active_jobs` (the handler slots); reconnect with backoff; hands `WaitingJob`s (with `lock_until`) to the `ZenBpmJobDispatcher` |
| `ZenBpmJobLocks` | extends locks over REST `POST /v1/jobs/{key}/extend-lock` with the stream's client id: renewal of running handlers, parking of open asynchronous tasks, "come back after the backoff" of a failed handler; `409` = the lock is lost (a second delivery may be under way), `502` = repeat, `404` = gone; warns once per adapter id where the answered deadline shows the engine capped the request (`JOB_MANAGER_MAX_LOCK_DURATION_MS`) |
| `ZenBpmErrors` | the one classification: `permanentFailure(Throwable)` (400, 413, 415, 404 of `POST /v1/process-instances`, key not a number) and `isRepeatable` (everything else), `isGoneJob(Throwable)` (404 on a job, 409 on its completion or failure), `nobodyWaits(Throwable)` (404 on a message), `lockLost(Throwable)` (409 on a lock extension), `isUnavailable(Throwable)` (no answer, timeout, 502, 503, 504); read from status, method and path, never from text (decision 30, which superseded decision 10; Camunda 8 decision 16 applies verbatim) |
| `ZenBpmEngineWait` | before the first deployment round per adapter id: poll `GET /system/health/ready` until 200, `startup-wait` runs out, or a permanent answer; log the address and the last answer every few seconds |
| `ZenBpmHealth` | `checkHealth()`: READY -> UP, 503 -> DOWN with `reasons`, unreachable -> DOWN, unconfigured -> UNKNOWN |
| `ZenBpmInstanceCache` | bounded LRU `instanceKey -> InstanceFacts(key, scopedProcessId, version, definitionKey, businessKey, parentKey, processType, variables)`, the variables holding the id variable only and only once present; a 404 is not kept; `workflowIdOf(key)` walks up `parentKey` through `subprocess` and `multiInstance` children to the root or a `callActivity` instance, keeping the facts on the way, so a second walk asks nothing (decision 28); filled by `GET /v1/process-instances/{key}`; per adapter id, held by the client factory; `instance-cache-max-entries` (10 000, 0 keeps nothing) |
| `ZenBpmExecutor`, `ZenBpmExecutionModel` | the handler slots: `worker-threads` platform threads (4) or `virtual` bounded by `worker-threads-bound`; two platform threads for timing (reconnect, pollers, backoff) which no handler can occupy |
| `ZenBpmEnvironmentInfo` | logs engine version and node from `GET /system/status` once per adapter id at startup |

Transport rule: **the stream delivers, REST does everything else.** Completing and failing a job goes
over REST although the stream offers it, so that every command answers with an HTTP status the
classification can read, and so that a completion survives a stream reconnect.

## 3. Model handling (`core`, package `org.pbinitiative.zenbpmadapter.model`)

The `BPMN` type parameter is `ZenBpmBpmnModel`: a record holding the `org.w3c.dom.Document`, the
filename, the plain `bpmnProcessId`, and what was read off it (job-producing tasks as
`BpmnTaskSpec`, user tasks, message names with and without a start event, every start event with its
kind, multi-instance elements with their nesting and whether they name an `inputElement`, end
events, concurrent-token elements, the model's expressions with their place). Reading is by local
name in either extension namespace (`zeebe:` or `zenbpm:`); writing uses the `zenbpm` namespace,
declared on the root once.

`prepareBpmn` rewrites the document ONCE per file (the context remembers the filename), because the
scoping is not idempotent (a second pass would prefix twice); every boot rewrites the file as it was
read, so the same file gives the same deployment, and no rewrite overwrites what the modeller wrote
(draft decision 4). Step 4 runs in `wireBpmn`, the other steps in `prepareBpmn`:

1. **Scoping** (`ZenBpmScoping`, from `PeaScoping`): under `use-prefix` rewrite `process@id` (and
   the `participant@processRef` and `BPMNPlane@bpmnElement` pointing at it), `message@name`,
   `error@errorCode`, `escalation@escalationCode`, `taskDefinition@type` (scoped per process by
   default), `calledElement@processId`, `calledDecision@decisionId`; unchanged in `none`. A
   `decisionId` the engine would split once it is scoped (`<dmn-id>.<decision-id>`, or any decision
   of a workflow module whose id holds a `.`) is refused unless bound by `versionTag`. Before it,
   `by-adapter` is refused for the module (the platform's `validateNativeIsolationSupported`) and for
   a workflow which sets it (decision 24); `readDmn` scopes the decision ids of a DMN file the same
   way.
2. **Correlation keys** (`ZenBpmCorrelationKeys`, claimed processes only): every `bpmn:message`
   used by a catch element, boundary event, receive task or event sub-process start without a
   `subscription` gets `zenbpm:subscription correlationKey="=<aggregate-id variable>"`, and every
   multi-instance sub-process around such an element an input mapping of that variable, because the
   engine evaluates the key in the body's scope, which holds the item only. A message used ONLY by
   a start event gets none. A message which starts a workflow of the module and which a claimed
   workflow waits for is refused with a guiding message (the engine's fallback from an unmatched
   key to the start subscription would start a second workflow on a correlation, see GAPS 4).
3. **Task parameters** (`ZenBpmTaskParameters`, claimed processes only): for every job-producing
   task inside a multi-instance body, one `ioMapping input` per name `taskParameterNames` (by
   element id and task definition) and `extensionTaskParameterNames` report, plus the aggregate id
   (`source="=<name>" target="<name>"`), on the task where it is a multi-instance element and on
   every multi-instance sub-process around it; an element maps in the items of the multi-instance
   elements around it, never its own or those of the elements inside it, and the targets the
   modeller maps are left out. A job anywhere else gets a copy of its scope's variables from the engine and needs none.
4. **Marker tasks** (decisions 26 and 44, `ZenBpmMarkerTasks`), only in a process a workflow service
   claims (`isClaimedByAWorkflowService`), and written in `wireBpmn` right after
   `validateTaskWiring`, not while preparing: the core answers `startsAWorkflowOfItsOwn` correctly
   only once the wiring of the process was validated. Where the process starts a workflow of its own
   (`startsAWorkflowOfItsOwn`), a service task `vanillabp__started__<startEventId>` with the type
   `vanillabp__started__<scoped process id>` (one type per process, the same in every
   name-clash-avoidance mode) is inserted right after EVERY start event of the top-level process:
   none, message and timer. It takes over the flows leaving the start event, those of the event's
   `outgoing` references, which the engine follows, and those whose `sourceRef` names it, and one
   new flow `<marker id>__flow` leads from the event to it. Where `workflowEndedHandlerExists` for
   the process (it also answers `true` when `release-on-workflow-end` is on), a service task
   `vanillabp__ended__<endEventId>` is to go in front of every end event of the TOP-LEVEL process
   (none, error, message, terminate), redirecting the end event's incoming flows to the task and
   adding one flow task -> end event; it is written in the same change as the handler of its job.
   Sub-processes and event sub-processes get no marker, because ZenBPM runs them as child
   instances, and a called process gets neither marker. A marker is drawn as a `bpmndi:BPMNShape` of
   60 x 40, its left edge 80 points right of its start event's left edge, with a
   `bpmndi:BPMNEdge` for the new flow; nothing else of the diagram moves. Where an element of the
   marker's id exists already, nothing is written, so a second rewrite of a document writes nothing.
5. **Unsupported constructs** are refused BEFORE deployment with a message naming file, process and
   element: signal events, conditional events, escalation events, script tasks, an instantiating
   receive task, a file with more than one executable process.

`wireBpmn` returns at once for a process no workflow service claims (`isClaimedByAWorkflowService`):
the core ends the start over such a process unless it is marked `implemented-externally`, and the
adapter makes no call for it (platform decision 120). For a claimed process it makes the core calls,
reading the model BEFORE its own rewrites add keys and mappings: `reportModelExpressions` (every `=`
expression with its `ExpressionPlace`), the refusal of an element which creates a job and names no
task definition, unless it is marked `implemented-externally` (the engine would give it a job of the
type `''`, which nothing subscribes, GAPS 25), `reportStartMessages` (the plain names of the message
start events; an empty collection where there is none, and no call at all where a name is an
expression), `validateBpmsInitiatedStarts` with EVERY start event, `validateTaskWiring` in its
four-argument form (service, send, business-rule-as-job tasks and message throw events as mandatory
specs with their `name`; user tasks as optional specs; a business rule task with `calledDecision` is
engine-served and skipped), `multiInstanceElementNames` by element id and task definition for every
task inside a multi-instance chain whose element names no `inputElement` (a method wanting that item
ends the start), `reportConcurrentTokenElements`, `reportCompensation` (nothing: compensation is
refused) and `workflowsShareTheWorkflowAggregate` for a call activity inside a multi-instance body.
The engine hands a called instance the caller's business key always and the caller's variables
outside a body, so only inside a body is the id variable carried, by an input mapping on every
multi-instance element the call runs in, and only where the called process shares the aggregate.
`unsharedWorkflowAggregatePaths` and `declaredTypesOfWorkflowAggregatePaths` are not called, as in
the Camunda 8 adapter: a model expression reading an attribute the aggregate does not share fails at
runtime as a FEEL error (deviation 14). The process version catalog (`registerProcessVersions`)
comes with E10; until then nothing is registered, which the core reads as "not asked yet".

`deployResources`: `validateNoCollidingProcessIds` (before anything is sent), then
`ZenBpmEngineWait` and `ZenBpmEnvironmentInfo`, then one `POST /v1/dmn-resource-definitions` per DMN
first, so no new process version calls a decision the engine does not hold yet, then one `POST
/v1/process-definitions` per BPMN file (the bytes as read where the pipeline changed nothing, the
document written out otherwise) with a `GET /v1/process-definitions/{key}` to learn its version.
Only once the engine took every file come `registerDeployedVersion` (unchanged files included) and
the `ZenBpmDeployedProcesses` record (scoped id, key, version, version tag, model as the engine
holds it) in the client factory, which serves the viewer and the ownership checks, and
`reportIdentifiersTheModelsDeclare`. The identifiers the engine held before are not reported: every
REST deployment is recorded under the resource name `process.bpmn` and a definition carries no
owner, so nothing tells this application's earlier deployments from another's (GAPS 24). A file the
engine refuses ends the deployment with the engine's reason; the files sent before it stay deployed,
because the engine takes one file per request, so a module's deployment is not atomic.

Name-clash mode (decision 24): ZenBPM has no tenant, so `by-adapter` cannot be served. It stays the
default, as the platform's contract asks of a BPMS without isolation of its own, and is refused
while deploying with `validateNativeIsolationSupported`, whose message names `use-prefix` and
`none`. So every application configures the mode, once per adapter id
(`vanillabp.adapters.<id>.name-clash-avoidance: use-prefix`) or per module or workflow.

## 4. Runtime flows

### 4.1 Starting a workflow

Phase one: nothing but resolving the aggregate id and asserting the client is configured. Phase two:
`POST /v1/process-instances {bpmnProcessId: scoped, businessKey: aggregateId, variables: shared
values + {<idName>: aggregateId}, historyTimeToLive: history-time-to-live if configured}`. The
engine runs the instance synchronously, so the response may already say `completed`; nothing to do
about it. The response's `key` (as the root instance key, decision 28) and `version` go to
`PhaseTwoRequest#reportStartedWorkflow`, so the core writes them into the start row. A repeated
dispatch is caught by `awarenessOfWorkflowForRedispatch(scope, persistence, id, plannedAt)`: the
business-key search with `createdFrom = plannedAt` (inclusive, milliseconds), no state filter; a
`null` `plannedAt` counts every instance. The residual duplicate window is the outbox's documented
at-least-once.

### 4.2 Locating a workflow (the probes)

`awarenessOfWorkflow(scope, persistence, id, workflowId)` (decision 25):

1. where the core hands a `workflowId` (from the start row, a task record or the election cache):
   `GET /v1/process-instances/<workflowId>`; an `active` or `failed` instance of one of the scope's
   scoped process ids whose id variable or business key is `<id>` -> ACTIVE. Every other answer
   (another state, process or aggregate, a 404, a 502 for a key of another partition, any failure,
   an id which is no positive number) falls through to step 2: the key may shorten a YES, never turn
   a negative into UNKNOWN_TO_BPMS (`ADAPTER-AUTHORS.md` 4.1);
2. for each plain process id of the scope, stopping at the first which runs:
   `GET /v1/process-instances?businessKey=<id>&bpmnProcessId=<scoped>&size=100` (the size applies
   per partition); an item `active|failed` = ACTIVE, else a `totalCount` above 0 = COMPLETED. Only
   where the page does not hold every match and none of those read runs, two more searches with
   `state=active` and `state=failed` and `size=1` read the engine's count;
3. nothing -> UNKNOWN_TO_BPMS; any failure of a search -> BPMS_UNAVAILABLE, the first after an
   answer logged at WARN, the next ones at DEBUG until a probe is answered again.

A workflow the engine started itself (a timer start, or a message start without a business key) has
no business key, so step 1 is the only way to it. It works while the core still knows the instance
key: the start row (`vanillabp.delivery.workflow-start-retention`, `P30D` by default, `0` keeps it,
or `keep-workflow-start-while-aggregate-exists`), the open-task records, the election cache. E13.4
removes the limit (GAPS 1). Under `message-start-lookup: scan` a step 4 follows where the search
found no workflow which runs: `GET /v1/process-instances?bpmnProcessId=<scoped>&state=active`, then
`state=failed`, page by page (the engine's largest page, 100 per partition), for each process of the
scope of which a version the engine holds has a message start event, and an instance whose
`variables[<id name>]` is the aggregate id is ACTIVE (a lookup over several pages may miss one while
other instances of the process end, because the engine pages after filtering by state); the probe before a start is sent again reads every state
from `createdFrom = plannedAt` instead. A lookup which would read more than
`message-start-lookup-max-instances` instances is BPMS_UNAVAILABLE with a WARN naming the bound
(`ZenBpmMessageStartScan`).

`awarenessOfTask` / `awarenessOfUserTask`: a task id which is no positive number -> UNKNOWN without
a request; `GET /v1/jobs/{key}`; 404 -> UNKNOWN; a 502 -> `GET /system/status`, a key of a partition
it does not report -> UNKNOWN, a status naming no partition tells nothing (decision 32); then the job's instance through the cache; instance not
in scope -> UNKNOWN; job `active` or `failed` -> ACTIVE, `completed` or `terminated` -> COMPLETED;
every other failure -> BPMS_UNAVAILABLE. `openTaskCount(module, process)`: the versions under the
scoped id (`GET /v1/process-definitions`), per version its model (`GET
/v1/process-definitions/{key}`) and its statistics, the active counts of the elements which give a
worker a job summed; `null` where the engine does not answer. The platform asks it before the
application deployed anything. `workflowVisibilityDelay()` = the configured
`workflow-visibility-timeout` (default `PT2S`, poll `PT0.25S`);
`workflowVisibilityDelay(workflowId)` keeps that window too, because a follower may not see an
instance the leader just created (GAPS 20).

### 4.3 Delivering a task

```
JobStream ──WaitingJob──▶ ZenBpmJobDispatcher ──(queue, bounded by the subscriptions' caps)──▶ ZenBpmExecutor ──▶ ZenBpmJobHandler
                          subscription per job type: lock_duration_ms = job-timeout, max_active_jobs = slots
ZenBpmJobHandler, before invoking:
  lock renewal: if receipt + job-timeout - now < job-timeout / 2, extend by job-timeout, counted on the
  application's clock (decision 36); while the handler runs, a timing thread extends every
  job-timeout / 2, each extension naming its delivery token
ZenBpmJobHandler:
  facts   = instanceCache.get(job.instance_key)              (GET /process-instances/{key} once)
  context = TaskInvocationContext{ taskDefinition = plain(job.type), bpmnElementId = job.element_id,
            taskKind = TASK, workflowId = root instance key (decision 28),
            businessKey = facts.businessKey,
            aggregateId = facts.variables[idName] ?? facts.businessKey, taskId = job.key,
            deliveryId = job.key, activationId = job.key, processVersion = facts.version, adapterId,
            taskParameters = job.input_variables, multiInstances = from parent chain,
            predatesDeployedVersion = facts.version < deployed }
  outcome = workflowTaskInvoker.invokeWorkflowTask(module, plainProcess, context)   (own transaction)
  COMPLETED           → PATCH /process-instances/{key}/variables {shared values + id var} for the
                        job's instance and every instance above it up to the root (decision 35)
                        POST /jobs/{key}/complete {same values}
  BPMN_ERROR          → PATCH variables as for COMPLETED; POST /jobs/{key}/fail {errorCode: scoped
                        code, variables, message for the incident, deliveryToken, clientId}; the
                        job read once after it: `failed` = nothing caught the code → WARN
  COMPLETION_PENDING  → end the handler's lock renewal, then extend the lock by async-task-lock-renewal
                        (default PT1H); when it lapses the redelivery is answered from the record
                        (COMPLETION_PENDING again) and renews; overdue with async-task-max-age-action
                        incident → POST /jobs/{key}/fail {retries: 0, message} instead
  unknown workflow    → DeliveryOfAnUnknownWorkflowException: POST /jobs/{key}/fail {retries: 0,
                        message, deliveryToken, clientId} → incident; WARN without stack trace
  exception           → POST /jobs/{key}/fail {message, retryBackoff (if set), deliveryToken,
                        clientId} (decision 27): no `retries`, so one attempt is spent; the engine
                        hands the job out again after the backoff, and files an incident when no
                        retry is left (`clientId` releases the stream's lock, `deliveryToken` makes
                        a repeated request count once)
then: workflowTaskInvoker.reportTasksTheBpmsNoLongerHas(module, plainProcess, context, probe)
```

The `PATCH` before `complete` is what makes a gateway behind the task see the values (job outputs
propagate only through output mappings, section 4 of the capability analysis). Both calls are
idempotent: a repeated PATCH writes the same values, a repeated complete is answered 201.

The core routes a delivery by the task definition AND the element id (platform decision 67), so
`bpmnElementId` is always filled. It refuses a delivery whose business key is not the aggregate id
(platform decision 69). The retries a job starts with come from the model's `zenbpm:taskDefinition
retries` or the engine's `jobs.defaultRetries`. After the outcome was sent, the core asks the
`ZenBpmOpenTaskProbe` (S6.1.3) about the other tasks it believes open in that workflow: `GET
/v1/jobs/{key}`, `completed`/`terminated` = GONE, `active`/`failed` = STILL_THERE, anything
else CANNOT_SAY, a `404` included (a lagging node, GAPS 20). That is how `@TaskEvent CANCELED` reaches a service task the engine dropped.

A task the handler left open is completed or cancelled later by the application
(`ZenBpmTaskOutcomes`, S6.3.1). Phase one registers a check for the transaction of the aggregate
(`preCommitRegistrar().beforeCommit`), which reads the job and the process of its instance right
before the commit: `active` and of a process the module serves passes; `completed`, `terminated`
or unknown after `workflow-visibility-timeout` throws `TaskNotFoundException`; another module's
process and `failed` (an incident) throw `IllegalStateException`. Phase two writes the values as
for COMPLETED and then sends `POST /jobs/{key}/complete`, resp. `POST /jobs/{key}/fail {errorCode:
scoped code, variables, clientId}`, without a delivery token, which the application does not have;
the engine takes both whoever sends them. A job this node parked stops raising its type's cap once
the engine dropped its lock (decision 40).

Every handler runs `load -> invoke -> save -> commit -> report`, at least once. `deliversTasksAtLeastOnce()`
is `true`, and the delivery id is the job key. A second delivery while a handler still runs happens
only where the lock was lost: the renewal answered `409`, or the partition leader changed (the lock
lives in the leader's memory, E13.1 section 0). The adapter logs the lost lock with the task and the
aggregate, and the core names and counts the concurrent delivery.

### 4.4 Messages

`CORRELATE_MESSAGE` phase one never asks the engine whether something waits: a model of the module
waits for the message, compared by its scoped name with every version the engine holds of the
processes the module deployed, which the deployment read once it took every file, and the newest
version of none starts with it (decision 42). A name the newest version starts with fails, also
where an older version waits for it, because the engine hands a message nothing waits for under its
key to the newest version's start event (GAPS 4); a name nowhere fails naming the processes read and
their messages. Before it refuses, phase one reads every version from the engine again, because
another node may have deployed a newer one since. The check is silent where the module deployed
nothing, a version could not be read, or the module serves a process it brought no model for.
Phase two: `POST /v1/messages {messageName: scoped, correlationKey: correlationId ?? aggregateId,
variables: shared values + id var}`. 404 -> `PhaseTwoRetryLater(workflow-visibility-timeout)`: the
outbox is the buffer the engine does not have. A retry-later counts no attempt; the entry is blocked
after `vanillabp.outbox.wait-for-visibility-at-most`. The engine answers the same 404 where the
message was received and the workflow failed right behind the waiting element on something it does
not find (GAPS 29); the status cannot tell the two apart, so that publication is repeated as well
(decision 43). A zero `workflow-visibility-timeout` makes the 404 an ordinary failure with the
outbox's own backoff, as the platform treats a zero window. A correlation to a workflow which ended
is skipped by the core's own probe before either phase; one to a workflow which passed the waiting
element is answered 404 like one which has not reached it, and so repeated until the window ran out
(GAPS 5). `START_WORKFLOW_BY_MESSAGE`
publishes without a key, with the shared values and the id variable; `POST /v1/messages` returns no
instance key, so phase two reports none. The `vanillabp__started` marker after the message start event (decision 26)
reports the instance key to the core, which writes it into the start row. The instance itself has no
business key (GAPS 1, E13.4); `message-start-lookup: scan` stays an opt-in for what the start row
cannot cover until E13.4 lands, and `refuse` refuses every start by a message in phase one. Phase
one judges the name by the start messages of the newest version of the module's processes, read
again before a refusal, as for a correlation (decision 42). Start messages of one name which reach
the engine at the same moment lose starts (GAPS 30), so each node publishes them one after the other,
and a `404` is an ordinary failure, repeated with the outbox's backoff (decision 47).

### 4.5 Aggregate push

`AGGREGATE_CHANGED` without a task (`ZenBpmAggregatePush`): the root is the instance named by
`PhaseTwoRequest#workflowId()` (the start row) where it runs the workflow of the aggregate, else the
root instance of the business-key search, else, under `message-start-lookup: scan`, the running
instance whose id variable names the aggregate; then `PATCH .../{key}/variables` of the root, and of
every instance below it which is `active` or `failed`, read with `GET .../{key}/child-processes`
after its parent was written, because a sub-process, a multi-instance body, each iteration and a
called instance hold a copy of their own (decisions 35 and 49, GAPS 31). A called instance which
names another aggregate is not entered (decision 45). With a task: `GET /v1/jobs/{taskId}` -> its
`processInstanceKey` IS the scope the task runs in (a sub-process or an iteration is a child
instance) -> `PATCH` that instance and no other. `taskRecord()` is not read: no field of the row says
that a handler holds the task (the row exists for a task the adapter left open), and the row names
the workflow, not the instance the task waits in. A `409` (the instance ended) makes the write a
no-op, a `404` is repeated (decision 34). No idempotency key, as the core defines it.

### 4.6 User tasks

A ZenBPM user task is a job of type `zenbpm:taskDefinition type` (default `user-task-type`), which
is the VanillaBP task definition. Notifications come from `ZenBpmUserTaskPoller` (one per adapter
id, timing thread): every `user-task-poll-interval` it lists `GET
/v1/jobs?jobType=<scoped>&state=active` and `state=terminated` per user-task type of the deployed
modules, keeps a bounded seen-set per node, and invokes the core with `TaskEvent CREATED` resp.
`CANCELED`, delivery id = `job.key + ":created"` resp. `":canceled"`. The delivery record makes a
restarted node harmless. `COMPLETE_USER_TASK` / `CANCEL_USER_TASK` are the job commands. The stream
is deliberately NOT subscribed to user-task types (decision 12): a lock would have to be renewed for
the whole life of a user task, and a terminated job is never delivered, so polling is needed for
`CANCELED` anyway. The context reports `taskKind = USER_TASK`. Because the poller already reports
every canceled user task, the open-task probe of section 4.3 answers CANNOT_SAY for user-task
definitions: the platform's contract forbids a second source for the same cancellation.

A process id a workflow service declares without a model (the old id of a renamed process) is
served by `ZenBpmDeclaredProcessWorkers` (S6.4.1) when its module starts processing: the methods'
task definitions (`taskWiringOfProcessesNobodyDeployed`) are scoped by the id, the models the engine
holds under the id are read, every version, and a composed type is subscribed only where a held
model gives it to a task, so the stream never takes a user task of the old id (decision 41). The
user-task types the read finds are named at startup, which is where the poller can take them from.

### 4.7 Workflow ended and BPMS-initiated start

Both arrive as ordinary jobs of the marker tasks (section 3, step 4), served by two dedicated handlers:

- `ZenBpmWorkflowEndedHandler`, which comes with the end marker: kind COMPLETED, `endEventId` from the marker's id, `workflowId` =
  the root instance key (decision 28) and the business key; the core cancels the tasks it still
  believes open in that workflow before `@WorkflowEnded` runs (platform decision 73). Then complete
  the job so the instance ends. A canceled instance runs no marker, so kind CANCELED is never
  reported.
- `ZenBpmBpmsInitiatedStartHandler` (decisions 25 and 46), which the module's `ZenBpmJobHandler`
  hands the job of a start marker, inside the module's drain and under the renewal of its lock: the
  context carries the start event and its kind (remembered per process while wiring), the job's
  variables with the id variable the instance holds now (the engine hands a job out with the
  variables it was created with, so an id written later is read from the instance through the
  instance cache), `nativeInstanceId` = the job's instance, which is the workflow's root because a
  marker sits in the process itself, the version, sync mode FULL and the instance's business key
  where it has a non-empty one; there is no start time and nothing derives an id.
  `startWorkflowByBpms` builds the aggregate through the application's `@WorkflowStartedByBpms`
  method where the instance carries no name, and writes the start row with the instance key in every
  case but a refusal. Wherever the instance does not carry the result's id under its name, the
  handler writes `result.variables()` with `PATCH /v1/process-instances/{key}/variables`, then
  completes the marker job without variables. A redelivery after the update, and the marker of a
  workflow the application started itself, find the id and write nothing; a read a lagging node
  answers before it saw the update is the one way left to a second aggregate (decision 46). A
  refusal or a method which threw fails the job like a task's handler (decision 27), with the
  `retry-backoff` resolved for the name `vanillabp__started`. A call activity which calls a process
  of the module on an aggregate of its own gets `zenbpm:in businessKey=""` and an input mapping
  `=null` of the called process's id variable while deploying, so the called process starts without
  the caller's name (decision 45).

### 4.8 Shutdown

`stopWorkflowProcessing(module)`: unsubscribe the module's job types, stop the module's pollers, wait
`shutdown-grace` (default `PT20S`) for handlers still inside the application, never `fail` a job while
shutting down (the delivery record answers the redelivery). The client factory closes the stream
and the HTTP client last, for every module which never reached the stop; closing the stream releases
every lock of the adapter's client at once (E13.1), so another node gets the open jobs immediately
instead of after `job-timeout`. Asynchronous tasks parked by a lock extension are released with them
and are re-parked by whichever node receives them next.

## 5. Threading

- Handler slots: `worker-threads` (default 4) or `virtual` with `worker-threads-bound`; sized against
  the database connection pool. Every job type is subscribed with `max_active_jobs` = the slot count,
  and the engine counts that cap per type, so the queue in front of the slots is bounded by the
  number of subscribed types times the slots; a queued job does not lose its lock, because the handler
  renews it when it starts.
- Timing threads: two per adapter id, for the stream's reconnect, the lock renewals, the user-task
  poller and the engine wait. No handler runs on them.
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
| `job-timeout` | adapter, module, workflow, task | `PT5M` | `lock_duration_ms` of the subscription; renewed at half while a handler runs; one value per job type, so conflicting task-level values of one type end the boot naming both |
| `async-task-lock-renewal` | adapter | `PT1H` | how far an open asynchronous task's lock is extended; one not shorter than `vanillabp.delivery.retention`, `PT0S` or a negative one ends the start; above the engine's `jobManager.maxLockDurationMs` the engine lowers it, which a WARN names once per adapter id |
| `retry-backoff` | adapter, module, workflow, task | none | the `retryBackoff` sent with a failure of a handler (decision 27); unset, the request leaves it out and the task definition's `retryBackoff` or the engine's `jobs.defaultRetryBackoff` applies; the number of attempts is the job's `retries` (model or engine default) |
| `shutdown-grace` | adapter | `PT20S` | |
| `workflow-visibility-timeout` | adapter | `PT2S` | `workflowVisibilityDelay()` and the retry-later window of a message |
| `user-task-poll-interval` | adapter | `PT5S` | `PT0S` switches user-task notifications off with a WARN |
| `history-time-to-live` | adapter, module, workflow | none (engine default) | sent with every start |
| `instance-cache-max-entries` | adapter | `10000` | |
| `name-clash-avoidance` | adapter, module, workflow | `by-adapter` (the platform's default) | refused while deploying, so every application sets `use-prefix` or `none` (decision 24); `none` warns; two ids on one `rest-address` boot only where their modes at the adapter level differ, the setup of a prefix migration |
| `accept-unscoped-identifiers` | adapter, module | `false` | silences the `none` warning |
| `message-start-lookup` | adapter | `start-row` | `scan` adds the bounded client-side lookup of message-started workflows for what the start row cannot cover (GAPS 1); an opt-in until E13.4 lands, then removed; `refuse` starts no workflow by a message; each start names the processes a message starts and the value in force, `scan` with a WARN |
| `message-start-lookup-max-instances` | adapter | `1000` | the bound of that scan; below `1` ends the start |
| `async-task-max-age-action` | adapter | `report` | `incident` fails an overdue asynchronous task's job with `retries: 0`, so the engine raises an incident; any other value ends the start |
| `deployment-failure` | adapter | `fail` | core key |

Scoped keys resolve through the four levels of the configuration model, most specific first, with
the same resolver shape on both platforms (`PeaFetchVariables` pattern). Every key is modelled in
the Quarkus overlay, or the startup fails on it.

Startup validation, per configured id of type `zenbpm`: no `rest-address` and no other key without a
default (`grpc-address`, `client-id`, `history-time-to-live` at any level) -> WARN naming
`vanillabp.adapters.<id>.rest-address`, the application boots, the adapter refuses work with the same
message; a malformed address, a `grpc-address` which is not `host:port`, a blank `client-id` or a
started configuration without `rest-address` -> the boot ends naming the key, unless the id is
nowhere first and `deployment-failure: warn`; a number or duration which cannot work -> the boot
ends for every id; complete -> the client is built without contacting the engine, and one INFO line
names address, gRPC address, client id and the engine version once the wait answered. That INFO
line describes a working configuration; the validation messages never repeat an address or the
client id.

## 7. Messages and observability

- Every message names module, process, aggregate id, the attempted operation and the way out, keys in
  full (`vanillabp.adapters.<id>.<key>`), following the config-validation skill.
- Meters where Micrometer is present (`ZenBpmMetrics`, optional on both platforms):
  `vanillabp.zenbpm.jobs.received`, `.completed`, `.failed`, `.queued` (waiting for a slot), `.lock.renewed`, `.lock.lost`,
  `.redelivered` (a delivery id seen before), `vanillabp.zenbpm.stream.reconnects`,
  `vanillabp.zenbpm.slots.busy` / `.free`, `vanillabp.zenbpm.usertasks.polls`.
- Health contributes the engine's readiness per adapter id.

## 8. Test infrastructure

- `EngineUnderTest` (Spring ITs) and `EngineImage` (Quarkus) start `${zenbpm.image}` with
  `REST_API_ADDR=:8080`, `GRPC_API_ADDR=:9090`, `CLUSTER_RAFT_BOOTSTRAP_EXPECT=1`,
  `POLL_TIMER_DELAY_SECONDS=1`, waiting for `GET /system/health/ready` = 200; ports from
  `FreePortUtil` where fixed ports are needed.
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
  model/ZenBpmModelExpressions         the model's expressions with their place, for the core
  deployment/ZenBpmDeploymentService   AdapterDeploymentService<ZenBpmBpmnModel, ZenBpmProcessingContext>
  deployment/ZenBpmDeployedProcesses   what this boot deployed per adapter id
  deployment/ZenBpmProcessVersions     CachingProcessVersionCatalog over GET /process-definitions
  deployment/ZenBpmDeclaredProcessWorkers  job types composed for renamed processes
  ZenBpmInstanceCache                  instance facts and the walk to the workflow id, per adapter id
  processservice/ZenBpmProcessService  MigratableProcessService<A>: handler map, probes, viewer
  processservice/ZenBpmWorkflowViewer  definitions, XML, history
  processservice/ZenBpmSharedValues    shared values + id variable for every command
  processservice/ZenBpmValueTypes      whatThisBpmsDoesWith: what a value type becomes in the engine
  wiring/ZenBpmJobDispatcher           stream -> bounded queue -> slots, routes by the instance's process
  wiring/ZenBpmLockRenewal             renews locks of running handlers, parks async tasks
  wiring/ZenBpmJobHandler              section 4.3
  wiring/ZenBpmOpenTaskProbe           OpenTaskProbe: is a task the core believes open still there
  wiring/ZenBpmUserTaskPoller          section 4.6
  wiring/ZenBpmWorkflowEndedHandler    marker job -> WorkflowEndedInvoker
  wiring/ZenBpmBpmsInitiatedStartHandler marker job -> BpmsInitiatedStartInvoker
  wiring/ZenBpmMultiInstance           iteration facts from the instance cache and child lists
  wiring/ZenBpmDrain                   handlers in flight per module
  health/ZenBpmHealth
  observability/ZenBpmMetrics, MicrometerZenBpmMetrics
```

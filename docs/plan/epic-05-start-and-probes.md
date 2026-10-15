# E5 - Workflow start and the election

**Goal.** `startWorkflow` creates an instance after the commit and never after a rollback; every probe
of `MigratableProcessService` answers honestly, scoped, and never advances anything; the instance cache
which every later inbound story reads exists.

**Done when.** The exemplary end-to-end start runs on both platforms against the container, and the
probes are pinned by unit tests with the fake server and by ITs.

Template: `Camunda8ProcessService` (handler map, probes, redispatch probe, visibility delay),
`Camunda8DeployedProcesses` for the ownership comparison, `Camunda8VariableFilters` is NOT needed (no
variable search). Contract: `MigratableProcessService` type javadoc, `ADAPTER-AUTHORS.md` section 4.

---

## F5.1 Starting a workflow

### S5.1.1 `START_WORKFLOW` handler and the shared values

- [ ] **Depends on:** E4.

**Instructions**

1. `ZenBpmSharedValues.forCommand(module, plainProcessId, aggregateIdName, aggregateId, sharedValues)`:
   a `Map<String, Object>` of the shared values plus `aggregateIdName -> aggregateId as String`, the
   id last so it wins over a same-named attribute. Used by every command (decision 25).
2. `ZenBpmProcessService.phaseOperations()` gains `START_WORKFLOW`:
   - phase one: resolve the aggregate id (`aggregatePersistence.getAggregateId(aggregate)` as String)
     and assert `factory.isConfigured()`, else the unconfigured message; nothing else (a start has
     nothing to ask).
   - phase two: `workflowAggregateSync().syncedValues(loaded aggregate, FULL)` - load the aggregate
     through the persistence in phase two, the request carries the id only - then
     `rest.createInstance(scopedProcessId, businessKey = aggregateId, variables, historyTimeToLive
     resolved for module/workflow)`. Log the instance key at DEBUG. A `ZenBpmApiException` 400 (the
     process id is unknown to the engine: nothing deployed under that scoped id) is rethrown with a
     message naming module, process, scoped id and `deployment-failure`. Then
     `request.reportStartedWorkflow(String.valueOf(key), String.valueOf(version))` with the `key`
     and `version` the create answers (platform decisions 108 and 110): the core writes them into
     the start row, which later hands the key back to the probes (S5.2.1). The version is written in
     the same form `TaskInvocationContext.getProcessVersion()` reports (`ADAPTER-AUTHORS.md` 2.2).
     The instance a start creates is a root instance, so its key is the workflow id of decision 28.
3. `isPhaseTwoFailureRepeatable(t)` = `!ZenBpmErrors.permanentFailure(t)`.
4. `deliversTasksAtLeastOnce()` = `true`.
5. `getAdapterId()`, and the rest of the required handlers still throwing "later story" so the boot
   accepts the map.
6. `whatThisBpmsDoesWith(type, direction)` through `ZenBpmValueTypes` (platform decision 57): the
   engine keeps every number as a float64 (S2.2.1 measured `2^53+1` coming back as `2^53`, and
   `1000.0` coming back as `1000`). So per type and direction: `String`, `Boolean` and enums
   `survives()`; `Integer`, `Short` and `Byte` `survives()` (every value fits a float64 exactly);
   `Long`, `BigInteger` and `BigDecimal` `changed(...)` naming the precision lost above 2^53 or
   after the 17th digit; `Double` and `Float` `survives()` TO the engine and `changed(...)` FROM it
   where the value is whole (it comes back without its fraction, so as an integer type);
   `Date` and `Instant` travel as the instant; anything else `cannotSay(...)`. Platform decision 55
   applies on the way back: a number reaches a handler as the engine reported it, the adapter
   converts nothing. Check each answer against the engine in the contract IT before relying on it.

The engine takes `historyTimeToLive` as a Go-style text (`30d`, `1M5d8h`): `ParseTTL` in
`internal/cluster/types/ttl.go`, described in `openapi/api.yaml`. It keeps whole seconds, cut off
by `int64(historyTTL.Seconds())` in `internal/cluster/partition/partition_persistence.go` into the
column `history_ttl_sec` of `internal/sql/migrations/0001_schema.up.sql` (all at the pinned commit
`bdd795a3`). Convert the resolved `Duration` to that form, and decide at startup what a
value below one second and `PT0S` mean (the configuration accepts both, it refuses only negative
values), checking against the engine what an explicit `0` does.

*Found by S2.2.2's `ZenBpmErrorsIT`, 2026-10-14.* A start of a process the engine does not hold is
answered `404`, not `400` as instruction 2 says, so the rethrow has to look for a `404` of
`POST /v1/process-instances`. `ZenBpmErrors` reads that `404` as repeatable, as any `404` which is
not a job's or a message's, so the outbox repeats such a start until it is blocked. Camunda 8 makes
it permanent with `Camunda8RefusedStart`; whether this adapter does the same is this story's
decision, and a new row of the README's table "Which phase-two failures are repeated" and of
decision 10.

*Note from a review of S2.3.1, 2026-10-14.* The pinned engine refuses a start whose variables are
above 4 MiB, and a variable update which would bring an instance above 4 MiB, with `500
TECHNICAL_ERROR` ("grpc: received message larger than max (5242957 vs. 4194304)"). The node which
takes the request passes it on over the gRPC between the nodes. That gRPC keeps its default limit of
4 MiB: neither `Server.Open` in `internal/cluster/server/server.go` nor `ClientManager.newClient` in
`internal/cluster/client/client_manager.go` sets another. `ZenNode.CreateInstance` and
`ZenNode.ModifyProcessInstance` in `internal/cluster/node.go` turn the failed call into
`zenerr.TechnicalError`. REST itself takes bodies up to 10 MiB, the default of
`HttpServer.MaxRequestBodyBytes` in `internal/config/config.go` (all at the pinned commit
`bdd795a3`). The adapter's `ZenBpmErrors`
(`core/src/main/java/org/pbinitiative/zenbpmadapter/client/ZenBpmErrors.java`) reads a `500` as
repeatable, so such a start would be repeated for as long as the outbox keeps it, refused the same
way every time. Decide here whether the adapter measures the variables before it sends them; E13.13
asks the engine for a status which says what happened.

**Tests**: `ZenBpmProcessServiceTest` (fake server): phase one contacts nothing (zero requests),
phase two sends business key, id variable and shared values, the 400 message, and reports the
started workflow with key and version; `ZenBpmSharedValuesTest` for the id winning and
`@NoSyncWithBPMS` still carrying the id; `ZenBpmValueTypesTest` per type and direction, and a
contract-IT case per class of answer against the pinned engine.

**Acceptance criteria**

- [ ] The create request carries `businessKey`, `variables[<idName>]` and the shared values, and
  `historyTimeToLive` only where configured.
- [ ] Phase one makes no HTTP request (asserted).
- [ ] `reportStartedWorkflow` is called once per start, with the instance key and its version.

## F5.2 The probes

### S5.2.1 `ZenBpmInstanceCache` and the workflow probes

- [ ] **Depends on:** S5.1.1.

**Instructions**

1. `ZenBpmInstanceCache(rest, maxEntries)`: `facts(instanceKey)` -> `InstanceFacts(key, scopedProcessId,
   version, definitionKey, businessKey, parentKey, processType, variables)` from `rest.instance(key)`,
   LRU bounded by `instance-cache-max-entries`, an `Optional.empty()` for 404 which is NOT cached (the
   instance may appear on a follower a moment later). Thread-safe. Variables are cached only for the
   id variable. The cache also answers `workflowIdOf(instanceKey)`, the root key of decision 28: it
   walks up `parentKey` while the instance's `processType` is `subprocess` or `multiInstance` (a
   child instance of the same process) and stops at `default` or `callActivity`: a task of a called
   process names the called instance (platform decision 124). The walk is cached with the facts.
   E13.14 puts the root key on the job and makes the walk unnecessary.
2. `awarenessOfWorkflow(scope, persistence, id, workflowId)`, the four-argument form (platform
   decision 112, merged into platform decision 5). The fourth argument is the instance key the core
   learned about this aggregate: from the start row (S5.1.1's `reportStartedWorkflow`, the start
   marker of E9), from a task's delivery record, or from the election cache (`ADAPTER-AUTHORS.md`
   4.1). It is the only way to find a workflow without a business key, which is every workflow the
   engine started itself (decision 25). The order:
   1. Where `workflowId` is not null: `rest.instance(workflowId)`. Answer ACTIVE where the instance
      is `active` or `failed`, its `bpmnProcessId` is one of the scope's scoped ids, and its id
      variable or business key equals `id`. Anything else - another state, another process,
      another id, a `404`, a `502` (a key of a partition this engine does not have; the core hands
      the same key to every adapter of its list) or a failure - falls through to step 2. The key may
      only make a YES shorter and may never turn an answer into UNKNOWN (`ADAPTER-AUTHORS.md` 4.1):
      a stale key may name an earlier, ended workflow of the same aggregate, so `COMPLETED` is never
      answered from the key. This is what Camunda 8 does (`theEngineHoldsTheInstance`).
   2. The business-key search per scoped process id of the scope (`size=1`, read `totalCount` and
      the first item's state). State mapping `active|failed -> ACTIVE`, `completed|terminated ->
      COMPLETED`.
   3. UNKNOWN. There is no lookup "instance key where `id` parses as a long" any more: the id of a
      workflow is never its instance key (decision 25).
   `ZenBpmErrors.isUnavailable` -> BPMS_UNAVAILABLE; any other exception in step 2 ->
   BPMS_UNAVAILABLE as well, logged once per adapter id (never UNKNOWN on a failure).
3. `awarenessOfWorkflowForRedispatch(scope, persistence, id, plannedAt)`, the four-argument form
   (platform decision 116). Keeping the default is a contract violation (`ADAPTER-AUTHORS.md`:
   "override the form with four arguments"): an aggregate may carry a second workflow once its first
   ended, and only workflows started at or after `plannedAt` count. So: the business-key search
   without a state filter and with `createdFrom = plannedAt` (S2.2.1 found the filter inclusive and
   in milliseconds); an instance found -> ACTIVE or COMPLETED by its state; a `null` `plannedAt`
   counts every instance; nothing found -> UNKNOWN; a failed query -> BPMS_UNAVAILABLE, never
   ACTIVE. A message-started workflow has no business key and is never found here; S7.1.2 says what
   that means for a re-dispatched message start.
4. `workflowVisibilityDelay()` = `WorkflowVisibilityDelay.of(workflow-visibility-timeout, PT0.25S)`.
   The per-id form `workflowVisibilityDelay(workflowId)` keeps its default, which answers the same:
   a `404` for a key the engine just answered must not shorten the window, because a follower may
   lag (GAPS 20).
5. `canLocateWorkflows()` = `true`.

**Tests**: `ZenBpmProcessServiceTest` cases with the fake server: found by business key; found by
the workflow id with matching process and id -> ACTIVE; a workflow id naming a completed instance
falls through to the search; a workflow id with a process outside the scope, another id, `404` or
`502` falls through; empty -> UNKNOWN; 503 -> BPMS_UNAVAILABLE; timeout -> BPMS_UNAVAILABLE; the
redispatch probe counts an instance created at `plannedAt` and not one created a millisecond before;
`ZenBpmInstanceCacheTest` for bound, eviction, the uncached miss and the root walk (stops at a call
activity).

**Acceptance criteria**

- [ ] A workflow of another module of the same engine with the same aggregate id is UNKNOWN.
- [ ] No probe makes more than `scope.processIds().size() + 1` requests.
- [ ] No answer other than ACTIVE comes from the workflow id; a `404` or `502` for it is never
  UNKNOWN by itself.

*Note from S2.2.1, 2026-10-13:* an instance key of a partition the engine does not have is answered
502, not 404 (see the note under S2.2.2). Settled by step 2.1 above: the workflow-id lookup falls
through to the search on a 502.

### S5.2.2 Task probes, `openTaskCount`

- [ ] **Depends on:** S5.2.1.

**Instructions**

1. `awarenessOfTask(scope, aggregateId, taskId)`: `taskId` not a long -> UNKNOWN with a DEBUG line
   (a Camunda key handed to the wrong adapter); `rest.job(key)` empty -> UNKNOWN; job's instance via
   the cache; instance's `bpmnProcessId` not in the scope -> UNKNOWN; job `active` -> ACTIVE,
   `completed|terminated|failed`... `failed` means an incident on an OPEN task, so ACTIVE;
   `completed|terminated` -> COMPLETED; failure -> BPMS_UNAVAILABLE. Non-advancing (a GET).
2. `awarenessOfUserTask` = the same (a user task is a job). The core knows which kind a task id is
   from the delivery record (`TaskKind` `TASK` or `USER_TASK`, platform decision 106), which S6.1.1
   and S8.1.1 report; the probes need not tell the kinds apart.
3. `openTaskCount(module, plainProcessId)`: `rest.processDefinitionStatistics(key)` for every version
   key `ZenBpmDeployedProcesses` and the catalog know, summing active element instances of job-producing
   elements; `null` where nothing was deployed. Never a job page (decision 14).

**Tests**: fake-server cases per outcome; `ZenBpmProbesIT` (Docker): a started instance's waiting job
is ACTIVE for its scope and UNKNOWN for a scope naming another process; after completion COMPLETED.

**Acceptance criteria**

- [ ] The task probes never send a command (fake server asserts GET only).

## F5.3 Exemplary end-to-end start

### S5.3.1 The two-phase start on both platforms

- [ ] **Depends on:** S5.2.2.

**Instructions**

- Spring `ZenBpmDeploymentAndStartIT` (module `start-it`, JPA with the platform's JDBC outbox, the
  default since gruelbox left the platform (platform decision 102), `@DirtiesContext`, own
  container; the test aggregate marks what it does not share with `@NoSyncWithBPMS`, or its workflow
  sets `allow-full-sync-with-bpms: true`, because an aggregate which shares everything ends the
  start (`FullSyncCheck`, platform decision 66)): `instanceAppearsOnlyAfterCommit` (inside the
  transaction the business-key search is empty; after the commit it finds the instance with state
  `active`), `noInstanceAfterRollback` (the transaction rolls back; after two outbox cycles nothing
  exists and no outbox entry remains), `aWorkflowNobodyStartedIsUnknownToBothProbes`,
  `theProbeFindsTheStartedWorkflow` through `MigratableProcessService` directly.
- Quarkus `quarkus/integration-tests`: `ZenBpmWorkflowLifecycleTest` (`QuarkusProdModeTest`, JVM
  args from `testCoverageJavaAgent(quarkusProdModeTestDefaults())`, `quarkus.http.port` free, the
  container's addresses passed as system properties), introspection endpoints
  `introspect/start/{id}`, `introspect/rollback/{id}`, `introspect/instances/{id}`; cases
  `aStartCreatesTheInstanceAfterTheCommit`, `aRolledBackStartCreatesNothing`. This class grows with
  every later epic (one boot per class, the Camunda 8 rule).

**Acceptance criteria**

- [ ] Both platforms prove the two-phase start against the real engine.
- [ ] The Quarkus coverage report shows `ZenBpmProcessService.phaseTwo` covered.

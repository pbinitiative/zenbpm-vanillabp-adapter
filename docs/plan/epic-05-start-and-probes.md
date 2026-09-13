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
   id last so it wins over a same-named attribute. Used by every command (decision 6).
2. `ZenBpmProcessService.phaseOperations()` gains `START_WORKFLOW`:
   - phase one: resolve the aggregate id (`aggregatePersistence.getAggregateId(aggregate)` as String)
     and assert `factory.isConfigured()`, else the unconfigured message; nothing else (a start has
     nothing to ask).
   - phase two: `workflowAggregateSync().syncedValues(loaded aggregate, FULL)` - load the aggregate
     through the persistence in phase two, the request carries the id only - then
     `rest.createInstance(scopedProcessId, businessKey = aggregateId, variables, historyTimeToLive
     resolved for module/workflow)`. Log the instance key at DEBUG. A `ZenBpmApiException` 400 (the
     process id is unknown to the engine: nothing deployed under that scoped id) is rethrown with a
     message naming module, process, scoped id and `deployment-failure`.
3. `isPhaseTwoFailureRepeatable(t)` = `!ZenBpmErrors.permanentFailure(t)`.
4. `deliversTasksAtLeastOnce()` = `true`.
5. `getAdapterId()`, and the rest of the required handlers still throwing "later story" so the boot
   accepts the map.

**Tests**: `ZenBpmProcessServiceTest` (fake server): phase one contacts nothing (zero requests), phase
two sends business key, id variable and shared values, the 400 message; `ZenBpmSharedValuesTest` for
the id winning and `@NoSyncWithBPMS` still carrying the id.

**Acceptance criteria**

- [ ] The create request carries `businessKey`, `variables[<idName>]` and the shared values, and
  `historyTimeToLive` only where configured.
- [ ] Phase one makes no HTTP request (asserted).

## F5.2 The probes

### S5.2.1 `ZenBpmInstanceCache` and the workflow probes

- [ ] **Depends on:** S5.1.1.

**Instructions**

1. `ZenBpmInstanceCache(rest, maxEntries)`: `facts(instanceKey)` -> `InstanceFacts(key, scopedProcessId,
   version, definitionKey, businessKey, parentKey, processType, variables)` from `rest.instance(key)`,
   LRU bounded by `instance-cache-max-entries`, an `Optional.empty()` for 404 which is NOT cached (the
   instance may appear on a follower a moment later). Thread-safe. Variables are cached only for the
   id variable (read once, so a BPMS-initiated workflow's id can be found).
2. `awarenessOfWorkflow(scope, persistence, id)` as `architecture/01-architecture.md` 4.2: business-key
   search per scoped process id of the scope (`size=1`, read `totalCount` and the first item's state),
   then the instance-key lookup where `id` parses as a long and the instance's `bpmnProcessId` is one
   of the scope's scoped ids, then UNKNOWN. State mapping `active|failed -> ACTIVE`,
   `completed|terminated -> COMPLETED`. `ZenBpmErrors.isUnavailable` -> BPMS_UNAVAILABLE; any other
   exception -> BPMS_UNAVAILABLE as well, logged once per adapter id (never UNKNOWN on a failure).
3. `awarenessOfWorkflowForRedispatch` keeps the default (the probe is an honest query), and a test
   pins that a failing query answers BPMS_UNAVAILABLE and never ACTIVE.
4. `workflowVisibilityDelay()` = `WorkflowVisibilityDelay.of(workflow-visibility-timeout, PT0.25S)`.
5. `canLocateWorkflows()` = `true`.

**Tests**: `ZenBpmProcessServiceTest` cases with the fake server: found by business key, found by
instance key with matching process, instance key with a process outside the scope -> UNKNOWN, empty ->
UNKNOWN, 503 -> BPMS_UNAVAILABLE, timeout -> BPMS_UNAVAILABLE; `ZenBpmInstanceCacheTest` for bound,
eviction and the uncached miss.

**Acceptance criteria**

- [ ] A workflow of another module of the same engine with the same aggregate id is UNKNOWN.
- [ ] No probe makes more than `scope.processIds().size() + 1` requests.

### S5.2.2 Task probes, `openTaskCount`

- [ ] **Depends on:** S5.2.1.

**Instructions**

1. `awarenessOfTask(scope, aggregateId, taskId)`: `taskId` not a long -> UNKNOWN with a DEBUG line
   (a Camunda key handed to the wrong adapter); `rest.job(key)` empty -> UNKNOWN; job's instance via
   the cache; instance's `bpmnProcessId` not in the scope -> UNKNOWN; job `active` -> ACTIVE,
   `completed|terminated|failed`... `failed` means an incident on an OPEN task, so ACTIVE;
   `completed|terminated` -> COMPLETED; failure -> BPMS_UNAVAILABLE. Non-advancing (a GET).
2. `awarenessOfUserTask` = the same (a user task is a job).
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

- Spring `ZenBpmDeploymentAndStartIT` (module `start-it`, JPA + gruelbox outbox, `@DirtiesContext`,
  own container): `instanceAppearsOnlyAfterCommit` (inside the transaction the business-key search is
  empty; after the commit it finds the instance with state `active`),
  `noInstanceAfterRollback` (the transaction rolls back; after two outbox cycles nothing exists and no
  outbox entry remains), `aWorkflowNobodyStartedIsUnknownToBothProbes`,
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

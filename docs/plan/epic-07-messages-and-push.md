# E7 - Messages and the pushed aggregate

**Goal.** `correlateMessage`, `startWorkflowByMessage` and `aggregateChanged` work with the engine's
message semantics: no buffering, one active subscription per name and key, a 404 when nobody waits,
variables at the process scope, child instances as the scope of a task.

**Done when.** A waiting workflow continues on a correlation published before AND after its
subscription exists, a conditional gateway behind a pushed aggregate takes the new branch, and both
run on Quarkus as well.

Template: `Camunda8ProcessService.correlateMessage*`, `Camunda8MessageDeclarationTest`, the
`aggregateChanged` section of Camunda 8's README. Contract: `PhaseOperation.CORRELATE_MESSAGE`
(activation-scoped key), `AGGREGATE_CHANGED` (no key), `PhaseTwoRetryLater`.

---

## F7.1 Messages

### S7.1.1 `CORRELATE_MESSAGE`

- [ ] **Depends on:** E6.

**Instructions**

1. Phase one: the message name must be in `catchMessages` of a model of the module this application
   deployed (through `ZenBpmDeployedProcesses`); a name only in `startMessages` fails with the hint to
   use `startWorkflowByMessage`; a name nowhere fails naming the models; where the module deployed no
   process (an older definition still runs) stay silent. No engine call (a subscription may not exist
   yet, and the engine has no TTL to wait it out).
2. Phase two: `rest.publishMessage(scopedName, correlationKey = correlationId ?? aggregateId,
   variables = shared values + id variable)`; `ZenBpmErrors.nobodyWaits` -> throw
   `PhaseTwoRetryLater("no subscription yet ...", workflow-visibility-timeout)` naming module,
   process, aggregate, message and `vanillabp.outbox.block-after-attempts` (GAPS 5). Anything
   permanent rethrown; the rest repeated by the outbox.
3. Deduplication: the engine has none; the outbox key (with the activation) is the only net, which the
   README says. A correlation id is the correlation key, so the modeller's subscription expression has
   to read it (the injected default reads the aggregate id); document as on Camunda 8.

**Tests**: `ZenBpmMessageDeclarationTest` (three cases), `ZenBpmProcessServiceTest#nobodyWaitsIsRetryLater`,
Spring `ZenBpmMessageIT`: `correlateMessageResumesTheWaitingWorkflow`,
`aMessagePublishedBeforeTheSubscriptionIsRepeatedUntilItCorrelates` (start a workflow whose first
element is a 5-second timer before the catch; correlate at once; assert the workflow continues after
the timer), `aCorrelationIdSelectsTheWaitingOccurrence` (two receive tasks with modelled keys).

**Acceptance criteria**

- [ ] A 404 on publish leaves the outbox entry due after the visibility window, never blocks it at once.
- [ ] Phase one never calls the engine.

### S7.1.2 `START_WORKFLOW_BY_MESSAGE` and `message-start-lookup` (engine-gated)

- [ ] **Depends on:** S7.1.1. **Engine twin:** E13.4.

**Instructions**

1. Phase one: the message must be in `startMessages` of a deployed model; if `message-start-lookup`
   is `refuse` (default), fail with a guiding message naming GAPS 1, the setting `scan` and the engine
   change which will remove the limit - the workflow would start but never be found again.
2. Phase two: `rest.publishMessage(scopedName, null, shared values + id variable)`; 404 -> permanent
   (no start event deployed: the message names `deployment-failure`).
3. `scan` mode: `awarenessOfWorkflow` gains a third step for processes of the scope which have a
   message start event: page `GET /process-instances?bpmnProcessId=&state=active` (size 200) and
   compare `variables[idName]`; bounded by `message-start-lookup-max-instances` (default 1000, then
   BPMS_UNAVAILABLE with a WARN naming the bound); a completed workflow of this kind is UNKNOWN (only
   active pages are read, so it costs the at-least-once residual, which the wiki says).
4. Both modes log once per module at startup which processes are message-started and what applies.

**Tests**: `ZenBpmProcessServiceTest` for both modes; `ZenBpmMessageIT#startWorkflowByMessageIsRefusedByDefault`,
`#startWorkflowByMessageWithScanFindsTheWorkflow` (with `scan`).

**Acceptance criteria**

- [ ] The default refuses with a message naming the setting and the gap; `scan` works within the bound.
- [ ] The story is superseded by E13.4's twin, which replaces the scan by the business-key search.

## F7.2 The pushed aggregate

### S7.2.1 `AGGREGATE_CHANGED`, global and task-scoped

- [ ] **Depends on:** S7.1.1.

**Instructions**

1. Global (no task id): phase one nothing (the probe already located the workflow); phase two: find the
   instance key like the probe does (business key, then instance key), load the shared values,
   `rest.patchVariables(key, values)`. UNKNOWN -> a warned no-op (the core consumes the entry).
2. Task-scoped: `rest.job(taskId)` -> its `processInstanceKey` IS the scope the task runs in (a
   sub-process or multi-instance iteration is a child instance, decision 11's cache tells the parent);
   `patchVariables(thatKey, values)`; NOT the root instance additionally.
3. The engine evaluates conditions on its own variables, so a gateway behind the pushed values reads
   them; conditional EVENTS do not exist (no marker variable needed).

**Tests**: fake-server cases; Spring `ZenBpmAggregateChangedIT`: `aGlobalPushReachesTheGateway`
(a workflow waiting at a timer, then an exclusive gateway on a pushed boolean),
`aTaskScopedPushWritesTheChildInstance` (a task inside a sub-process; the sub-process' child instance
carries the value, the root does not).

**Acceptance criteria**

- [ ] A task-scoped push leaves the root instance's variables untouched.

## F7.3 Quarkus twins

### S7.3.1 Messages and push on Quarkus

- [ ] **Depends on:** S7.2.1.

`ZenBpmWorkflowLifecycleTest` gains `correlateMessageResumesTheWorkflow`, `aGlobalPushReachesTheGateway`,
`aTaskScopedPushReachesTheChildScope`.

**Acceptance criteria**

- [ ] The Quarkus report covers the correlation and push handlers.

# E7 - Messages and the pushed aggregate

**Goal.** `correlateMessage`, `startWorkflowByMessage` and `aggregateChanged` work with the engine's
message semantics: no buffering, one active subscription per name and key, a 404 when nobody waits,
variables at the process scope, child instances as the scope of a task.

**Done when.** A waiting workflow continues on a correlation published before AND after its
subscription exists, a conditional gateway behind a pushed aggregate takes the new branch, and both
run on Quarkus as well.

Template: `Camunda8ProcessService.correlateMessage*`, `Camunda8MessageDeclarationTest`, the
`aggregateChanged` section of Camunda 8's README. Contract: `PhaseOperation.CORRELATE_MESSAGE`
(activation-scoped key), `AGGREGATE_CHANGED` (no key), `PhaseTwoRetryLater`,
`PhaseTwoRequest.workflowId()` and `taskRecord()`, `ADAPTER-AUTHORS.md` sections 3.3 and 4.1.

---

## F7.1 Messages

### S7.1.1 `CORRELATE_MESSAGE`

- [ ] **Depends on:** E6.

**Instructions**

1. Phase one: the message name must be in `catchMessages` of a model of the module this application
   deployed (through `ZenBpmDeployedProcesses`); a name only in `startMessages` fails with the hint
   to use `startWorkflowByMessage`; a name nowhere fails naming the models; where the module
   deployed no process (an older definition still runs) stay silent. No engine call (a subscription
   may not exist yet, and the engine has no TTL to wait it out). This check stays the adapter's: the
   core's check behind `reportStartMessages` (platform decision 109) covers starts only.
2. Phase two: `rest.publishMessage(scopedName, correlationKey = correlationId ?? aggregateId,
   variables = shared values + id variable)`; `ZenBpmErrors.nobodyWaits` -> throw
   `PhaseTwoRetryLater("no subscription yet ...", workflow-visibility-timeout)` naming module,
   process, aggregate, message and `vanillabp.outbox.wait-for-visibility-at-most` (GAPS 5). A
   `PhaseTwoRetryLater` is not counted as an attempt; the outbox repeats it until that window (about
   four hours by default) has passed and then blocks the entry (platform decision 49). Throw it only
   while nothing reached the engine, which a `404` from publishing guarantees. Anything permanent
   rethrown; the rest repeated by the outbox.
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

- [ ] **Depends on:** S7.1.1, S9.1.1 (the start marker) and S9.3.1 (its handler, which reports the
  instance key). **Engine twin:** E13.4.

A workflow started by a message carries no business key: `POST /v1/messages` takes none, and it
answers "Message sent" without the key of an instance it started (GAPS 1). So phase two cannot
report the started workflow, as on Camunda 8, which reports nothing there either. The start marker
of decision 26 does it instead: it runs after the message start event, and its report writes the
instance key into the start row (`BpmsInitiatedStartContext.getNativeInstanceId()`). From then on
the core hands that key to every election about the workflow (decision 25).

**Instructions**

1. Phase one: the core already refused a message which starts no process of this process service
   (`reportStartMessages` of S4.3.1, platform decision 109). The adapter checks what the core
   cannot: the message is in `startMessages` of a model of the module this application deployed.
   Where `message-start-lookup` is `refuse`, fail with a guiding message naming GAPS 1 and the other
   two values.
2. Phase two: `rest.publishMessage(scopedName, null, shared values + id variable)`; no
   `reportStartedWorkflow`; 404 -> permanent (no start event deployed: the message names
   `deployment-failure`).
3. `message-start-lookup` has three values. The merged
   `ZenBpmAdapterConfiguration.MessageStartLookup` (`REFUSE`, `SCAN`, default `REFUSE`) gains
   `START_ROW` and makes it the default:
   - `start-row` (default): the start is allowed; the workflow is found through the start row the
     marker wrote, and nothing else.
   - `scan`: as `start-row`, and in addition `awarenessOfWorkflow` and the redispatch probe gain a
     third step for processes of the scope which have a message start event: page `GET
     /process-instances?bpmnProcessId=&state=active` (size 200) and compare `variables[idName]`;
     bounded by `message-start-lookup-max-instances` (default 1000, then `BPMS_UNAVAILABLE` with a
     WARN naming the bound). A completed workflow of this kind answers `UNKNOWN_TO_BPMS`, which the
     contract does not allow (`ADAPTER-AUTHORS.md` 4.1); that is why `scan` is an opt-in and goes
     away with E13.4.
   - `refuse`: no start by message at all.
4. What the start row cannot cover, named in GAPS 1 and the wiki:
   - an application without a delivery log writes no start row;
   - between publishing and the commit of the marker's report only the dispatching node's election
     cache knows the workflow, so a correlation or a push dispatched on another node, or after a
     restart, in that window finds nothing;
   - the redispatch probe (`awarenessOfWorkflowForRedispatch`) gets no key, and a business-key
     search never finds a message-started workflow: a start re-dispatched after a lost answer
     publishes the message again and may start a second instance. `scan` closes this for an active
     workflow.

   `vanillabp.delivery.workflow-start-retention` (default 30 days, `0` keeps rows for good) and
   `vanillabp.delivery.keep-workflow-start-while-aggregate-exists` decide how long the start row
   lives; the wiki names both.
5. Every mode logs once per module at startup which processes are message-started and what applies.

**Tests**: `ZenBpmProcessServiceTest` for the three modes; Spring
`ZenBpmMessageIT#aMessageStartedWorkflowIsFoundThroughItsStartRow` (start by message, then correlate
a message to the same aggregate: it reaches the workflow),
`#startWorkflowByMessageIsRefusedWithRefuse`, `#scanFindsTheWorkflowOfARedispatchedStart` (with
`scan`: a re-dispatched start publishes nothing twice while the first instance is active).

**Acceptance criteria**

- [ ] By default, a message-started workflow is found after its start marker ran.
- [ ] `refuse` refuses with a message naming the setting and the gap; `scan` works within the bound.
- [ ] E13.4's twin replaces the scan by the business-key search, and `scan` is removed then.

## F7.2 The pushed aggregate

### S7.2.1 `AGGREGATE_CHANGED`, global and task-scoped

- [ ] **Depends on:** S7.1.1.

**Instructions**

1. Global (no task id): phase one nothing (the probe already located the workflow); phase two:
   `PhaseTwoRequest.workflowId()` first - the instance key of the start row, where that row names
   this adapter (decision 25) - then the business-key search where it is null or the instance is not
   `active`; load the shared values, `rest.patchVariables(key, values)`. UNKNOWN -> a warned no-op
   (the core consumes the entry). Whether variables written to the root instance are seen by a
   gateway inside a child instance (a sub-process, a multi-instance body) is not verified: the IT
   proves it, or this story pushes into the child instances as Camunda 8 pushes into called ones.
2. Task-scoped: `rest.job(taskId)` -> its `processInstanceKey` IS the scope the task runs in (a
   sub-process or multi-instance iteration is a child instance, decision 11's cache tells the
   parent); `patchVariables(thatKey, values)`; NOT the root instance additionally.
   `PhaseTwoRequest.taskRecord()` is the task's own delivery row: where it says a handler holds the
   task right now, skip the push (the handler's outcome writes the values anyway); the job read
   stays, because only the job names the child instance.
3. The engine evaluates conditions on its own variables, so a gateway behind the pushed values reads
   them; conditional EVENTS do not exist (no marker variable needed).

**Tests**: fake-server cases; Spring `ZenBpmAggregateChangedIT`: `aGlobalPushReachesTheGateway` (a
workflow waiting at a timer, then an exclusive gateway on a pushed boolean),
`aGlobalPushReachesAGatewayInsideASubProcess`, `aTaskScopedPushWritesTheChildInstance` (a task
inside a sub-process; the sub-process' child instance carries the value, the root does not).

**Acceptance criteria**

- [ ] A task-scoped push leaves the root instance's variables untouched.

## F7.3 Quarkus twins

### S7.3.1 Messages and push on Quarkus

- [ ] **Depends on:** S7.2.1.

`ZenBpmWorkflowLifecycleTest` gains `correlateMessageResumesTheWorkflow`, `aGlobalPushReachesTheGateway`,
`aTaskScopedPushReachesTheChildScope`.

**Acceptance criteria**

- [ ] The Quarkus report covers the correlation and push handlers.

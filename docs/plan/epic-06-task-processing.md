# E6 - Task processing

**Goal.** Jobs of the stream run `@WorkflowTask` methods at least once with the full invocation
context, outcomes reach the engine (with the shared values visible to the model), asynchronous tasks
are completed and cancelled later, failures back off locally and end in incidents, workers exist for
renamed processes, and shutdown drains.

**Done when.** The task-matrix IT (service, send, business-rule job, gateway behind the task, BPMN
error, pending task) is green on Spring, its twin on Quarkus, and inbound idempotency and restart
delivery are proven.

Template: `Camunda8JobHandler`, `Camunda8CommandRetry`, `Camunda8Drain`, `Camunda8RetryBackoffResolver`,
`Camunda8DeclaredProcessWorkers` (decision 19 of that repository). Contract: `ADAPTER-AUTHORS.md`
sections 3.3 and 5, `TaskInvocationContext` javadoc.

---

## F6.1 Delivering and completing a task

### S6.1.1 `JobDispatcher` and `ZenBpmJobHandler` - the happy path

- [ ] **Depends on:** E5.

**Instructions**

1. `JobDispatcher` (one per adapter id): registered as the stream's consumer; per job type a
   `JobHandler`; every type is subscribed with `lock_duration_ms` = the resolved `job-timeout`
   (new key, four levels, default `PT5M`; conflicting task-level values of one job type end the boot
   naming both, as on Camunda 8) and `max_active_jobs` = the handler slots. A `WaitingJob` is queued
   for the executor; the queue is bounded by what the subscriptions allow, so nothing is dropped
   (decision 9). A type without a handler is the WARN of S4.3.3.
2. `ZenBpmLockRenewal` (per adapter id, on a timing thread): when a handler starts it extends the
   job's lock by `job-timeout` where less than half of it is left (a job which waited in the queue
   starts with a full lock), then every `job-timeout / 2` while the handler runs, through
   `rest.extendJobLock(key, clientId, jobTimeout)`. `lockLost` (`409`) stops the renewal and logs a
   WARN naming task, aggregate and that a second delivery may run concurrently; `502` and I/O are
   repeated within the remaining lock; the renewal ends with the handler.
3. `ZenBpmJobHandler(module, taskType, deployedProcesses, cache, rest, invoker, sharedValues, scoping,
   retry, drain, renewal)`:
   - `facts = cache.facts(job.instance_key)`; empty -> the instance is gone (cancelled meanwhile),
     log at INFO and return (do not fail the job);
   - `plainProcessId = scoping.plainProcessId(module, facts.scopedProcessId)`; unknown to the module
     -> WARN and return (another module's or another id's scope; decision 11);
   - `aggregateId = facts.businessKey ?? facts.variables[idName]`; both null -> WARN naming the
     instance, do NOT fail (an instance nobody started through VanillaBP), return;
   - build the `TaskInvocationContext`: `taskDefinition = scoping.plainTaskDefinition(module,
     plainProcessId, job.type)`, `workflowAggregateId`, `taskId = job.key`, `deliveryId = job.key`,
     `activationId = job.key` (until the stream carries the element instance key, GAPS 15),
     `processVersion = facts.version`, `adapterId`, `taskParameter(name) = job.input_variables[name]`
     (decoded JSON), `predatesDeployedVersion = facts.version < deployedProcesses.versionOf(...)`,
     `runInCurrentTransaction = false`, `multiInstances` from E9 (empty until then);
   - `drain.enter(job.key)`; `outcome = invoker.invokeWorkflowTask(module, plainProcessId, context)`;
   - COMPLETED: `values = invoker.syncedWorkflowAggregateValues(module, process, aggregateId, FULL)`
     plus the id variable; `rest.patchVariables(facts.key, values)`; `rest.completeJob(job.key,
     values)`; a `ZenBpmErrors.isGoneJob` on either -> WARN, return (at-least-once residual);
   - `drain.leave(job.key)`, `executor.releaseSlot()` in `finally`.
4. Commands back to the engine go through `ZenBpmCommandRetry`: repeat what `isUnavailable` says
   (5 attempts, 50 ms doubling, bounded by the remaining lock read from `lock_until`), stop at once
   while the module shuts down.

**Tests**

- `ZenBpmJobHandlerTest` (fake server + a `TestCollaborators` invoker): the context's fields, the
  PATCH-before-complete order, the id variable in both, the gone job tolerated, an instance outside
  the module skipped, a slot released on every path; `ZenBpmLockRenewalTest` (renewal at start only
  where less than half is left, then every half timeout, stop on `409`, repeat on `502`, stop with the
  handler); `ZenBpmJobTimeoutResolverTest` (four levels, conflicting values of one type refused).
- Spring `ZenBpmTaskProcessingIT` (module `task-it`, fixture `task-matrix.bpmn`: service task ->
  exclusive gateway on a shared boolean -> send task -> business rule task as job -> end):
  `happyPathRunsEveryTaskKind`, `gatewayAfterTaskSeesTheNewValues` (the handler flips the boolean,
  the gateway takes the branch), `aDeclaredTaskParameterIsBound` (an input mapping the model brings plus
  a `@TaskParam`), `theAggregateIdIsBoundFromTheBusinessKey`.

**Acceptance criteria**

- [ ] A gateway behind a `@WorkflowTask` decides on what the handler computed (the PATCH).
- [ ] `deliveryId` equals the job key and `activationId` equals the job key (pinned, so a later
  change to the element instance key is deliberate).

### S6.1.2 BPMN error and pending outcomes

- [ ] **Depends on:** S6.1.1.

**Instructions**

- BPMN_ERROR: `rest.patchVariables(...)` then `rest.failJob(job.key, scoping.scopedIdentifier(module,
  errorCode), values)`; the engine routes a boundary error event or an error event sub-process; an
  unmatched code becomes an incident, which the WARN says.
- COMPLETION_PENDING: the job is parked: `rest.extendJobLock(key, clientId, async-task-lock-renewal)`
  (new key, adapter level, default `PT1H`). When that lock lapses the engine delivers the job again,
  the core answers from the record with `COMPLETION_PENDING(openFor, maxAgeExceeded)`, and the handler
  parks it again - the renewal is driven by the engine's redelivery and needs no timer of the
  adapter's (Camunda 8's shape). Startup validation: the window has to stay below
  `vanillabp.delivery.retention` (else the boot ends naming both keys), and where the answered
  deadline shows the engine capped it (`JOB_MANAGER_MAX_LOCK_DURATION_MS`, 24 h by default) a WARN
  names that setting once. Where `maxAgeExceeded` and `async-task-max-age-action: incident` (adapter
  level, default `report`) the handler fails the job without a code so an incident names the
  aggregate and the age.

**Tests**: `ZenBpmTaskProcessingIT#aBpmnErrorRoutesTheBoundaryEvent`, `#anUnknownErrorCodeIsAnIncident`
(asserted through `GET .../incidents`), `#anAsynchronousTaskStaysOpen` (with
`async-task-lock-renewal: PT2S`: the job is redelivered about every 2 s, the handler runs once, the job
stays active and is parked again each time), `ZenBpmJobHandlerTest` for the incident action and the
capped-deadline WARN.

**Acceptance criteria**

- [ ] The error code reaches the engine scoped; the aggregate changes stay committed.

## F6.2 Failures and shutdown

### S6.2.1 Backoff as a lock extension, `max-redeliveries`, incident

- [ ] **Depends on:** S6.1.1.

**Instructions**

`ZenBpmLocalRetry` (per adapter id, bounded map `jobKey -> Attempt(count, notBefore)`): when the
handler throws (transaction rolled back), record the attempt, compute `backoff = retry-backoff *
2^(count-1)` capped at 5 minutes (resolved per module/workflow/task), and extend the job's lock by
exactly that backoff (decision 8): the engine hands the job out again when it lapses, to whichever
node has a slot. Nothing else is sent. Log at WARN with the exception type and message (an
`NullPointerException` used to print `null` in Camunda 8 - name the type). A `409` on that extension
means the lock was already lost; the attempt is counted anyway. At `count >= max-redeliveries`:
`rest.failJob(job.key, null, {})` so the engine raises an incident, message naming module, process,
aggregate, task and the attempts; remove the entry. A successful run removes the entry. Entries older
than one hour are swept by a timing thread.

**Tests**: `ZenBpmLocalRetryTest` (backoff arithmetic, cap, the extension sent per attempt, sweep);
`ZenBpmTaskProcessingIT#aThrowingHandlerEndsInAnIncidentAfterMaxRedeliveries` with
`max-redeliveries: 2` and `retry-backoff: PT1S` (a few seconds: the backoff IS the redelivery delay);
`#theBackoffDecidesWhenTheJobComesBack` (redelivery not before the backoff, soon after it).

**Acceptance criteria**

- [ ] A thrown handler never calls `fail` before `max-redeliveries`; the incident names the aggregate.

### S6.2.2 Drain and shutdown

- [ ] **Depends on:** S6.2.1.

**Instructions**

`ZenBpmDrain` per module (from `Camunda8Drain`): `stopWorkflowProcessing` unsubscribes the module's
types, sets the module's state to `stopping` (the retry and the command retry read it), waits
`shutdown-grace` for handlers inside the application, names what is still running (job key, task,
aggregate) and returns. While stopping, a handler outcome is still reported (the work is committed)
but a thrown handler is not backed off or failed. The factory's `close()` runs the drain for any
module which never stopped, with a WARN, and then closes the stream, which releases every lock of the
adapter's client at once (E13.1): the jobs still in the queue and the ones a cut-off handler held go
to another node immediately instead of after `job-timeout`. A `shutdown-grace` above 30 s warns at startup
that Spring's and Kubernetes' budgets have to be raised with it.

**Tests**: `ZenBpmDrainTest`, `ZenBpmShutdownDrainIT#aHandlerWithinTheGraceFinishes`,
`#aCutOffHandlerCostsNoFailure` (the job is still `active` afterwards, no incident),
`#anotherNodeGetsTheOpenJobsAtOnce` (a second application context receives the queued jobs of the
stopped one within a second, not after `job-timeout`).

**Acceptance criteria**

- [ ] No `fail` command leaves the adapter while a module shuts down.

## F6.3 Asynchronous tasks

### S6.3.1 `COMPLETE_TASK` and `CANCEL_TASK`

- [ ] **Depends on:** S6.1.2.

**Instructions**

- Phase one for both: `preCommitRegistrar().beforeCommit(aggregate, () -> ...)`: `rest.job(taskId)`
  must be `active` and its instance in the module's scope; empty -> `IllegalStateException` naming the
  task id and saying the task is gone; wrong scope -> the same message plus the scope. A GET is
  non-advancing.
- Phase two `COMPLETE_TASK`: load shared values (own transaction, `syncedWorkflowAggregateValues`),
  `patchVariables` then `completeJob`; `isGoneJob` -> WARN and return. `CANCEL_TASK`: `patchVariables`
  then `failJob(taskId, scopedErrorCode, values)`; gone -> WARN.
- `NumberFormatException` on the task id is permanent (`ZenBpmErrors`).

**Tests**: `ZenBpmProcessServiceTest` (fake server) for both phases and the gone job;
`ZenBpmTaskProcessingIT#completeTaskEndsTheDormantWorkflow`, `#cancelTaskRoutesTheBoundaryEvent`,
`#completingAGoneTaskIsAWarnedNoOp`; `ZenBpmPreCommitCheckTest` copied from Camunda 8's shape.

**Acceptance criteria**

- [ ] The pre-commit check runs right before the commit (the registrar's hook), not at the call.

## F6.4 Renamed processes

### S6.4.1 Workers for declared process ids

- [ ] **Depends on:** S6.1.1.

**Instructions**

In `startWorkflowProcessing`, ask `taskWiringOfProcessesNobodyDeployed(module)`; per declared id and
task definition, subscribe `scoping.scopedTaskDefinition(module, declaredId, taskDefinition)` where it
is not already subscribed (only `use-prefix` with per-process task definitions produces new types), with
a handler whose `plainProcessId` is the declared id. An id with an empty collection (methods wired by
element id) is named at startup with the two ways out (Camunda 8 decision 19's wording). Such a
handler cannot know user-task types, so it subscribes nothing for them; and it fetches no input
mappings (the model is not this application's), which the startup line says.

**Tests**: `ZenBpmDeclaredProcessWorkersTest` (which types per mode, the message);
`ZenBpmRenamedProcessIT` (deploy v1 under id A, start, redeploy the application declaring A as
secondary with model B, the old workflow's task is served).

**Acceptance criteria**

- [ ] A workflow of a renamed process finishes after the rename under `use-prefix`.

## F6.5 Idempotency and restarts on both platforms

### S6.5.1 Inbound idempotency, restart delivery, Quarkus twins

- [ ] **Depends on:** S6.3.1.

**Instructions**

- Spring `ZenBpmInboundIdempotencyIT`: `longHandlersKeepTheirLock` - with `job-timeout: PT2S` a
  handler blocking 6 s is not delivered a second time (the renewal holds the lock);
  `aLostLockIsNamed` - the test lets the renewal fail (a `ZenBpmLockRenewal` hook refusing to extend),
  the second delivery runs concurrently and the core names both, and after the first commits a third
  delivery is answered from the record (the leader-change residual, GAPS 2). A second case: a handler which completes but the completion command is made to
  fail once (fake by stopping the container briefly is too heavy - instead use a `ZenBpmCommandRetry`
  hook in tests to reject the first `complete`) -> the redelivery is skipped by the record and the
  completion sent.
- Spring `ZenBpmRestartDeliveryIT` and Quarkus `ZenBpmRestartDeliveryTest` (own application with a
  restart endpoint, Camunda 8's shape): a task delivered before the restart and completed after it is
  not run twice.
- `ZenBpmWorkflowLifecycleTest` gains `serviceTaskRunsAndGatewaySeesValues`, `bpmnErrorRoutesBoundary`,
  `asyncTaskIsCompletedLater`, `declaredTaskParametersAreBound`.

**Acceptance criteria**

- [ ] Both platforms prove delivery, outcome and idempotency; the Quarkus report covers
  `ZenBpmJobHandler`.

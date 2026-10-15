# E6 - Task processing

**Goal.** Jobs of the stream run `@WorkflowTask` methods at least once with the full invocation
context, outcomes reach the engine (with the shared values visible to the model), asynchronous tasks
are completed and cancelled later, failures are failed at the engine with retries and backoff and
end in incidents (decision 27), tasks the engine took away are reported as canceled, workers exist
for renamed processes, and shutdown drains.

**Done when.** The task-matrix IT (service, send, business-rule job, gateway behind the task, BPMN
error, pending task) is green on Spring, its twin on Quarkus, and inbound idempotency and restart
delivery are proven.

Template: `Camunda8JobHandler`, `Camunda8CommandRetry`, `Camunda8Drain`,
`Camunda8RetryBackoffResolver`, `Camunda8DeclaredProcessWorkers` (decision 19 of that repository),
`Camunda8OpenTaskProbe`. Contract: `ADAPTER-AUTHORS.md` sections 3.3, 3.4 and 5,
`TaskInvocationContext` javadoc.

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
   failure, drain, renewal)` (`failure` is the failing of S6.2.1):
   - `facts = cache.facts(job.instance_key)`; empty -> the instance is gone (cancelled meanwhile),
     log at INFO and return (do not fail the job);
   - `plainProcessId = scoping.plainProcessId(module, facts.scopedProcessId)`; unknown to the module
     -> WARN and return (another module's or another id's scope; decision 11);
   - `aggregateId = facts.businessKey ?? facts.variables[idName]`; both null -> WARN naming the
     instance, do NOT fail (an instance nobody started through VanillaBP), return;
   - build the `TaskInvocationContext`: `taskDefinition = scoping.plainTaskDefinition(module,
     plainProcessId, job.type)`, `bpmnElementId = job.element_id` (the core routes a delivery by
     both wiring keys, platform decision 67, so a method wired by element id needs it), `taskKind =
     TASK`, `workflowAggregateId`, `businessKey = facts.businessKey` (the core refuses a delivery
     whose key is not the aggregate id, platform decision 69, which is the honest answer for an
     instance somebody started past VanillaBP), `workflowId` = the root instance key of decision 28
     (resolved through the instance cache of S5.2.1, stopping at a call activity), `taskId =
     job.key`, `deliveryId = job.key`, `activationId = job.key` (until the stream carries the
     element instance key, GAPS 15), `processVersion = facts.version`, `adapterId`,
     `taskParameter(name) = job.input_variables[name]` (decoded JSON, numbers as the engine reported
     them, platform decision 55), `predatesDeployedVersion = facts.version <
     deployedProcesses.versionOf(...)`, `runInCurrentTransaction = false`, `multiInstances` from E9
     (empty until then);
   - `drain.enter(job.key)`; `outcome = invoker.invokeWorkflowTask(module, plainProcessId, context)`;
   - `DeliveryOfAnUnknownWorkflowException` (the aggregate the job names does not exist in this
     application, platform decision 99): fail the job at once with `retries: 0`, the exception's
     message as `message`, the delivery's `deliveryToken` and the stream's `clientId`, outside the
     retries of S6.2.1, and log a WARN without stack trace (decision 27). The incident then carries
     the core's explanation where an operator reads it. Do not raise the retries for it;
   - COMPLETED: `values = invoker.syncedWorkflowAggregateValues(module, process, aggregateId, FULL)`
     plus the id variable; `rest.patchVariables(facts.key, values)`; `rest.completeJob(job.key,
     values)`; a `ZenBpmErrors.isGoneJob` on either -> WARN, return (at-least-once residual);
   - `drain.leave(job.key)`, `executor.releaseSlot()` in `finally`.
4. Commands back to the engine go through `ZenBpmCommandRetry`: repeat what `isUnavailable` says
   (5 attempts, 50 ms doubling, bounded by the remaining lock read from `lock_until`), stop at once
   while the module shuts down.

*Note from S2.4.2, 2026-10-15.* The executor counts the slots itself, so instruction 3's
`executor.releaseSlot()` in `finally` does not exist: a slot is taken by `executor.tryExecute(handler)`
and given back by the executor once the handler returned. `tryExecute` never waits; it answers
`false` where every slot is taken, and throws `RejectedExecutionException` once the executor shuts
down. So the dispatcher keeps the jobs which wait for a slot, and nothing queues in the executor.
`executor.onSlotFreed(listener)` tells the dispatcher on the handler thread, right after the slot
came back, that it can hand over the next job; the listener may call `tryExecute` directly, as
`ZenBpmExecutorTest#aDispatcherToldAboutFreedSlotsRunsEveryJob` does. It is not called once the
executor shuts down. The dispatcher puts a job `tryExecute` refused back BEFORE it looks for a free
slot once more, and tries again where it finds one: a slot which comes free meanwhile is then seen
either by that look or by its listener. Giving up before the job is back can leave it beside a free
slot with nobody to hand it over (`#aJobPutBackAfterARefusalIsNotStranded`, against the test's
`WaitingJobs`, which shows the rule in a few lines). `execute(handler)` waits for a slot and is
refused on a handler thread and on a timing thread. After `shutdown()` the timing threads keep
running until the last handler returned, so the lock renewal of a running handler still has them.
An exception which escapes a handler is logged at ERROR by the executor, and its thread serves the
next one; an `Error` is logged and passed on.

*Found by a review of S2.4.2, 2026-10-15.* With several job types up to types x slots jobs wait in
the dispatcher, and where handlers run long a waiting job's lock can lapse before its handler
starts: the renewal of instruction 2 then gets `409`, and the engine may have handed the job out
again. Decision 9 accepts that second delivery. Still, the dispatcher should not start a handler
for a job whose `lock_until` has passed, but drop it and leave it to the engine to hand it out
again; the retry budget of instruction 4 reads the same `lock_until`.

*Found by S2.2.2, 2026-10-14.* `isGoneJob` reads calls under `/v1/jobs/{key}` only, so it does not
catch the `PATCH` of instruction 3. Read in the engine's code at `bdd795a3` (not measured):
`UpdateProcessInstanceVariables` answers `409` for an instance which is neither active nor failed,
and `404` for one the engine does not hold. Decide what the handler does with either before it
completes the job; by the README's table both are merely repeatable.

*Open point (not decided).* Instruction 3 answers a job whose instance carries no aggregate NAME
(neither business key nor id variable) with a WARN and no command: the job's lock lapses and the
engine hands it out again, for as long as the instance lives. Camunda 8 fails such a job instead
(`Camunda8FetchVariables.missingAggregateId` throws, the job loses a retry and ends in an incident).
Decide before this story is built which of the two the adapter does; with decision 27 failing it
costs one request and makes the instance visible as an incident.

**Tests**

- `ZenBpmJobHandlerTest` (fake server + a `TestCollaborators` invoker): the context's fields
  (`bpmnElementId`, `taskKind`, `businessKey`, the root `workflowId` for a job of a sub-process),
  the unknown workflow failed with `retries: 0` and the core's message, the PATCH-before-complete
  order, the id variable in both, the gone job tolerated, an instance outside the module skipped, a
  slot released on every path; `ZenBpmLockRenewalTest` (renewal at start only where less than half
  is left, then every half timeout, stop on `409`, repeat on `502`, stop with the handler);
  `ZenBpmJobTimeoutResolverTest` (four levels, conflicting values of one type refused).
- Spring `ZenBpmTaskProcessingIT` (module `task-it`, fixture `task-matrix.bpmn`: service task ->
  exclusive gateway on a shared boolean -> send task -> business rule task as job -> end):
  `happyPathRunsEveryTaskKind`, `gatewayAfterTaskSeesTheNewValues` (the handler flips the boolean,
  the gateway takes the branch), `aDeclaredTaskParameterIsBound` (an input mapping the model brings plus
  a `@TaskParam`), `theAggregateIdIsBoundFromTheBusinessKey`.

**Acceptance criteria**

- [ ] A gateway behind a `@WorkflowTask` decides on what the handler computed (the PATCH).
- [ ] `deliveryId` equals the job key and `activationId` equals the job key (pinned, so a later
  change to the element instance key is deliberate).
- [ ] A job inside an embedded sub-process reports the root instance key as `workflowId`; a job of a
  called process reports the called instance's key (decision 28).
- [ ] A job of a workflow this application does not own ends in an incident at once, whose message
  is the core's.

### S6.1.2 BPMN error and pending outcomes

- [ ] **Depends on:** S6.1.1.

**Instructions**

- BPMN_ERROR: `rest.patchVariables(...)` then `rest.failJob(job.key,
  scoping.scopedIdentifier(module, errorCode), values)` with the delivery's `deliveryToken` and the
  stream's `clientId` (the failure releases the stream's lock, and a repeat after a lost answer
  changes nothing); the engine routes a boundary error event or an error event sub-process; an
  unmatched code becomes an incident, which the WARN says. Retries are neither read nor spent by a
  failure with an error code.
- COMPLETION_PENDING: the job is parked: `rest.extendJobLock(key, clientId,
  async-task-lock-renewal)` (new key, adapter level, default `PT1H`). When that lock lapses the
  engine delivers the job again, the core answers from the record with `COMPLETION_PENDING(openFor,
  maxAgeExceeded)`, and the handler parks it again - the renewal is driven by the engine's
  redelivery and needs no timer of the adapter's (Camunda 8's shape). Startup validation: the window
  has to stay below `vanillabp.delivery.retention` (else the boot ends naming both keys), and where
  the answered deadline shows the engine capped it (`JOB_MANAGER_MAX_LOCK_DURATION_MS`, 24 h by
  default) a WARN names that setting once. Where `maxAgeExceeded` and `async-task-max-age-action:
  incident` (adapter level, default `report`) the handler fails the job without a code and with
  `retries: 0`, so an incident names the aggregate and the age at once. A failure without a code
  spends one attempt (decision 27): without `retries: 0` a job with retries left would only come
  back after its backoff. The `message` names the aggregate and the age; the `deliveryToken` is the
  delivery's.

**Tests**: `ZenBpmTaskProcessingIT#aBpmnErrorRoutesTheBoundaryEvent`, `#anUnknownErrorCodeIsAnIncident`
(asserted through `GET .../incidents`), `#anAsynchronousTaskStaysOpen` (with
`async-task-lock-renewal: PT2S`: the job is redelivered about every 2 s, the handler runs once, the job
stays active and is parked again each time), `ZenBpmJobHandlerTest` for the incident action and the
capped-deadline WARN.

**Acceptance criteria**

- [ ] The error code reaches the engine scoped; the aggregate changes stay committed.
- [ ] The incident action fails the job with `retries: 0`, whatever retries the job has left.

### S6.1.3 Report the tasks the engine no longer has

- [ ] **Depends on:** S6.1.1.

The engine cannot say per element what it took away: a boundary event, an instance cancelled over
REST or a terminate end event removes jobs, and nobody tells the application. The contract's answer
is `ADAPTER-AUTHORS.md` section 3.4: after every delivery the adapter lets the core ask a probe
about the tasks it still believes open in the same workflow, and the core reports the gone ones as
`@TaskEvent CANCELED` (platform decision 74).

**Instructions**

1. `ZenBpmOpenTaskProbe` (per adapter id) implements `OpenTaskProbe`: the abstract
   `stillExists(workflowId, taskId)` and the three-argument `stillExists(workflowId, taskId,
   taskDefinition)`, which the core calls. One round trip per question: `rest.job(taskId)`.
   - 404, or state `completed` or `terminated` -> `GONE`;
   - state `active` or `failed` -> `STILL_THERE` (a failed job waits for its incident);
   - the job belongs to another workflow: its instance's root (decision 28) is not `workflowId` ->
     `CANNOT_SAY`;
   - `ZenBpmErrors.isUnavailable`, a malformed id or anything else -> `CANNOT_SAY`. Never turn a
     failure into `GONE`: that would cancel every open task of an instance at the engine's next
     hiccup.
   - a task definition of a user task -> `CANNOT_SAY` while the poller of S8.1.1 reports user-task
     cancellations: `ADAPTER-AUTHORS.md` 3.4 says "Do not call it from an adapter whose BPMS cancels
     each element by itself. The application would hear about the same task twice." The third
     argument lets the service tasks of the same process keep their derived cancellation.
2. `ZenBpmJobHandler` calls `invoker.reportTasksTheBpmsNoLongerHas(module, plainProcessId, context,
   probe)` after the outcome of a delivery reached the engine (completion, BPMN error, parked
   pending task), on the handler's thread, never for a delivery the core refused. The core never
   probes the task of the wake-up itself, never a record another adapter wrote, and nothing for a
   process without an asynchronous task. A probe which throws counts as `CANNOT_SAY`.
3. The two platform settings decide how much this costs and are named in the wiki:
   `vanillabp.delivery.check-open-tasks-on-delivery` (default `true`, also per task) and
   `vanillabp.delivery.max-open-tasks-checked` (default 10, `0` switches it off). The adapter reads
   neither; the core does.
4. The GET is non-advancing: asking about a job changes nothing at the engine.

**Tests**: `ZenBpmOpenTaskProbeTest` (fake server: each state, 404, 502, a job of another workflow,
a job of a sub-process of the same workflow is `STILL_THERE`, a user-task definition is
`CANNOT_SAY`); Spring `ZenBpmTaskProcessingIT#aTaskTakenAwayByABoundaryEventIsReportedCanceled` (two
parallel service tasks, a timer boundary event on one; the second task's delivery after the timer
fired reports the first one `CANCELED` once), `#aProbeOutageCancelsNothing` (the probe's GET made to
fail: no `CANCELED`).

**Acceptance criteria**

- [ ] A service task the engine took away reaches the application as `@TaskEvent CANCELED` at the
  next delivery of the same workflow, once.
- [ ] An unavailable engine never produces a `CANCELED`.
- [ ] A user task is never reported twice (the poller of S8.1.1 and this probe).

## F6.2 Failures and shutdown

### S6.2.1 A failed handler is failed at the engine, with retries and backoff

- [ ] **Depends on:** S6.1.1, and the engine's job retries (E13.3, in the pinned engine since
  `bdd795a3`).

This story is the adapter twin of E13.3 and replaces the local retry the plan had before the engine
counted retries (decision 27, which supersedes decision 8).

**Instructions**

1. When the handler throws (the transaction rolled back): `rest.failJob(job.key, ...)` without an
   error code, with
   - no `retries`: the engine reads an absent value as "one less than now", which is all the adapter
     wants (Camunda 8 sends the number itself); the log names `WaitingJob.retries` minus one;
   - `retryBackoff` = the resolved `retry-backoff` (four levels, as Camunda 8's
     `Camunda8RetryBackoffResolver`); where no level configures it the request leaves it out, so the
     task definition's `retryBackoff` or the engine's `jobs.defaultRetryBackoff` applies. The engine
     caps it at `jobs.maxRetryBackoff`;
   - `message` = the exception's type and message (a `NullPointerException` used to print `null` in
     Camunda 8 - name the type);
   - `deliveryToken` = the delivery's `WaitingJob.delivery_token`, so a failure repeated after its
     answer was lost counts once;
   - `clientId` = the stream's client id. Without it the stream's lock stands and the job comes back
     only when the lock lapses, whatever `retryBackoff` says.

   Log at WARN with the exception type, the attempt (`WaitingJob.attempt`) and the retries left.
2. While retries remain, the engine keeps the job `active` and hands it out again after the backoff,
   to whichever node has a slot; at zero it fails the job and raises an incident naming the attempts
   and the last message. The adapter sends nothing else and keeps no state per job.
3. `409` on the failure (`ZenBpmErrors.isGoneJob`): the job is gone or another delivery superseded
   this one; the newer delivery decides. WARN, nothing else.
4. `ZenBpmLocalRetry` and the configuration key `max-redeliveries` go: the merged
   `ZenBpmAdapterConfiguration` loses the key, its validation and its tests, and the README's table
   loses the row. `retry-backoff` stays, with the meaning above; its startup WARN about the
   adapter's 5-minute cap goes, because the engine caps it now.
5. How many retries a job has is the model's: `zenbpm:taskDefinition retries`, else the engine's
   default, which is 1. *Open point (not decided):* with that default a handler which throws once
   ends in an incident at once, where the plan's local retry allowed 10 attempts. Either the wiki
   says so and leaves it to the modeller, or the adapter writes a default `retries` into a task
   definition which has none while it rewrites the model (decision 4 never overwrites what the
   modeller wrote), resolved from a new key. Decide before this story is built.

*Found by a review of S2.2.2, 2026-10-14.* Should this story (or S6.1.2) send a `deliveryToken`
with `failJob`, a failure of a delivery the engine already handed out again is answered `409`
(`ErrDeliverySuperseded`), which `ZenBpmErrors.isGoneJob` reads as a gone job: the handler accepts
it, and the newer delivery decides. Without the token, a failure repeated after its answer was lost
counts twice (`JobFailByKey` in the engine's `pkg/bpmn/jobs_api.go`).

**Tests**: `ZenBpmJobFailureTest` (fake server: the request's fields, retries counted down from
the job's, `retryBackoff` left out where no level sets it, `409` tolerated);
`ZenBpmTaskProcessingIT#aThrowingHandlerEndsInAnIncidentAfterItsRetries` (a model with `retries="2"`
and `retry-backoff: PT1S`: two deliveries, then an incident naming the attempts and the message),
`#theBackoffDecidesWhenTheJobComesBack` (redelivery not before the backoff, soon after it, so the
`clientId` released the lock), `#aFailureRepeatedAfterALostAnswerCountsOnce` (the same request twice
with the same `deliveryToken`).

**Acceptance criteria**

- [ ] A thrown handler spends exactly one attempt per delivery; the incident names the aggregate.
- [ ] No local retry state and no `max-redeliveries` key remain in the adapter.

### S6.2.2 Drain and shutdown

- [ ] **Depends on:** S6.2.1.

**Instructions**

`ZenBpmDrain` per module (from `Camunda8Drain`): `stopWorkflowProcessing` unsubscribes the module's
types, sets the module's state to `stopping` (the failure of a thrown handler and the command retry
read it), waits `shutdown-grace` for handlers inside the application, names what is still running
(job key, task, aggregate) and returns. While stopping, a handler outcome is still reported (the
work is committed) but a thrown handler is not failed at the engine: a failure would spend one of
the job's retries (decision 27) for a cause which is the shutdown, not the job. The job's lock is
released when the stream closes, so the job goes to another node with its retries untouched. The
factory's `close()` runs the drain for any module which never stopped, with a WARN, and then closes
the stream, which releases every lock of the adapter's client at once (E13.1): the jobs still in the
queue and the ones a cut-off handler held go to another node immediately instead of after
`job-timeout`. A `shutdown-grace` above 30 s warns at startup that Spring's and Kubernetes' budgets
have to be raised with it.

**Tests**: `ZenBpmDrainTest`, `ZenBpmShutdownDrainIT#aHandlerWithinTheGraceFinishes`,
`#aCutOffHandlerCostsNoFailure` (the job is still `active` afterwards, no incident),
`#anotherNodeGetsTheOpenJobsAtOnce` (a second application context receives the queued jobs of the
stopped one within a second, not after `job-timeout`).

**Acceptance criteria**

- [ ] No `fail` command leaves the adapter while a module shuts down; the job's retries are
  unchanged afterwards.

## F6.3 Asynchronous tasks

### S6.3.1 `COMPLETE_TASK` and `CANCEL_TASK`

- [ ] **Depends on:** S6.1.2.

**Instructions**

- Phase one for both: `preCommitRegistrar().beforeCommit(aggregate, () -> ...)`: `rest.job(taskId)`
  must be `active` and its instance in the module's scope; empty, `completed` or `terminated` ->
  `io.vanillabp.spi.process.TaskNotFoundException` naming the task id (`ADAPTER-AUTHORS.md` section
  4: the core throws the same type where its own probe finds no BPMS holding the task, so an
  application catches one type); wrong scope -> `IllegalStateException` naming the task id and the
  scope (the task exists, it is another module's). A GET is non-advancing.
- Phase two `COMPLETE_TASK`: load shared values (own transaction, `syncedWorkflowAggregateValues`),
  `patchVariables` then `completeJob`; `isGoneJob` -> WARN and return. `CANCEL_TASK`: `patchVariables`
  then `failJob(taskId, scopedErrorCode, values)`; gone -> WARN.
- `NumberFormatException` on the task id is permanent (`ZenBpmErrors`).

**Tests**: `ZenBpmProcessServiceTest` (fake server) for both phases, the gone job as
`TaskNotFoundException` and the foreign scope;
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
- `ZenBpmWorkflowLifecycleTest` gains `serviceTaskRunsAndGatewaySeesValues`,
  `bpmnErrorRoutesBoundary`, `asyncTaskIsCompletedLater`, `declaredTaskParametersAreBound`, and the
  Quarkus twin of S6.1.3, `aTaskTakenAwayIsReportedCanceled`.

**Acceptance criteria**

- [ ] Both platforms prove delivery, outcome and idempotency; the Quarkus report covers
  `ZenBpmJobHandler` and `ZenBpmOpenTaskProbe`.

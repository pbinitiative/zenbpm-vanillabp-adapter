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

- [x] **Depends on:** E5.

**Instructions**

1. `JobDispatcher` (one per adapter id): registered as the stream's consumer; per job type a
   `JobHandler`; every type is subscribed with `lock_duration_ms` = the resolved `job-timeout`
   (new key, four levels, default `PT5M`; conflicting task-level values of one job type end the boot
   naming both, as on Camunda 8) and `max_active_jobs` = the handler slots. A `WaitingJob` is queued
   for the executor; the queue is bounded by what the subscriptions allow, so nothing is dropped
   (decision 9). A type without a handler is the WARN of S4.3.3.
   *Note from S4.3.3, 2026-10-17.* `startWorkflowProcessing` subscribes the job types of a module's
   claimed processes (`ZenBpmProcessingContext#jobTypes()`, no user task and no task served
   elsewhere) with the engine's defaults, `Duration.ZERO` and `0`, and hands every job to a private
   method of `ZenBpmDeploymentService` which logs it at WARN and leaves it to its lock. Replace that
   consumer with the `JobDispatcher`, and the defaults with the resolved `job-timeout` and the handler
   slots; the hook of the client factory, the stop and the shared types stay as they are.
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

*Note from S5.2.1, 2026-10-17.* The instance cache is `ZenBpmInstanceCache` in the root package,
built and held per adapter id by the client factory (`factory.instances()`), so the handler needs
no cache of its own. `facts(key, idVariableName)` keeps the id variable once the instance has it
(an entry without it is read again); the facts hold no state. `workflowIdOf(key)` answers the
workflow id of decision 28 as an `OptionalLong`, empty where an instance on the way is unknown; it
walks through the kept facts, so a job of a known workflow costs no request. Its walk was pinned
against the engine: a job in an embedded sub-process names the child instance, and the walk ends at
the instance a call activity started (`ZenBpmProbesIT#theWorkflowIdOfATaskIsItsRootUpToACallActivity`).
A start writes the id variable into the root instance; whether the engine answers it for a child
instance as well was not checked, so check it against the engine before reading the aggregate id
from the facts of the job's own instance.

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

*Note from S5.1.1, 2026-10-17.* A start reports the version of its instance as
`String.valueOf(version)` of the create answer (an `int`, written in decimal digits), so
`processVersion` is written the same way from `facts.version`; otherwise the start row and the
deliveries of one workflow name two versions (`ADAPTER-AUTHORS.md` 2.2).

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

*Fit check, 2026-10-17, decided by the Implementor.* Checked against `main` (`0cecfaa`),
`ADAPTER-AUTHORS.md` (sections 3.3, 3.4 and 5), the platform's code (`TaskInvocationContext`,
`WorkflowTaskInvoker`, `WorkflowTaskOutcome`, `DeliveryOfAnUnknownWorkflowException`,
`MigrationProcessService#invokeWorkflowTask`, `JdbcTaskDeliveryStore`, `TaskDeliveryLogReader` of
`test-utils`), Camunda 8's `Camunda8JobHandler`, `Camunda8CommandRetry`,
`Camunda8JobTimeoutResolver`, `Camunda8ListenerJobs`, `Camunda8DeploymentService` and its Quarkus
`Camunda8JobTimeoutOverlayTest`, and the engine at the pin `bdd795a3` (source exported with
`git archive`, image run by `ZenBpmJobHandlingIT`). What did not fit, and what was decided:

1. **Names and places.** The plan's `JobDispatcher` is `wiring/ZenBpmJobDispatcher`, as every class
   of the repository carries the prefix; `ZenBpmLockRenewal`, `ZenBpmJobHandler` and
   `ZenBpmJobTimeoutResolver` are in `wiring` (the architecture's layout), `ZenBpmCommandRetry` in
   `client`, where Camunda 8 keeps its own.
2. **One handler per workflow module, routed by the instance's process.** A job names its instance
   and its type, and a type may be served by two processes, or two modules, under
   `name-clash-avoidance: none`; the name of the id variable belongs to the process. So the
   dispatcher reads the facts of the job's instance on the handler thread, picking the id variable
   by the instance's process in the same read (`ZenBpmInstanceCache#facts(long, Function)`), and
   hands the job to the module which claims that process and serves the job's type in it; a job of
   a process no module serves that way is logged (WARN, pointing at `use-prefix`) and left to its
   lock. The handler's constructor takes the adapter id, the client factory, the collaborators and
   the module's processing context instead of the plan's list. The `failure` of S6.2.1 and the
   `drain` of S6.2.2 are not built: the handler has one method per outcome where they plug in, and
   the smallest module state the command retry needs, `ZenBpmJobHandler#stopping()`, which the
   module's stop sets (brief, question 3). The grace wait stays with S6.2.2.
3. **The executor's real API** (note from S2.4.2) is used as the note says: a job waits in the
   dispatcher, `tryExecute` takes a slot or refuses, a refused job is put back before the next look
   for a free slot, and `onSlotFreed` hands over the next one. A job whose lock lapsed while it
   waited is not started (INFO); the lock's end is the moment of receipt plus the job-timeout
   (`ReceivedJob#lockEnd`), on the application's clock alone (decision 36, changed after the
   reviews, see below).
4. **The lock renewal** is scheduled on the timing threads, but each extension is sent from a
   virtual thread of its own: a REST call may take `request-timeout`, and the executor allows nothing
   longer than a hand-over on its timing threads. A lock is extended whenever half of the
   job-timeout is left, so at once for a job which waited that long; each extension names its
   delivery and is counted from its sending (decision 36, changed after the reviews, see below).
   `502` and I/O are repeated after 50 ms,
   doubling up to an eighth of the job-timeout, while the lock lasts; `409` ends it with a WARN
   naming job, task, aggregate and the possible second delivery; a job the engine no longer holds
   (`404`) ends it quietly; anything else ends it with a WARN.
5. **The child instance** (note from S5.2.1). Measured (`ZenBpmJobHandlingIT`): an instance of an
   embedded sub-process and an instance a call activity started both carry the business key and the
   id variable of their workflow. So the handler reads the aggregate in the job's own instance and
   reads the workflow's root only where that names neither (a workflow the engine started and
   VanillaBP named later). `workflowId` is the root of decision 28, pinned for a job of a
   sub-process by `ZenBpmJobHandlerTest` and end to end by `ZenBpmTaskProcessingIT` through the
   platform's delivery log; for a job of a called process by `ZenBpmProbesIT`.
6. **The aggregate is named by the id variable first, the business key is reported beside it.** The
   plan wrote `facts.businessKey ?? facts.variables[idName]`. Then the core's check of platform
   decision 69 could never fire, because the two values reported would always be the same one.
   Decision 25 has every start carry both, Camunda 8 reads the variable, and an instance started
   past VanillaBP with nothing but a business key is still served by it
   (`#theAggregateIdIsBoundFromTheBusinessKey`).
7. **The `PATCH` before `complete`** (note from S2.2.2, brief question 2): measured `409` for a
   completed and a cancelled workflow and `404` for a key nobody holds; read in the code that the
   engine looks the instance up on a follower before it updates it on the leader. So a `404` is
   repeated within the lock and a `409` ends the delivery with a WARN; the read of the job's
   instance is treated the same way: decision 34 (new).
8. **Where the values are written.** Measured: a sub-process holds a copy of the variables taken
   when it started, and what is written into it is gone once it ended. The plan's single `PATCH` of
   `facts.key` would leave a gateway behind a sub-process deciding on stale values. The handler
   writes them into every instance from the job's own up to the workflow's root before it
   completes: decision 35 (new), pinned by `ZenBpmJobHandlerTest` and by
   `ZenBpmTaskProcessingIT#aTaskInASubProcessNamesTheRootAndTheGatewayBehindItSeesTheValues`.
9. **A job whose instance names no aggregate** (the open point, brief question 1): failed at once
   with `retries: 0`, a message naming the instance, the missing business key and the id variable,
   the delivery's `deliveryToken` and the stream's `clientId`, WARN without stack trace: decision 33
   (new). A delivery the core refuses with `DeliveryOfAnUnknownWorkflowException` is failed the same
   way with the core's message. `ZenBpmRestClient#failJob(long, JobFailure)` carries the message,
   the retries, the token and the client id; `retryBackoff` is left to S6.2.1, which needs it.
10. **BPMN_ERROR, COMPLETION_PENDING and a thrown handler** (brief question 3) are logged at WARN
   and left to the job's lock, pinned by
   `ZenBpmJobHandlerTest#aBpmnErrorAndAnOpenTaskAreLeftToTheirLocks` and
   `#aThrowingHandlerIsLeftToItsLock`; left for S6.1.2 and S6.2.1.
11. **`job-timeout`.** Validated by the core configuration (a negative duration and `PT0S` end the
   start, at the adapter level and below it), bound on both platforms at the four levels
   (`jobTimeoutFor` of both overlays, `ZenBpmDeploymentService#jobTimeoutOf`), and handed to the
   deployment service as a `ZenBpmJobTimeoutResolver`. Two tasks of one job type with different
   job-timeouts end the start while the processes are wired, before anything reaches the engine,
   naming both values and both task-level keys; Camunda 8 checks this only when its workers start.
   Two workflow modules of one adapter id sharing a job type are held to the same rule, because
   they share its subscription (added after the reviews, see below). Under `use-prefix` a job type
   belongs to one process, so the conflict needs `name-clash-avoidance: none`. Proven on Spring
   Boot (`ZenBpmJobTimeoutBootTest`, and the resolution in `ZenBpmTaskProcessingIT`) and on Quarkus
   (`ZenBpmJobTimeoutTest`, `ZenBpmOverlayResolutionTest#jobTimeoutThroughTheFourLevels`), brief
   question 5.
12. **Where the Spring IT lives** (brief question 4): not a module `task-it`, but the `spring-boot`
   module's `it` package with its application in `it/task`, as F5.3 placed its own; one module per
   IT would repeat the POM and the coverage wiring for nothing. The fixture `task-it/task-matrix.bpmn`
   has the plan's shape plus an embedded sub-process with a service task and a second gateway
   behind it, which pins decision 35 and the workflow id of a sub-process's job end to end.
13. **What the earlier ITs assumed.** The Quarkus prod-mode case waited for the WARN line of a job
   nothing handled; it now waits for the call of `E2eWorkflow#prepare` (`introspect/prepared/{id}`)
   and for the completion of that instance's own job (`introspect/jobs/{key}` now names each job's
   state), and the instance is `COMPLETED` instead of `ACTIVE` (note under S6.5.1). The Spring
   `Ordering` process of `ZenBpmDeploymentAndStartIT` got a timer of one hour behind its task, so its
   instance stays active once the task is handled. `ZenBpmDeploymentIT`'s application now names its
   id variable and holds no shipment, so the job of the instance it starts over REST is failed as a
   workflow the application does not own, and that WARN is what the test waits for.
14. **README.** The status names the job-timeout of a subscription and what a job runs; the table
   "Which phase-two failures are repeated" is unchanged, because the handler reads the update of the
   variables itself and no phase-two operation sends one yet.

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

- [x] A gateway behind a `@WorkflowTask` decides on what the handler computed (the PATCH).
  `ZenBpmTaskProcessingIT#gatewayAfterTaskSeesTheNewValues`: the workflow starts with `express`
  false, the handler of `Weigh` makes the parcel express, and the gateway behind it takes the branch
  to `NotifyCourier`, which runs. `#aTaskInASubProcessNamesTheRootAndTheGatewayBehindItSeesTheValues`:
  the handler of `Pack` inside the embedded sub-process sets `packed`, and the gateway behind the
  sub-process ends in `Packed` (decision 35; with the values written into the job's own instance
  only, this case fails). `ZenBpmJobHandlerTest#aCompletedTaskWritesTheValuesUpToTheWorkflowBeforeItCompletes`
  pins the order: `PATCH` of the sub-process, `PATCH` of the root, then the completion, each with
  the shared values and the id.
- [x] `deliveryId` equals the job key and `activationId` equals the job key (pinned, so a later
  change to the element instance key is deliberate).
  `ZenBpmJobHandlerTest#theContextNamesWhatTheJobAndItsInstanceSay` asserts `getTaskId()`,
  `getDeliveryId()` and `getActivationId()` are the job key `42`, beside every other field of the
  context.
- [x] A job inside an embedded sub-process reports the root instance key as `workflowId`; a job of a
  called process reports the called instance's key (decision 28).
  End to end: the platform's delivery log of the booted Spring Boot application records the
  instance key of the workflow for the delivery of `Pack`, whose job names the sub-process's
  instance (`ZenBpmTaskProcessingIT#aTaskInASubProcessNamesTheRootAndTheGatewayBehindItSeesTheValues`);
  `ZenBpmJobHandlerTest#theContextNamesWhatTheJobAndItsInstanceSay` with the fake engine. The
  walk itself ends at the instance a call activity started (`ZenBpmProbesIT#theWorkflowIdOfATaskIsItsRootUpToACallActivity`,
  `ZenBpmInstanceCacheTest#theWayUpNamesEveryInstanceOnIt`), and the handler takes the workflow id
  from that walk.
- [x] A job of a workflow this application does not own ends in an incident at once, whose message
  is the core's.
  `ZenBpmTaskProcessingIT#aJobOfAWorkflowThisApplicationDoesNotOwnEndsInAnIncident`: an instance
  started over REST with the business key of a parcel nobody stored gets an incident on `Weigh`
  whose message holds the core's "This application was given a task of a workflow it does not own."
  and the aggregate id, its job is `FAILED` after the first delivery, and the delivery log holds no
  record of it. `ZenBpmDeploymentIT#aStartedModuleSubscribesItsJobTypeAndStopsFirst` sees the same
  failure for its own application. `ZenBpmJobHandlerTest#aJobOfAWorkflowNobodyOwnsHereIsFailedWithTheCoresMessage`
  pins the request: the core's message, `retries: 0`, the delivery token and the stream's client id,
  and a WARN without stack trace.

**Implemented 2026-10-17.** Core: `wiring/ZenBpmJobDispatcher`, `wiring/ZenBpmJobHandler`,
`wiring/ZenBpmLockRenewal`, `wiring/ZenBpmJobTimeoutResolver`, `client/ZenBpmCommandRetry`,
`client/api/JobFailure`; `ZenBpmRestClient#failJob(long, JobFailure)` and the decoding of a job's
input variables (`ReceivedJob#inputVariables`, `ReceivedJob#lockEnd`);
`ZenBpmErrors#variablesFoundNoInstance` and `#variablesFoundTheInstanceEnded`;
`ZenBpmInstanceCache#facts(long, Function)` and `#instancesUpToTheWorkflow`;
`ZenBpmProcessingContext#recordJobTimeout` and `#jobTimeouts`; `job-timeout` in
`ZenBpmAdapterConfiguration`; `ZenBpmDeploymentService` records the job-timeouts while wiring,
subscribes with them and the slot count, registers the module's handler with the dispatcher, and
answers `jobTimeoutOf`. Spring Boot and Quarkus: `job-timeout` at the four levels and the resolver
handed to the deployment service. Decisions 33, 34 and 35 (new); the README's status. No
`ZenBpmJobTimeoutResolverTest`: the resolver is an interface, and the resolution it stands for is
tested in both platforms' `VanillaBpZenBpmPropertiesTest` and their boot tests.

New tests: `ZenBpmJobHandlerTest` (18), `ZenBpmJobDispatcherTest` (10), `ZenBpmLockRenewalTest` (11),
`ZenBpmCommandRetryTest` (8), `ReceivedJobTest` (3), `ZenBpmJobHandlingIT` (3, pinned engine),
cases in `ZenBpmAdapterConfigurationTest`, `ZenBpmRestClientTest`, `ZenBpmErrorsTest`,
`ZenBpmInstanceCacheTest`, `ZenBpmDeploymentWiringTest` and `ZenBpmWorkflowProcessingTest`; Spring
Boot `ZenBpmTaskProcessingIT` (7: `happyPathRunsEveryTaskKind`, `gatewayAfterTaskSeesTheNewValues`,
`aTaskInASubProcessNamesTheRootAndTheGatewayBehindItSeesTheValues`, `aDeclaredTaskParameterIsBound`,
`theAggregateIdIsBoundFromTheBusinessKey`, `aJobOfAWorkflowThisApplicationDoesNotOwnEndsInAnIncident`,
`theJobTimeoutOfATaskComesFromItsMostSpecificLevel`) with the application `it/task` and
`task-it/task-matrix.bpmn`, `ZenBpmJobTimeoutBootTest` (1) with the application `it/timeouts`, the
job-timeout cases of `VanillaBpZenBpmPropertiesTest`; Quarkus `ZenBpmJobTimeoutTest` (1),
`ZenBpmOverlayResolutionTest#jobTimeoutThroughTheFourLevels`, the job-timeout cases of the runtime's
`VanillaBpZenBpmPropertiesTest`, and `ZenBpmWorkflowLifecycleTest#aStartCreatesTheInstanceAfterTheCommit`
now waiting for the handler.

Seen to fail, each restored afterwards (19 of 19 mutants, against green unmutated runs): a job whose
lock lapsed while it waited started; a refused job forgotten instead of put back; a job of a type no
module subscribes queued; a job served by a process which does not serve its type; a stopped module
which keeps repeating its commands; the failure of a job naming no aggregate without `retries: 0`;
the core's refusal of an unknown workflow handled like a thrown handler; the values written into
the job's own instance only (also against the engine: fails
`ZenBpmTaskProcessingIT#aTaskInASubProcessNamesTheRootAndTheGatewayBehindItSeesTheValues`); a `404`
of the update not repeated; a `409` of the update not read as an ended workflow; the command retry
repeating while the module shuts down, and ignoring the lock; the renewal extending a full lock at
once, going on after a lost lock, and reporting after the handler ended; two job-timeouts of one
type accepted; a job-timeout of zero accepted; the job types subscribed with the engine's defaults.

`TZ=Etc/UTC mvn clean install` on OpenJDK 25.0.2 builds green with every integration test run and
none skipped (engine test support 12 unit and 3 integration tests, core 837 unit and 73 integration
tests, Spring Boot 11 unit and 13 integration tests, smoke test 35, Quarkus runtime 10, Quarkus
deployment 48, Quarkus integration tests 3, coverage gate 37). The gate measures 100 % of 20,769
instructions for Spring Boot and 100 % of 20,643 for Quarkus, and `bin/check-deploy-safety.sh`
passes. No flake was hit.

**Answers to two reviews of S6.1.1, 2026-10-17** (one by Claude, approve, 5 findings; one by
Copilot, request changes, 2 findings, its first the same as Claude 5). Every claim was checked in
the code of the branch and in the engine's source at `bdd795a3`; the delivery fence was measured
against the pinned image.

Valid and fixed:

- **The lock renewal did not name its delivery** (Copilot 1, Major; Claude 5, Nit). Where a lock
  lapsed and the engine handed the job to the same client again, the earlier handler's renewal
  extended the later delivery's lock and never learned that a second delivery may run. The engine
  takes the delivery on an extension (`JobExtendLockRequest.delivery_token`, `deliveryToken` of
  `POST /v1/jobs/{key}/extend-lock`). `ZenBpmRestClient#extendJobLock` gained an overload with the
  token (`0` sends none), and every extension of the renewal names the delivery it belongs to
  (decision 36). `ZenBpmJobLockIT#anExtensionOfALapsedDeliveryLeavesTheNextOneAlone` measures the
  engine: the earlier delivery's token is answered `409`, read as a lost lock, and the later one's
  extends; `ZenBpmRestClientTest#aLockExtendedForOneDeliveryNamesIt` and the body asserted in
  `ZenBpmLockRenewalTest#aFullLockIsExtendedOnceHalfOfItIsLeft` pin the request.
- **An engine clock behind the application's shortened every lock** (Claude 2, Minor). The lock's
  end was the earlier of `lock_until`, on the engine's clock, and the moment of receipt plus the
  job-timeout; the renewal took the earlier of the answered deadline and its own count as well. So a
  drift made the renewal extend more often, without any pause once the drift reached the
  job-timeout, from which on the dispatcher also dropped every job before its handler started. The
  lock is now counted on the application's clock alone, as the engine's description of `lock_until`
  recommends where the clocks cannot be trusted: from the receipt, and for an extension from its
  sending (decision 36). The engine's moment is only compared, and where it is earlier by more than
  a second a WARN says once per adapter id that the engine's clock runs behind or that the
  job-timeout is above `jobManager.maxLockDurationMs`, naming the key to lower. Tests:
  `ReceivedJobTest#theLockIsCountedFromTheReceipt`,
  `ZenBpmJobDispatcherTest#aJobFromAnEngineClockBehindIsStarted`,
  `ZenBpmLockRenewalTest#anEngineClockBehindIsNamedOnceAndChangesNothing`,
  `#aLockAsCountedRaisesNoWarn` and `#theGrantedLockIsCountedFromTheSending`.
- **Two modules sharing a job type with different job-timeouts were refused late and without a key**
  (Claude 1, Minor). The check of fit-check item 11 covered the tasks of one module; two modules of
  one adapter id share a job type under `name-clash-avoidance: none`, and its subscription, so the
  second module's processing start failed with the job stream's message. The deployment service now
  keeps the job-timeout of every job type its modules wired and refuses a different one of another
  module while wiring, naming both modules, processes, tasks and values, both task-level keys and
  `use-prefix` as the other way out
  (`ZenBpmDeploymentWiringTest#twoModulesSharingAJobTypeNeedTheSameJobTimeout`).
- **A stop during the pause before a repeated command did not stop it** (Copilot 2, Minor):
  `ZenBpmCommandRetry` read the module's state before the pause only. It reads it after the pause
  as well (`ZenBpmCommandRetryTest#aShutdownDuringThePauseEndsTheRepetition`).
- **Javadoc of `E2eIntrospection`** (Claude 3, Nit) restored for `aggregate`, written for
  `prepared`, corrected for `jobs`; **the README's status paragraph** (Claude 4, Nit) rewrapped.

Seen to fail, each restored afterwards (9 of 9 mutants, against green unmutated runs): an extension
without the delivery's token; the lock's end back to the earlier of both moments; an extension
counted from the engine's answer; no look at the module's state after the pause; the cross-module
check never refusing; the WARN about a shorter lock said every time, never, and without its
tolerance; a token of `0` sent.

`TZ=Etc/UTC mvn clean install` on OpenJDK 25.0.2 builds green after the fixes with every
integration test run and none skipped (engine test support 12 unit and 3 integration tests, core
843 unit and 74 integration tests, Spring Boot 11 unit and 13 integration tests, smoke test 35,
Quarkus runtime 10, Quarkus deployment 48, Quarkus integration tests 3, coverage gate 37). The gate
measures 100 % of 21,030 instructions on Spring Boot and 100 % of 20,904 on Quarkus, and
`bin/check-deploy-safety.sh` passes (13 files on a green build, nothing after a red gate).

**S6.1.1 done 2026-10-17**, merged to `main` as `89388e1` (pull request #42, after two reviews and
CodeRabbit's review, which left no comment). On that commit (GitHub keeps run logs for 90 days, so
what they showed is written here as well):

- `Checks`, run
  [39067637049](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/actions/runs/39067637049):
  green on both JDKs. `bin/check-engine-copies.sh` reported both copies as the engine's files of
  `bdd795a3...`. `ZenBpmTaskProcessingIT` ran 7 tests, `ZenBpmJobHandlingIT` 3, `ZenBpmJobLockIT` 4,
  `ZenBpmJobTimeoutBootTest` 1, `ZenBpmJobHandlerTest` 18, `ZenBpmJobDispatcherTest` 11,
  `ZenBpmLockRenewalTest` 13, `ZenBpmCommandRetryTest` 9, `ReceivedJobTest` 3,
  `ZenBpmDeploymentWiringTest` 16, Quarkus `ZenBpmJobTimeoutTest` 1 and
  `ZenBpmWorkflowLifecycleTest` 3, none skipped (engine test support 12 unit and 3 integration
  tests, core 843 unit and 74 integration tests, Spring Boot 11 unit and 13 integration tests, smoke
  test 35, Quarkus runtime 10, Quarkus deployment 48, Quarkus integration tests 3, coverage gate 37).
  The gate measured 100 % on both platforms: of 21,030 instructions for Spring Boot and 20,904 for
  Quarkus on JDK 25, of 21,022 and 20,896 on JDK 21.
- `Publish snapshots`, run
  [39067636930](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/actions/runs/39067636930):
  green. The core snapshot it published to GitHub Packages, `2.0.0-20261017.203945-38`, holds the
  story's new main classes (`ZenBpmJobDispatcher`, `ZenBpmJobHandler`, `ZenBpmLockRenewal`,
  `ZenBpmJobTimeoutResolver`, `ZenBpmCommandRetry`, `JobFailure`) and no test class,
  fake, fixture, BPMN or DMN file; `quarkus/integration-tests`, the smoke test, the engine test
  support and the coverage modules skipped deployment. Both report pages
  (`spring-boot-report/index.html`, `quarkus-report/index.html`) answer HTTP 200 and show 0 of
  21,030 (Spring Boot) and 0 of 20,904 (Quarkus) instructions missed.

### S6.1.2 BPMN error and pending outcomes

- [x] **Depends on:** S6.1.1.

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

- [x] The error code reaches the engine scoped; the aggregate changes stay committed.
  `ZenBpmTaskProcessingIT#aBpmnErrorRoutesTheBoundaryEvent`: the handler of `Inspect` raises
  `damaged`, which the model carries as `deploy-it__damaged`; the error boundary event `Damaged`
  catches it, the gateway behind it ends in `Refunded` on the value the handler set, the job is
  `TERMINATED`, the claim is inspected and refundable, and the delivery log records `BPMN_ERROR`.
  With the code sent unscoped the boundary event does not catch it (mutant).
  `#anUnknownErrorCodeIsAnIncident`: an uncaught `lost` ends in an incident on `Inspect` naming
  `deploy-it__lost`, the job is `FAILED`, and the handler's work is committed.
  `ZenBpmJobHandlerTest#aBpmnErrorIsSentScopedWithTheValuesAfterTheyWereWritten` pins the requests:
  the updates of the sub-process and the root, then the failure with `shop__out-of-stock`, the
  values, the delivery token and the client id, and no `retries`.
- [x] The incident action fails the job with `retries: 0`, whatever retries the job has left.
  `ZenBpmJobHandlerTest#anOverdueTaskWithTheIncidentActionIsFailedWithoutRetries`: with
  `async-task-max-age-action: incident` the delivery whose outcome says the maximum age passed is
  failed with `retries: 0`, no code, the message naming the task, the aggregate, the age and the
  keys, the delivery token and the client id, and is not parked; the one before it, under the age,
  is parked. A task over the age under `report` is parked (`#aTaskLeftOpenIsParkedForTheWindow`).
  End to end, `ZenBpmTaskProcessingIT#anAsynchronousTaskStaysOpen` sees a task left open come back
  at least twice within 15 s under a window of two seconds while its handler ran once and the job
  stays active.

*Fit check, 2026-10-17, decided by the Implementor.* Checked against `main` (`116d701`),
`ADAPTER-AUTHORS.md` (sections 3.3, 3.4 and 5), the platform's code as the adapter resolves it
(`WorkflowTaskOutcome`, `MigrationAdapterProperties#resolvedDeliveryRetention`), Camunda 8's
`Camunda8JobHandler` (its BPMN_ERROR and COMPLETION_PENDING branches, `failAsOverdue`) and
`Camunda8AdapterConfiguration#validateAsyncTaskLockRenewal`, and the engine at the pin `bdd795a3`
(source exported with `git archive`: `JobFailByKey`, `prepareBoundaryErrorTransition` and
`failJobWithIncident` in `pkg/bpmn/jobs_api.go` and `pkg/bpmn/engine_error_event.go`,
`jobServer.extendLock` in `internal/cluster/jobmanager/server.go`, the descriptions of
`/jobs/{jobKey}/fail` and `/jobs/{jobKey}/extend-lock` in `openapi/api.yaml`; image run by
`ZenBpmTaskProcessingIT`). What did not fit, and what was decided:

1. **The values of a BPMN error** (brief question 1). Read at the pin: with an error code the engine
   hands the failure's variables to the catching event. An error boundary event propagates them
   into the instance which holds it, through its output mapping or all of them where it has none
   (`PropagateMappedOutputsOrAll`); an error event sub-process starts with them; where nothing
   catches the code they are only kept as the job's output variables. So the handler writes the
   values exactly as before a completion, into every instance from the job's own up to the root
   with decision 34's handling of `404` and `409` (decision 35), and then sends the same values with
   the failure, which adds the output mapping of a catching event a modeller may rely on (Camunda
   8's shape). No new decision. Measured end to end: the gateway behind the error boundary event
   of `#aBpmnErrorRoutesTheBoundaryEvent` decides on what the handler computed.
2. **An unmatched error code** (brief question 2). Read: `failJobWithIncident` fails the job and
   raises an incident whose message is the failure's `message`, `: ` and the code; the retries are
   neither read nor spent. Measured: the incident of `#anUnknownErrorCodeIsAnIncident` reads exactly
   `BPMN error 'lost' of task 'inspect' (workflow aggregate '…') is caught by no error boundary
   event and no error event sub-process, its code: deploy-it__lost`, and the job is `FAILED`. The
   engine behaves as the plan says, so there is no GAPS entry. Its answer to the failure is the same
   whether an event caught the code or not, so the handler reads the job once afterwards and logs a
   WARN where it is `failed`; a node which lags behind may still answer `active`, and then only the
   incident says it. The engine uses the failure's `message` for that incident alone, which is why
   the message says that nothing caught the error.
3. **The capped deadline** (brief question 3). The answer of `extend-lock` carries the new
   deadline, `now` plus the smaller of the duration asked for and `jobManager.maxLockDurationMs`.
   So the parking compares it as `ZenBpmLockRenewal` compares a running handler's, with the same
   tolerance of a second, and logs a WARN once per adapter id naming `async-task-lock-renewal` and
   `JOB_MANAGER_MAX_LOCK_DURATION_MS` (and, as for the running handler, an engine clock behind).
   No WARN at startup.
4. **The parking and the renewal of S6.1.1.** The engine extends a lock from the moment a request
   reaches it, so an extension of the running handler's renewal which arrives after the parking
   would cut the window down to the `job-timeout`. `ZenBpmLockRenewal.Renewal#park` ends the
   renewal, waits for an extension under way, and then extends by the window through the
   delivery's command retry; the handler returns right after, which frees its slot. A lost lock or
   a gone job at the parking is quiet (the task was completed meanwhile, or the engine hands the
   job out again, which parks it then); any other refusal leaves the job to its lock with the WARN
   of S6.1.1.
5. **Startup validation** (brief question 4). `ZenBpmAdapterConfiguration#validate` takes the
   platform's `vanillabp.delivery.retention` (`MigrationAdapterProperties#resolvedDeliveryRetention`),
   handed in by `ZenBpmStartupValidation#registryOf` on both platforms. An `async-task-lock-renewal`
   of `PT0S`, a negative one, one not shorter than the retention, and an `async-task-max-age-action`
   which names no action are value defects, which end the start even of an adapter id which may
   boot degraded. Both are checked while the application starts on Quarkus as well, where the
   adapter reads every key from the runtime configuration. `async-task-max-age-action` is bound as
   text and read by the adapter, as `worker-threads` is, so both platforms name the key and the
   values in the adapter's words instead of the binder's. Both keys exist at the adapter level only.
6. **The incident action** fails with `retries: 0`, no error code, a message adapted from Camunda 8
   which names the task, the aggregate, the process, the module, the age, `vanillabp.delivery.max-task-age`
   and the key to set back, the delivery's token and the stream's client id. A task over the age
   under `report` is parked as before, and the core's line says it.
7. **Where the tests live** (brief question 8). `ZenBpmTaskProcessingIT` gained the process
   `TaskOutcomes` (`task-it/task-outcomes.bpmn`, with its diagram) with the aggregate `Claim`; the
   application sets `async-task-lock-renewal: PT2S`, which nothing else of the class meets. The
   redeliveries of the parked job are counted by the job's `deliveryToken`, which the engine raises
   with every delivery, read past the adapter's client. The Quarkus twins belong to S6.5.1 (brief
   question 9); on Quarkus this story has the binding and the validation
   (`ZenBpmAsyncTaskKeysTest`).
8. `ZenBpmJobHandlerTest#aBpmnErrorAndAnOpenTaskAreLeftToTheirLocks` is replaced by the tests of
   both outcomes; a thrown handler stays logged and left to its lock until S6.2.1
   (`#aThrowingHandlerIsLeftToItsLock`).

**Implemented 2026-10-17.** Core: the BPMN error, the parking and the incident action in
`wiring/ZenBpmJobHandler`; `ZenBpmLockRenewal.Renewal#park` and the WARN about a lowered parking;
`async-task-lock-renewal`, `async-task-max-age-action` (`AsyncTaskMaxAgeAction`) and the check
against the delivery retention in `ZenBpmAdapterConfiguration` (`validate` with the retention),
`ZenBpmStartupValidation#registryOf` with the retention; `JobFailure#withErrorCode(String, String)`.
Spring Boot and Quarkus: both keys at the adapter level and the platform's retention handed to the
validation. The README's status, the key table and the handler's outline in
`docs/architecture/01-architecture.md`, the BPMN error row of `docs/analysis/02-spi-requirements-mapping.md`.

New tests: in `ZenBpmJobHandlerTest` `#aBpmnErrorIsSentScopedWithTheValuesAfterTheyWereWritten`,
`#aBpmnErrorNothingCatchesIsNamed`, `#aBpmnErrorWhoseJobCannotBeReadIsDone`,
`#aBpmnErrorOfAGoneJobOrAnEndedWorkflowIsWarned`, `#aRefusedBpmnErrorLeavesTheJobToItsLock`,
`#aTaskLeftOpenIsParkedForTheWindow`, `#aTaskLeftOpenWhoseLockIsLostIsQuiet`,
`#aRefusedParkingLeavesTheJobToItsLock`, `#anOverdueTaskWithTheIncidentActionIsFailedWithoutRetries`;
in `ZenBpmLockRenewalTest` `#aParkedJobIsExtendedOnceByTheWindow`,
`#aParkingWaitsForTheExtensionUnderWay`, `#anExtensionDueBeforeTheParkingSendsNothingAfterIt`,
`#aParkingTheEngineLoweredIsNamedOnce`, `#aParkingAsCountedRaisesNoWarn`, `#aRefusedParkingIsPassedOn`;
`ZenBpmAdapterConfigurationTest#theAsyncTaskLockRenewalStaysBelowTheDeliveryRetention` and three
defect cases; `ZenBpmStartupValidationTest#theDeliveryRetentionReachesTheValidation`; the bindings in
both platforms' `VanillaBpZenBpmPropertiesTest`; the smoke test's
`ZenBpmStartupValidationBootTest#theKeysOfTasksLeftOpenAreValidatedAgainstThePlatform`; Quarkus
`ZenBpmAsyncTaskKeysTest`; Spring Boot `ZenBpmTaskProcessingIT#aBpmnErrorRoutesTheBoundaryEvent`,
`#anUnknownErrorCodeIsAnIncident` and `#anAsynchronousTaskStaysOpen` with the process
`TaskOutcomes` of `task-it/task-outcomes.bpmn`.

Seen to fail, each restored afterwards (22 of 22 mutants, against green unmutated runs): the error
code sent unscoped (also against the engine: the boundary event does not catch it, and
`#anUnknownErrorCodeIsAnIncident` reads another code), the values not written before a BPMN error,
a BPMN error without the delivery's token and client id, the WARN of an uncaught error said always,
a gone job at a BPMN error not tolerated, another refusal of a BPMN error swallowed, the task left
open not parked (also against the engine: `#anAsynchronousTaskStaysOpen` sees no redelivery within
30 s), the incident action never taken, and taken under `report`, a lost lock at the parking not
tolerated, the parking not ending the renewal, not waiting for the extension under way, an
extension which was due before the parking sent after it, the WARN of a lowered parking never, every
time and without its tolerance, a renewal equal to the retention accepted, the retention not handed
to the validation, a renewal of zero accepted, an action which names none accepted.

`TZ=Etc/UTC mvn clean install` on OpenJDK 25.0.2 builds green with every integration test run and
none skipped (engine test support 12 unit and 3 integration tests, core 862 unit and 74 integration
tests, Spring Boot 11 unit and 16 integration tests, smoke test 36, Quarkus runtime 10, Quarkus
deployment 49, Quarkus integration tests 3, coverage gate 37). The gate measures 100 % of 21,758
instructions for Spring Boot and 100 % of 21,624 for Quarkus, and `bin/check-deploy-safety.sh`
passes. No flake was hit.

### S6.1.3 Report the tasks the engine no longer has

- [x] **Depends on:** S6.1.1.

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

- [x] A service task the engine took away reaches the application as `@TaskEvent CANCELED` at the
  next delivery of the same workflow, once.
  `ZenBpmTaskProcessingIT#aTaskTakenAwayByABoundaryEventIsReportedCanceled`: the interrupting timer
  boundary event of `Hold` (`task-it/task-taken-away.bpmn`) terminates its job after two seconds;
  at a following delivery of `Check`, the other task left open of the workflow, the method of
  `hold` is called with `CANCELED`, and after two more deliveries of `Check` it was called so once.
  With the handler never handing the core a probe, the cancellation does not come (mutant).
- [x] An unavailable engine never produces a `CANCELED`.
  `ZenBpmProbeOutageIT#aProbeOutageCancelsNothing`: behind a proxy which answers every read of a job
  with `502`, the same workflow's hold is terminated, the check is delivered twice more, the probe's
  read of the hold's job was refused, and the hold is never reported cancelled. With a failure read
  as gone it is (mutant). `ZenBpmOpenTaskProbeTest#whatIsNoAnswerCannotSay` pins `502`, `500`, a job
  of another workflow and an instance the cache cannot place as `CANNOT_SAY`.
- [x] A user task is never reported twice (the poller of S8.1.1 and this probe).
  The probe answers `CANNOT_SAY` without a request for every task definition its process does not
  serve by a job of the job stream, user tasks among them, and for a question without a definition
  (`ZenBpmOpenTaskProbeTest#whatIsNotAJobOfThisProcessCannotSay`); the handler's probe is built
  from the process's job types, which hold no user task
  (`ZenBpmJobHandlerTest#theOtherOpenTasksAreLookedAtOnceTheOutcomeReachedTheEngine`). The poller
  of S8.1.1 does not exist yet.

*Fit check, 2026-10-17, decided by the Implementor.* Checked against `main` with S6.1.2 on the
branch, `ADAPTER-AUTHORS.md` section 3.4, the platform's SPI as the adapter resolves it (the jar
of `vanillabp-adapter-spi` 2.0.0-SNAPSHOT: `OpenTaskProbe#stillExists` with two arguments, abstract,
and with three, the default the core calls; `TaskExistence`;
`WorkflowTaskInvoker#reportTasksTheBpmsNoLongerHas(String, String, TaskInvocationContext,
OpenTaskProbe)`), Camunda 8's `Camunda8OpenTaskProbe` and its call in `Camunda8JobHandler`, and the
engine at the pin `bdd795a3` (`handleBoundaryTimer` in `pkg/bpmn/timer.go`, the `Job` resource of
`openapi/api.yaml`). What did not fit, and what was decided:

1. **One probe per claimed process, not per adapter id.** The rule of brief question 5 needs the
   task definitions a process serves by jobs, which are the module's, so the handler of a module
   builds one `ZenBpmOpenTaskProbe` per claimed process at its start, from the process's job types
   (`WiredProcess#jobTypes`, which holds no user task) named as the application knows them
   (`plainTaskDefinition`, the definition the core recorded). The probe holds the adapter id's REST
   client and instance cache only; it costs no client of its own.
2. **A user task, and anything else no job of the process serves** (brief question 5): answered
   `CANNOT_SAY` without a request, and so is a question without a task definition (the abstract
   two-argument method, which the core does not call). Pinned by
   `ZenBpmOpenTaskProbeTest#whatIsNotAJobOfThisProcessCannotSay`; S8.1.1's note 4 holds from now on.
3. **The job's workflow.** The probe compares the root of the job's instance (decision 28, through
   `ZenBpmInstanceCache#workflowIdOf`) with the workflow the core names; another one, and an
   instance the cache cannot place, is `CANNOT_SAY`. A job of a sub-process of the same workflow
   is answered (`#aJobOfASubProcessBelongsToTheWorkflow`).
4. **A `404` cannot say**, unlike instruction 1, which reads it as gone. A read is answered by a
   follower which may lag behind (GAPS 20), a job taken away stays readable as `terminated`, and
   the engine keeps the jobs of a workflow while it runs. The story first read a `404` as gone, as
   the plan says; the answers to the two reviews below say why that changed. Every other failure,
   the engine's `502` and `500` among them, is `CANNOT_SAY` as well.
5. **When the core is asked** (instruction 2). After a completion the engine took, a BPMN error it
   took and a parked task; not after a delivery the handler refused (no aggregate, a workflow
   nobody owns here), a thrown handler, a gone job, an ended workflow, a lost lock at the parking,
   nor the incident action (Camunda 8 does not either). A look which throws is logged at DEBUG and
   costs the delivery nothing (`ZenBpmJobHandlerTest#theOtherOpenTasksAreLookedAtOnceTheOutcomeReachedTheEngine`,
   `#nothingIsLookedAtWhereNoOutcomeReachedTheEngine`, `#aFailingLookCostsTheDeliveryNothing`).
6. **The timer boundary event** (brief question 7). Read at the pin: an interrupting timer boundary
   event sets the active jobs of its activity to `terminated`. Measured: the hold of
   `#aTaskTakenAwayByABoundaryEventIsReportedCanceled` is `terminated` once its timer of two seconds
   fired. The IT models the plan's two parallel tasks as two tasks left open (`@TaskId`), because
   the core asks only about a task whose record is open; the wake-up is the redelivery of the
   second task every `async-task-lock-renewal`, Camunda 8's shape (`Camunda8OtherOpenTasksIT`).
   The aggregate `Reservation` is `@DynamicUpdate`: the two branches' handlers save it side by side,
   which the platform warns about at startup, and each writes an attribute of its own.
7. **The probe outage** (brief question 6) has an IT class of its own, `ZenBpmProbeOutageIT`, with
   the same application and fixture, whose REST address is a proxy of the test,
   `JobReadsRefusingProxy`, which answers every `GET /v1/jobs/{key}` with `502` and passes
   everything else on; the job stream goes to the engine directly. No switch in main code. The test
   waits until the hold is terminated, then for two more deliveries of the check and for the proxy
   to have refused the read of the hold's job, and finds the hold never reported cancelled.
8. Instructions 3 and 4 hold as written: the adapter reads neither platform setting, and the probe
   sends a read only.

**Implemented 2026-10-18.** Core: `wiring/ZenBpmOpenTaskProbe`; `ZenBpmJobHandler` builds one per
claimed process and hands it to `reportTasksTheBpmsNoLongerHas` once an outcome reached the engine.
GAPS 20's adapter behaviour names the probe; the README's status and the `OpenTaskProbe` row of
`docs/analysis/02-spi-requirements-mapping.md`.

New tests: `ZenBpmOpenTaskProbeTest` (8); in `ZenBpmJobHandlerTest`
`#theOtherOpenTasksAreLookedAtOnceTheOutcomeReachedTheEngine`,
`#nothingIsLookedAtWhereNoOutcomeReachedTheEngine` and `#aFailingLookCostsTheDeliveryNothing`;
Spring Boot `ZenBpmTaskProcessingIT#aTaskTakenAwayByABoundaryEventIsReportedCanceled` with the
process `TaskTakenAway` of `task-it/task-taken-away.bpmn`, and `ZenBpmProbeOutageIT` (1) with the
proxy `JobReadsRefusingProxy`. The Quarkus twin, `ZenBpmWorkflowLifecycleTest#aTaskTakenAwayIsReportedCanceled`,
belongs to S6.5.1.

Seen to fail, each restored afterwards (14 of 14 mutants, against green unmutated runs): the probe
asking about a task its process does not serve by a job, and about one without a definition;
answering for a job of another workflow, and for one whose instance the cache cannot place; a
failure read as gone (also against the engine: `ZenBpmProbeOutageIT` sees the hold cancelled); a
failed job gone; a terminated job still there; a `404` that cannot say; the handler never handing
the core the probe (also against the engine), handing it after every outcome, and after a completion
which found the job gone; a failing look costing the delivery.

`TZ=Etc/UTC mvn clean install` on OpenJDK 25.0.2 builds green with every integration test run and
none skipped (engine test support 12 unit and 3 integration tests, core 873 unit and 74 integration
tests, Spring Boot 11 unit and 18 integration tests, smoke test 36, Quarkus runtime 10, Quarkus
deployment 49, Quarkus integration tests 3, coverage gate 37). The gate measures 100 % of 21,980
instructions for Spring Boot and 100 % of 21,846 for Quarkus, and `bin/check-deploy-safety.sh`
passes. No flake was hit.

**Answers to two reviews of S6.1.2 and S6.1.3, 2026-10-18** (one by Claude, request changes, 3
findings; one by Copilot, request changes, 1 finding, the same as Claude 1). Every claim was
checked in the code of the branch and in the engine's source at `bdd795a3`. Both reviewers
measured the first one against the pinned engine, and the fix was measured there too.

Valid and fixed:

- **A parked job kept its slot of the engine's cap** (Claude 1, Blocker; Copilot 1, Major). The
  engine counts every job whose lock has not lapsed against its client's cap of active jobs of the
  type (`capacityLocked` in `internal/cluster/jobmanager/server.go`). `extendLock` only moves the
  lock's end, and the cap was the number of handler slots (decision 9). So as many tasks left open
  of one type as there are slots stopped every further job of that type. After a lapse the engine
  hands out the oldest job first, which is the parked one, parked again. With one slot, the
  reviewers saw a second job never arrive in 25 s. `ZenBpmJobStream#parked` keeps the parked jobs
  of a type, each with the end of its lock on the application's clock (decision 36), which
  `Renewal#park` now returns. It subscribes the type again with its cap raised by those whose lock
  has not lapsed; the engine replaces a client's settings on a second subscription
  (`subscribeClient`). A parked job stops counting when it arrives again or its lock lapsed. A
  reconnect sends the raised cap. Past the engine's default ceiling of 1000 a WARN names
  `JOB_MANAGER_MAX_ACTIVE_JOBS_CAP` once. This is decision 37, which supersedes in part decision 9.
  GAPS 28 records the engine's side. Pinned by
  `ZenBpmParkedTasksIT#aParkedTaskLeavesRoomForTheNextOfItsType` (one handler slot, a window of a
  minute: the second task of the type arrives and both stay open),
  `ZenBpmJobStreamTest#aParkedJobRaisesTheCapOfItsType`, `#aReconnectKeepsTheRaisedCap`,
  `#aCapAboveTheEnginesCeilingIsWarnedAboutOnce` and
  `ZenBpmLockRenewalTest#aParkedLockIsCountedOnTheApplicationsClock`. Subscribing with the
  engine's largest cap instead was rejected: one node of the application would take every waiting
  job of a type while the others stay idle.
- **The probe read a `404` as gone** (Claude 2, Major). A job read is answered by a follower
  (`ZenNode.GetJob` in `internal/cluster/node.go` asks `GetPartitionFollower`). The engine deletes
  jobs only together with an instance that ended under a parent that ended, after its history time
  to live (`DeleteProcessInstancesJobs` and `FindInactiveInstancesToDelete`). A job taken away
  stays readable as `terminated`. The core asks only during a delivery of the workflow. So a `404`
  is a lagging node far more often than a task taken away, and it now answers `CANNOT_SAY`
  (`ZenBpmOpenTaskProbeTest#aJobTheEngineDoesNotKnowCannotSay`). Fit-check item 4 of S6.1.3,
  GAPS 20, the probe's javadoc, the architecture and the mapping were corrected. The rare case
  left, a job of a sub-process nested in a sub-process which ended, keeps its record open until
  the workflow ends, which is the safe direction.
- **An unwrapped line of the README's status paragraph** (Claude 3, Nit): rewrapped.

Seen to fail, each restored afterwards (10 of 10 mutants, against green unmutated runs): the
handler not telling the stream about a parked job (also against the engine:
`ZenBpmParkedTasksIT` waits 30 s for the second task in vain); lapsed locks never forgotten; a job
which arrives again still counted; the WARN said at the ceiling, and every time; a type of the
engine's default cap raised; a reconnect sending the cap without the raise; a changed cap not sent;
the lock counted to the engine's end instead of the application's; a `404` read as gone again.

`TZ=Etc/UTC mvn clean install` on OpenJDK 25.0.2 builds green after the fixes with every
integration test run and none skipped (engine test support 12 unit and 3 integration tests, core
877 unit and 74 integration tests, Spring Boot 11 unit and 19 integration tests, smoke test 36,
Quarkus runtime 10, Quarkus deployment 49, Quarkus integration tests 3, coverage gate 37). The gate
measures 100 % of 22,164 instructions on Spring Boot and 100 % of 22,030 on Quarkus, and
`bin/check-deploy-safety.sh` passes (13 files on a green build, nothing after a red gate).

**S6.1.2 and S6.1.3 done 2026-10-18**, merged to `main` as `56ff68b` (pull request #44, after two
reviews, whose three findings were all valid and fixed, and CodeRabbit's review, whose two
comments were answered: one valid and fixed in `26a03f6`, one withdrawn by CodeRabbit). F6.1 is
complete. On that commit (GitHub keeps run logs for 90 days, so what they showed is written here
as well):

- `Checks`, run
  [39082742564](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/actions/runs/39082742564):
  green on both JDKs. `bin/check-engine-copies.sh` reported both copies as the engine's files of
  `bdd795a3...`. `ZenBpmTaskProcessingIT` ran 11 tests, `ZenBpmProbeOutageIT` 1,
  `ZenBpmParkedTasksIT` 1, `ZenBpmStartupValidationBootTest` 15, `ZenBpmOpenTaskProbeTest` 8,
  `ZenBpmJobStreamTest` 42, `ZenBpmJobHandlerTest` 29, `ZenBpmLockRenewalTest` 20,
  `ZenBpmAdapterConfigurationTest` 112, core `ZenBpmStartupValidationTest` 14 and Quarkus
  `ZenBpmAsyncTaskKeysTest` 1, none skipped (engine test support 12 unit and 3 integration tests,
  core 877 unit and 74 integration tests, Spring Boot 11 unit and 19 integration tests, smoke test
  36, Quarkus runtime 10, Quarkus deployment 49, Quarkus integration tests 3, coverage gate 37). The
  gate measured 100 % on both platforms: of 22,164 instructions for Spring Boot and 22,030 for
  Quarkus on JDK 25, of 22,156 and 22,022 on JDK 21.
- `Publish snapshots`, run
  [39082742698](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/actions/runs/39082742698):
  green. The core snapshot it published to GitHub Packages, `2.0.0-20261018.002625-40`, holds the
  stories' new and changed main classes (`ZenBpmOpenTaskProbe`, `ZenBpmJobHandler`,
  `ZenBpmLockRenewal`, `ZenBpmJobStream` with its parked jobs, `JobFailure`,
  `ZenBpmAdapterConfiguration$AsyncTaskMaxAgeAction`) and no test class, fake, fixture, BPMN or
  DMN file; `quarkus/integration-tests`, the smoke test, the engine test support and the coverage
  modules skipped deployment. Both report pages (`spring-boot-report/index.html`,
  `quarkus-report/index.html`) answer HTTP 200 and show 0 of 22,164 (Spring Boot) and 0 of 22,030
  (Quarkus) instructions missed.

## F6.2 Failures and shutdown

### S6.2.1 A failed handler is failed at the engine, with retries and backoff

- [x] **Depends on:** S6.1.1, and the engine's job retries (E13.3, in the pinned engine since
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

*Fit check, 2026-10-18, decided by the Implementor.* Checked against `main` (`31fc5de`),
`ADAPTER-AUTHORS.md` (3.3: an exception the core throws means a rolled-back transaction, apply the
BPMS' retry rules; 5: the shutdown policy is written down), the platform's
`WorkflowTaskHandler` (a `RuntimeException` of the method reaches the adapter unwrapped), Camunda 8's
`Camunda8JobHandler` (its failure branch), `Camunda8RetryBackoffResolver`,
`Camunda8RetryBackoffHeader` and `Camunda8Errors#incidentMessage`, and the engine at the pin
`bdd795a3` (source exported with `git archive`: `pkg/bpmn/jobs_api.go`, `pkg/bpmn/job_retries.go`,
`pkg/bpmn/model/extensions/retries.go`, `internal/rest/server.go`,
`internal/cluster/jobmanager/server.go`, the proto's `WaitingJob`; image run by
`ZenBpmTaskProcessingIT`). What did not fit, and what was decided:

1. **The default of `retries`** (open point 5, brief question 1): the adapter writes none into a
   model and sends none with a failure. How many attempts a job has is the model's
   `zenbpm:taskDefinition retries`, else the engine's `JOBS_DEFAULT_RETRIES` (1 at the pin). The
   Camunda 8 adapter writes none either, decision 4 of the plan's drafts never writes over the
   modeller, and the engine has the setting. The README says what a model without `retries` costs
   and the two ways out: decision 38 (new), pinned by
   `ZenBpmTaskProcessingIT#aModelWithoutRetriesEndsInAnIncidentAtTheFirstThrow`.
2. **The default of `retry-backoff`** (brief question 2): gone. It was `PT10S`; the engine prefers a
   backoff the failure carries over the model's policy (`backoffForAttempt`), so a default would
   override every `retryBackoff` a modeller wrote. Unset at every level, the failure carries none
   (`ZenBpmRetryBackoffResolver` answers empty). This differs from Camunda 8, which always sends ten
   seconds: Camunda 8 has no backoff of the engine to fall back to. Decision 38. With the default
   gone the key is one "set only by somebody who configures this adapter", so the comment of
   `ZenBpmAdapterConfiguration#validate` stays true by counting `retry-backoff`, at the adapter
   level and below it, as a started configuration (`job-timeout` below the adapter level still
   falls back to a default and does not count).
3. **What `WaitingJob` carries** (brief question 3): `retries`, "attempts left: failures without an
   error code the job may still report; the one which leaves none creates an incident", so the count
   before this failure, and `attempt`, 1 for the first delivery. The WARN names
   `max(0, retries - 1)` and `attempt`, and says at zero that the engine raises an incident.
4. **`clientId`** (brief question 4). The plan and the API description say a failure without
   `clientId` leaves the lock standing. At the pin that holds only for a failure which names no
   delivery: one which names its `deliveryToken` releases that delivery's lock whoever reports it
   (`releaseLockOfFailedDelivery`). The code decides: both are sent, the client id for a leader
   which issues no tokens. Measured: a handler's failure without the client id came back as fast as
   with it (the mutant survives, an equivalent one), without both it came back only when the
   job-timeout of thirty seconds lapsed (the mutant is killed by
   `#theBackoffDecidesWhenTheJobComesBack`). Decision 27 says so.
5. **The message.** "Task '<task>' of workflow aggregate '<id>' threw <type>: <message>", the type
   alone where the message is empty, so the incident names the aggregate (the acceptance criterion)
   and the type (the story). The engine appends "(job <key>: <n> attempts, retries exhausted)".
6. **Whole seconds.** The engine reads `retryBackoff` with an ISO-8601 parser which takes whole
   numbers only (`ParseBackoffDuration` on `github.com/senseyeio/duration`), so `PT1.5S` is answered
   `400`. A `retry-backoff` with a fraction of a second ends the start at every level, as
   `history-time-to-live` does, and the REST client refuses one before it sends anything; it writes
   `PT<seconds>S`. No WARN about a large backoff (instruction 4): the engine caps it at
   `JOBS_MAX_RETRY_BACKOFF`, a day by default.
7. **A failure repeated after a lost answer** (brief question 5): the engine answers it like the
   first, with `204`, and records it once ("a repeat of a failure the job recorded changes nothing
   and returns nil"). Measured through the client in `#aFailureRepeatedAfterALostAnswerCountsOnce`:
   retries 3 before, 2 after both, one failure record. A failure of a delivery the engine handed out
   again is answered `409` (`ErrDeliverySuperseded`), which `isGoneJob` reads as a gone job: WARN,
   nothing else (`ZenBpmJobFailureTest#aSupersededFailureIsAWarnAndNothingElse`).
8. **How soon after the backoff** (brief question 4): the engine's distribution loop at the pin
   pauses at most a second between two rounds which find nothing (`distributeJobs`), so a job due
   again is handed out within about a second of its `retryAt`. The IT accepts a redelivery from the
   backoff of two seconds up to ten seconds, against a job-timeout of thirty.
9. **Parked jobs** (advice of the previous batch): nothing in the failure path calls
   `ZenBpmJobStream#parked`; a parked job stops counting when the engine hands it out again
   (`ZenBpmJobStream`, the end of a parked job's count at its receipt), so one which fails at a
   later delivery no longer counts by then.
10. **Names and places.** `wiring/ZenBpmRetryBackoffResolver` beside `ZenBpmJobTimeoutResolver`;
   the deployment service takes it and answers `retryBackoffOf` as it answers `jobTimeoutOf`, which
   the Quarkus extension test calls to prove the producer's resolver. `JobRef` gained no field: the
   stream's `WaitingJob` carries what the failure needs. `ZenBpmLocalRetry` never existed, so only
   the key, its validation, both platforms' binding and their tests went.
11. **ITs** (brief question 9): Spring, in `ZenBpmTaskProcessingIT` with the process
   `TaskFailures` of `task-it/task-failures.bpmn` (diagram added by `bin/add-bpmn-diagram.py`) and
   the aggregate `Payment`; the attempts are recorded outside the handler's transaction, which a
   throw rolls back. On Quarkus the removal and the new meaning are proven by extension tests
   (`ZenBpmRemovedKeyTest`: the key ends the start with `SRCFG00050`;
   `ZenBpmRetryBackoffFractionTest`; `ZenBpmOverlayResolutionTest#retryBackoffThroughTheFourLevels`);
   the Quarkus twins of the ITs belong to S6.5.1.
12. **README.** It has no table of keys; the status paragraph says what a thrown handler costs and
   how many attempts a task has. The architecture's key table already gave `retry-backoff` this
   meaning. A thrown handler during a shutdown is still failed here; S6.2.2 changes that.

**Tests**: `ZenBpmJobFailureTest` (fake server: the request's fields, retries counted down from
the job's, `retryBackoff` left out where no level sets it, `409` tolerated);
`ZenBpmTaskProcessingIT#aThrowingHandlerEndsInAnIncidentAfterItsRetries` (a model with `retries="2"`
and `retry-backoff: PT1S`: two deliveries, then an incident naming the attempts and the message),
`#theBackoffDecidesWhenTheJobComesBack` (redelivery not before the backoff, soon after it, so the
`clientId` released the lock), `#aFailureRepeatedAfterALostAnswerCountsOnce` (the same request twice
with the same `deliveryToken`).

**Acceptance criteria**

- [x] A thrown handler spends exactly one attempt per delivery; the incident names the aggregate.
  `ZenBpmTaskProcessingIT#aThrowingHandlerEndsInAnIncidentAfterItsRetries`: the task `Charge`
  (`retries="2"`, the workflow's `retry-backoff: PT1S`) whose handler always throws is delivered
  twice, at least a second apart, the job has two failure records and is `FAILED`, and the incident
  reads "Task 'charge' of workflow aggregate '<id>' threw java.lang.IllegalStateException: the card
  was declined (job <key>: 2 attempts, retries exhausted)"; the delivery log holds no record of the
  rolled-back attempts. `#aModelWithoutRetriesEndsInAnIncidentAtTheFirstThrow`: one attempt, then
  an incident ending in "1 attempt, retries exhausted)". `#theBackoffDecidesWhenTheJobComesBack`:
  a handler failing once is delivered again not before the task's `retry-backoff` of two seconds
  and before ten seconds, against a job-timeout of thirty, completes, and its job has 2 of 3 retries
  left. `#aFailureRepeatedAfterALostAnswerCountsOnce`: the same failure sent twice through the
  client with one delivery token is answered `204` twice, leaves 2 of 3 retries and one failure
  record. `ZenBpmJobFailureTest` pins the request: no `retries`, no `errorCode`, `retryBackoff`
  `PT7S` where configured and absent where no level sets one, the message, delivery token 7 and the
  stream's client id; the WARN names the type, the attempt and the retries left; `409` is a WARN
  and nothing else; another refusal leaves the job to its lock.
- [x] No local retry state and no `max-redeliveries` key remain in the adapter.
  `max-redeliveries` is gone from `ZenBpmAdapterConfiguration`, both overlays, both properties tests
  and the Quarkus resolution test (`git grep -i max-redeliveries -- '*.java'` finds only
  `ZenBpmRemovedKeyTest`); on Quarkus the key now ends the start like any key the overlay does not model (`ZenBpmRemovedKeyTest`,
  `SRCFG00050`). The handler keeps no state per job: a failure carries nothing but what the job and
  the configuration say.

**Implemented 2026-10-18.** Core: `wiring/ZenBpmRetryBackoffResolver`, the failure of a thrown
handler in `ZenBpmJobHandler` (`failAThrownHandler`), `JobFailure#retryBackoff` and
`#withRetryBackoff`, `retryBackoff` on the wire of `ZenBpmRestClient#failJob` in whole seconds,
`ZenBpmDeploymentService#retryBackoffOf` and its constructor with the resolver;
`ZenBpmAdapterConfiguration` without `max-redeliveries` and without a default of `retry-backoff`,
which is refused with a fraction of a second and counts as a started configuration. Spring Boot and
Quarkus: `max-redeliveries` gone, `retryBackoffFor` answers an `Optional`, the resolver handed to
the deployment service. Decisions 27 (the plan's number, first relied on here) and 38 (new). README
status; deviations 6 of `docs/architecture/03-deviations-and-gaps.md`, open questions 3 and 14,
the E13.1 and E13.3 twins of epic 13, the note under S2.1.1 in epic 2.

New tests: `ZenBpmJobFailureTest` (7), cases in `ZenBpmAdapterConfigurationTest`,
`ZenBpmRestClientTest` (3), both platforms' `VanillaBpZenBpmPropertiesTest`,
`ZenBpmOverlayResolutionTest#retryBackoffThroughTheFourLevels`; Quarkus `ZenBpmRemovedKeyTest` (1),
`ZenBpmRetryBackoffFractionTest` (1); Spring Boot `ZenBpmTaskProcessingIT` (4 new: the four above)
with the process `TaskFailures` of `task-it/task-failures.bpmn`, the aggregate `Payment` and
`PaymentAttempts`. `ZenBpmJobHandlerTest#aThrowingHandlerIsLeftToItsLock` is gone.

Seen to fail, each restored afterwards (16 of 17 mutants, against green unmutated runs): the
failure without its backoff (also against the engine: the job came back at once); without its
delivery token; without the client id (unit); a gone job not tolerated; the last attempt not named
as one; a blank message named `null`; the thrown handler not failed at all; a refused failure
swallowed; a fraction of a second accepted at the adapter level and below it; a negative
`retry-backoff` accepted; `retry-backoff` not counted as a started configuration, at the adapter
level and below it; the REST client sending a fraction. The one which survived, the failure without
the client id against the engine, is equivalent at the pin (fit-check item 4); without the token
and the client id together the job came back only after its job-timeout of thirty seconds.

`TZ=Etc/UTC mvn clean install` on OpenJDK 25.0.2 builds green with every integration test run and
none skipped (engine test support 12 unit and 3 integration tests, core 889 unit and 74 integration
tests, Spring Boot 11 unit and 23 integration tests, smoke test 36, Quarkus runtime 10, Quarkus
deployment 50, Quarkus integration tests 3, coverage gate 37). The gate measures 100 % of 22,391
instructions for Spring Boot and 100 % of 22,265 for Quarkus, and `bin/check-deploy-safety.sh`
passes (13 files on a green build, nothing after a red gate). No flake was hit.

### S6.2.2 Drain and shutdown

- [x] **Depends on:** S6.2.1.

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

*Fit check, 2026-10-18, decided by the Implementor.* Checked against `main` with S6.2.1
(`afeac31`), `ADAPTER-AUTHORS.md` (5: `stopWorkflowProcessing` races the handlers, the policy is
written down in the README), the platform's `SpringBootDeploymentService` (a `SmartLifecycle` with
the synchronous `stop()` only; what bounds it is corrected in the answers to the reviews below),
Camunda 8's `Camunda8Drain`,
`Camunda8DeploymentService#stopWorkflowProcessing` and its decisions 6 and 51, and the engine at
the pin `bdd795a3` (`removeClient` and `releaseLockOfFailedDelivery` in
`internal/cluster/jobmanager/server.go`; the image run by `ZenBpmShutdownDrainIT`). What did not
fit, and what was decided:

1. **One wait per adapter id, not per module** (beyond the story). The platform stops the modules
   one after another, so a wait in each stop would spend `shutdown-grace` once per module. As in
   Camunda 8 (its decision 51), each stop unsubscribes and marks its module, and the stop of the
   last running module waits for the handlers of every stopped module together:
   `ZenBpmDrain#awaitEveryModuleQuiet`, decision 39 (new).
   `ZenBpmWorkflowProcessingTest#modulesWhichStopAreWaitedForOnce`,
   `ZenBpmDrainTest#severalModulesAreOneWait`. Camunda 8's second half, waiting until the cluster
   released the workers, has no counterpart: closing the stream drops every lock of the client at
   once (`removeClient`).
2. **When the state is read** (brief question 6): after the handler returned, so a handler which
   started before the stop and threw during it is not failed
   (`ZenBpmJobHandlerTest#aHandlerWhichThrowsWhileTheModuleStopsIsNotFailed`,
   `ZenBpmShutdownDrainIT#aCutOffHandlerCostsNoFailure`). Besides, a job which reaches the handler
   once its module stops is not started at all: the dispatcher may have found the handler just
   before the stop, and its handler would start after the wait looked
   (`#aJobWhichArrivesWhileTheModuleStopsIsNotStarted`). A handler enters the drain before it reads
   the state, and the stop sets the state before it looks at the drain, so one of the two sees the
   other.
3. **What is still sent while a module stops.** An outcome a handler reached (completion, BPMN
   error, a parked task) and the failure without retries of a job whose workflow this application
   does not own or whose instance names no aggregate (decision 33): those answer the job, they are
   not caused by the shutdown. The acceptance criterion's "no `fail` command" is read as no failure
   of a thrown handler.
4. **`shutdown-grace` stays at the adapter level** (brief question 7): it is bound there only on both
   platforms, and only the whole shutdown has a budget to compare it with (decision 39). The WARN
   above thirty seconds goes through a new list of warnings of the validation, which both platforms
   write into the block at the end of the start at WARN (`ZenBpmStartupValidationBootTest`,
   Quarkus `ZenBpmLongShutdownGraceTest`).
5. **Another node gets the jobs at once** (brief question 8): measured, node b ran the packing whose
   handler node a had cut off and the labelling which waited for node a's one handler slot within
   65 and 112 ms of node a's close (two runs), against a job-timeout of sixty seconds. The engine
   at the pin drops the locks of a client whose stream closed, so no gap was found. With the
   factory not closing the stream, the case fails after its thirty seconds.
6. **The factory's `close()`** needed no change: its backstop already stopped every module which
   never stopped, with a WARN, before it closes the stream, and that stop now waits as the last one;
   `close()` does not wait the grace again (the note from S2.4.2 under S2.4.1). The executor does
   not interrupt a handler it was shut down under; such a handler which throws later is left to its
   lock as well.
7. **The IT** (brief question 9): `ZenBpmShutdownDrainIT` boots its own application `it/drain`
   (`DrainItApplication`, the process `Drain` of `drain-it/drain.bpmn` with its diagram: the packing
   and the labelling in parallel) once per node with `SpringApplicationBuilder`, both nodes on one
   named in-memory database, with one handler slot and a job-timeout of a minute. It has the
   story's three cases and `#aHandlerAfterTheGraceIsNamed`, which keeps the handler running past a
   grace of a second. The Quarkus twin belongs to S6.5.1.

**Tests**: `ZenBpmDrainTest`, `ZenBpmShutdownDrainIT#aHandlerWithinTheGraceFinishes`,
`#aCutOffHandlerCostsNoFailure` (the job is still `active` afterwards, no incident),
`#anotherNodeGetsTheOpenJobsAtOnce` (a second application context receives the queued jobs of the
stopped one within a second, not after `job-timeout`).

**Acceptance criteria**

- [x] No `fail` command leaves the adapter while a module shuts down; the job's retries are
  unchanged afterwards.
  `ZenBpmShutdownDrainIT#aCutOffHandlerCostsNoFailure`: the handler of `Pack` (`retries="3"`)
  which started before node a closed throws while the module stops; afterwards the job is `ACTIVE`
  with 3 retries, has no failure record and its instance no incident, and the log says that it is
  not failed and keeps its retries. `#aHandlerAfterTheGraceIsNamed`: a handler still running after a
  grace of one second is named with job key, task, aggregate and module, the close takes at least
  the grace, and the job keeps its 3 retries. `#aHandlerWithinTheGraceFinishes`: a handler released
  during the stop completes its job while the module stops, and the module drains.
  `#anotherNodeGetsTheOpenJobsAtOnce`: node b ran the cut-off packing and the labelling which waited
  for node a's slot within 65 and 112 ms of node a's close (two runs; job-timeout sixty seconds).
  `ZenBpmJobHandlerTest` pins the stop races (a throw during the stop sends nothing; an outcome
  reached during it is sent; a job reaching the handler after the stop is not started; the drain
  holds a handler with its task and aggregate while it runs), `ZenBpmDrainTest` the wait (until the
  last handler leaves, the grace once for several modules, a WARN per cut-off handler, a grace of
  zero, an interrupted wait), `ZenBpmWorkflowProcessingTest#modulesWhichStopAreWaitedForOnce` that
  the stop of the last running module waits for all of them.

**Implemented 2026-10-18.** Core: `wiring/ZenBpmDrain` (per module, held by its handler);
`ZenBpmJobHandler` enters and leaves the drain, starts no job and fails no thrown handler while the
module stops; `ZenBpmDeploymentService#stopWorkflowProcessing` lets the stop of the last running
module of the adapter id wait for every stopped module within one `shutdown-grace`;
`ZenBpmAdapterConfiguration.ValidationResult#warnings` with the WARN about a `shutdown-grace` above
thirty seconds (`LONGEST_SHUTDOWN_GRACE_WITHIN_THE_USUAL_BUDGETS`), which `ZenBpmStartupValidation`
reports at WARN on both platforms. Decision 39 (new). README: the shutdown policy.

New tests: `ZenBpmDrainTest` (7), four cases in `ZenBpmJobHandlerTest`,
`ZenBpmWorkflowProcessingTest#modulesWhichStopAreWaitedForOnce`,
`ZenBpmAdapterConfigurationTest#aLongShutdownGraceIsWarnedAbout`,
`ZenBpmStartupValidationTest#warningsAreWarnings`, the smoke test's
`ZenBpmStartupValidationBootTest#aLongShutdownGraceIsWarnedAboutInTheBlock`, Quarkus
`ZenBpmLongShutdownGraceTest` (1), and Spring Boot `ZenBpmShutdownDrainIT` (4) with the application
`it/drain` and `drain-it/drain.bpmn`.

Seen to fail, each restored afterwards (12 of 12 mutants, against green unmutated runs): a thrown
handler failed while its module stops (also against the engine: the job lost a retry); a job
started after the stop; the drain not left, not told the aggregate; the wait not waiting, ignoring
an interrupt; every module waiting on its own; the WARN at thirty seconds; the warnings logged as
notices; against the engine, the stop not waiting at all (the completion never reached the engine)
and the factory not closing the stream (node b got nothing within thirty seconds). A mutant written
as `while (false)` did not compile and was written again as a condition.

`TZ=Etc/UTC mvn clean install` on OpenJDK 25.0.2 builds green with every integration test run and
none skipped (engine test support 12 unit and 3 integration tests, core 903 unit and 74 integration
tests, Spring Boot 11 unit and 27 integration tests, smoke test 37, Quarkus runtime 10, Quarkus
deployment 51, Quarkus integration tests 3, coverage gate 37). The gate measures 100 % of 22,908
instructions for Spring Boot and 100 % of 22,782 for Quarkus, and `bin/check-deploy-safety.sh`
passes (13 files on a green build, nothing after a red gate). No flake was hit.

**Answers to two reviews of S6.2.1 and S6.2.2, 2026-10-18** (one by Claude, approve, 2 Minor and
2 Nit; one by Copilot, request changes, 1 Major, the same as Claude's fourth). Every claim was
checked in the code of the branch, in Spring Framework 7.0.9's sources and in the platform jar the
build resolves. All four findings were valid and are fixed; none was about S6.2.1.

- **A second delivery of one job hid the first from the drain** (Copilot 1, Major; Claude 4,
  Nit). `ZenBpmDrain` kept one entry per job key, so of two runs of one job on one node, the one
  which returned first removed the other's entry, and the shutdown neither waited for it nor named
  it. Copilot reproduced it with the real dispatcher and handler (two delivery tokens of job 42;
  the drain reported quiet after 0 ms with one handler held). The engine hands a job out again
  once its lock lapsed (`capacityLocked`), to the same client too
  (`ZenBpmJobLockIT#anExtensionOfALapsedDeliveryLeavesTheNextOneAlone`). Now `enter` returns a
  handle per run, which `describe` and `leave` take
  (`ZenBpmDrainTest#twoDeliveriesOfOneJobAreTwoHandlers`); decision 39 says every run is waited
  for on its own. Camunda 8's `Camunda8Drain` has the same shape; it is not changed (policy).
- **`spring.lifecycle.timeout-per-shutdown-phase` does not bound the drain** (Claude 1, Minor).
  The platform's `SpringBootDeploymentService` implements only the synchronous
  `SmartLifecycle#stop()`; Spring's `stop(Runnable)` runs it to its end on the shutdown thread
  (`DefaultLifecycleProcessor#doStop`) before it counts the timeout. So the startup WARN, the
  WARN about a cut-off handler and the constant's javadoc name only Kubernetes'
  `terminationGracePeriodSeconds` (or whatever else stops the application) as the budget to raise.
  Decision 39, written in this batch and not merged before, was corrected in place, and so was the
  fit check above. The story's sentence ("Spring's and Kubernetes' budgets") is answered here.
- **A job whose module stopped during the read of its instance was logged as a `use-prefix`
  clash** (Claude 2, Minor). The dispatcher reads the instance's facts on the handler thread and
  then looks for the module serving it; `unregister` had removed the module by then, and the WARN
  sent an operator after a configuration problem which does not exist. The dispatcher now keeps
  the handlers of stopped modules (put there before the module leaves the running ones) and hands
  such a job to its module's handler, which starts nothing and logs that the module shuts down
  (`ZenBpmJobDispatcherTest#aJobWhoseModuleStopsWhileItsInstanceIsReadIsNotStarted`, which stops
  the module inside the engine's answer to the read).
- **A module a closing factory refused stayed among the running modules** (Claude 3, Nit). Its
  drain was put among them before the factory refused the start, so every later stop would have
  waited for it and so for no handler at all. It is now put there after the start was accepted. A
  reordering, not a guard; the reviewer and I found no path on the platforms which reaches it.

Seen to fail, each restored afterwards (3 of 3 mutants, against green unmutated runs): the
dispatcher without the fall-back to stopped modules; `unregister` without keeping the stopped
handler; `leave` removing every run of the job key.

`TZ=Etc/UTC mvn clean install` on OpenJDK 25.0.2 builds green after the fixes with every
integration test run and none skipped (engine test support 12 unit and 3 integration tests, core
905 unit and 74 integration tests, Spring Boot 11 unit and 27 integration tests, smoke test 37,
Quarkus runtime 10, Quarkus deployment 51, Quarkus integration tests 3, coverage gate 37). The gate
measures 100 % of 22,952 instructions for Spring Boot and 100 % of 22,826 for Quarkus, and
`bin/check-deploy-safety.sh` passes (13 files on a green build, nothing after a red gate).

CodeRabbit's review of pull request #46 had one comment, valid: the closing factory runs a
module's hook on its own thread, and that hook could stop a module while it was starting. Between
the start's `processingStarted` and the drain's registration (an order the answer to Claude 3
made), the stop added no drain, and the shutdown's wait then failed with a
`NullPointerException`. Before `processingStarted`, the stop found nothing to stop. The start now
holds its context from the factory's hook to the last subscription, so the hook's stop finds all of
the start or nothing of it (`ZenBpmWorkflowProcessingTest#aStartIsOneStepForAStopWhichRacesIt`,
seen to fail without the lock). Nothing that holds the factory's or the stream's monitor waits for
the context, so the lock cannot deadlock. The full build ran again green, none skipped (core 906 unit tests; 100 % of 22,959 and 22,833
instructions), and `bin/check-deploy-safety.sh` passes.

After the merge (`4651056`), the `Checks` run 39098017032 on `main` failed on JDK 25 in
`ZenBpmShutdownDrainIT#anotherNodeGetsTheOpenJobsAtOnce`; JDK 21 and `Publish snapshots` were
green. The test assumed that node a's only handler slot always takes the packing and the labelling
waits for it. The engine hands the two jobs of the parallel branches out in no fixed order: in that
run node a received the labelling first and completed it (the engine logged its completion while
node a held the lock), so node b packed the parcel but could never label it. No adapter code was
at fault. The test now holds whichever of the two tasks reaches node a's handler first
(`PackingGate#holdEither`), and waits until both reached node a.

**S6.2.1 and S6.2.2 done 2026-10-18**, merged to `main` as `4651056` (pull request #46, after two
reviews, whose four findings were all valid and fixed, and CodeRabbit's review, whose one comment
was valid and fixed in `c38a300`), and the test fix of the red run above as `09f9530` (pull
request #47, test code only; CodeRabbit had no comment). F6.2 is complete. On `09f9530` (GitHub
keeps run logs for 90 days, so what they showed is written here as well):

- `Checks`, run
  [39101170517](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/actions/runs/39101170517):
  green on both JDKs. `bin/check-engine-copies.sh` (in the JDK 25 job) reported both copies as the
  engine's files of `bdd795a3...`. On both JDKs `ZenBpmTaskProcessingIT` ran 15 tests,
  `ZenBpmShutdownDrainIT` 4, `ZenBpmStartupValidationBootTest` 16, `ZenBpmJobHandlerTest` 32,
  `ZenBpmJobDispatcherTest` 12, `ZenBpmDrainTest` 8, `ZenBpmJobFailureTest` 7,
  `ZenBpmWorkflowProcessingTest` 9, `ZenBpmAdapterConfigurationTest` 116 and Quarkus
  `ZenBpmRemovedKeyTest`, `ZenBpmRetryBackoffFractionTest` and `ZenBpmLongShutdownGraceTest` 1
  each, none skipped (engine test support 12 unit and 3 integration tests, core 906 unit and 74
  integration tests, Spring Boot 11 unit and 27 integration tests, smoke test 37, Quarkus runtime
  10, Quarkus deployment 51, Quarkus integration tests 3, coverage gate 37). The gate measured
  100 % on both platforms: of 22,959 instructions for Spring Boot and 22,833 for Quarkus on JDK 25,
  of 22,951 and 22,825 on JDK 21.
- `Publish snapshots`, run
  [39101170515](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/actions/runs/39101170515):
  green. The core snapshot it published to GitHub Packages, `2.0.0-20261018.053728-43`, holds the
  stories' new and changed main classes (`ZenBpmDrain` with `Entered` and `Running`,
  `ZenBpmRetryBackoffResolver`, `ZenBpmJobDispatcher`, `JobFailure`) and no test class, fake,
  fixture or BPMN file; `quarkus/integration-tests`, the smoke test, the engine test support and
  the coverage modules skipped deployment. Both report pages (`spring-boot-report/index.html`,
  `quarkus-report/index.html`) answer HTTP 200 and show 0 of 22,959 (Spring Boot) and 0 of 22,833
  (Quarkus) instructions missed.

## F6.3 Asynchronous tasks

### S6.3.1 `COMPLETE_TASK` and `CANCEL_TASK`

- [x] **Depends on:** S6.1.2.

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

- [x] The pre-commit check runs right before the commit (the registrar's hook), not at the call.
  `ZenBpmTaskProcessingIT#aTaskWhichEndsBeforeTheCommitIsNotFoundAtTheCommit`: inside the
  application's transaction the task left open is completed through the process service while it
  still waits, and then completed by another client over REST; the commit fails with the platform's
  `TaskNotFoundException` ("task '<id>' is gone, the engine holds it as completed"), the aggregate's
  change is rolled back, and the workflow ends on the path of the value it had. With the check run at
  the call the commit goes through (mutant, also against the engine).
  `ZenBpmPreCommitCheckTest#phaseOneRegistersTheCheckWithoutAskingTheEngine`: phase one of both
  operations registers one check each, for the aggregate's class, against an engine on a closed
  port, and `#theCheckAsksTheEngineWhenTheHookRuns` sees the check fail when the hook runs.
  `ZenBpmTaskProcessingIT#completeTaskEndsTheDormantWorkflow`: the gateway behind the completed task
  takes the branch of the value the application set before the completion, and the job is
  `COMPLETED`; `#cancelTaskRoutesTheBoundaryEvent`: the error boundary event `Withdrawn` catches the
  code the model carries scoped, and the job is `TERMINATED`; `#completingAGoneTaskIsAWarnedNoOp`:
  a completion and a cancellation of one task in one transaction end the workflow once, and the
  second costs a WARN.

*Fit check, 2026-10-18, decided by the Implementor.* Checked against `main` (`39a6e80`),
`ADAPTER-AUTHORS.md` (2.2: phase one only asks; 3.4: a task phase one finds gone throws
`TaskNotFoundException`; 5: a phase-two call may arrive twice and for a task which is gone), the
platform as the adapter resolves it (`PreCommitRegistrar#beforeCommit(Class<?>, Runnable)`,
`PhaseOneRequest`, `PhaseTwoRequest`, `PhaseOperation.COMPLETE_TASK`/`CANCEL_TASK` and their keys,
`MigrationProcessService#phaseOneRequest`, `DeliveryRecords`), Camunda 8's
`Camunda8ProcessService` (`registerPreCommitExistenceCheck`, `newTaskNotFound`, `completeTask`,
`cancelTask`, `variablesOf`) and `Camunda8PreCommitCheckTest`, and the engine at the pin `bdd795a3`
(source exported with `git archive`: `completeJob`, `failJob`, `releaseLock`,
`releaseLockOfFailedDelivery`, `releaseLockOfEndedJob` and `capacityLocked` in
`internal/cluster/jobmanager/server.go`, `JobCompleteByKey`, `repeatedCompletion`, `JobFailByKey`
and `ensureJobStillWaits` in `pkg/bpmn/jobs_api.go`, `ZenNode.CompleteJob`/`FailJob` in
`internal/cluster/node.go`; the image run by `ZenBpmJobLockIT` and `ZenBpmTaskProcessingIT`). What
did not fit, and what was decided:

1. **Names and places.** Both handlers are `processservice/ZenBpmTaskOutcomes`, which the process
   service builds once and hands the start's way of loading the shared values; the key helpers the
   task probes used (`keyOf`, the partition of a key, decision 32's read of the partitions) moved
   into `processservice/ZenBpmKeys`, so the check reads a key as the probes do. The fake-server
   tests are `ZenBpmTaskOutcomesTest` instead of cases in `ZenBpmProcessServiceTest`, as the probes
   have classes of their own; `ZenBpmPreCommitCheckTest` has Camunda 8's shape. S8.2.1 reuses the
   class for the user task, which is a job as well (a note there says so).
2. **The registrar's signature.** In the resolved jars `beforeCommit` takes the aggregate's class;
   the check passes `aggregatePersistence().getAggregateClass()`, so the platform resolves the
   transaction runner of that aggregate.
3. **The shared values** (the story names `syncedWorkflowAggregateValues`). Phase two loads the
   aggregate through the request's persistence and asks the platform's sync, as the start does and
   as Camunda 8's `variablesOf` does, with the id variable last (`ZenBpmSharedValues.forCommand`):
   one way for every command of phase two. The invoker's method serves the handler of a delivery,
   which names its process by the job.
4. **Who may complete a parked job** (brief question 1). Read and measured at the pin: the engine
   completes a job whoever asks, without a delivery, and drops its lock; it fails one whoever asks
   as well, but a failure without a delivery releases the lock only where the client id it names
   holds it; a command refused because the job ended drops the lock too. `ZenBpmJobLockIT` measures
   it (`#aJobAnotherStreamHoldsIsCompletedByAnyoneAndItsSlotComesFreeAtOnce`,
   `#aJobAnotherStreamHoldsIsFailedByAnyoneAndTheHolderKeepsItsSlot`,
   `#aJobFailedWithTheHoldersClientIdFreesItsSlotAtOnce`). So phase two sends no delivery token;
   unlike the brief's recommendation the cancellation names this node's client id, which releases
   the lock where this node parked the job and changes nothing elsewhere. Sending a token read from
   the job was rejected: a redelivery between the read and the failure would answer `409` and lose
   the cancellation. Decision 40 (new). End to end, `ZenBpmTaskProcessingIT` completes and cancels a
   task the application's job stream parked, through REST calls which name no delivery.
5. **A `404` in the check** (brief question 2). Asked again every quarter of a second within
   `workflow-visibility-timeout`, as the workflow probes' window, then `TaskNotFoundException`; the
   same for an instance of the job the node does not know yet, which then rolls the transaction
   back with an `IllegalStateException`, because whose task it is cannot be said. A `502` for a key
   of a partition the engine does not report is not found (decision 32). This reads a `404` as
   decision 32 does, so it is no decision of its own. An interrupted thread asks no more and keeps
   its interrupt.
6. **The parked count** (brief question 3). Where the engine dropped the lock, after a completion,
   a cancellation from the node which parked the job, or a command refused because the job ended,
   that node ends the count at once (`ZenBpmJobStream#parkedJobEnded`); where nothing was sent
   because the job had ended already, the engine keeps the lock until it lapses (nothing else
   removes it) and the count stays as long. Decision 40 supersedes decision 37 in part; pinned by
   `ZenBpmTaskOutcomesTest#aJobThisNodeParkedStopsCountingOnceItsTaskEnded` and
   `ZenBpmJobStreamTest#aParkedJobWhoseTaskEndedLowersTheCapAtOnce`.
7. **Whose task it is** (brief question 4). The dispatcher's rule: the process of the job's own
   instance, read through the instance cache, and the workflow module which serves it. The phase-one
   request names only the workflow service's primary process (`MigrationProcessService` builds it
   with that id), so a task of a called or a declared process could not be told from the request;
   `ZenBpmDeployedProcesses#recordServed` keeps which module serves which process, written while
   wiring (S6.4.1 adds the declared ids), and `#moduleServing` answers the check. A task of
   another module names that module, one of a process no module of the application serves says so.
8. **A task which carries an incident** (beyond the story). At the pin a `failed` job takes no
   completion and no failure (`ensureJobStillWaits`), so it is refused at the commit with an
   `IllegalStateException`, not `TaskNotFoundException`: the task is there. This contradicted the
   incident S6.1.2's `async-task-max-age-action: incident` raises, whose message advised to
   complete or cancel the task and then resolve the incident, an order the engine refuses. Its
   message now says to set the key back to `report` (or raise the maximum age) and resolve the
   incident first, after which the next delivery leaves the task open again and the application can
   end it (`ZenBpmJobHandlerTest#anOverdueTaskWithTheIncidentActionIsFailedWithoutRetries`).
9. **Phase two of a job which ended.** A job read as anything but `active` is a WARN and nothing is
   sent: a node which lags behind answers an earlier state, never a later one. A `404` or `409` of
   the command is a WARN too, and so is a `409` of the `PATCH` (decision 34). Measured end to end:
   the platform probes the task before it dispatches an entry and skips one whose task ended, with a
   WARN of its own ("Skipped phase two of canceling task ... it has ended already; the entry is
   consumed", `ZenBpmTaskProcessingIT#completingAGoneTaskIsAWarnedNoOp`), so the adapter's WARN
   covers a task which ends after that probe, which the fake-server tests hold. A completion of a
   completed job is answered `201` (`repeatedCompletion`).
10. **While a module stops** (brief question 5). Phase two is no run of a handler: it is not in the
   drain, it uses no `ZenBpmCommandRetry`, and it is sent while a module stops. A failure which may
   pass is thrown and the outbox repeats it. A parked job runs no handler, so the drain neither
   counts nor waits for it; nothing changed there.
11. **What is repeated** (brief question 6). A job or an instance the node does not know yet, a
   `404` of the `PATCH` and an unavailable engine are thrown and repeatable; a task id which is no
   number fails `Long.parseLong`, which `ZenBpmErrors` reads as permanent; a gone job is a WARN and
   no exception (`ZenBpmTaskOutcomesTest#whatMayPassIsRepeated`, `#aTaskIdWhichIsNoNumberIsPermanent`).
12. **The cancellation's incident.** Where no error event catches the code, the failure's message
   becomes the incident's ("Task <key> (element '<id>', workflow aggregate '<id>') was cancelled
   with BPMN error '<code>', which no error boundary event and no error event sub-process catches,
   its code: <scoped code>"), and the job read once afterwards names it in a WARN, as the handler
   does for a BPMN error.
13. **ITs** (brief question 10). Spring `ZenBpmTaskProcessingIT`: the process `TaskOutcomes` got a
   gateway on the shared `approved` behind the task left open and an error boundary event
   `withdrawn` on it (`task-it/task-outcomes.bpmn`, diagram regenerated). The Quarkus twin
   (`#asyncTaskIsCompletedLater`) belongs to S6.5.1; the core's tests count for the Quarkus report
   as well, so it stays at 100 % without one.
14. **Docs.** README status, the architecture's outline, the `COMPLETE_TASK`/`CANCEL_TASK` row of
   the SPI mapping; the README's table of failures is unchanged.

**Implemented 2026-10-18.** Core: `processservice/ZenBpmTaskOutcomes` (both phases of both
operations), `processservice/ZenBpmKeys` (the key helpers of the task probes, moved),
`ZenBpmProcessService` serving `COMPLETE_TASK` and `CANCEL_TASK` and sharing the start's variables,
`ZenBpmDeployedProcesses#recordServed` and `#moduleServing` (written by `wireBpmn`),
`ZenBpmJobStream#parkedJobEnded`; the message of the incident action in `ZenBpmJobHandler`.
Decision 40 (new), decision 37 marked as superseded in part. README status, the architecture's
outline, the SPI mapping.

New tests: `ZenBpmTaskOutcomesTest` (29), `ZenBpmPreCommitCheckTest` (2),
`ZenBpmJobStreamTest#aParkedJobWhoseTaskEndedLowersTheCapAtOnce`,
`ZenBpmDeployedProcessesTest#theModuleServingAProcessIsFoundByItsScopedId`, the served processes in
`ZenBpmWorkflowProcessingTest#aModuleSubscribesTheJobTypesOfItsClaimedProcesses`, `ZenBpmJobLockIT`
(3 new, pinned engine), Spring Boot `ZenBpmTaskProcessingIT` (4 new) with the process `TaskOutcomes`
extended. `ZenBpmProcessServiceTest` counts the two operations among the built ones.

Seen to fail, each restored afterwards (22 of 22 mutants, against green unmutated runs): an ended
task, and one with an incident, passing the check; the scope not checked; a node which lags behind
not asked again; an interrupt swallowed; a key of a missing partition read as unavailable; the check
run at the call (also against the engine); a job which ended completed all the same; the values
written into the job's instance only; an ended workflow, and a gone completion and cancellation,
thrown; another failure of a cancellation swallowed; the cancellation without the client id; the
error code sent unscoped (also against the engine, where the mutant had to strip the IT's module);
the count not ended after a completion; the count ended where nothing was sent (it survived the
first test, which then parked a second job to tell the two apart); a cancellation nothing caught
always warned; the stream keeping the count of an ended job; wiring recording no served process.

`TZ=Etc/UTC mvn clean install` on OpenJDK 25.0.2 builds green with every integration test run and
none skipped (engine test support 12 unit and 3 integration tests, core 939 unit and 77 integration
tests, Spring Boot 11 unit and 31 integration tests, smoke test 37, Quarkus runtime 10, Quarkus
deployment 51, Quarkus integration tests 3, coverage gate 37). The gate measures 100 % of 23,961
instructions for Spring Boot and 100 % of 23,835 for Quarkus, and `bin/check-deploy-safety.sh`
passes (13 files on a green build, nothing after a red gate). Flakes hit: the known
`ZenBpmJobStreamTest#aConnectionNobodyAnswersIsNoReturn` ("Address already in use") once, green on
the next run.

## F6.4 Renamed processes

### S6.4.1 Workers for declared process ids

- [x] **Depends on:** S6.1.1.

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

- [x] A workflow of a renamed process finishes after the rename under `use-prefix`.
  `ZenBpmRenamedProcessIT#aWorkflowOfARenamedProcessFinishesAfterTheRename`: the version of the
  application before the rename deploys `Shipping`, starts a workflow of it which waits at a timer,
  and stops; the version after it deploys `Dispatch`, declares `Shipping` beside it, and its start
  line says that `deploy-it__Shipping__ship` is subscribed for it. The workflow's instance of
  `deploy-it__Shipping` ends `COMPLETED`, the shipment was shipped by the version after the rename,
  and its `@TaskParam` `destination` was bound from the job of the old model. With the declared id
  not served at all the workflow never ends (mutant, against the engine).
  `ZenBpmDeclaredProcessWorkersTest#aDeclaredIdIsServedByTheTaskTypesTheEngineHolds` pins which
  types: the task's type of every version the engine holds is subscribed with its job-timeout and
  routed to the module, a user task's type is not, and the start's line names both and the task
  definition no held model names.

*Fit check, 2026-10-18, decided by the Implementor.* Checked against `main` with S6.3.1 on the
branch, `ADAPTER-AUTHORS.md` (3.2: "Serving those workflows is a second matter, and that one IS
yours"; 3.3: a declared id answers `true` to both lifecycle questions), the platform as the adapter
resolves it (`WorkflowTaskWiring#taskWiringOfProcessesNobodyDeployed(String)` answering
`Map<String, Collection<String>>`, a method wired by element id contributing nothing), Camunda 8's
`Camunda8DeploymentService` (`openTheWorkersOfTheProcessesNobodyDeployed`,
`reportWhatADeclaredIdIsServedWith`, `Camunda8ProcessVersions#predatesDeployedVersion`), its decision
19 and `Camunda8RenamedProcessIT`, and the engine at the pin `bdd795a3` (`createUserTask` in
`pkg/bpmn/usertasks.go`, the distribution by type in `internal/cluster/jobmanager/server.go`; the
image run by `ZenBpmRenamedProcessIT`). What did not fit, and what was decided:

1. **Names and places.** `deployment/ZenBpmDeclaredProcessWorkers`, the name the architecture's
   layout gave it; `startWorkflowProcessing` asks it before it subscribes and records what it found
   like the wiring of a claimed process (`WiredProcess` and the job-timeout of each type in the
   module's context), so the types are subscribed, routed to the module, probed and unsubscribed
   with the others, and no code of the job stream, the dispatcher or the handler changed.
2. **User tasks are jobs at this engine** (brief question 8). The story's premise that a composed
   type "cannot know user-task types" and so subscribes nothing for them does not hold: the engine
   creates a user task as a job of its `zenbpm:taskDefinition` type, which the deployment scoped
   like a task's, and the job stream hands out any job of a subscribed type. A method serving a
   user task of the old model by task definition would compose that user task's type, and the
   handler would complete the user task like a service task. So the models the engine holds under
   the id are read (every version), and a composed type is subscribed only where a held model gives
   it to an element which creates a job for a worker; a user task's type and a type no held model
   names are named in the start's line. Where the models cannot be read, nothing is subscribed for
   the id and a WARN says so. Decision 41 (new), because it shapes what E8 builds on: the user tasks
   of a renamed process are read from the same models (a note under S8.1.1 says so).
3. **The start's line** (brief question 7). One INFO per declared id names the module, the id, the
   id the engine holds it under, the types subscribed for it, the types the deployed processes
   subscribe already, the user tasks and the task definitions no held model names. An id whose
   methods are all wired by element id gets the WARN of Camunda 8's decision 19 with both ways out.
   What a job of such a workflow carries, checked at the pin and in this repository: the adapter
   writes input mappings only into multi-instance bodies (`ZenBpmTaskParameters`); outside one a job
   carries the variables of its scope from the engine. So the line says that, instead of the story's
   "fetches no input mappings". `ZenBpmRenamedProcessIT` binds the `@TaskParam` `destination` of the
   version after the rename from a job of the model the version before deployed.
4. **Routing and the probe** (brief question 9). The declared id is recorded as a process the
   module serves (`WiredProcess`, and `ZenBpmDeployedProcesses#recordServed`, which the check of
   S6.3.1 reads), so the dispatcher hands a job of its instances to the module, the open-task probe
   of the handler answers for its types (no user-task type), and a task of it can be completed
   through the process service. Under `none` the composed type is the deployed process's type, so
   nothing new is subscribed, and the jobs are still routed
   (`ZenBpmDeclaredProcessWorkersTest#underNoneADeclaredIdSubscribesNoTypeOfItsOwn`).
5. **Job-timeouts.** A composed type is subscribed with the `job-timeout` resolved for the declared
   id and the task definition, and the rule that a type shared by two modules has one job-timeout
   holds for it too; the conflict ends the start of the processing, since the declared ids are
   known only after the module deployed.
6. **`predatesDeployedVersion`** of a job of a declared id stays `false`, because nothing of this
   start was deployed under the id, as Camunda 8's `predatesDeployedVersion` answers it.
7. **Where the IT lives** (brief question 10). `ZenBpmRenamedProcessIT` in `springboot/it` boots
   two applications one after the other on one named in-memory database (`it/renamed/before`,
   deploying `Shipping` from `renamed-it/before/shipping.bpmn`, and `it/renamed/after`, deploying
   `Dispatch` from `renamed-it/after/dispatch.bpmn` and declaring `Shipping`), both models with
   their diagrams. The workflow waits at a timer of eight seconds, so the version before the rename
   is closed before the task is due. The Quarkus side needs nothing beyond the core's tests, which
   count for its report.

**Implemented 2026-10-18.** Core: `deployment/ZenBpmDeclaredProcessWorkers`;
`ZenBpmDeploymentService#startWorkflowProcessing` serves the declared ids before it subscribes
(recorded like a claimed process: `WiredProcess`, job-timeouts, the served process of S6.3.1), and
the two lifecycle flags of a process are read by one helper each. Decision 41 (new). README status,
the architecture's section on user tasks, the SPI mapping, a note under S8.1.1.

New tests: `ZenBpmDeclaredProcessWorkersTest` (8); Spring Boot `ZenBpmRenamedProcessIT` (1)
with the applications `it/renamed/before` and `it/renamed/after`, the aggregate `Shipment`, the
models `renamed-it/before/shipping.bpmn` and `renamed-it/after/dispatch.bpmn` and
`renamed-it.yaml`. `TestWiring` answers the declared ids.

Seen to fail, each restored afterwards (12 of 12 mutants, against green unmutated runs): every
composed type served, a user task's included; a user task's type taken for one no model holds; an
id wired by element id reading the engine; models which cannot be read serving nothing without a
word; a failed read taken as models without tasks; a declared id not recorded as served; not routed
to its module; its types subscribed with the default job-timeout; a type shared with another
module not checked; a restart counting the declared types as the deployed ones; an adapter id
without clients asking the wiring; nothing of a declared id served (against the engine:
`ZenBpmRenamedProcessIT` waits for the workflow in vain).

`TZ=Etc/UTC mvn clean install` on OpenJDK 25.0.2 builds green with every integration test run and
none skipped (engine test support 12 unit and 3 integration tests, core 947 unit and 77 integration
tests, Spring Boot 11 unit and 32 integration tests, smoke test 37, Quarkus runtime 10, Quarkus
deployment 51, Quarkus integration tests 3, coverage gate 37). The gate measures 100 % of 24,501
instructions for Spring Boot and 100 % of 24,375 for Quarkus, and `bin/check-deploy-safety.sh`
passes (13 files on a green build, nothing after a red gate). No flake was hit.

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

  *Note from S5.3.1, 2026-10-17.* `ZenBpmWorkflowLifecycleTest#aStartCreatesTheInstanceAfterTheCommit`
  proves the subscribed job type by waiting in the application's log file for the WARN line of a
  job "which nothing handles yet". Once the handler runs, that line is gone: replace the wait with
  the call of `E2eWorkflow#prepare` (an introspection endpoint) and keep the assertion that the job
  of the started instance arrives, tied to that instance's job key (`introspect/jobs/{key}`) as it
  is now, because every case of the class starts `StartProcess` on one engine. `StartProcess`
  evaluates the decision `rating` twice before `Prepare`, the second time without an output mapping
  (GAPS 27); a case which completes `Prepare` ends the instance. When the class reads the engine
  more often, move its status-checked JSON read (`ZenBpmWorkflowLifecycleTest#engine`) into
  `engine-test-support` and let the Spring `ZenBpmDeploymentIT` use it instead of its substring
  checks.

  *Note from S6.1.1, 2026-10-17.* Done in S6.1.1, because the dispatcher removed the WARN line:
  `#aStartCreatesTheInstanceAfterTheCommit` waits for `E2eWorkflow#prepare` to run for its aggregate
  (`introspect/prepared/{id}`) and for the completion of its instance's own job
  (`introspect/jobs/{key}` names each job's key and state), and the instance is `COMPLETED`. The
  move of the status-checked read into `engine-test-support` and the other Quarkus cases stay here.

**Acceptance criteria**

- [ ] Both platforms prove delivery, outcome and idempotency; the Quarkus report covers
  `ZenBpmJobHandler` and `ZenBpmOpenTaskProbe`.

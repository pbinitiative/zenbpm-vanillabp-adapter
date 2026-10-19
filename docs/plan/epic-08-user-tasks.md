# E8 - User tasks

**Goal.** `@TaskEvent CREATED` and `CANCELED` notifications for user tasks, `completeUserTask` and
`cancelUserTask` by BPMN error, and the user-task probe - on an engine where a user task is a job and
nobody pushes lifecycle events.

**Done when.** A user task's creation and cancellation reach the application once each, completion
continues the workflow with the shared values, cancellation routes an error boundary event, on both
platforms.

Contract: `TaskInvocationContext.getTaskEvent` and `getTaskKind`,
`WorkflowTaskInvoker.workflowTaskHandlerExists`,
`PhaseOperation.COMPLETE_USER_TASK/CANCEL_USER_TASK`, `ADAPTER-AUTHORS.md` sections 3.3 and 3.4.
Decision 12 (polling).

---

## F8.1 Notifications

### S8.1.1 `ZenBpmUserTaskPoller` (engine-gated)

- [x] **Depends on:** E6. **Engine twin:** E13.5.

**Instructions**

*Note from S4.3.1, 2026-10-16.* `wireBpmn` exists; it remembers per claimed process the scoped job
types of the tasks this application serves (`ZenBpmProcessingContext.WiredProcess#jobTypes`), and no
user-task type, so the job stream of S4.3.3 subscribes none: a user task is polled here, and the
engine's default type `user-task-type` is shared by every application on the engine. Add the user
tasks of instruction 1 to `WiredProcess`, or beside it.

1. In `wireBpmn`, remember per module the user tasks (activity id, scoped type, plain type) and
   whether `workflowTaskHandlerExists(module, process, plainType or activityId)`. Since platform
   decision 119 every user task of a claimed process has a method or is marked
   `implemented-externally`, so the answer is no only for a marked task, or for one whose method
   asks for no event of the user task (`ADAPTER-AUTHORS.md` 3.3). Such a task is skipped entirely
   (no polling for it, and no delivery record, platform decision 54), logged at TRACE.
2. `ZenBpmUserTaskPoller` (per adapter id, timing thread): every `user-task-poll-interval` (default
   `PT5S`; `PT0S` switches it off with a WARN at startup) for each polled type: `rest.jobs(type,
   state=active)` and `rest.jobs(type, state=terminated)` paged; for every job key not in the
   bounded seen-set (per node, 100 000 keys, LRU) build a `TaskInvocationContext` with `taskEvent =
   CREATED` resp. `CANCELED`, `taskKind = USER_TASK` (the kind travels into the delivery record, and
   VanillaBP reads it back from there), `taskId = job.key`, `deliveryId = job.key + "-created"`
   resp. `"-canceled"`, `bpmnElementId = job.elementId`, `businessKey` and the root `workflowId` as
   in S6.1.1 (decision 28), the instance facts from the cache (aggregate id, version, scope), run it
   on a handler slot through the invoker; the outcome of a notification is ignored except an
   exception (logged; the key is NOT added to the seen-set so the next poll retries). A `terminated`
   job whose `created` was never seen (a restart) is still reported as CANCELED - the core's record
   decides what is new.
3. The poller's cost is one request per type per state per interval; the startup line names the types
   and the interval.
4. The poller is this adapter's source of `CANCELED` for user tasks. The open-task probe of S6.1.3
   therefore answers `CANNOT_SAY` for a user-task definition while the poller runs, so the core does
   not derive a second cancellation for the same task (`ADAPTER-AUTHORS.md` 3.4). The core closes a
   record on the first cancellation it hears of (platform decision 72); the IT proves one `CANCELED`
   per task even where a service task of the same workflow is delivered meanwhile.

An interval above zero but below one second polls the engine almost without pause: WARN at startup
naming the key, as the configuration refuses only negative values.

*Note from S6.4.1, 2026-10-18.* A process id a workflow module declares without a model (the old id
of a renamed process) has user tasks as well, and only the models the engine holds under that id
say which types they have. `deployment/ZenBpmDeclaredProcessWorkers` reads those models already
(every version) and names the user-task types it found in the start's line; decision 41 keeps them
off the job stream. Poll the user-task types of a declared id from the same read (Camunda 8's
decision 19 reads its user-task listeners from the held models for the same reason), so a user task
created in a workflow of the old id after the rename is notified.

*Note from S2.4.2, 2026-10-15.* The poller runs on a timing thread, so it hands a notification to a
handler slot with `ZenBpmExecutor.tryExecute`, which never waits, and not with `execute`, which
waits for a slot and would hold the timing thread for as long as a handler runs. A notification
which finds no free slot is treated like one which threw: its key is not added to the seen-set, and
the next poll tries it again.

*Fit check, 2026-10-19 (implementation pipeline, batch e08-f8.1).* Checked against `main` at
`5452efd`, the platform snapshot the build resolves (`vanillabp-adapter-spi` `2.0.0-20261015.045823-21`,
whose sources are `adapter-platform-integration` at `f9505797`), the Camunda 8 adapter's
`Camunda8UserTaskListenerHandler`, and the engine at the pin `bdd795a3` (source exported from the
pin, behaviour through the ITs against the pinned image). Decided by the Implementor in place of
the maintainers, all recorded in decision 12, which this story enters together with GAPS 9 and 10:

- *Engine facts.* A cancelled instance and an interrupting boundary event set the user task's job
  `terminated` (`handleProcessInstanceInnerCancel` in `pkg/bpmn/engine.go`, `pkg/bpmn/events.go`;
  both measured by `ZenBpmUserTaskIT`). A terminated job stays listed until its instance's history
  is deleted with it (`DeleteProcessInstancesJobs`), which the engine keeps forever unless
  `PERSISTENCE_INSTANCE_HISTORY_TTL` or the start's `history-time-to-live` says otherwise.
  `GET /v1/jobs` filters by `jobType`, `state`, `processInstanceKey` and `assignee`, not by time or
  key, and a job names no process, so its instance is read (cached). A user task
  without a task definition is a job of type `user-task-type` (`TUserTask.GetTaskType`), element type
  `USER_TASK`.
- *Instruction 3 does not fit:* a look is not one request per type per state; it reads every page,
  and its cost grows with the terminated user tasks the engine keeps. Said in the startup line, the
  README, GAPS 10 and decision 12. No key cursor: a job created long ago and terminated late would
  be missed.
- *The shared default type:* each distinct type is looked for once per adapter id; a
  job goes to the module claiming its instance's process, and one of a process nobody here claims
  is remembered as done with after one instance read and never reaches the core. The task
  definition reported is the plain type the wiring recorded (`user-task-type` where the model names
  none), the element id travels beside it, and the platform is asked whether a method serves the
  user task by both, at wiring and again per notification (the core routes by either, see
  `WorkflowTaskRegistry#serves`). The type is read from the deployed document
  (`ZenBpmModelReader#userTaskTypesOf`), so a task definition written as `user-task-type` is told
  from the default.
- *The seen-set across both states:* one seen entry per job and event. A `terminated` job never seen `active` is
  told as `CANCELED` only: the core runs a method taking `CANCELED` for a task it holds no record
  of and records it (`MigrationProcessService#deliverWorkflowTask`), while a `CREATED` told for a
  task already gone would set a person to work on it
  (`ZenBpmUserTaskRestartIT#aUserTaskNobodyWasToldOfIsToldAsCancelledOnly`).
- *The delivery id's form:* `<key>-created` / `<key>-canceled`, the plan's form, in one place
  (`ZenBpmUserTaskNotifier#deliveryIdOf`); a delivery id of the stream is the job key alone, and the
  core puts the event into its key as well (`TaskDeliveryKey`). Architecture 4.6 corrected.
- *Who starts and stops the poller:* the module is registered with the poller once its job types are subscribed; a
  module with user tasks and no job type starts its processing for them alone; the stop unregisters
  it before the handler hears of the stop, the looks end with the last module, and a notification
  runs in the module's drain. The looks are timed by the timing threads and run on a virtual thread
  of their own (as the lock renewal's extensions), so a slow engine holds no timing thread; a look
  which met a stop and a new start leaves one line of looks.
- *A failed look:* a failed look is a WARN once per outage and remembers nothing; a failed
  instance read skips that job only.
- *Beyond the instructions:* after the core took a notification it is handed the module's open-task
  probe, as the Camunda 8 adapter does after a user-task listener, so a cancelled user task tells the
  service task left open in the same cancelled workflow as well (part of GAPS 9's answer; the
  `@WorkflowEnded` half stays planned for S9.2.1). A notification whose method raises a BPMN error is
  warned about (a notification cannot route one; `cancelUserTask` can).
- *A limit, not built:* after a restart, a terminated user task whose record the platform no longer
  holds (`vanillabp.delivery.retention`, or released at the end of its workflow) is told as cancelled
  again where the engine still lists it; keeping `history-time-to-live` below the retention rules it
  out. Recorded in decision 12 and GAPS 10. *Note for S9.2.1:* once the end marker makes the core
  release the records of an ended workflow, this case reaches every user task of a workflow which
  ended at an end event while its user task was cancelled; decide there whether a terminated job of
  an instance which ended is still told.
- The WARNs for `PT0S` and below one second are configuration warnings beside the one about a long
  `shutdown-grace` (`ZenBpmAdapterConfiguration#validate`), so the startup report logs each once.

**Tests**: `ZenBpmUserTaskPollerTest` (fake server: created once, canceled once, an exception
retried, the bound, `PT0S` polls nothing and WARNs once, `PT0.5S` WARNs); Spring
`ZenBpmUserTaskIT#userTaskCreatedIsNotifiedOnce`, `#userTaskCanceledOnInstanceCancellation` (cancel
the instance over REST, the CANCELED arrives), `#aUserTaskTakenAwayIsCanceledOnce` (a user task and
a service task in parallel, a boundary event takes the user task away, then the service task is
delivered: one `CANCELED`), `#aMarkedUserTaskIsNotPolled` (`implemented-externally=true`).

**Acceptance criteria**

- [x] Each lifecycle event is delivered to the handler once per node and, across a restart, once per
  application (the record).
  Once per node: `ZenBpmUserTaskPollerTest` (27 tests: created once, cancelled once with a delivery
  id of its own, a terminated job never seen active told as cancelled only, a notification which
  threw told again and then not, the bound of 100,000 forgetting the eldest, a job whose
  notification still runs never handed over twice, a refused slot ending the look). Against the
  pinned engine, `ZenBpmUserTaskIT#userTaskCreatedIsNotifiedOnce`,
  `#userTaskCanceledOnInstanceCancellation` and `#aUserTaskTakenAwayIsCanceledOnce` watch three
  more looks after each event (poll interval `PT1S`) and count one creation and one cancellation;
  in the last, the service task of the same workflow is delivered after the boundary event took the
  user task away, and the platform's look at the other open tasks adds no second `CANCELED`.
  Once per application: `ZenBpmUserTaskRestartIT#eachEventOfAUserTaskIsToldOnceAcrossARestart`
  boots the application twice on one database: a creation and a cancellation told before the
  restart are listed again and answered from the record ("Skipping the repeated delivery of task
  'approve'"), the counts stay one each, and a cancellation which happened while the application
  was down is told once; `#aUserTaskNobodyWasToldOfIsToldAsCancelledOnly` (looks off before the
  restart) gets one `CANCELED` and no `CREATED`, recorded `COMPLETED`.
- [x] The stream is never subscribed for a user-task type (asserted on the in-process server).
  `ZenBpmWorkflowProcessingTest#theStreamNeverSubscribesAUserTask`: on the in-process gRPC server
  (`FakeJobStreamEngine`) the module of `wiring.bpmn`, whose user task `Approve` a method serves,
  subscribes and unsubscribes its four job types and never `user-task-type`, which it has looked
  for instead; `#aModuleWithUserTasksOnlySubscribesNothing` opens no call at all. Against the pinned
  engine, the user task's job in `ZenBpmUserTaskIT#userTaskCreatedIsNotifiedOnce` keeps delivery
  token 0: the stream never handed it out.

*Evidence of the clean build, 2026-10-19* (`TZ=Etc/UTC mvn clean install`, Docker ITs included,
none skipped, then `bin/check-deploy-safety.sh`: 13 files deployed by the green build, nothing by
the red gate. Its first run on the final tree had one error in a core IT inside its green half,
whose logs the script deletes: the unidentified flake the journal names since S6.4.1. The core's 92
ITs run alone right after were green, and a copy of the script which keeps its logs passed both
halves on the same tree). Tests per module: engine-test-support 12 + 5 ITs; core 1,092 + 92 ITs; Spring
Boot 14 + 65 ITs; smoke test 38; Quarkus runtime 13, deployment 52, integration tests 15; coverage
gate 37. Coverage per platform: Spring Boot 100.00 % (0 of 30,968 instructions missed), Quarkus
100.00 % (0 of 30,838 missed). Mutants: 46 of 46 killed in the core's tests (the poller's 16
guards, the notifier's 13, the wiring's, the start's and the stop's 10, the declared ids' 2, the
configuration's 3, the model reader's and the context's 2); two of them were first survivors whose
tests then got sharper (a failing look met by a stop; a lagging instance the first look never
reached). One mutant against the pinned engine: the wake-up after a notification switched off
fails `ZenBpmUserTaskIT#theCancellationOfAUserTaskTellsTheOpenServiceTaskOfItsWorkflow`.

## F8.2 Completing and cancelling

### S8.2.1 `COMPLETE_USER_TASK` and `CANCEL_USER_TASK`

- [ ] **Depends on:** S8.1.1.

**Instructions**

Both are the job handlers of S6.3.1 with the user-task key: pre-commit `GET /jobs/{key}` active and
in scope, a gone task throws `io.vanillabp.spi.process.TaskNotFoundException` as in S6.3.1; phase
two `patchVariables` + `completeJob` resp. `failJob(key, scopedErrorCode, values)`. The engine
routes a boundary error event on a user task like on a service task, which is the one thing this
adapter can do and Camunda 8 cannot - say so in the README. `awarenessOfUserTask` is S5.2.2.

*Note from S6.3.1, 2026-10-18.* Both handlers of a task the application left open are
`processservice/ZenBpmTaskOutcomes`: the check before the commit (`active`, of a process the module
serves by `ZenBpmDeployedProcesses#moduleServing`; ended or unknown within
`workflow-visibility-timeout` is `TaskNotFoundException`, another module's or `failed` an
`IllegalStateException`) and phase two (the values up to the root, then `complete` or `fail` with
the scoped code, no delivery token, this node's client id; a job which ended is a WARN). A user task
is a job, so build the two user-task handlers from the same class instead of a copy; its messages say
"task", so give it the word for a user task then. The platform probes a task before phase one and
before it dispatches an entry, and ignores or skips one whose task ended with a WARN of its own, so
the `TaskNotFoundException` of the check shows only for a task which ends between the call and the
commit (`ZenBpmTaskProcessingIT#aTaskWhichEndsBeforeTheCommitIsNotFoundAtTheCommit` is the shape of
`#completingAUserTaskWhichIsGoneThrowsTaskNotFound`).

**Tests**: `ZenBpmUserTaskIT#completeUserTaskContinuesWithSharedValues`,
`#cancelUserTaskRoutesTheBoundaryEvent`, `#completingAGoneUserTaskIsAWarnedNoOp`,
`#completingAUserTaskWhichIsGoneThrowsTaskNotFound` (phase one).

**Acceptance criteria**

- [ ] The gateway behind the completed user task sees the pushed values.

## F8.3 Quarkus twin

### S8.3.1 User tasks on Quarkus

- [ ] **Depends on:** S8.2.1.

`ZenBpmWorkflowLifecycleTest#userTaskNotificationAndCompletion`, `#userTaskCancellationByError`.

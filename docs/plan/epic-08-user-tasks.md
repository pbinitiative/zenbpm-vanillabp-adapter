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

- [ ] **Depends on:** E6. **Engine twin:** E13.5.

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

**Tests**: `ZenBpmUserTaskPollerTest` (fake server: created once, canceled once, an exception
retried, the bound, `PT0S` polls nothing and WARNs once, `PT0.5S` WARNs); Spring
`ZenBpmUserTaskIT#userTaskCreatedIsNotifiedOnce`, `#userTaskCanceledOnInstanceCancellation` (cancel
the instance over REST, the CANCELED arrives), `#aUserTaskTakenAwayIsCanceledOnce` (a user task and
a service task in parallel, a boundary event takes the user task away, then the service task is
delivered: one `CANCELED`), `#aMarkedUserTaskIsNotPolled` (`implemented-externally=true`).

**Acceptance criteria**

- [ ] Each lifecycle event is delivered to the handler once per node and, across a restart, once per
  application (the record).
- [ ] The stream is never subscribed for a user-task type (asserted on the in-process server).

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

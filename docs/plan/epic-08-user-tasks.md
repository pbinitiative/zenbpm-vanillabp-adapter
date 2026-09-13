# E8 - User tasks

**Goal.** `@TaskEvent CREATED` and `CANCELED` notifications for user tasks, `completeUserTask` and
`cancelUserTask` by BPMN error, and the user-task probe - on an engine where a user task is a job and
nobody pushes lifecycle events.

**Done when.** A user task's creation and cancellation reach the application once each, completion
continues the workflow with the shared values, cancellation routes an error boundary event, on both
platforms.

Contract: `TaskInvocationContext.getTaskEvent`, `WorkflowTaskInvoker.workflowTaskHandlerExists`,
`PhaseOperation.COMPLETE_USER_TASK/CANCEL_USER_TASK`. Decision 12 (polling).

---

## F8.1 Notifications

### S8.1.1 `ZenBpmUserTaskPoller` (engine-gated)

- [ ] **Depends on:** E6. **Engine twin:** E13.5.

**Instructions**

1. In `wireBpmn`, remember per module the user tasks (activity id, scoped type, plain type) and
   whether `workflowTaskHandlerExists(module, process, plainType or activityId)`; a user task without
   a handler is skipped entirely (no polling for it).
2. `ZenBpmUserTaskPoller` (per adapter id, timing thread): every `user-task-poll-interval` (default
   `PT5S`; `PT0S` switches it off with a WARN at startup) for each polled type:
   `rest.jobs(type, state=active)` and `rest.jobs(type, state=terminated)` paged; for every job key
   not in the bounded seen-set (per node, 100 000 keys, LRU) build a `TaskInvocationContext` with
   `taskEvent = CREATED` resp. `CANCELED`, `taskId = job.key`, `deliveryId = job.key + "-created"`
   resp. `"-canceled"`, the instance facts from the cache (aggregate id, version, scope), run it on a
   handler slot through the invoker; the outcome of a notification is ignored except an exception
   (logged; the key is NOT added to the seen-set so the next poll retries). A `terminated` job whose
   `created` was never seen (a restart) is still reported as CANCELED - the core's record decides what
   is new.
3. The poller's cost is one request per type per state per interval; the startup line names the types
   and the interval.

**Tests**: `ZenBpmUserTaskPollerTest` (fake server: created once, canceled once, an exception retried,
the bound); Spring `ZenBpmUserTaskIT#userTaskCreatedIsNotifiedOnce`,
`#userTaskCanceledOnInstanceCancellation` (cancel the instance over REST, the CANCELED arrives).

**Acceptance criteria**

- [ ] Each lifecycle event is delivered to the handler once per node and, across a restart, once per
  application (the record).
- [ ] The stream is never subscribed for a user-task type (asserted on the in-process server).

## F8.2 Completing and cancelling

### S8.2.1 `COMPLETE_USER_TASK` and `CANCEL_USER_TASK`

- [ ] **Depends on:** S8.1.1.

**Instructions**

Both are the job handlers of S6.3.1 with the user-task key: pre-commit `GET /jobs/{key}` active and in
scope; phase two `patchVariables` + `completeJob` resp. `failJob(key, scopedErrorCode, values)`. The
engine routes a boundary error event on a user task like on a service task, which is the one thing this
adapter can do and Camunda 8 cannot - say so in the README. `awarenessOfUserTask` is S5.2.2.

**Tests**: `ZenBpmUserTaskIT#completeUserTaskContinuesWithSharedValues`,
`#cancelUserTaskRoutesTheBoundaryEvent`, `#completingAGoneUserTaskIsAWarnedNoOp`.

**Acceptance criteria**

- [ ] The gateway behind the completed user task sees the pushed values.

## F8.3 Quarkus twin

### S8.3.1 User tasks on Quarkus

- [ ] **Depends on:** S8.2.1.

`ZenBpmWorkflowLifecycleTest#userTaskNotificationAndCompletion`, `#userTaskCancellationByError`.

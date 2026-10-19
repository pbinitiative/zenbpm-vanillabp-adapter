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
  is remembered as done with after one instance read and never reaches the core (corrected by the
  answers to the reviews below: remembered until a module of the adapter id starts). The task
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
  application (the record). Once per node: `ZenBpmUserTaskPollerTest` (33 tests since the answers to
  the reviews below: created once, cancelled once with a delivery id of its own, a terminated job
  never seen active told as cancelled only, a notification which threw told again and then not, a
  delivery forgotten only once two complete looks no longer list it, 300 listed user tasks each
  handed over once, a job whose notification still runs never handed over twice, a refused slot
  ending the look). Against the pinned engine, `ZenBpmUserTaskIT#userTaskCreatedIsNotifiedOnce`,
  `#userTaskCanceledOnInstanceCancellation` and `#aUserTaskTakenAwayIsCanceledOnce` watch three more
  looks after each event (poll interval `PT1S`) and count one creation and one cancellation; in the
  last, the service task of the same workflow is delivered after the boundary event took the user
  task away, and the platform's look at the other open tasks adds no second `CANCELED`. Once per
  application: `ZenBpmUserTaskRestartIT#eachEventOfAUserTaskIsToldOnceAcrossARestart` boots the
  application twice on one database: a creation and a cancellation told before the restart are
  listed again and answered from the record ("Skipping the repeated delivery of task 'approve'"),
  the counts stay one each, and a cancellation which happened while the application was down is told
  once; `#aUserTaskNobodyWasToldOfIsToldAsCancelledOnly` (looks off before the restart) gets one
  `CANCELED` and no `CREATED`, recorded `COMPLETED`.
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

**Answers to two reviews of e08-f8.1, 2026-10-19.** Claude (approve with changes: 1 Major, 3
Minor) and Copilot (changes requested: 3 Major, 2 Minor) reviewed `8c7e171`. Each finding was
checked against the code, the engine at the pin and, where it could be, a test written first. Seven
distinct findings, all valid; decision 12 was corrected in place, since it is entered by this
unmerged story.

1. *The seen-set thrashes above its bound (Claude, Major).* Valid: a look lists the jobs in the same
   order every time, so an LRU set of 100,000 forgets each delivery before the next look reaches it
   once one more job is listed, and every listed job is handed over at every look. The set now holds
   what the engine lists: a delivery is forgotten once two looks in a row which listed every job no
   longer list it (two, because offset paging may pass over a job while jobs before it leave the
   state). `ZenBpmUserTaskPollerTest#aDeliveryIsForgottenOnceTwoLooksListedItNoMore`,
   `#aLookWhichListedNotEveryJobForgetsNothing`, `#aLongListingIsHandedOverOnce` (300 listed, each
   handed over once). The plan's "100,000 keys, LRU" is replaced; decision 12 says why.
2. *A module not started yet is taken for nobody (both, Minor and Major).* Valid, and reproduced by
   Copilot: the platform starts the modules one after the other, and a job of a later module found
   by an earlier look was remembered as done with. Deliveries of nobody's processes are now kept
   apart and cleared by every start of a module; one found to be nobody's while a module starts is
   not remembered (the modules are read at the hand-over, after the starts were counted).
   `#aUserTaskOfAModuleWhichStartsLaterIsToldOnceItStarted`,
   `#aUserTaskFoundToBeNobodysWhileItsModuleStartsIsToldAtTheNextLook`.
3. *A caught BPMN error terminates the job (Claude, Minor).* Valid: `processBoundaryErrorEvent` in
   `pkg/bpmn/jobs_api.go` and `pkg/bpmn/engine_error_event_subprocess.go` set the job `terminated`
   at the pin. Named in decision 12's engine facts; S8.2.1 decides what follows (note there). No
   code here, as `cancelUserTask` is not built yet.
4. *A notification which keeps failing (Claude, Minor: a stack trace at every look; Copilot, Major:
   it takes the slots from the others).* Both valid; Copilot's reproduction showed a failing first
   job keeping a healthy creation and a cancellation from ever running with one slot. A notification
   handed over and not told is now handed over again by the next look, then after a wait which
   doubles with each failure, at most 64 looks; a method's failure is a WARN with its stack trace,
   and one right after another a DEBUG. `#notificationsWhichKeepFailingLeaveTheSlotsToTheOthers`
   (two failing jobs and two slots: the creation and the cancellation behind them are told at the
   third look, and two WARNs for four failures),
   `#aNotificationWhichKeepsFailingIsRetriedAfterTheLongestWaitAtTheLatest` (ten tries in 300
   looks). `#anInstanceWhichCannotBeReadSkipsItsUserTaskOnly` and
   `#aUserTaskInASubProcessNamesTheRoot` now look twice at the end: their second failure waits two
   looks.
5. *The default type of a declared id under `use-prefix` (Copilot, Major).* Valid: the declared path
   scoped `user-task-type` before comparing it with the held models, whose user task without a task
   definition keeps the unscoped default. Both types are now polled where the held models carry
   them, and the default only for that task definition.
   `ZenBpmDeclaredProcessWorkersTest#theDefaultTypeOfAHeldUserTaskIsPolledUnscoped`, red before the
   fix.
6. *A short `history-time-to-live` loses a cancellation (Copilot, Minor).* Valid: the engine deletes
   the terminated job with its instance's history, and Copilot measured it on the pinned image. Said
   in decision 12's limits, GAPS 10, the README and the startup line. No test: it pins the engine's
   cleanup, not a promise of the adapter, and its timing is the engine's.
7. *"Up to one interval late" (Copilot, Minor).* Valid: the next look is timed when one ends, and
   full slots defer more. The startup line, the README, GAPS 10 and decision 12 say what holds;
   `#theStartOfTheLooksIsSaid` asserts the line.

Mutants of the fixes: 12 of 12 killed (the default type of a declared id, and for any task
definition; forgetting after an incomplete look, after one look, never; no wait after a failure, no
longest wait; a start which keeps nobody's deliveries, one found to be nobody's during a start
remembered, nobody's deliveries asked again; every failure a WARN, a throw not remembered as said).
The first run left the race of a start during an instance read unkilled, because the modules were
read after the read; reading them at the hand-over made it testable. Clean build of the fixes,
2026-10-19 (`TZ=Etc/UTC mvn clean install`, Docker ITs included, none skipped, then
`bin/check-deploy-safety.sh`: 13 files deployed by the green build, nothing by the red gate):
engine-test-support 12 + 5 ITs; core 1,099 + 92 ITs; Spring Boot 14 + 65 ITs; smoke test 38;
Quarkus runtime 13, deployment 52, integration tests 15; coverage gate 37. Coverage per platform:
Spring Boot 100.00 % (0 of 31,295 instructions missed), Quarkus 100.00 % (0 of 31,165 missed).

**Answers to CodeRabbit's review of pull request #61, 2026-10-19.** One inline comment and two
concerns its summary retained, each checked against the code and the platform at `f9505797`:

1. *A service task of a declared id which shares a polled type stands still unnamed (inline,
   Minor).* Valid: where one held version gives a type to a user task and another to a task, the
   task's job is taken by neither the stream nor the poller, and the start's line, which named the
   definition as not served before this story, named it only as polled. The line names it as not
   served again, asserted by
   `ZenBpmDeclaredProcessWorkersTest#aTypeAUserTaskOfAnyVersionCarriesIsPolledAndNotSubscribed`,
   which was red first; 2 of 2 mutants killed.
2. *A creation told after the cancellation (summary, resilience).* Valid on one node: the
   platform's record runs a creation without looking for a closed record of the task
   (`DeliveryRecords#answerToARepeatedDelivery` looks up the delivery's own key), and a node of the
   engine which lags behind (GAPS 20) may list a job active which a look before listed terminated.
   A job told as cancelled is never told as created afterwards on the node
   (`ZenBpmUserTaskPollerTest#aCancelledUserTaskIsNeverToldAsCreatedAfterwards`, red first, its
   mutant killed). Across two nodes of the application it stays a limit, named in decision 12.
3. *The sets grow with the jobs of other applications of the shared default type (summary,
   availability).* Not changed: the sets hold what a look lists, which a look reads anyway, and the
   engine has no authentication (GAPS 12), so anybody who can reach it can make it list more. A
   user task with a task definition of its own gets a scoped type under `use-prefix`, which only
   this module's user tasks carry.
4. *Docstring coverage 43.10 % (pre-merge warning).* Not valid here: the conventions of the
   VanillaBP adapters, which this repository follows, ask for names which read like sentences and
   comments which say why; the public API carries javadoc, and private methods and test methods
   are not documented for a count.

Clean build of these fixes, 2026-10-19 (`TZ=Etc/UTC mvn clean install`, none skipped, then
`bin/check-deploy-safety.sh`, green): core 1,100 + 92 ITs, Spring Boot 14 + 65 ITs, the other
modules as above. Coverage: Spring Boot 100.00 % (0 of 31,342 instructions missed), Quarkus
100.00 % (0 of 31,212 missed).

**S8.1.1 done 2026-10-19**, merged to `main` as `fff448b` (pull request #61: story `8c7e171`; two
reviews, whose seven distinct findings were all valid and fixed in `785205c`; CodeRabbit's review,
whose inline comment and one retained concern were valid and fixed in `ef3d7a6`, after which it
raised no objection, while its other retained concern and its docstring-coverage warning were
answered without a change). F8.1 is complete; E8 goes on with S8.2.1 and S8.3.1. On `fff448b`
(GitHub keeps run logs for 90 days, so what they showed is written here as well):

- `Checks`, run
  [39297280141](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/actions/runs/39297280141):
  green on both JDKs. `bin/check-engine-copies.sh` (in the JDK 25 job) reported both copies as the
  engine's files of `bdd795a3...`. On both JDKs `ZenBpmUserTaskPollerTest` ran 34 tests,
  `ZenBpmDeclaredProcessWorkersTest` 20, `ZenBpmWorkflowProcessingTest` 12,
  `ZenBpmDeploymentWiringTest` 19, `ZenBpmDeploymentServiceTest` 30, `ZenBpmJobHandlerTest` 32,
  `ZenBpmAdapterConfigurationTest` 118, `ZenBpmUserTaskIT` 5 and `ZenBpmUserTaskRestartIT` 2, none
  skipped (engine test support 12 unit and 5 integration tests, core 1,100 unit and 92 integration
  tests, Spring Boot 14 unit and 65 integration tests, smoke test 38, Quarkus runtime 13, Quarkus
  deployment 52, Quarkus integration tests 15, coverage gate 37). The gate measured 100 % on both
  platforms: of 31,342 instructions for Spring Boot and 31,212 for Quarkus on JDK 25, of 31,332 and
  31,202 on JDK 21.
- `Publish snapshots`, run
  [39297280111](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/actions/runs/39297280111):
  green. The core snapshot it published to GitHub Packages, `2.0.0-20261019.174845-57`, holds the
  story's new main classes (`ZenBpmUserTaskPoller` with its records `Delivery`, `Retry`, `Modules`,
  `Polling` and `TimedLook`, `ZenBpmUserTaskNotifier` with `Outcome` and `Notification`,
  `NamedAggregate`) and no test class, fake, fixture or BPMN file; `quarkus/integration-tests`,
  the smoke test, the engine test support and the coverage modules skipped deployment. Both report
  pages (`spring-boot-report/index.html`, `quarkus-report/index.html`) answer HTTP 200 and show 0
  of 31,342 (Spring Boot) and 0 of 31,212 (Quarkus) instructions missed.

## F8.2 Completing and cancelling

### S8.2.1 `COMPLETE_USER_TASK` and `CANCEL_USER_TASK`

- [x] **Depends on:** S8.1.1.

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

*Note from S8.1.1, 2026-10-19.* A job failed with an error code which a boundary error event or an
error event sub-process catches is set `terminated` at the pin (`processBoundaryErrorEvent` in
`pkg/bpmn/jobs_api.go`, `pkg/bpmn/engine_error_event_subprocess.go`). So after `cancelUserTask`
the user-task poller lists the job as terminated and tells the application `CANCELED`
(`<key>-canceled`) for a user task it cancelled itself a moment before. Decide whether that
notification is wanted (check what Camunda 8 tells after its own BPMN error on a user task), and,
if not, have the node remember the keys it failed or skip such a job; pin the answer in
`#cancelUserTaskRoutesTheBoundaryEvent`.

**Tests**: `ZenBpmUserTaskIT#completeUserTaskContinuesWithSharedValues`,
`#cancelUserTaskRoutesTheBoundaryEvent`, `#completingAGoneUserTaskIsAWarnedNoOp`,
`#completingAUserTaskWhichIsGoneThrowsTaskNotFound` (phase one).

*Fit check, 2026-10-19 (implementation pipeline, batch e08-f8.2-f8.3).* Checked against `main` at
`3c7bc91`, the platform snapshot the build resolves (`vanillabp-adapter-spi`
`2.0.0-20261015.045823-21`, whose sources are `adapter-platform-integration` at `f9505797`),
Camunda 8's `Camunda8ProcessService` (`awarenessOfUserTask`, `whatThe404WasAbout`,
`cancelUserTask`), Camunda 7's `Camunda7UserTaskEventListener` and `Camunda7TaskProcessingIT`, and
the engine at the pin `bdd795a3` (behaviour through `ZenBpmUserTaskIT` against the pinned image).
Decided by the Implementor in place of the maintainers:

1. **The `CANCELED` after the application's own `cancelUserTask` (open question 1): told, decision
   50.** Camunda 7 tells it: `handleBpmnError` deletes the task, and the `delete` listener delivers
   `CANCELED` (`Camunda7TaskProcessingIT#cancelUserTaskRoutesBoundary` and its Quarkus twin assert
   it). The core takes it without harm, read in the resolved jars: phase two of `CANCEL_USER_TASK`
   closes the records of the user task (`MigrationProcessService#addressWorkflowPhaseTwo`,
   `DeliveryRecords#writeDownThatTheTaskIsClosed`); the later notification is a delivery of its own,
   because the event is part of the key (`TaskDeliveryKey`), so it runs the method with `CANCELED`
   once, is recorded as `COMPLETED` and closes what is still open. The core does not ask whether the
   application closed the task itself, so the method hears of it as a cancellation, as on Camunda 7.
   Suppressing it on the node was rejected: another node of the application, or this one after a
   restart, would still tell it. Pinned by `#cancelUserTaskRoutesTheBoundaryEvent` (one `CANCELED`,
   two records, both closed) and its Quarkus twin; a completed user task is never told as cancelled,
   because its job is `completed` (`#completeUserTaskContinuesWithSharedValues` and
   `#completingAGoneUserTaskIsAWarnedNoOp` count no cancellation).
2. **The kind behind an id (open question 2): did not fit, fixed here, decision 51.** S6.3.1's check
   accepted the job of a user task for `completeTask`, and S5.2.2's probes answered a task and a user
   task alike (its note "the probes need not tell the kinds apart" no longer holds). The platform's
   record answers the election only where its kind matches the operation; for the other kind it asks
   the adapters and words its `TaskNotFoundException` from the record only where every adapter
   answers `UNKNOWN_TO_BPMS` (`DeliveryRecords#locate`, `MigrationProcessService#unknownToEveryBpms`).
   With both probes alike, `completeTask` completed a user task on this engine, which fails on every
   other. Now both probes answer `UNKNOWN_TO_BPMS` for a job of the other kind (by `elementType`,
   `JobRef#isAUserTask`, which the poller uses as well) without reading its instance, and both checks
   before the commit throw `TaskNotFoundException` for it, whatever its state, naming the element and
   the methods for it (Camunda 8's `whatThe404WasAbout` has the same shape). Both directions tested:
   `ZenBpmTaskProbesTest#aJobOfTheOtherKindIsUnknown`, `ZenBpmTaskOutcomesTest#aJobOfTheOtherKindIsNotFound`,
   and against the pinned engine `ZenBpmUserTaskIT#theCommandsOfATaskFindNoUserTask` (the platform's
   message names `completeUserTask`) and `ZenBpmProbesIT#aWaitingJobIsActiveForItsScopeOnly` (the job
   of a service task is `SERVICE_TASK`, unknown to the probe of a user task).
3. **The word in the messages (open question 3): as recommended.** `ZenBpmTaskOutcomes` takes the
   SPI's `TaskKind`, is built once per kind, and every message says "task" or "user task"
   (`ZenBpmTaskOutcomesTest#theMessagesOfAUserTaskSayUserTask`); the incident message of a
   cancellation starts with "User task". The new message names the adapter id, the key, the element
   and the methods which end it.
4. **A user task of a declared id (open question 4): passes.** The declared ids are recorded as
   served by the module (`ZenBpmDeclaredProcessWorkersTest` asserts `moduleServing` of a held id),
   and `ZenBpmTaskOutcomesTest#aUserTaskOfADeclaredIdPassesTheCheck` checks a user task of such a
   process at the SPI boundary.
5. **Every required operation is built now**, so the process service's constructor no longer fills
   the required operations with handlers which say "not built yet" (that code became unreachable);
   only the viewer/history methods still say it (`ZenBpmProcessServiceTest`).
6. The instructions' "phase two `patchVariables` + `completeJob`" is decision 35's write up to the
   root, as for a task. The README's sentence about Camunda 8 is worded as
   `camunda8-adapter/README.md` says it (no cluster up to 8.9 offers a command to cancel a
   Camunda-managed user task by BPMN error); the ZenBPM half names its test.

**Acceptance criteria**

- [x] The gateway behind the completed user task sees the pushed values.
  `ZenBpmUserTaskIT#completeUserTaskContinuesWithSharedValues`, against the pinned engine: the
  review starts not accepted (process `Reviewing` of `usertask-it/reviews.bpmn`), the application
  accepts it and completes the user task in one transaction, and the instance passes `Decision` and
  ends at `Accepted`, never at `Rejected`; the job is `completed`, the one record of the user task is
  closed, and three more looks tell nothing as cancelled. Beside it, against the same engine:
  `#cancelUserTaskRoutesTheBoundaryEvent` (the error boundary event `Withdrawn` takes over and the
  instance ends at `ReviewWithdrawn` without passing `Decision`, the job is `terminated`, and the
  application is told once that the user task was cancelled, decision 50: two records, both closed),
  `#completingAGoneUserTaskIsAWarnedNoOp` (a completion and a cancellation in one transaction: the
  second phase two is the platform's WARN "it has ended already; the entry is consumed", and nothing
  is told as cancelled), `#completingAUserTaskWhichIsGoneThrowsTaskNotFound` (a user task completed
  by somebody else between the call and the commit: `TaskNotFoundException` "user task '...' is
  gone, the engine holds it as completed", the aggregate's change rolled back, the instance ends at
  `Rejected`), and `#theCommandsOfATaskFindNoUserTask` (decision 51: `completeTask` with the id of a
  user task throws the platform's `TaskNotFoundException`, which says that the id is the one of a user
  task and names `completeUserTask`, and the user task still waits).

*Evidence of the clean build, 2026-10-19* (`TZ=Etc/UTC mvn clean install` on JDK 25, Docker ITs
included, none skipped, then `bin/check-deploy-safety.sh`: 13 files deployed by the green build,
nothing by the red gate). Its first run failed in the core on `ZenBpmJobStreamTest#aConnectionNobodyAnswersIsNoReturn`
with `BindException: Address already in use`: the test's relay binds its port again after a cut, and
another process had taken it; the safety script's own build of the same tree and the next clean
build were green. Tests per module: engine-test-support 12 + 5 ITs; core 1,110 + 92 ITs; Spring Boot
14 + 70 ITs; smoke test 38; Quarkus runtime 13, deployment 52, integration tests 15; coverage gate
37. `ZenBpmTaskOutcomesTest` 41, `ZenBpmTaskProbesTest` 27, `ZenBpmProcessServiceTest` 23,
`ZenBpmUserTaskIT` 10. Coverage per platform: Spring Boot 100.00 % (0 of 31,622 instructions
missed), Quarkus 100.00 % (0 of 31,492 missed). Mutants: 6 of 6 killed in the core's tests (the
check of the kind off, the probe of the kind off, the user-task handlers built as task handlers, the
word always "task", no job a user task, `completeUserTask` cancelling in phase two), and 1 of 1
against the pinned engine (the probe of the kind off fails `ZenBpmUserTaskIT#theCommandsOfATaskFindNoUserTask`:
the check before the commit still refuses, with its own message instead of the platform's).

## F8.3 Quarkus twin

### S8.3.1 User tasks on Quarkus

- [x] **Depends on:** S8.2.1.

`ZenBpmWorkflowLifecycleTest#userTaskNotificationAndCompletion`, `#userTaskCancellationByError`.

*Fit check, 2026-10-19 (implementation pipeline, batch e08-f8.2-f8.3).* Checked against S8.2.1 on
the batch branch and `ZenBpmWorkflowLifecycleTest` (prod mode, `EngineUnderTest`). Nothing did not
fit. Decided by the Implementor: the model is a copy of the Spring Boot fixture
(`zenbpm-e2e/processes/zenbpm/Reviews.bpmn`, process `Reviewing`), served by `E2eReviewWorkflow`
and driven through `introspect/user-tasks/reviews/...` (`E2eUserTaskIntrospection`), since the
application runs in a JVM of its own; the application's `user-task-poll-interval` is `PT1S`, so a
test can watch three looks for a second notification. The notification test asserts the creation
once, before and after the completion, and that nothing is told as cancelled; the cancellation test
asserts the `CANCELED` of decision 50 once, so "cancellation reaches the application once" on
Quarkus needs no test of its own.

**Tests**: `ZenBpmWorkflowLifecycleTest#userTaskNotificationAndCompletion` (the creation is told
once, with the job key as the task id; the completion of the accepted review ends the instance at
`Accepted` through `Decision`, never `Rejected`; after three more looks one creation and no
cancellation), `#userTaskCancellationByError` (the error boundary event `Withdrawn` takes over and
the instance ends at `ReviewWithdrawn` without passing `Decision`; the job is `TERMINATED`; after
three more looks one creation and one cancellation).

**Acceptance criteria**

- [x] The user tasks of S8.1.1 and S8.2.1 work on a booted Quarkus application: both tests above,
  green against the pinned engine in the clean build below. They are the first Quarkus proof of
  S8.1.1's poller.

*Evidence of the clean build, 2026-10-19* (`TZ=Etc/UTC mvn clean install` on JDK 25, Docker ITs
included, none skipped, then `bin/check-deploy-safety.sh`: 13 files deployed by the green build,
nothing by the red gate). Tests per module: engine-test-support 12 + 5 ITs; core 1,110 + 92 ITs;
Spring Boot 14 + 70 ITs; smoke test 38; Quarkus runtime 13, deployment 52, integration tests 17
(`ZenBpmWorkflowLifecycleTest` 16); coverage gate 37. Coverage per platform: Spring Boot 100.00 % (0
of 31,622 instructions missed), Quarkus 100.00 % (0 of 31,492 missed). No guard is new in this
story, so its mutants prove the two tests instead, against the pinned engine: the node and the
platform deduplicate a notification each on their own (the seen-set, the delivery record), so
both were switched off together. With the seen-set's check off and a delivery id new at every
look, `#userTaskNotificationAndCompletion` counts 4 creations instead of 1; with the memory of what
was told as cancelled off as well, `#userTaskCancellationByError` counts 4 cancellations. Both
killed, 2 of 2.

**E8 "Done when", checked after S8.3.1 (2026-10-19).** "A user task's creation and cancellation
reach the application once each, completion continues the workflow with the shared values,
cancellation routes an error boundary event, on both platforms." Met, half by half:

| Half | Spring Boot | Quarkus |
|---|---|---|
| creation reaches the application once | `ZenBpmUserTaskIT#userTaskCreatedIsNotifiedOnce` (three more looks, one creation, job never streamed), across a restart `ZenBpmUserTaskRestartIT#eachEventOfAUserTaskIsToldOnceAcrossARestart` | `ZenBpmWorkflowLifecycleTest#userTaskNotificationAndCompletion` (one creation after three more looks, and after the completion) |
| cancellation reaches the application once | `ZenBpmUserTaskIT#userTaskCanceledOnInstanceCancellation`, `#aUserTaskTakenAwayIsCanceledOnce`, `#cancelUserTaskRoutesTheBoundaryEvent` (decision 50), across a restart `ZenBpmUserTaskRestartIT` | `ZenBpmWorkflowLifecycleTest#userTaskCancellationByError` (one cancellation after three more looks, decision 50) |
| completion continues the workflow with the shared values | `ZenBpmUserTaskIT#completeUserTaskContinuesWithSharedValues` | `ZenBpmWorkflowLifecycleTest#userTaskNotificationAndCompletion` |
| cancellation routes an error boundary event | `ZenBpmUserTaskIT#cancelUserTaskRoutesTheBoundaryEvent` | `ZenBpmWorkflowLifecycleTest#userTaskCancellationByError` |

On Quarkus a cancellation is proved through `cancelUserTask` only; the cancellation of an instance
over the engine's API and one by an interrupting boundary event run through the same poller and
notifier, which the Spring Boot tests prove, and the platform glue under test is the same call of
the invoker. E8 is complete; with it E7, E8 and the start half of E9 are merged, which is milestone
M4 "It talks". Left for the engine, out of the run's scope: E13.5 (user-task events on the stream)
and E13.2 (outputs without the `PATCH` before `complete`).

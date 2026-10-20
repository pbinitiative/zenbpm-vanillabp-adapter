# E9 - Lifecycle markers and multi-instance

**Goal.** `@WorkflowEnded`, the report of every start of a workflow, and the multi-instance
annotations, all delivered through model rewriting (decision 26) and the instance cache.

**Done when.** A workflow's end reaches the application with its end event id, a workflow the engine
started by timer (or somebody started past VanillaBP) gets the aggregate the application's
`@WorkflowStartedByBpms` method builds and names, and is found afterwards (decision 25), and a task
inside a parallel multi-instance sub-process binds element, index and total - on both platforms.
Met on 2026-10-20, on both platforms; the tests which hold each clause are named after S9.5.1.

Template: `Camunda8WorkflowEndedHandler`, `Camunda8BpmsInitiatedStartHandler`, `Camunda8MultiInstance`.
Contract: `WorkflowEndedInvoker`, `BpmsInitiatedStartInvoker` (validate the start events while
wiring), `MultiInstanceValue`.

---

## F9.1 Model rewriting

### S9.1.1 `ZenBpmMarkerTasks`

- [x] **Depends on:** S4.2.3 and S4.3.1. The markers are the last rewrite of `prepareBpmn`, after
  scoping (S4.2.1), correlation keys (S4.2.2) and task parameters (S4.2.3), so E4 alone fixes the
  order; nothing in E7 rewrites the model. S7.1.2 depends on this story (and on S9.3.1), because the
  start marker is what records the instance key of a message-started workflow.

**Instructions**

Every rewrite and call below is made only for a process `isClaimedByAWorkflowService(module,
process)` answers `true` for; a process nobody claims is left as it was modelled (platform decision
120). The rules are decision 26.

1. In `prepareBpmn`, after scoping, correlation keys and task parameters: where
   `workflowEndedInvoker().workflowEndedHandlerExists(module, plainProcessId)` answers `true` (which
   it also does where `vanillabp.delivery.release-on-workflow-end` is on, so the marker is gated on
   this call and not on the existence of a `@WorkflowEnded` method), for every end event of the
   TOP-LEVEL process insert a `bpmn:serviceTask id="vanillabp__ended__<endEventId>" name="VanillaBP:
   workflow ended"` with `zenbpm:taskDefinition type="<scopedTaskDefinition(module, process,
   'vanillabp__ended')>"` and an input mapping of the id variable; move every incoming
   `sequenceFlow@targetRef` of the end event to the marker; add one flow marker -> end event. The
   end event keeps its definition (error, message, terminate), so its semantics hold after the
   marker completed. A called process gets no end marker (`workflowEndedHandlerExists` answers
   `false` for it, platform decision 124).
2. Where `bpmsInitiatedStartInvoker().startsAWorkflowOfItsOwn(module, plainProcessId)` answers
   `true` (it answers `false` for a called process): insert `vanillabp__started__<startEventId>`
   right after EVERY start event of the TOP-LEVEL process - none, message and timer alike -
   (retarget its outgoing flow), type `scopedTaskDefinition(module, process, 'vanillabp__started')`.
   No marker goes into an embedded sub-process or an event sub-process: ZenBPM runs them as child
   instances, and a marker there would report the child's key as the workflow's. No input mappings
   are needed: the marker job carries a copy of the instance's variables (the S2.2.1 finding), which
   is where a redelivery reads the name back from. (The start event's own outputs are no longer
   copied into the aggregate by the core: platform decision 69 removed that copying, and the
   application's `@WorkflowStartedByBpms` method builds the aggregate.)
3. In `wireBpmn`, next to the calls of S4.3.1: hand EVERY start event the process itself holds
   (none, message, timer; not those of event sub-processes) to
   `bpmsInitiatedStartInvoker().validateBpmsInitiatedStarts(module, plainProcessId, specs)`, one
   `BpmsInitiatedStartSpec.of(elementId, kind)` each. Do this for a called process too: the core
   then refuses a timer start there, because nothing would build its aggregate (platform decision
   124). The core ends the boot when a `@WorkflowStartedByBpms` method serves a start event nobody
   reported, so a timer-only report would break a method serving a plain or message start.
4. Diagram interchange: for each inserted element add a `bpmndi:BPMNShape` (60x40, placed 80 px left
   of the end event resp. right of the start event) and `bpmndi:BPMNEdge`s with two waypoints, so the
   engine's DI stays valid and a viewer can draw it.
5. Idempotent: an element whose id starts with `vanillabp__` is never inserted twice; a second
   `prepareBpmn` of the same document is a no-op (the context guard already ensures it).
6. `whatOlderVersionsMiss` (E10) names these markers. A version deployed before the start markers
   existed writes no start row for its instances.

The cost of the start marker is one job, one transaction in the application and one variable update
per started workflow, also for the workflows the application starts itself (decision 26).

*Note from S4.3.1, 2026-10-16.* Instruction 3 is built: `wireBpmn` hands every start event of the
process itself to `validateBpmsInitiatedStarts`, a called process's too, and the context remembers
the answers of `startsAWorkflowOfItsOwn` and `workflowEndedHandlerExists` per claimed process
(`ZenBpmProcessingContext.WiredProcess#reportsItsStart` and `#reportsItsEnd`, both `false` where the
platform hands no invoker). The markers are inserted in `prepareBpmn`, which runs before `wireBpmn`,
so they cannot read those answers from the context: ask the two invokers in `prepareBpmn`, as
instructions 1 and 2 say. One more thing found there: the engine hands a called instance the
caller's business key and a copy of the caller's variables whatever the called process is
(`resolveChildBusinessKey` and `newChildProcessInstanceData` in `pkg/bpmn/sub_process.go`, proven by
a scratch program at `bdd795a3`). A process called on an aggregate of its own therefore starts
named by the caller's aggregate; its start marker has to cut that name, for example with
`zenbpm:in businessKey=""` on the call activity, which replaces the inherited key, and an input
mapping which empties the id variable, before the core reads the start.

*Fit check, 2026-10-18, decided by the Implementor where it says so.* Checked against `main`
(`0772183`), `ADAPTER-AUTHORS.md` (3.3, "A called process is not a workflow of its own"), the
platform as the adapter resolves it (`2.0.0-20261015.045823-21`: `BpmsInitiatedStartInvoker`,
`BpmsInitiatedStartSpec`; `WorkflowTaskRegistry#isACalledProcess`, `#startsAWorkflowOfItsOwn` and
`#workflowEndedHandlerExists`, read at the commit the snapshot was built from), Camunda 8's
`Camunda8DeploymentService#wireBpmn` and `Camunda8TaskWiring#listenerJobTypeOf`, and the engine at
the pin `bdd795a3` (source exported with `git archive`: `TFlowNode` and `TSequenceFlow` of
`pkg/bpmn/model/bpmn20/core.go`, `createInstance` of `pkg/bpmn/engine.go`, the timer start of
`pkg/bpmn/engine.go` and `pkg/bpmn/timer_cycle.go`; the image run by the new IT). What did not fit,
and what was decided:

1. **Where the markers are written** (a contradiction, settled by the platform's code). The plan and
   the note above say `prepareBpmn`. But the core answers `startsAWorkflowOfItsOwn` by
   `isACalledProcess`, which is true only once `validateTaskWiring` of the process ran, so in
   `prepareBpmn` it answers `true` for a called process too; `ADAPTER-AUTHORS.md` says the core can
   answer both questions "after `validateTaskWiring` of that process", and Camunda 8 writes its start
   listener in `wireBpmn` right after it. Options: `prepareBpmn` (a called process gets a marker and
   hands the core its caller's name), `wireBpmn` after `validateTaskWiring`. Decided the second,
   recorded as decision 44. `wireBpmn` already rewrites the model (`ZenBpmCallActivities`), and the
   document it rewrites is the one `deployResources` writes out.
2. **The end marker** (decided by the orchestrator, open question 1 of the brief). Instruction 1 and
   its DI and fixtures move to S9.2.1, which lands with the handler serving the marker's job: a
   marker nobody serves would stop every workflow of an application with a `@WorkflowEnded` method
   in front of its end event. `ZenBpmMarkerTasks` holds the insertion, the drawing and the guard
   against a second insertion, so the end marker joins it as a method of its own. Decision 26 is
   entered whole, with a line that the end marker comes with its handler; a test of the wiring keeps
   a process whose end is heard without any marker until then.
3. **The commits of the batch** (decided by the Implementor, for the same reason). This story's
   commit holds the rewrite, its tests and the IT on the engine. The call in `wireBpmn`, the
   subscription of the marker type and the tests at the level of the wiring (a called process, a
   process nobody claims, an end which is heard) land in the commit of S9.3.1, together with the
   handler of the marker's job, so no commit leaves a workflow at a marker nobody serves. Both
   stories are ticked with that commit.
4. **The job type** (decided by the Implementor, decision 44). `scopedTaskDefinition(module, process,
   'vanillabp__started')` is the plain `vanillabp__started` under `none`, one type for every process
   of every application deploying under `none`, so a node would be handed the marker jobs of other
   applications and leave them to their lock. The type is `vanillabp__started__<scoped process id>`
   in every mode; Camunda 8's listener types carry the process id as well.
5. **The diagram** (decided by the Implementor, decision 44). The story places the shape 80 px from
   the start event, the architecture copies it from its neighbour, which would cover the neighbour
   whole. Decided the story's rule: 60 x 40, its left edge 80 points right of the start event's left
   edge, its middle on the event's middle; the edge which left the event now leaves the marker, and
   nothing else moves. Section 3 of the architecture is corrected.
6. **What the engine follows.** It walks a model by the `outgoing` references of a flow node
   (`OutgoingAssociationsIDs` of `TFlowNode`), not by `sourceRef`. The marker takes over both: the
   event's `outgoing` references move to the marker, every flow whose `sourceRef` names the event
   leaves the marker, and the event gets one `outgoing` reference of the new flow.
7. **The claims of instruction 2, proven at the pin** (`ZenBpmMarkerTasksIT`): the marker's job
   carries the instance's variables; an instance started by a request without a business key, and
   one started by a message, carry no business key; a variable written into the instance while it
   waits at the marker reaches the task behind it. A timer start with a duration needs an instance
   (`missing processInstanceKey for timer start event with duration`), so a timer which starts a
   workflow uses `timeDate` or `timeCycle`; the IT of S9.3.1 uses `R1/PT2S`.
8. **A process called on an aggregate of its own** (open question 2 of the brief): built with
   S9.3.1, see its fit check.
9. GAPS 8 is entered under the plan's number, marked *planned* until the commit of S9.3.1.

**Tests**: `ZenBpmMarkerTasksTest` with fixtures: none/error/terminate end events, two end events,
end and start events inside a sub-process and an event sub-process left alone, a none, a message and
a timer start each getting a marker, a called process getting neither marker, a process nobody
claims left untouched, idempotency, DI shapes present; `validateBpmsInitiatedStarts` receives every
start event, a called process's included; the rewritten model deploys
(`ZenBpmDeploymentIT#aModelWithMarkersDeploys`).

**Acceptance criteria**

- [x] The engine accepts every rewritten fixture (400 would name the element). Against the pinned
  image: `ZenBpmMarkerTasksIT` deploys `models/markers.bpmn` with a marker behind its plain,
  message and timer start event, and a workflow started by a request and one started by a message
  each wait at their marker until its job is completed; `ZenBpmDeploymentIT#aModelWithMarkersDeploys`
  boots an application whose process the engine then holds with the marker, its job type and its
  shape, and whose first job of a new instance is the marker's; the three models of
  `ZenBpmLifecycleIT` (timer, plain start with a call activity, called process) deploy and run.
- [x] A process without an end marker gate and which starts no workflow of its own is not rewritten.
  `ZenBpmDeploymentWiringTest#aCalledProcessAndOneNobodyClaimsGetNoMarker` (a called process and a
  process nobody claims get no element of the reserved prefix and no marker type), and
  `ZenBpmDeploymentServiceIT#aVersion1ModelDeploysByteForByte` (a called process reaches the engine
  byte for byte). No end marker is written at all yet (fit check, point 2):
  `ZenBpmDeploymentWiringTest#aProcessWhichStartsAWorkflowGetsItsStartMarkers` wires a process
  whose end is heard and finds start markers only.
- [x] A `@WorkflowStartedByBpms` method serving a plain start event does not end the boot.
  `ZenBpmLifecycleIT` boots `DeliveryWorkflow` and `InvoiceWorkflow`, whose methods serve plain
  start events, and every case runs. Clean build of the commit of S9.3.1 (`TZ=Etc/UTC mvn clean
  install`, Docker ITs included): engine test support 12 + 5, core 1007 + 84, Spring Boot 11 + 48,
  smoke test 37, Quarkus runtime 10, deployment 51, integration tests 10, gate 37; coverage 100 %
  on both platforms (0 of 27,276 instructions missed on Spring Boot, 0 of 27,150 on Quarkus);
  `bin/check-deploy-safety.sh` green. Mutants: 3/3 killed for this story (the guard against a
  second insertion, the two guards of the drawing).

**S9.1.1 done 2026-10-18**, merged to `main` together with S9.3.1 as `5041e85` (pull request #55).
The runs on that commit and what they showed are recorded under S9.3.1. The end marker goes to
S9.2.1, as the note there says.

*Note from S9.2.1, 2026-10-20.* The end marker is written now, with the handler of its job
(decision 52), so the condition of decision 26's last paragraph is met. The acceptance above which
found no end marker describes the commit of S9.3.1: its test became
`ZenBpmDeploymentWiringTest#aProcessWhoseStartAndEndAreHeardGetsItsMarkers`, which finds both. An
error end event gets no end marker, unlike instruction 1 (see the fit check of S9.2.1).

## F9.2 The end of a workflow

### S9.2.1 `ZenBpmWorkflowEndedHandler`

- [x] **Depends on:** S9.1.1.

**Instructions**

A `JobHandler` for the `vanillabp__ended` type of each process: facts from the cache, aggregate id
from the business key or id variable, `WorkflowEndedContext{kind = COMPLETED, endTime = now,
endEventId = the id after "vanillabp__ended__", processVersion, adapterId, workflowId,
businessKey}`. `workflowId` is the root instance key (decision 28) - the same value the tasks of
this workflow report, so the core finds them. `businessKey` is the instance's business key where it
has one; the core checks it against the aggregate id (platform decision 69). Then
`workflowEndedInvoker() .workflowEnded(module, plainProcessId, context)`: before the
`@WorkflowEnded` method runs, the core reports every task it still believes open in that workflow as
`@TaskEvent CANCELED` (platform decision 73). Then `completeJob` (which lets the end event fire). A
deleted aggregate is skipped, not an error. An exception fails the job at the engine with retries
and backoff, like any handler (decision 27). The end kinds are `COMPLETED` and `CANCELED`; a
canceled instance passes no marker, so `CANCELED` is not reported (GAPS 9).

*Note from S9.1.1, 2026-10-18.* This story writes the end marker too: instruction 1 of S9.1.1,
with its DI and its fixtures in `ZenBpmMarkerTasksTest` (`models/markers.bpmn` holds a plain, a
terminate and an error end event, and end events inside an embedded and an event sub-process), so
that the marker and the handler of its job land together. `ZenBpmMarkerTasks` holds the insertion
of the start markers, the drawing and the guard against a second insertion; the end marker is a
method of its own beside `insertStartMarkers`, inserted in `wireBpmn` after `validateTaskWiring`,
like the start markers (decision 44: the core answers `workflowEndedHandlerExists` correctly only
then). Its job type should carry the scoped process id as the start marker's does. Decision 26 says
the end marker comes with its handler; `ZenBpmDeploymentWiringTest#aProcessWhichStartsAWorkflowGetsItsStartMarkers`
holds that none is written until then and changes with this story. A process declared without a
model subscribes its start markers where a held model carries them (decision 46,
`ZenBpmDeclaredProcessWorkers`); its workflows reach their END marker far more often, so the end
marker's type of such a process needs the same.

*Note from S8.1.1, 2026-10-19.* The user-task poller (decision 12) tells a terminated user task
as `CANCELED` whenever its job is listed and the node has not told it yet, and after a restart the
platform's record answers. Where this story's end marker makes the core release the records of an
ended workflow (`vanillabp.delivery.release-on-workflow-end`), a user task the end of its workflow
took away is told again after the next restart, because the engine still lists its terminated job
until the instance's history is deleted. Decide here whether the poller leaves out a terminated job
whose instance ended at an end event (the end already cancels the open tasks of the workflow,
platform decision 73), and test it with `ZenBpmUserTaskRestartIT`. A cancelled instance runs no
marker, so its user tasks keep being told by the poller (GAPS 9).

*Fit check, 2026-10-20, decided by the Implementor where it says so.* Checked against `main`
(`1bf5ad8`), `ADAPTER-AUTHORS.md` (the inbound notifications: name the workflow as the deliveries
name it, skip a missing aggregate, `workflowEndedHandlerExists` is `false` for a called process),
the platform as the adapter resolves it (`2.0.0-20261015.045823-21`: `WorkflowEndedInvoker`,
`WorkflowEndedContext`, `WorkflowTaskRegistry#workflowEndedHandlerExists`, `#workflowEnded` and
`#reportTheTasksTheEndedWorkflowTookAway`, `WorkflowEndedHandlers`, `DerivedCancelations`,
`MigrationProcessService#deliverWorkflowTask`, read in the snapshot's sources), Camunda 8's
`Camunda8WorkflowEndedHandler` and `Camunda8TaskWiring#attachWorkflowEndedListener`, and the engine
at the pin `bdd795a3` (source exported with `git archive`: `handleEndEvent`, `handlePlainEndEvent`
and `handleTerminateEndEvent` of `pkg/bpmn/engine.go`, `handleEndErrorEvent` and
`failUnhandledEndError` of `pkg/bpmn/engine_error_end_event.go`, `GetProcessInstance` of
`internal/rest/server.go` and `internal/cluster/server/server.go`, whose `activeElementInstances` are
the instance's tokens which wait, run or failed); a scratch Go program against the engine's
in-memory runtime and REST probes against the pinned image, then the ITs below. What did not fit,
and what was decided:

1. **Where, under which type, how drawn** (decision 52). As decision 44 says for the start markers:
   written in `wireBpmn` right after `validateTaskWiring`, because the core answers
   `workflowEndedHandlerExists` with no for a called process only then; type
   `vanillabp__ended__<scoped process id>`; the marker takes over every flow which led to the end
   event, and its right edge stands 80 points left of the end event's right edge.
2. **End events the engine does not simply complete at** (open question 1, decided by the
   Implementor as recommended, decision 52, which supersedes decision 26 in part). Measured on the
   pinned image: a terminate end event while another branch waits completes the instance and
   terminates the other branch's job and its embedded sub-process, so its marker reports at once. An
   error end event which nothing catches, with an id on its definition as every modeller writes it,
   raises an incident (`unhandled error end event <id>: <code>`) and leaves the instance active
   without a token (`ZenBpmMarkerTasksIT#anUncaughtErrorEndEventRaisesAnIncident`); without an id on
   the definition the pinned engine completes the instance instead, a quirk which was not chased
   further. Zeebe raises an incident as well, and the Camunda 8 adapter, whose listener sits on the
   process, then reports nothing, and an error event sub-process which catches the error lets the
   workflow go on there. So no error end event gets a marker. A message end event's job is created
   once the marker's job is completed, and the instance completes after it, so its marker stands in
   front and the message end event's own task runs after the report, a named limit.
3. **An end event which ends a branch only** (not foreseen by the plan, decided by the Implementor,
   decision 52). A none end event in one branch while another branch still waits does not end the
   instance (the engine completes it only where no token, job or event sub-process is left), so the
   marker as planned would report too early, cancel the tasks of the other branch and release its
   records: against the pinned image, without the check below, `ZenBpmLifecycleIT` was told the end
   at `Inspected` while the refund branch still waited. Options: report at every end event; report
   after the instance completed, which needs a poll or loses the report at a crash; read where the
   tokens stand before the report. Decided the last: a token at anything but an end marker or an end
   event means another branch goes on, and the job is completed without a report; no token elsewhere
   but an active child instance means an event sub-process still runs, and the job is left to its
   lock until it ended; otherwise the end is reported. A terminate end event skips the read. Named
   limits: two branches which end at once may both report; a workflow which ends in an interrupting
   event sub-process, or in an error event sub-process, is not reported, as nothing of the process
   itself ends it; a lagging node (GAPS 20) can hide both of two ends which happen at once.
   `ZenBpmRestClient#activeElementsOf` reads the tokens (`ZenBpmRestClientContractIT#theTokensOfAnInstanceStandWhereTheyWait`).
4. **The user tasks of an ended workflow** (open question 2, decided by the Implementor as
   recommended, decision 53, which supersedes decision 12 in part). Read in the resolved platform:
   (a) `#reportTheTasksTheEndedWorkflowTookAway` reports every open record of the workflow id as
   `CANCELED`, and a user task's creation leaves one (its method takes a `@TaskId`), so user tasks
   are among them; (b) the core answers a delivery by its delivery key only, so the poller's
   `<key>-canceled` runs the method a second time. Measured: with the rule switched off,
   `ZenBpmUserTaskRestartIT#aUserTaskTheEndTookAwayIsToldOnceAcrossARestart` counted two
   cancellations before any restart, and the second record outlived `release-on-workflow-end`. So
   the notifier tells no cancellation of a user task whose workflow's root instance completed and
   holds a completed job of the process's end markers, only for a process which writes end markers
   (`WiredProcess#endMarkerJobType`); the completed job is the proof that the report committed,
   because a marker's job is completed only after it, and a version without markers has none. A
   cancelled instance's user tasks are told as before (GAPS 9). *Corrected after the reviews:* the
   rule lost cancellations the core never delivered, and was replaced by a delivery id which the
   core answers from its record; see finding 3 of the answers below.
5. **A deleted aggregate** (open question 3). `WorkflowEndedHandlers#invokeHandler` logs it and
   returns, and `DerivedCancelations` swallows the failed cancellation of its task; the marker's job
   is completed and the workflow ends (`ZenBpmLifecycleIT#aWorkflowWhoseAggregateIsGoneEnds`).
6. **The business key and a called process** (open question 4). The marker's job runs in the root
   instance, so its business key is the root's, reported where it is not empty.
   `TestStarts` now answers `workflowEndedHandlerExists` as the core does, `false` for a called
   process, and `ZenBpmDeploymentWiringTest#aCalledProcessAndOneNobodyClaimsGetNoMarker` wires a
   called process whose end the application listens to and finds no end marker.
7. **The incident's cause sentence** (decided by the Implementor). The brief named
   `ZenBpmTaskOutcomes#whereTheIncidentComesFrom`, which serves `completeTask` and `cancelTask` of a
   task or user task carrying an incident; no application holds the key of a marker's job, so it
   never reaches that method, which stays as it is. The failure message of the end marker's job,
   which the engine writes into the incident at the last attempt, names the `@WorkflowEnded` method
   instead (`ZenBpmWorkflowEndedHandlerTest#aReportWhichThrowsFailsTheJob`).
8. **Retries.** The marker's job has the engine's default of one attempt (decision 38), so a
   `@WorkflowEnded` method which throws raises an incident at once unless the model or
   `JOBS_DEFAULT_RETRIES` gives more; `ZenBpmLifecycleIT` runs its engine with three, and
   `#aReportWhichThrewIsRepeated` sees the second attempt after the `retry-backoff`.
9. **Decision 26's last paragraph** ("until that change no end marker is written") is met by this
   change; the entry is left as it is.
10. **Existing tests the marker changes.** `ZenBpmDeploymentWiringTest#aProcessWhichStartsAWorkflowGetsItsStartMarkers`
    became `#aProcessWhoseStartAndEndAreHeardGetsItsMarkers`, and `#aClaimedProcessGetsEveryWiringCallOnce`
    expects the end marker of `Done`; `JobRunning`'s process carries two end markers. No assertion
    was weakened. A process declared without a model serves the end markers of its held models
    (`ZenBpmDeclaredProcessWorkersTest#theEndMarkersOfAHeldModelAreServed` and three more). The
    Quarkus twin is S9.5.1's; the core's new classes are covered by the core's tests on both
    platforms.
11. GAPS 8, 9 and 10 now say what is built; no new gap was entered (GAPS 32 stays free). Decisions
    52 and 53 are new.

**Tests**: `ZenBpmWorkflowEndedHandlerTest`; Spring `ZenBpmLifecycleIT#theEndOfAWorkflowIsReportedWithItsEndEvent`
(two end events, each reported with its own id), `#aCancelledWorkflowReportsNothing`,
`#theEndCancelsTheTasksStillOpen` (a task waiting in a parallel branch inside an embedded
sub-process is reported `CANCELED` before `@WorkflowEnded` runs, because both carry the root key).
Added: `ZenBpmMarkerTasksTest` (nine cases of the end marker), `ZenBpmMarkerTasksIT#aWorkflowWaitsAtTheMarkerOfItsEndEvent`
and `#anUncaughtErrorEndEventRaisesAnIncident`, `ZenBpmUserTaskPollerTest` (one case after the
reviews),
`ZenBpmDeclaredProcessWorkersTest` (four cases), `ZenBpmRestClientTest#whereTheTokensStandIsRead`,
`ZenBpmRestClientContractIT#theTokensOfAnInstanceStandWhereTheyWait`, `ZenBpmLifecycleIT#aBranchWhichEndsWhileAnotherRunsReportsNothing`,
`#theEndWaitsForAnEventSubProcessStillRunning`, `#aReportWhichThrewIsRepeated`,
`#aWorkflowWhoseAggregateIsGoneEnds`, `ZenBpmUserTaskRestartIT#aUserTaskTheEndTookAwayIsToldOnceAcrossARestart`,
and after the reviews `#aCancellationTheEndCouldNotReportIsToldByALook`,
`#aUserTaskTheEndTookAwayIsToldTwiceWhereTheEndReleasesTheRecords` and three cases of
`ZenBpmWorkflowEndedHandlerTest`.

**Acceptance criteria**

- [x] The instance completes only after the handler committed. `ZenBpmLifecycleIT#theEndCancelsTheTasksStillOpen`
  reads the instance from inside the `@WorkflowEnded` method and finds it active, and
  `ZenBpmWorkflowEndedHandlerTest#theContextNamesWhatTheJobAndItsInstanceSay` finds no command sent
  to the engine while the core is told; `ZenBpmMarkerTasksIT#aWorkflowWaitsAtTheMarkerOfItsEndEvent`
  holds the instance active while the marker's job waits.
- [x] The context carries the root instance key as `workflowId`. `ZenBpmWorkflowEndedHandlerTest#theContextNamesWhatTheJobAndItsInstanceSay`,
  and against the pinned image `ZenBpmLifecycleIT#theEndCancelsTheTasksStillOpen`: the core finds
  the task inside the embedded sub-process, recorded under the root, and cancels it before
  `@WorkflowEnded` runs. Clean build of the
  story's commit (`TZ=Etc/UTC mvn clean install` on OpenJDK 25.0.2, Docker ITs included, none
  skipped): engine test support 12 + 5, core 1,142 + 96, Spring Boot 14 + 78, smoke test 38, Quarkus
  runtime 13, deployment 52, integration tests 17, gate 37; coverage 100 % on both platforms (0 of
  33,280 instructions missed on Spring Boot, 0 of 33,150 on Quarkus); `bin/check-deploy-safety.sh`
  green. Mutants: 24 of 24 killed, 21 at the unit level (the read of the tokens, the read of the
  child instances, the terminate end event's shortcut, a token at an end event, the default of an
  end event the model does not hold, the instance a node does not know, the completion after the
  report, the empty business key, the failure while stopping, the error end event's exclusion, the
  second insertion, the terminate end event's kind, the drawing without bounds, the poller's read of
  the root's state, its gate on the end markers, its failed read, the declared process's gate on the
  end and its read for an id wired by element id, the gate on `workflowEndedHandlerExists` in
  `wireBpmn`, the end markers' job-timeout, the routing of the marker's job) and 3 against the
  pinned engine (the poller's rule, failing `ZenBpmUserTaskRestartIT#aUserTaskTheEndTookAwayIsToldOnceAcrossARestart`
  with two cancellations before any restart; the read of the tokens, failing
  `ZenBpmLifecycleIT#aBranchWhichEndsWhileAnotherRunsReportsNothing` and `#theEndCancelsTheTasksStillOpen`
  with an end reported at `Inspected`; the read of the child instances, failing
  `#theEndWaitsForAnEventSubProcessStillRunning`).

*Answers to two reviews of e09-f9.2, 2026-10-20* (Claude: approve after one Major; Copilot:
changes requested, 2 Major, 1 Minor; both of `a3917f7`). Every finding was checked against the
code, the resolved platform snapshot `2.0.0-20261015.045823-21` and the pinned engine; all seven
were valid. Decisions 52 and 53, not merged yet, were rewritten in place.

1. **An end event was read by the model this application deployed** (Copilot 1, Claude 2). The
   handler looked the end event up in this boot's model, so an older version's terminate end
   event whose id the new model lacks went unreported while another branch ran, and an older end
   of a branch which the new model names as terminate reported too early; Copilot reproduced both
   on the pinned engine. Fixed: the handler reads the model of the instance's own version
   (`facts.definitionKey()`, `GET /v1/process-definitions/{key}`) once per version, and uses it
   both for the kind of end event and for a token which stands at an end event; a model which
   cannot be read leaves the job to its lock. Tests:
   `ZenBpmWorkflowEndedHandlerTest#anEndEventIsReadByTheModelOfTheInstancesVersion` (both
   directions, one read for two jobs) and `#aModelWhichCannotBeReadLeavesTheJob`. Decision 52.
2. **The end markers of older versions stopped their workflows once nobody listened** (Claude 1).
   The type was subscribed only where this boot wrote markers, and a ZenBPM job nobody subscribes
   is never handed out and raises no incident. Fixed: the type of every wired process's end
   markers is subscribed wherever the module subscribes a type anyway
   (`ZenBpmProcessingContext#jobTypes`), with its job-timeout; a marker's job where the end is no
   longer heard is completed without a report (`#anEndNobodyListensToAnyMoreIsCompletedWithoutAReport`);
   a declared id serves the end markers of its held models wherever the platform hands an
   invoker of ends (`ZenBpmDeclaredProcessWorkersTest#theEndMarkersOfAHeldModelAreServedWhereNobodyListensAnyMore`).
   A module which subscribes no type at all opens no stream for them, so an adapter id without a
   job stream still starts; that limit is in decision 52. The subscription lists of
   `ZenBpmWorkflowProcessingTest`, `ZenBpmDeploymentWiringTest` and
   `ZenBpmDeclaredProcessWorkersTest` name the new type.
3. **The poller's rule dropped cancellations the core never delivered** (Copilot 2, Claude 3).
   `DerivedCancelations` reports each open record in a transaction of its own and swallows a
   failure, leaving the record open, and a creation whose method takes no `@TaskId` leaves no
   record; the rule left both out, and it looked at the called process instead of the root.
   Fixed: the rule is gone. The poller tells a user task's cancellation under the job key alone,
   the id the core gives its own derived cancellation (the task id), so the core answers the
   poller from its record where it reported the cancellation, and runs the method where it did
   not. Against the pinned engine: `ZenBpmUserTaskRestartIT#aUserTaskTheEndTookAwayIsToldOnceAcrossARestart`
   (records kept: creation, cancellation and end once each, before and after a restart),
   `#aCancellationTheEndCouldNotReportIsToldByALook` (the method throws at the end; a look tells it)
   and `#aUserTaskTheEndTookAwayIsToldTwiceWhereTheEndReleasesTheRecords` (the limit: the released
   record cannot answer the look). Decision 53, and decision 12's "superseded in part" line.
4. **Documents** (Copilot 3, Claude 4 and 5). The SPI mapping's user-task row and GAPS 10 no longer
   call the poller the only source; README and decision 52 say that two branches whose markers wait
   at the same time may both report, that an end behind a running event sub-process waits up to one
   `job-timeout` of `vanillabp__ended`, and what a report costs.

Mutants of the fixes: 9 of 9 killed. At the unit level: the guard for an end nobody hears, the
endings of this boot instead of the version's, the cache per version, the version's end events in
the read of the tokens, the cancellation's id (7 poller tests), the end types riding along (5
processing tests), the declared gate on an invoker of ends, the job-timeout of an end type nobody
hears. Against the pinned engine: the cancellation's id, failing
`ZenBpmUserTaskRestartIT#aUserTaskTheEndTookAwayIsToldOnceAcrossARestart` with two cancellations.
Clean build of the review-fix commit (`TZ=Etc/UTC mvn clean install` on OpenJDK 25.0.2, Docker ITs
included, none skipped): engine test support 12 + 5, core 1,143 + 96, Spring Boot 14 + 80, smoke
test 38, Quarkus runtime 13, deployment 52, integration tests 17, gate 37; coverage 100 % on both
platforms (0 of 33,281 instructions missed on Spring Boot, 0 of 33,151 on Quarkus);
`bin/check-deploy-safety.sh` green.

*Answer to CodeRabbit on pull request #65, 2026-10-20.* One actionable comment, valid: the fix of
finding 2 subscribes the end markers' type also where the platform hands no invoker of ends, while
`ZenBpmJobHandler` built the handler of the end markers only with one, so the job of an older
version's marker met `null` and was left to its lock again and again. Fixed: the handler is built
in every case, and a missing invoker counts as an end nobody listens to
(`ZenBpmWorkflowEndedHandlerTest#anEndMarkerWithoutAnInvokerOfEndsIsCompletedWithoutAReport`; the
mutant without the guard is killed).

**S9.2.1 done 2026-10-20**, merged to `main` as `b1a93dd` (pull request #65: story `a3917f7`; two
reviews, whose seven findings, one Major and two Minor of Claude's and two Major and one Minor of
Copilot's, were all valid and fixed in `376cd2c`, answered above; CodeRabbit's one actionable
comment, a null handler of end markers where the platform hands no invoker of ends, was valid and
fixed in `af530ac`, and CodeRabbit resolved its thread). F9.2 is complete; E9 goes on with S9.4.1
and S9.5.1. On `b1a93dd` (GitHub keeps run logs for 90 days, so what they showed is written here
as well):

- `Checks`, run
  [39375039905](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/actions/runs/39375039905):
  green on both JDKs. `bin/check-engine-copies.sh` (in the JDK 25 job) reported both copies as the
  engine's files of `bdd795a3...`. On both JDKs `ZenBpmWorkflowEndedHandlerTest` ran 17 tests,
  `ZenBpmMarkerTasksTest` 19, `ZenBpmMarkerTasksIT` 5, `ZenBpmUserTaskPollerTest` 35,
  `ZenBpmDeclaredProcessWorkersTest` 25, `ZenBpmDeploymentWiringTest` 19,
  `ZenBpmWorkflowProcessingTest` 12, `ZenBpmLifecycleIT` 12 and `ZenBpmUserTaskRestartIT` 5, none
  skipped (engine test support 12 unit and 5 integration tests, core 1,144 unit and 96 integration
  tests, Spring Boot 14 unit and 80 integration tests, smoke test 38, Quarkus runtime 13, Quarkus
  deployment 52, Quarkus integration tests 17, coverage gate 37). The gate measured 100 % on both
  platforms: of 33,277 instructions for Spring Boot and 33,147 for Quarkus on JDK 25, of 33,267 and
  33,137 on JDK 21.
- `Publish snapshots`, run
  [39375039892](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/actions/runs/39375039892):
  green. The core snapshot it published to GitHub Packages, `2.0.0-20261020.052814-61`, holds the
  story's new main classes (`ZenBpmWorkflowEndedHandler` with `Ended` and `Elsewhere`,
  `ZenBpmMarkerTasks$Ending`) and the changed ones (`ZenBpmMarkerTasks`, `ZenBpmUserTaskNotifier`,
  `ZenBpmProcessingContext`), 237 classes, and no test class, fake, fixture or BPMN file. Both
  report pages (`spring-boot-report/index.html`, `quarkus-report/index.html`) answer HTTP 200 and
  show 0 of 33,277 (Spring Boot) and 0 of 33,147 (Quarkus) instructions missed.

## F9.3 Workflows the engine starts itself

### S9.3.1 `ZenBpmBpmsInitiatedStartHandler`

- [x] **Depends on:** S9.1.1 and S6.1.1.

**Instructions**

A `JobHandler` for the `vanillabp__started` type of each process, following decision 25:

1. Build `BpmsInitiatedStartContext{startEventId = the id after "vanillabp__started__", kind = the
   start event's kind (NONE, MESSAGE or TIMER, remembered per process while wiring), variables = the
   job's variables, nativeInstanceId = the root instance key (decision 28; for a top-level start it
   is the job's own instance), processVersion, aggregateSyncMode = FULL, businessKey = the
   instance's business key where it has one, adapterId}`. There is no start time and no natural
   identity: the application names the workflow (platform decision 69).
2. `startWorkflowByBpms(module, plainProcessId, context)`. The core reads the name the instance
   already holds - the business key first, else the id variable - and answers in one of three ways:
   the instance carries a name and an aggregate exists (the application started it itself, the
   normal case for a NONE or MESSAGE start of its own workflow): `created() == false`; the instance
   carries no name (a timer start, or a start past VanillaBP over REST, the UI or `zenctl`): the
   `@WorkflowStartedByBpms` method builds the aggregate and names it, `created() == true`; the
   instance carries a name no aggregate has: refused. In every case the core writes the start row
   with `nativeInstanceId`, so later operations find the workflow through the fourth argument of
   `awarenessOfWorkflow` (S5.2.1).
3. Where `created()` is true: `PATCH /v1/process-instances/{key}/variables` with
   `result.variables()` (they already carry the id under `workflowAggregateIdName()`), then
   `completeJob`. Otherwise complete the job at once.
4. A redelivery finds the name in the variables written in step 3 and answers `created() == false`,
   so nothing is built twice. The one residual window: a crash after the aggregate's commit and
   before the PATCH lets the redelivery build a second aggregate unless the application's id is
   deterministic. Camunda 8 has the same window; the wiki says so.
5. A refusal fails the job like any handler (decision 27).

*Fit check, 2026-10-18, decided by the Implementor where it says so.* Checked against the
platform as the adapter resolves it (`2.0.0-20261015.045823-21`: `BpmsInitiatedStartContext`,
`BpmsInitiatedStartResult`, `startWorkflowByBpms` of `WorkflowTaskRegistry` and
`BpmsInitiatedStartExecution`, read at the commit the snapshot was built from), Camunda 8's
`Camunda8BpmsInitiatedStartHandler`, and the engine at the pin `bdd795a3` (`createCallActivity`,
`resolveChildBusinessKey` and `handleParentProcessContinuationForCallActivity` of
`pkg/bpmn/sub_process.go`, `PropagateOnlyMappedOutputs` of `pkg/bpmn/runtime/varholder.go`,
`openapi/api.yaml`; the image run by `ZenBpmMarkerTasksIT` and `ZenBpmLifecycleIT`). What did not
fit, and what was decided:

1. **When the name is written** (decided by the Implementor, decision 46). Instruction 3 writes
   `result.variables()` where `created()`. The core also answers `created() == false` for an
   instance which carries only a business key, and where the application's method named the
   workflow after an aggregate which exists; both instances would go on without the id variable
   their tasks and correlation keys read. So the handler writes wherever the instance does not
   carry the result's id under its name, read from the instance and not from the job, whose
   variables are those of its creation (corrected after the reviews, see their answers below), and
   completes the job without variables. The
   application's own start carries the id and writes nothing (open question 4: the cost is the job
   and the transaction; the README says so, `ZenBpmBpmsInitiatedStartHandlerTest#theApplicationsOwnStartWritesNothing`
   holds it). Camunda 8 completes its listener job with the variables every time.
2. **The instance key** (decided by the Implementor). A marker sits in the process itself, so the
   job's instance is the workflow's root; the handler reports it without walking up.
3. **The kind of the start event** (open question 5): remembered per process while wiring, in
   `WiredProcess#startMarkers`, where the markers are written. A start event the deployed model
   does not hold, of an older version, is reported as `NONE`.
4. **How the marker's job runs** (decided by the Implementor, decision 46). `ZenBpmJobHandler`
   hands it to `ZenBpmBpmsInitiatedStartHandler` inside the module's drain, with the commands
   repeated within the lock; the type is subscribed with the `job-timeout` resolved for the task
   name `vanillabp__started`, and a failure carries the `retry-backoff` resolved for it. The
   marker's job has no `retries` of its own (decision 38), so the engine's default applies.
5. **A process called on an aggregate of its own** (open question 2 of the brief, decided by the
   Implementor, decision 45). Camunda 8 hands the caller's variables on as well and does nothing
   about it; the core refuses a name no aggregate of the called process's class carries. Decided:
   a call activity of a claimed process which calls a process of the module which starts a
   workflow of its own on another aggregate gets `zenbpm:in businessKey=""` and an input mapping
   `=null` of the called process's id variable, written in `deployResources`, once every process
   of the module is wired; the engine copies back only mapped outputs, so the caller keeps its id.
   A call activity with a `zenbpm:in` of the modeller's, one naming its process by an expression,
   and one calling a process of another module or application are left alone: the boot cannot
   judge them, and their called process's marker hands the core the caller's name, which it
   refuses unless an aggregate of that id exists. Proven against the pinned image by
   `ZenBpmLifecycleIT#aProcessCalledOnAnAggregateOfItsOwnBuildsItsOwn`.
6. **Existing tests the marker changes** (open question 3). Every claimed process which starts a
   workflow of its own subscribes one more type, so the tests which list the subscribed types name
   it (`ZenBpmWorkflowProcessingTest`, `ZenBpmDeclaredProcessWorkersTest`,
   `ZenBpmDeploymentIT#aStartedModuleSubscribesItsJobTypeAndStopsFirst`). A workflow started under
   a business key no aggregate carries is now refused at its start marker, before any task, so
   `ZenBpmTaskProcessingIT#aJobOfAWorkflowThisApplicationDoesNotOwnEndsInAnIncident` finds the
   incident there, with the core's message. Two IT cases which complete jobs by hand complete the
   marker first, and the byte-for-byte case wires its process as a called one, which gets no marker
   (`ZenBpmDeploymentServiceIT`). `ZenBpmInboundIdempotencyIT` refuses the print's completion once
   the print waits, because a refusal armed before the start hit the marker's completion first, and
   reads the print's job by its type. On Quarkus, `ZenBpmWorkflowLifecycleTest#serviceTaskRunsAndGatewaySeesValues`
   counts four completed jobs, the first of them the marker's, and the helper which awaits the one
   job of a task leaves the marker's out. No assertion was weakened.
7. **A process declared without a model** (decided by the Implementor, decision 46): it subscribes
   the type of its start markers where a model the engine holds under the id carries one
   (`ZenBpmDeclaredProcessWorkers` reads those models anyway), with the start events of the held
   models which carry them. `ZenBpmRenamedProcessIT` found it: its first version starts a workflow
   and closes at once, so the renamed version may meet the workflow still at its start marker.
8. GAPS 1 is entered under the plan's number; GAPS 8 is no longer *planned* for the start
   markers. The Quarkus twins are S9.5.1's; the existing Quarkus tests run their workflows through
   the start marker and stay green.

**Tests**: `ZenBpmBpmsInitiatedStartHandlerTest` (the three answers, the redelivery after the PATCH,
the business key reported); `ZenBpmLifecycleIT#aTimerStartBuildsTheAggregate` (`timer-start.bpmn`
with `PT2S`; assert the aggregate exists with the id the method chose, the id variable holds it, the
following service task ran, `correlateMessage` on that aggregate works because the start row hands
the instance key to the probe), `#aWorkflowStartedPastVanillaBpGetsAnAggregate` (a plain start over
`POST /v1/process-instances` without a business key), `#theApplicationsOwnStartBuildsNothing`.

**Acceptance criteria**

- [x] A timer-started workflow gets the aggregate the application's method built, and every later
  operation on it is routed while the start row lives. `ZenBpmLifecycleIT#aTimerStartBuildsTheAggregate`
  against the pinned image: the timer `R1/PT2S` starts the workflow without a business key, the
  method is told `TIMER` and the start event, the id variable holds the id it chose, the task
  behind the start runs on that aggregate, and `correlateMessage` on it reaches the workflow, which
  has no business key to be searched by, so it completes.
  `#aWorkflowStartedPastVanillaBpGetsAnAggregate` does the same for a plain start over `POST
  /v1/process-instances` without a business key, and `#aProcessCalledOnAnAggregateOfItsOwnBuildsItsOwn`
  for a process a call activity starts on an aggregate of its own (decision 45).
- [x] A workflow the application started itself passes its start marker without a second aggregate.
  `ZenBpmLifecycleIT#theApplicationsOwnStartBuildsNothing` (the method is not called, no second
  aggregate exists, the instance keeps its id) and
  `ZenBpmBpmsInitiatedStartHandlerTest#theApplicationsOwnStartWritesNothing` (the job is completed
  without any update of the instance's variables, which the README promises). The clean build's
  numbers are under S9.1.1. Mutants: 14/14 killed for this story, 12 at the unit level (the check
  whether the instance carries the name, the guard while stopping, the routing of the marker's
  job, the four conditions of the cut of the caller's name, the gate on `startsAWorkflowOfItsOwn`
  in `wireBpmn`, the empty business key, the instance which ended meanwhile, the markers of a held
  model, the declared process without a served task type) and 2 against the pinned engine (the
  cut without its empty business key, the cut without its empty id variable, each failing
  `#aProcessCalledOnAnAggregateOfItsOwnBuildsItsOwn`).

**Answers to two reviews of S9.1.1 and S9.3.1, 2026-10-18** (one by Claude, changes requested, 1
Major, 2 Minor and 2 Nits; one by Copilot, changes requested, 2 Major and 1 Minor). Every claim was
checked in the code of the branch and, for the engine, at the pin. Two findings were raised by both,
so six distinct findings remain; all six were valid and are fixed. Decision 46, new on this branch,
was corrected in place before the merge; no new decision was needed.

- **A second delivery of the start marker after its update built a second aggregate** (Claude 1,
  Copilot 1, Major). The handler judged whether the instance carries the id by the job's variables.
  But the engine stores a job's variables when it creates the job and hands those out on every
  delivery (`InputVariables` in `pkg/bpmn/jobs.go` and `internal/cluster/jobmanager/server.go:712`
  at `bdd795a3`); `PATCH /v1/process-instances/{key}/variables` changes the instance only. Both
  reviewers probed the pinned image, and Copilot reproduced two aggregates for one instance with
  this branch's application. So after a completion which did not reach the engine, or a lost lock,
  the next delivery handed the core no name, and the method built again. The unit test passed
  only because its fake handed the id back in the job. The context now carries the job's variables
  with the id variable the instance holds now, from the facts the dispatcher reads (the instance
  cache reads an instance again where it did not hold the id). Decision 46, the handler's javadoc,
  section 4.7 of the architecture and the README say so, and name the one way left to a second
  aggregate: a read a lagging node answers before it has seen the update (GAPS 20). Decision 25's
  "a second delivery finds the id" holds again as written. Tests:
  `ZenBpmBpmsInitiatedStartHandlerTest#aRedeliveryFindsTheNameWrittenBefore` now hands a job
  without the id and an instance with it, as the engine does, and
  `ZenBpmLifecycleIT#aSecondDeliveryOfTheMarkerBuildsNothing` refuses the marker's first completion
  through a `RefusingEngineProxy`, so the engine hands the marker out again after its lock of two
  seconds, and asserts that the method ran once and the instance keeps the first id.
- **The task-level refusal of a workflow this application does not own lost its IT** (Claude 2,
  Minor). Every claimed top-level process now refuses such a start at its marker, so the E6 case
  had been turned into a test of the marker. The path is still reachable where no marker runs (a
  called process, a version deployed before the markers). The task-it application now holds
  `ParcelRating` (`task-it/parcel-rating.bpmn`), a process called on the parcel's aggregate, and
  `ZenBpmTaskProcessingIT#aJobOfAWorkflowThisApplicationDoesNotOwnEndsInAnIncident` starts it
  under a stranger's business key: the incident is on its task `Rate`, with the core's "does not
  own" message, as before. The marker's refusal is
  `#aWorkflowStartedUnderTheNameOfAStrangerEndsInAnIncidentAtItsStartMarker`. The E6 evidence says
  so.
- **A declared process whose methods are all wired by element id served no start marker** (Claude
  3, Minor; Copilot 2, Major). `ZenBpmDeclaredProcessWorkers` returned before it read the held
  models where the methods name no task definition, so a workflow started right before the rename
  waited at its marker for good, and the start line said "nothing serves its workflows" where only
  markers were served. A start marker needs no task definition: the held models are now read for
  their markers in that case too, the WARN says that they are served, a read which fails is a WARN
  of its own, and the start line names the marker's job type among what serves the workflows.
  Tests: `ZenBpmDeclaredProcessWorkersTest#theStartMarkersOfAnIdWiredByElementIdAreServed`,
  `#theStartMarkersOfAnIdWiredByElementIdWhoseModelsCannotBeReadAreAWarn`, the WARN asserted in
  `#theStartMarkersAreServedWithoutATaskTypeOfTheId`, and
  `#aDeclaredIdWiredByElementIdIsNamedWithBothWaysOut`, which now sees the engine asked.
- **A declared process served its start markers where the platform hands no invoker of starts**
  (Claude 4, Nit). The marker's job would have met no handler. The declared process now looks for
  start markers only where the platform hears a start of its workflows (`startsAWorkflowOfItsOwn`
  through the invoker, `false` without one), as a claimed process does, and does not ask the
  engine for them. `#withoutAnInvokerOfStartsNoStartMarkerIsServed`,
  `#withoutAnInvokerOfStartsAnIdWiredByElementIdAsksTheEngineNothing`.
- **The README's start-marker text was one line of 1,169 characters in the middle of another
  paragraph** (Claude 5, Nit). It is a paragraph of its own now, wrapped like the rest.
- **The README promised the cut of the caller's name without its limits** (Copilot 3, Minor). It
  now says that a call activity is left as the modeller wrote it where they wrote a `zenbpm:in` of
  their own or a mapping of the called process's id variable, and where the called process belongs
  to another workflow module or application; `ZenBpmCallActivitiesTest#everyOtherCallIsLeftAlone`
  and `#theModellersMappingOfTheIdStays` hold it.

Seen to fail, each restored afterwards (7 of 7 mutants killed, against green unmutated runs): the
id of the instance left out of the context (`#aRedeliveryFindsTheNameWrittenBefore`, and against
the pinned engine `ZenBpmLifecycleIT#aSecondDeliveryOfTheMarkerBuildsNothing`); the held models
not read for markers where no task definition is named
(`#theStartMarkersOfAnIdWiredByElementIdAreServed`,
`#theStartMarkersOfAnIdWiredByElementIdWhoseModelsCannotBeReadAreAWarn`); starts taken as heard
without an invoker, and the gate in the reading of the held models dropped (each
`#withoutAnInvokerOfStartsNoStartMarkerIsServed`); the marker's job type left out of the start
line (`#theStartMarkersAreServedWithoutATaskTypeOfTheId`); the held models read, and the WARN
promising their markers, without an invoker of starts (each
`#withoutAnInvokerOfStartsAnIdWiredByElementIdAsksTheEngineNothing`).

`TZ=Etc/UTC mvn clean install` on OpenJDK 25.0.2 builds green after the fixes with every
integration test run and none skipped (engine test support 12 unit and 5 integration tests, core
1,011 unit and 84 integration tests, Spring Boot 11 unit and 50 integration tests, smoke test 37,
Quarkus runtime 10, Quarkus deployment 51, Quarkus integration tests 10, coverage gate 37). The gate
measures 100 % of 27,401 instructions for Spring Boot and 100 % of 27,275 for Quarkus, and
`bin/check-deploy-safety.sh` passes. No flake was hit.

**S9.1.1 and S9.3.1 done 2026-10-18**, merged to `main` as `5041e85` (pull request #55). The pull
request went in after two reviews, whose six distinct findings were all valid and fixed in
`5cc201b`, and after CodeRabbit's review. Its one comment, a note under S7.1.2 which still described
the rule the reviews had corrected, was valid and fixed in `cfecc6a`, and CodeRabbit accepted the
fix. E9 goes on with S9.2.1 after E7 and E8, and the global order goes on with S7.1.2. On `5041e85`
(GitHub keeps run logs for 90 days, so what they showed is written here as well):

- `Checks`, run
  [39169363378](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/actions/runs/39169363378):
  green on both JDKs. `bin/check-engine-copies.sh` (in the JDK 25 job) reported both copies as the
  engine's files of `bdd795a3...`. On both JDKs the following ran, none skipped:
  `ZenBpmMarkerTasksTest` 10, `ZenBpmMarkerTasksIT` 2, `ZenBpmBpmsInitiatedStartHandlerTest` 14,
  `ZenBpmCallActivitiesTest` 3, `ZenBpmDeploymentWiringTest` 18, `ZenBpmDeclaredProcessWorkersTest`
  18, `ZenBpmLifecycleIT` 5, `ZenBpmDeploymentIT` 3 and `ZenBpmTaskProcessingIT` 20. The totals
  were engine test support 12 unit and 5 integration tests, core 1,011 unit and 84 integration
  tests, Spring Boot 11 unit and 50 integration tests, smoke test 37, Quarkus runtime 10, Quarkus
  deployment 51, Quarkus integration tests 10, coverage gate 37. The gate measured 100 % on both
  platforms: of 27,401 instructions for Spring Boot and 27,275 for Quarkus on JDK 25, of 27,391 and
  27,265 on JDK 21.
- `Publish snapshots`, run
  [39169363330](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/actions/runs/39169363330):
  green. The core snapshot it published to GitHub Packages, `2.0.0-20261018.214724-51`, holds the
  stories' new main classes (`ZenBpmMarkerTasks`, `ZenBpmCallActivities`,
  `ZenBpmBpmsInitiatedStartHandler` and `$Start`) and no test class, fake, fixture or BPMN file.
  `quarkus/integration-tests`, the smoke test, the engine test support and the coverage modules
  skipped deployment. Both report pages (`spring-boot-report/index.html`,
  `quarkus-report/index.html`) answer HTTP 200 and show 0 of 27,401 (Spring Boot) and 0 of 27,275
  (Quarkus) instructions missed.

## F9.4 Multi-instance

### S9.4.1 `ZenBpmMultiInstance` (engine-gated)

- [x] **Depends on:** S6.1.1. **Engine twin:** E13.7.

**Instructions**

While wiring, ask `multiInstanceElementNames(module, process, elementId or taskDefinition)` for
every multi-instance element and refuse the deployment where a method wants the item and the element
has no `inputElement` (the model reader of S4.1.1 already collects it).

*Note from S4.3.1, 2026-10-16.* The paragraph above is built: `model.ZenBpmMultiInstanceItems`,
called by `wireBpmn`, asks by element id and by task definition for every task and user task inside
a multi-instance chain and refuses an element of that chain without `inputElement`.

A job inside a multi-instance element belongs to a child instance (`processType = multiInstance`)
whose variables carry the input element under the model's `inputElement` name. `ZenBpmMultiInstance`
walks up the parent chain of the job's instance through the cache; for every multi-instance body:
`element = child.variables[inputElement]`, `index` = the position of the child among
`rest.children(parentKey)` ordered by `createdAt` then key (cached per parent while the parent is
active), `total` = `parent.variables[inputCollection]` size (a FEEL expression collection: evaluate
only the bare-variable case, else `null` with a DEBUG line). The map is keyed by the multi-instance
element's id (from the model, remembered per process while wiring), outermost first.

*Note from S4.2.3, 2026-10-16, corrected after the reviews of F4.2.* Inside a multi-instance body a
job carries the item of its own iteration, the input mappings S4.2.2 and S4.2.3 write (the
aggregate id and the `@TaskParam` names, the item of an outer iteration included where a method
reads it by name) and nothing else; a variable of the workflow which no mapping names is not among
them (proven with the engine's in-memory runtime at `bdd795a3`; see the fit check of S4.2.3). An
outer item reaches a job only where a `@TaskParam` names it, so the job's variables cannot replace
the walk up the parent chain above.

**Tests**: `ZenBpmMultiInstanceTest` (fake server with a parent and three children);
`ZenBpmLifecycleIT#theIterationIsReported` (`multi-instance.bpmn`: parallel MI sub-process over three
ids with a service task binding `@MultiInstanceElement`, `@MultiInstanceIndex`, `@MultiInstanceTotal`).

*Fit check, 2026-10-20, decided by the Implementor where it says so.* Checked against `main`
(`03a2ada`), `ADAPTER-AUTHORS.md` (`getMultiInstances`; `multiInstanceElementNames`: "an id your
model does not know belongs to a caller"), the platform as the adapter resolves it
(`2.0.0-20261015.045823-21`: `TaskInvocationContext#getMultiInstances`, `MultiInstanceValue`, whose
`total` is an `int`, `CoreParameterBinders` and `HandlerContexts#of`, which asks the adapter only
where a parameter binds an iteration, `NestedMultiInstancesKeepTheirOrderTest`,
`TheMultiInstanceShapeOfAHeldVersionTest`), Camunda 8's `Camunda8MultiInstance` (mappings written
at deployment, `-1` for a total it cannot tell, the caller's levels of its decision 30), and the
engine at the pin `bdd795a3` (source exported with `git archive`: `handleMultiInstanceActivity`,
`startParallelMultiInstance`, `startSequentialMultiInstance` and
`sortFlowElementInstancesSortByCreatedAtAndKey` of `pkg/bpmn/multi_instance.go`,
`createInstanceWithStartingElements` of `pkg/bpmn/engine.go`, `FindInactiveInstancesToDelete` of
`internal/sql/queries/process_instance.sql`, `openapi/api.yaml`, `WaitingJob` of `zenbpm.proto`);
REST probes against the pinned image (parallel and sequential multi-instance sub-processes, parallel
and sequential multi-instance service tasks, a multi-instance user task, nested multi-instance
sub-processes, a multi-instance call activity, a collection written as a FEEL expression), then the
tests below. What did not fit, and what was decided:

1. **The instance tree** (open question 1(a); the plan's picture was wrong, settled by the engine's
   code and the probes). The plan has one child instance of the process type `multiInstance` per
   iteration, whose variables carry the item. The engine creates ONE body per activation of the
   element, of the type `multiInstance`, under the instance the element stands in, holding a copy of
   that instance's variables. An iteration of a sub-process is a child instance of the body of the
   type `subprocess`, holding the item; an iteration of a call activity a child of the type
   `callActivity`, the item among its copied variables; an iteration of a task (service, send, user)
   is no instance at all but a job of the body, which carries the item. A sequential element has the
   same body, its iterations created one after the other. Built: every `multiInstance` instance on
   the way up is a body, and its iteration is the instance below it on the way up, or the job where
   the job waits in the body itself. The javadoc of `ProcessInstanceRef.ProcessType`, which called
   `MULTI_INSTANCE` one iteration, is corrected.
2. **The index** (open question 1(b)). The engine hands an iteration the item at the number of
   iterations the body created before it, and orders the outputs of the iterations by creation and
   then by key. Children and jobs are listed by key, not by creation. An iteration which ended stays
   listed: the engine's history clean-up deletes a child instance only once its parent ended, so an
   iteration keeps its place while its body runs. Built: the index is the position of the job's
   iteration among the body's children (`GET .../child-processes`) or jobs (`GET
   /v1/jobs?processInstanceKey=`, new `ZenBpmRestClient#jobsOf`) ordered by `createdAt` and then
   key. The job stream names no element instance (GAPS 15), so the iteration of a task is told by its
   job key. Measured: `ZenBpmRestClientContractIT#theIterationsOfAMultiInstanceElementAreKeptInTheOrderOfTheirItems`.
3. **The total** (open questions 1(c) and 5). The body holds a copy of the variables the collection
   was evaluated against, so for a collection written as a bare variable the body's copy is exactly
   what the engine iterates over; an iteration's own instance holds the item alone (GAPS 31). The
   result of any other FEEL expression is kept only in the history row of the element in the parent
   instance, which cannot be told apart from another activation of the same element.
   `MultiInstanceValue#total` is an `int`, so the plan's `null` cannot reach the core, and Camunda 8
   reports `-1` for a total it cannot tell. Decided `-1` with a DEBUG line; the README says so.
   *Corrected after the reviews of the batch:* the body's copy is NOT what the engine iterates over.
   The engine reads the collection of every iteration from the history row of the element's entry,
   and the body's copy is written by every completion and push of the aggregate's values; the total
   is now read from that history row, which also counts a FEEL expression. See the answers to the
   reviews below.
4. **Which element a body belongs to** (not foreseen, decided by the Implementor, decision 54). The
   REST API names a body's element in its tokens only, which are gone once the body ended. Decided the
   model: walking up, every body met is the next multi-instance element around the job from the
   inside out (`ZenBpmBpmnModel#multiInstanceChainOf`), read from the model of the instance's own
   version by its definition key, once per version, as decision 52 reads end events. A body the model
   does not show ends the walk with a DEBUG line.
5. **Mappings at deployment as Camunda 8 writes them** (open question 2, decided as recommended).
   The engine has no `loopCounter` to map an index from, and a mapping of the total alone would still
   leave the read of the index. Not built; decision 54 says why.
6. **The iterations of a caller** (open question 3, decided as recommended, decision 54). The
   engine's parent chain crosses a call activity: a called instance (`callActivity`) hangs under the
   caller's body where the call activity iterates (probed), and the platform asks for the ids of a
   caller's elements. Built: the walk goes on in the caller, from the call activity of the caller's
   model which calls the process (as the engine knows it) and iterates exactly where the called
   instance hangs under a body; where the caller's model holds more than one such call activity, the
   walk stops there, and the caller's iterations are not told. *Corrected after the reviews:* where
   there is more than one, the one the caller waits at is taken, and a WARN names the limit where
   that still leaves none or several (see the answers below). A call activity which
   names its process by an expression never matches.
7. **User tasks** (open question 4, decided as recommended). The notifier's context answers
   `getMultiInstances()` the same way (`ZenBpmUserTaskPollerTest#aUserTaskInAnIterationIsToldItsIteration`);
   a notification holds no lock, so the attempts of the repetition alone bound its reads.
8. **When it is read** (decided by the Implementor, decision 54). The core asks `getMultiInstances()`
   only where a parameter binds an iteration, once per such parameter. Built: read on the first ask,
   once per delivery, inside the core's invocation, through the delivery's command repetition; a read
   which fails fails the invocation like a method which threw.
9. **The cache** (open question 6). The iterations of a body are kept per workflow module, bounded
   by `instance-cache-max-entries` (least recently used first), and read again whenever the iteration
   asked about is missing, as a later iteration of a sequential element
   (`ZenBpmMultiInstanceTest#aSequentialTaskFindsAnIterationCreatedLater`, against the engine
   `ZenBpmLifecycleIT#theIterationsOfASequentialTaskAreReportedOneAfterTheOther`). Nothing else ends
   an entry, because an iteration keeps its place. The model of each version is kept without a bound,
   as the end markers' are. The instance cache itself is unchanged.
10. **Nested elements.** Probed: an inner element's collection is evaluated in the outer iteration's
    instance, which holds the item alone (GAPS 31), so a collection of the root iterates over
    nothing there; a nested collection has to come from the item or be mapped in. The unit test of
    nested iterations uses a collection mapped in.
11. **Documents.** GAPS 16 is entered under the plan's number; the deviations row, the SPI mapping
    row, the architecture's line and the README say what is built. Decision 54 is new. The box
    "Superseded by E13.7" stays open: the walk stays until the engine hands out `loopCounter` and
    `nrOfInstances` (E13.7 is out of the pipeline's scope).

Built and added: `ZenBpmMultiInstanceTest` (19 cases at the story's commit, 27 after the reviews,
against a fake engine in the pinned engine's
shape: a body with copied variables, iterations as children or jobs, listed by key and not in the
order they were created), `ZenBpmJobHandlerTest#theIterationsAreReadOnceWhereTheMethodAsksForThem`
and `#aReadOfTheIterationsWhichFailsFailsTheJob`,
`ZenBpmUserTaskPollerTest#aUserTaskInAnIterationIsToldItsIteration`,
`ZenBpmRestClientTest#theJobsOfAnInstanceAreReadToTheEnd`,
`ZenBpmRestClientContractIT#theIterationsOfAMultiInstanceElementAreKeptInTheOrderOfTheirItems`, and
against the pinned image `ZenBpmLifecycleIT#theIterationIsReported` (`lifecycle-it/multi-instance.bpmn`,
process `Picking`: a parallel multi-instance sub-process over three parcels, `=parcels` of the
aggregate, with a service task binding `@MultiInstanceElement`, `@MultiInstanceIndex` and
`@MultiInstanceTotal`), `#theIterationsOfASequentialTaskAreReportedOneAfterTheOther` and
`#aCalledProcessIsToldTheIterationOfItsCaller` (`Labelling`, called on the same aggregate by a
multi-instance call activity). The aggregate's list `parcels` is a declared aggregate value
(`lifecycle-it.yaml`): the platform refuses a collection which travels undeclared.

**Acceptance criteria**

- [x] Index counts from 0, total equals the collection size, element is the item.
  `ZenBpmLifecycleIT#theIterationIsReported` against the pinned image tells the three tasks
  `a:0/3`, `b:1/3` and `c:2/3`, and so do `#theIterationsOfASequentialTaskAreReportedOneAfterTheOther`
  (in that order) and `#aCalledProcessIsToldTheIterationOfItsCaller` (the caller's iteration).
  Clean build of the story's commit (`TZ=Etc/UTC mvn clean install` on OpenJDK
  25.0.2, Docker ITs included, none skipped): engine test support 12 + 5, core 1,167 + 97, Spring
  Boot 14 + 83, smoke test 38, Quarkus runtime 13, deployment 52, integration tests 17, gate 37;
  coverage 100 % on both platforms (0 of 34,305 instructions missed on Spring Boot, 0 of 34,175 on
  Quarkus); `bin/check-deploy-safety.sh` green. Mutants: 20 written, 19 killed. At the unit level
  18 of 19: the read again of a body which lacks the iteration, the failure for an iteration nobody lists,
  the repetition of that read, the end of the walk at a body the model does not show, the switch to
  the caller, the refusal of two candidate call activities, the filter of a caller's call activities
  by whether they iterate (dropped altogether), the caller's call which does not iterate inside an
  iteration, the total of an expression, the check for a list, the body kept, the model kept, the
  model read only at a body, an unreadable model, the order outermost first, the read once per
  delivery, the notification's iterations, the repetition of the notification's read. Against the
  pinned engine 1 of 1: without the read again, `ZenBpmLifecycleIT#theIterationsOfASequentialTaskAreReportedOneAfterTheOther`
  waits in vain for the second iteration. The one survivor, the order by key alone instead of by
  creation and then key, is equivalent on what the engine produces: the leader generates an
  iteration's key from the same clock and in the same order as its `createdAt`, so the two orders
  agree; the order by creation is kept because the engine orders the iterations by it itself.
- [ ] Superseded by E13.7 once the engine hands out `loopCounter` and `nrOfInstances`. Waits for
  E13.7, which is out of the pipeline's scope; until then the walk of decision 54 answers.

**Answers to two reviews of e09-f9.4-f9.5, 2026-10-20** (one by Claude, approve after 1 Major, with
2 Minor and 1 Nit; one by Copilot, changes requested, 1 Major). Every claim was checked in the code
of the branch and, for the engine, at the pin `bdd795a3` and against its image. The Major was
raised by both, so four distinct findings remain; all four were valid and are fixed. Decision 54,
new on this branch, was corrected in place before the merge; no new decision was needed.

- **The total was read from a copy which the adapter itself overwrites** (Claude 1, Copilot 1,
  Major). The fit check took the body's copy of the variables for what the engine iterates over.
  It is not: `handleMultiInstanceActivity` of `pkg/bpmn/multi_instance.go` reads the collection of
  every iteration from the history row which `startParallelMultiInstance` and
  `startSequentialMultiInstance` save for the element's entry in the instance the element stands
  in, under the name of the input element, and never reads the body. The body's copy is written by
  every completion inside the body (the aggregate's values go into every instance up to the
  workflow) and by every push (decision 49), so a list the application shortened or grew while the
  iterations run gave a reader without a kept body (another node, a restart, an evicted entry,
  `instance-cache-max-entries: 0`) a total other than the number of iterations; Copilot reproduced
  `total=1` for three iterations with the branch's classes. Probed again: after a `PATCH` of the
  body, the root's history row keeps the three items, under the key of the root's token at the
  element, for the parallel sub-process and for the sequential task during its second iteration;
  an expression is kept evaluated, under `""` where the element names no input element. The total
  is now the size of that row's collection (`ZenBpmRestClient#tokensAt`, the history's
  `inputVariables`), read once per body and kept with it; where the instance shows no token at the
  element, the history holds no list, or two activations at once differ in size, it is `-1`. So a
  collection written as a FEEL expression is counted as well, and the `-1` for an expression is
  gone. Tests: `ZenBpmMultiInstanceIT#aCollectionChangedWhileTheIterationsRunKeepsItsTotal` (the
  list changed in the body, and in the workflow during the sequential task, then readers which
  keep nothing; on the old code it reports `1` for each of the three iterations) and
  `#aCollectionWrittenAsAnExpressionIsCounted` (`-1` on the old code),
  `ZenBpmRestClientContractIT#theCollectionOfAnActivationIsKeptInTheHistory`,
  `ZenBpmRestClientTest#theTokensAtAnElementAreRead`, and in `ZenBpmMultiInstanceTest`
  `#theTotalIsNotReadFromTheBodysCopy`,
  `#aCollectionWrittenAsAnExpressionIsCountedAsTheEngineEvaluatedIt`,
  `#anActivationWithoutATokenHasNoTotal`, `#anEntryTheHistoryDoesNotListHasNoTotal`,
  `#theEntryIsFoundOnALaterPage`, `#twoActivationsOfOneSizeTellIt`,
  `#twoActivationsOfWhichTheHistoryListsOneHaveNoTotal`,
  `#twoActivationsOfDifferentSizesHaveNoTotal`.
  Decision 54, GAPS 16, the deviations row, the SPI mapping row and the README say what is built.
- **No test held the tie-break by key** (Claude 2, Minor). The case asked for the iteration with a
  millisecond of its own; it now asks for one of the two created in the same millisecond, and the
  mutant without the tie-break is killed.
- **An ambiguous caller ended the walk with a DEBUG line only** (Claude 3, Minor). Where the
  caller's model calls the process from more than one call activity, the one at which the instance
  the called one hangs under waits is now taken: probed, a body of a multi-instance call activity
  shows its tokens at the call activity, pinned by the contract test above. Where that still leaves
  none or several, a WARN once per version and called process names the fix (call the process from
  one call activity, or hand the item over as a `@TaskParam`).
  Tests: `ZenBpmMultiInstanceTest#aCallerWithTwoCallsOfTheProcessIsToldTheOneItsBodyWaitsAt` and
  `#aCallerWithTwoCallsOfTheProcessWhichWaitsAtNoneIsNotTold`.
- **`instance-cache-max-entries` was described as bounding the instance facts alone** (Claude 4,
  Nit). Its descriptions (the configuration's javadoc, the Spring Boot keys which become the
  configuration metadata, the Quarkus properties) now name the iterations of the bodies each
  workflow module keeps, and `0` keeping none.

Mutants of the new guards, each against a green unmutated run, 11 of 11 killed by
`ZenBpmMultiInstanceTest`: every history entry taken, no empty input-element name, missing entries
ignored, the last page ignored (killed by the surefire timeout), the tokens not asked again, the
history not read again, no narrowing by the waiting token, narrowing only above two candidates, the
WARN every time, no tie-break, differing totals taken. Against the pinned engine, the class as it
was at the story's commit fails both cases of `ZenBpmMultiInstanceIT`. Clean build after the fixes
(`TZ=Etc/UTC mvn clean install` on OpenJDK 25.0.2, Docker ITs included, none skipped): engine test
support 12 + 5, core 1,176 + 100, Spring Boot 14 + 83, smoke test 38, Quarkus runtime 13,
deployment 52, integration tests 20, gate 37; coverage 100 % on both platforms (0 of 34,526
instructions missed on Spring Boot, 0 of 34,396 on Quarkus); `bin/check-deploy-safety.sh` green.

*CodeRabbit on pull request #67, 2026-10-20* (2 actionable comments on `9464bcc`, both valid, fixed
in one commit after it):

- **The history's input variables were not normalized** (Minor). `historyEntry` handed them on as
  Jackson read them, while every other variable map of the client goes through `variables(...)`: a
  whole number as a `Long`, nested lists and objects unmodifiable. Fixed;
  `ZenBpmRestClientTest#aPageOfTheHistory` now reads a list of numbers and refuses to change it.
- **A `-1` total was kept with the body** (Minor). Where the first ask met a node which lagged
  behind, every later task of that body was told `-1` until the entry left the cache. A kept `-1` is
  now read again at the next ask, and a total which was read stays, because the engine never
  changes an activation's collection (`ZenBpmMultiInstanceTest#aTotalWhichCouldNotBeReadIsReadAgain`).
  This goes further than the suggestion, which read it again only for a later sequential
  iteration: a parallel body's next task gets the read as well.

Mutants: 4 of 4 killed (no normalization, the `-1` kept, the iterations read again with the total,
the total read on every ask). The pre-merge "docstring coverage" warning is CodeRabbit's generic
measure; the repository's rule is javadoc on the public API, which `javadoc:javadoc-no-fork` checks
in the build. Clean build after the fixes (`TZ=Etc/UTC mvn clean install`, OpenJDK 25.0.2, Docker
ITs included, none skipped): engine test support 12 + 5, core 1,177 + 100, Spring Boot 14 + 83,
smoke test 38, Quarkus runtime 13, deployment 52, integration tests 20, gate 37; coverage 100 % on
both platforms (0 of 34,550 instructions missed on Spring Boot, 0 of 34,420 on Quarkus);
`bin/check-deploy-safety.sh` green.

## F9.5 Quarkus twins

### S9.5.1 Lifecycle on Quarkus

- [x] **Depends on:** S9.4.1.

`ZenBpmWorkflowLifecycleTest#theWorkflowEndIsReported`, `#theEngineStartsAWorkflowOnItsOwn` (the
aggregate the method named, found afterwards through the start row, as S9.3.1),
`#multiInstanceBindsElementIndexAndTotal`.

*Fit check, 2026-10-20, decided by the Implementor where it says so.* Checked against `main`
(`03a2ada`) with S9.4.1 on top, the Quarkus glue (`quarkus/runtime`: `ZenBpmDeploymentServiceProducer`
hands the platform's `WorkflowEndedInvoker` and `BpmsInitiatedStartInvoker` through
`AdapterCollaboratorsSupport.collaborators` to the same `ZenBpmDeploymentService` the Spring Boot
glue builds; `quarkus/deployment`: `ZenBpmIntegrationProcessor` registers the producers and records
nothing about processes), and the Spring Boot ITs of S9.1.1 to S9.4.1. What did not fit, and what
was decided:

1. **The build-time side** (the brief's question). The Quarkus extension wires nothing at build
   time: the markers are written, `ZenBpmProcessingContext#jobTypes` is filled and a declared
   process's held models are read by the core at the application's start, exactly as on Spring
   Boot, from the collaborators the runtime producer hands it. What the glue could get wrong is
   whether the platform's Quarkus integration hands the two invokers at all; the twins below prove
   it does: the end marker's type is subscribed (`#theWorkflowEndIsReported` reads the start line,
   which names `vanillabp__ended__zenbpm-e2e__Packing`), its job reaches the `@WorkflowEnded`
   method, and the start marker of a workflow started past VanillaBP reaches the
   `@WorkflowStartedByBpms` method.
2. **The twins** (as the story names them, decided by the Implementor where the story leaves the
   fixture open). The one prod-mode application of `ZenBpmWorkflowLifecycleTest` got two processes:
   `Packing` (a copy of the Spring Boot `Picking` without its call activity: a parallel
   multi-instance sub-process and a sequential multi-instance task over the aggregate's declared
   list `items`, and a `@WorkflowEnded` method), and `Receiving` (started over the engine's REST API
   without a business key; its `@WorkflowStartedByBpms` method builds the aggregate, and a message
   correlated on it later reaches the workflow only through the start row).
   `#theWorkflowEndIsReported`, `#theEngineStartsAWorkflowOnItsOwn` and
   `#multiInstanceBindsElementIndexAndTotal` hold E9's three clauses on Quarkus. Started past
   VanillaBP rather than by a timer: the start marker's handler does not tell the two apart beyond
   the kind it reports, which the Spring Boot `#aTimerStartBuildsTheAggregate` holds, and a timer
   would start a workflow at every boot of the shared application.
3. **Spring Boot ITs without a Quarkus twin, and why none is needed.** Each of them proves a
   decision the core takes alone, from the same collaborators, so the core's unit tests (counted on
   both platforms) and the twins above, which prove the glue, cover them:
   `ZenBpmLifecycleIT#aSecondDeliveryOfTheMarkerBuildsNothing`, `#theApplicationsOwnStartBuildsNothing`
   (every Quarkus workflow the application starts passes its start marker already),
   `#aProcessCalledOnAnAggregateOfItsOwnBuildsItsOwn` (a rewrite of `deployResources`),
   `#aBranchWhichEndsWhileAnotherRunsReportsNothing`, `#theEndWaitsForAnEventSubProcessStillRunning`,
   `#aCancelledWorkflowReportsNothing`, `#aReportWhichThrewIsRepeated`,
   `#aWorkflowWhoseAggregateIsGoneEnds`, `#theEndCancelsTheTasksStillOpen`,
   `#aCalledProcessIsToldTheIterationOfItsCaller`, `ZenBpmUserTaskRestartIT` and
   `ZenBpmRenamedProcessIT` (a declared process's held models: read by
   `ZenBpmDeclaredProcessWorkers` in the core, and a twin would need a second boot of the shared
   prod-mode application).
4. **The incident's cause sentence of the end marker.** It is the failure message the core's
   `ZenBpmWorkflowEndedHandler` writes from the thrown exception
   (`ZenBpmWorkflowEndedHandlerTest#aReportWhichThrowsFailsTheJob`); nothing of the platform's glue
   takes part in it, so no Quarkus twin is needed.
5. Decisions, GAPS: nothing new.

**Acceptance** (the story names its three tests; recorded here as its criteria)

- [x] On a booted Quarkus application against the pinned image:
  `ZenBpmWorkflowLifecycleTest#theWorkflowEndIsReported` (the end event `Packed` reaches the
  `@WorkflowEnded` method, the instance completes after it, and the start line names the end
  markers' type), `#theEngineStartsAWorkflowOnItsOwn` (a start over `POST /v1/process-instances`
  without a business key gets the aggregate `receipt-<order>` the `@WorkflowStartedByBpms` method
  built, the start row names the instance, the id variable holds the id, and a correlation on the
  aggregate completes the workflow) and `#multiInstanceBindsElementIndexAndTotal` (`a:0/3`,
  `b:1/3`, `c:2/3` for the parallel sub-process and, in that order, for the sequential task).
  Clean build of the story's commit (`TZ=Etc/UTC mvn clean install` on OpenJDK 25.0.2,
  Docker ITs included, none skipped): engine test support 12 + 5, core 1,167 + 97, Spring Boot
  14 + 83, smoke test 38, Quarkus runtime 13, deployment 52, integration tests 20, gate 37; coverage
  100 % on both platforms (0 of 34,305 instructions missed on Spring Boot, 0 of 34,175 on Quarkus);
  `bin/check-deploy-safety.sh` green. The story adds no production code and no guard, so it has no
  mutant of its own; its three tests are the twins of the Spring Boot ITs.

**E9 "Done when", checked 2026-10-20 at the end of the epic.** Met on both platforms:

- *A workflow's end reaches the application with its end event id:* Spring Boot
  `ZenBpmLifecycleIT#theEndOfAWorkflowIsReportedWithItsEndEvent`, Quarkus
  `ZenBpmWorkflowLifecycleTest#theWorkflowEndIsReported`.
- *A workflow the engine started by timer, or somebody started past VanillaBP, gets the aggregate
  the application's `@WorkflowStartedByBpms` method builds and names, and is found afterwards
  (decision 25):* Spring Boot `ZenBpmLifecycleIT#aTimerStartBuildsTheAggregate` and
  `#aWorkflowStartedPastVanillaBpGetsAnAggregate`, Quarkus
  `ZenBpmWorkflowLifecycleTest#theEngineStartsAWorkflowOnItsOwn` (past VanillaBP; the timer is held
  on Spring Boot only, see point 2 of the fit check above).
- *A task inside a parallel multi-instance sub-process binds element, index and total:* Spring Boot
  `ZenBpmLifecycleIT#theIterationIsReported`, Quarkus
  `ZenBpmWorkflowLifecycleTest#multiInstanceBindsElementIndexAndTotal`.

What E9 leaves to the engine: E13.6 (end and cancellation notifications, GAPS 8 and 9), E13.7
(`loopCounter` and `nrOfInstances`, GAPS 16) and E13.14 (the root key on a job, GAPS 22), all out of
the pipeline's scope; the adapter's fallbacks above answer until then.

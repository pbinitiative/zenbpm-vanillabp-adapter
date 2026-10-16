# E9 - Lifecycle markers and multi-instance

**Goal.** `@WorkflowEnded`, the report of every start of a workflow, and the multi-instance
annotations, all delivered through model rewriting (decision 26) and the instance cache.

**Done when.** A workflow's end reaches the application with its end event id, a workflow the engine
started by timer (or somebody started past VanillaBP) gets the aggregate the application's
`@WorkflowStartedByBpms` method builds and names, and is found afterwards (decision 25), and a task
inside a parallel multi-instance sub-process binds element, index and total - on both platforms.

Template: `Camunda8WorkflowEndedHandler`, `Camunda8BpmsInitiatedStartHandler`, `Camunda8MultiInstance`.
Contract: `WorkflowEndedInvoker`, `BpmsInitiatedStartInvoker` (validate the start events while
wiring), `MultiInstanceValue`.

---

## F9.1 Model rewriting

### S9.1.1 `ZenBpmMarkerTasks`

- [ ] **Depends on:** S4.2.3 and S4.3.1. The markers are the last rewrite of `prepareBpmn`, after
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

**Tests**: `ZenBpmMarkerTasksTest` with fixtures: none/error/terminate end events, two end events,
end and start events inside a sub-process and an event sub-process left alone, a none, a message and
a timer start each getting a marker, a called process getting neither marker, a process nobody
claims left untouched, idempotency, DI shapes present; `validateBpmsInitiatedStarts` receives every
start event, a called process's included; the rewritten model deploys
(`ZenBpmDeploymentIT#aModelWithMarkersDeploys`).

**Acceptance criteria**

- [ ] The engine accepts every rewritten fixture (400 would name the element).
- [ ] A process without an end marker gate and which starts no workflow of its own is not rewritten.
- [ ] A `@WorkflowStartedByBpms` method serving a plain start event does not end the boot.

## F9.2 The end of a workflow

### S9.2.1 `ZenBpmWorkflowEndedHandler`

- [ ] **Depends on:** S9.1.1.

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

**Tests**: `ZenBpmWorkflowEndedHandlerTest`; Spring `ZenBpmLifecycleIT#theEndOfAWorkflowIsReportedWithItsEndEvent`
(two end events, each reported with its own id), `#aCancelledWorkflowReportsNothing`,
`#theEndCancelsTheTasksStillOpen` (a task waiting in a parallel branch inside an embedded
sub-process is reported `CANCELED` before `@WorkflowEnded` runs, because both carry the root key).

**Acceptance criteria**

- [ ] The instance completes only after the handler committed.
- [ ] The context carries the root instance key as `workflowId`.

## F9.3 Workflows the engine starts itself

### S9.3.1 `ZenBpmBpmsInitiatedStartHandler`

- [ ] **Depends on:** S9.1.1 and S6.1.1.

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

**Tests**: `ZenBpmBpmsInitiatedStartHandlerTest` (the three answers, the redelivery after the PATCH,
the business key reported); `ZenBpmLifecycleIT#aTimerStartBuildsTheAggregate` (`timer-start.bpmn`
with `PT2S`; assert the aggregate exists with the id the method chose, the id variable holds it, the
following service task ran, `correlateMessage` on that aggregate works because the start row hands
the instance key to the probe), `#aWorkflowStartedPastVanillaBpGetsAnAggregate` (a plain start over
`POST /v1/process-instances` without a business key), `#theApplicationsOwnStartBuildsNothing`.

**Acceptance criteria**

- [ ] A timer-started workflow gets the aggregate the application's method built, and every later
  operation on it is routed while the start row lives.
- [ ] A workflow the application started itself passes its start marker without a second aggregate.

## F9.4 Multi-instance

### S9.4.1 `ZenBpmMultiInstance` (engine-gated)

- [ ] **Depends on:** S6.1.1. **Engine twin:** E13.7.

**Instructions**

While wiring, ask `multiInstanceElementNames(module, process, elementId or taskDefinition)` for
every multi-instance element and refuse the deployment where a method wants the item and the element
has no `inputElement` (the model reader of S4.1.1 already collects it).

A job inside a multi-instance element belongs to a child instance (`processType = multiInstance`)
whose variables carry the input element under the model's `inputElement` name. `ZenBpmMultiInstance`
walks up the parent chain of the job's instance through the cache; for every multi-instance body:
`element = child.variables[inputElement]`, `index` = the position of the child among
`rest.children(parentKey)` ordered by `createdAt` then key (cached per parent while the parent is
active), `total` = `parent.variables[inputCollection]` size (a FEEL expression collection: evaluate
only the bare-variable case, else `null` with a DEBUG line). The map is keyed by the multi-instance
element's id (from the model, remembered per process while wiring), outermost first.

*Note from S4.2.3, 2026-10-16.* Inside a multi-instance body a job carries the item of its own
iteration, the input mappings S4.2.2 and S4.2.3 write (the aggregate id and the `@TaskParam` names)
and nothing else; the item of an outer iteration is not among them, and neither is a variable of
the workflow which no mapping names (proven with the engine's in-memory runtime at `bdd795a3`; see
the fit check of S4.2.3). So the job's variables cannot replace the walk up the parent chain above.

**Tests**: `ZenBpmMultiInstanceTest` (fake server with a parent and three children);
`ZenBpmLifecycleIT#theIterationIsReported` (`multi-instance.bpmn`: parallel MI sub-process over three
ids with a service task binding `@MultiInstanceElement`, `@MultiInstanceIndex`, `@MultiInstanceTotal`).

**Acceptance criteria**

- [ ] Index counts from 0, total equals the collection size, element is the item.
- [ ] Superseded by E13.7 once the engine hands out `loopCounter` and `nrOfInstances`.

## F9.5 Quarkus twins

### S9.5.1 Lifecycle on Quarkus

- [ ] **Depends on:** S9.4.1.

`ZenBpmWorkflowLifecycleTest#theWorkflowEndIsReported`, `#theEngineStartsAWorkflowOnItsOwn` (the
aggregate the method named, found afterwards through the start row, as S9.3.1),
`#multiInstanceBindsElementIndexAndTotal`.

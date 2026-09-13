# E9 - Lifecycle markers and multi-instance

**Goal.** `@WorkflowEnded`, workflows the engine starts by timer, and the multi-instance annotations,
all delivered through model rewriting (decision 5) and the instance cache.

**Done when.** A workflow's end reaches the application with its end event id, a timer-started workflow
gets an aggregate whose id is the instance key and is found by the probe, and a task inside a parallel
multi-instance sub-process binds element, index and total - on both platforms.

Template: `Camunda8WorkflowEndedHandler`, `Camunda8BpmsInitiatedStartHandler`, `Camunda8MultiInstance`.
Contract: `WorkflowEndedInvoker`, `BpmsInitiatedStartInvoker` (validate the start events while
wiring), `MultiInstanceValue`.

---

## F9.1 Model rewriting

### S9.1.1 `ZenBpmMarkerTasks`

- [ ] **Depends on:** E7 (the rewriting order in `prepareBpmn` has to be settled).

**Instructions**

1. In `prepareBpmn`, after scoping and correlation keys: where
   `workflowEndedInvoker().workflowEndedHandlerExists(module, plainProcessId)` (and the process is
   claimed by a workflow service), for every end event of the TOP-LEVEL process insert a
   `bpmn:serviceTask id="vanillabp__ended__<endEventId>" name="VanillaBP: workflow ended"` with
   `zenbpm:taskDefinition type="<scopedTaskDefinition(module, process, 'vanillabp__ended')>"` and an
   input mapping of the id variable; move every incoming `sequenceFlow@targetRef` of the end event to
   the marker; add one flow marker -> end event. The end event keeps its definition (error, message,
   terminate), so its semantics hold after the marker completed.
2. Where a timer start event exists: ask `bpmsInitiatedStartInvoker().validateBpmsInitiatedStarts`
   with a spec per timer start (kind TIMER); where the invoker is absent, refuse the deployment (a
   workflow without an aggregate is worse); insert `vanillabp__started__<startEventId>` right after
   the start event (retarget its outgoing flow), type `scopedTaskDefinition(module, process,
   'vanillabp__started')`, with input mappings copying the start event's output variables where the
   model declares any (`zenbpm:ioMapping output` on the start event) so the core can copy them into the
   aggregate.
3. Diagram interchange: for each inserted element add a `bpmndi:BPMNShape` (60x40, placed 80 px left
   of the end event resp. right of the start event) and `bpmndi:BPMNEdge`s with two waypoints, so the
   engine's DI stays valid and a viewer can draw it.
4. Idempotent: an element whose id starts with `vanillabp__` is never inserted twice; a second
   `prepareBpmn` of the same document is a no-op (the context guard already ensures it).
5. `whatOlderVersionsMiss` (E10) names these markers.

**Tests**: `ZenBpmMarkerTasksTest` with fixtures: none/error/terminate end events, two end events,
end events inside a sub-process left alone, a timer start, idempotency, DI shapes present; the
rewritten model deploys (`ZenBpmDeploymentIT#aModelWithMarkersDeploys`).

**Acceptance criteria**

- [ ] The engine accepts every rewritten fixture (400 would name the element).
- [ ] A process without a `@WorkflowEnded` handler and without timer starts is not rewritten.

## F9.2 The end of a workflow

### S9.2.1 `ZenBpmWorkflowEndedHandler`

- [ ] **Depends on:** S9.1.1.

**Instructions**

A `JobHandler` for the `vanillabp__ended` type of each process: facts from the cache, aggregate id
from the business key or id variable, `WorkflowEndedContext{kind = COMPLETED, endTime = now,
endEventId = the id after "vanillabp__ended__", processVersion, adapterId}`; `workflowEndedInvoker()
.workflowEnded(module, plainProcessId, context)`; then `completeJob` (which lets the end event fire).
A deleted aggregate is skipped, not an error. An exception leaves the job (local retry of S6.2.1).
`TERMINATED` is never reported (GAPS 9).

**Tests**: `ZenBpmWorkflowEndedHandlerTest`; Spring `ZenBpmLifecycleIT#theEndOfAWorkflowIsReportedWithItsEndEvent`
(two end events, each reported with its own id), `#aCancelledWorkflowReportsNothing`.

**Acceptance criteria**

- [ ] The instance completes only after the handler committed.

## F9.3 Workflows the engine starts itself

### S9.3.1 `ZenBpmBpmsInitiatedStartHandler`

- [ ] **Depends on:** S9.1.1.

**Instructions**

A `JobHandler` for `vanillabp__started` types: `BpmsInitiatedStartContext{startEventId, kind = TIMER,
startInstant = job.created_at, naturalIdentity = String.valueOf(job.instance_key), variables =
job.input_variables, nativeInstanceId, processVersion, aggregateSyncMode = FULL, adapterId}`;
`startWorkflowByBpms(module, plainProcessId, context)` -> result with the aggregate's id (which the
core derives from the natural identity = the instance key) and the values to report; `patchVariables`
(id variable + reported values) then `completeJob`. A redelivery finds the aggregate by the same natural
identity, so it builds nothing twice. The workflow probe finds such a workflow by instance key (S5.2.1
step 2).

**Tests**: `ZenBpmBpmsInitiatedStartHandlerTest`; `ZenBpmLifecycleIT#aTimerStartBuildsTheAggregate`
(`timer-start.bpmn` with `PT2S`; assert the aggregate exists with id = instance key, the following
service task ran, `correlateMessage` on that aggregate works because the probe finds it by key).

**Acceptance criteria**

- [ ] The aggregate id equals the process instance key and every later operation on it is routed.

## F9.4 Multi-instance

### S9.4.1 `ZenBpmMultiInstance` (engine-gated)

- [ ] **Depends on:** S6.1.1. **Engine twin:** E13.7.

**Instructions**

A job inside a multi-instance element belongs to a child instance (`processType = multiInstance`)
whose variables carry the input element under the model's `inputElement` name. `ZenBpmMultiInstance`
walks up the parent chain of the job's instance through the cache; for every multi-instance body:
`element = child.variables[inputElement]`, `index` = the position of the child among
`rest.children(parentKey)` ordered by `createdAt` then key (cached per parent while the parent is
active), `total` = `parent.variables[inputCollection]` size (a FEEL expression collection: evaluate
only the bare-variable case, else `null` with a DEBUG line). The map is keyed by the multi-instance
element's id (from the model, remembered per process while wiring), outermost first.

**Tests**: `ZenBpmMultiInstanceTest` (fake server with a parent and three children);
`ZenBpmLifecycleIT#theIterationIsReported` (`multi-instance.bpmn`: parallel MI sub-process over three
ids with a service task binding `@MultiInstanceElement`, `@MultiInstanceIndex`, `@MultiInstanceTotal`).

**Acceptance criteria**

- [ ] Index counts from 0, total equals the collection size, element is the item.
- [ ] Superseded by E13.7 once the engine hands out `loopCounter` and `nrOfInstances`.

## F9.5 Quarkus twins

### S9.5.1 Lifecycle on Quarkus

- [ ] **Depends on:** S9.4.1.

`ZenBpmWorkflowLifecycleTest#theWorkflowEndIsReported`, `#theEngineStartsAWorkflowOnItsOwn`,
`#multiInstanceBindsElementIndexAndTotal`.

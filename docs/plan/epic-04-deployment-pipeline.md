# E4 - Deployment pipeline

**Goal.** `readBpmn -> prepareBpmn -> wireBpmn -> readDmn -> deployResources -> startWorkflowProcessing
/ stopWorkflowProcessing` for ZenBPM: models are read without an engine artifact, rewritten once per
file (scoped identifiers, correlation keys, task-parameter mappings), checked against the core, refused
where the engine cannot run them, deployed one file at a time, and their versions reported.

**Done when.** A workflow module with BPMN and DMN deploys to the container from a booted Spring
application, the Quarkus pipeline test proves the pipeline runs on Quarkus, and every unsupported
construct is refused with a message naming file, process and element.

Template: `Camunda8DeploymentService` for the pipeline shape and the wiring calls; `PeaScoping`,
`PeaStartEvents` and `PeaDeploymentService.parseBpmn` for DOM handling. Contract: `ADAPTER-AUTHORS.md`
sections 2.1 and 3.2.

---

## F4.1 Reading models

### S4.1.1 `ZenBpmModelReader` and `ZenBpmBpmnModel`

- [ ] **Depends on:** E3.

**Instructions**

1. `org.pbinitiative.zenbpmadapter.model.ZenBpmBpmnModel`: record of `filename`, `Document document`,
   `bpmnProcessId` (plain), `List<BpmnTaskSpec> tasks`, `List<BpmnTaskSpec> userTasks`,
   `Set<String> catchMessages`, `Set<String> startMessages`, `List<StartEvent> timerStarts`,
   `List<String> endEventIds`, `List<MultiInstanceElement>` (element id, input collection, input
   element, enclosing chain), `List<CallActivity>` (element id, called process id plain),
   `Set<String> concurrentTokenElements`, `Set<String> expressionIdentifiers`.
2. `ZenBpmModelReader.read(filename, InputStream)`: hardened `DocumentBuilderFactory` (no DOCTYPE,
   no external entities, namespace aware), reads the stream WITHOUT closing it. One entry per
   `bpmn:process[isExecutable=true]`; two or more executable processes -> `BpmnParseException`
   naming both ids and saying the engine deploys one process per file; zero -> empty list.
3. Element reads by local name in either extension namespace: job-producing tasks are `serviceTask`,
   `sendTask`, `businessRuleTask` WITHOUT `calledDecision`, and `intermediateThrowEvent`/`endEvent`
   with a `messageEventDefinition` (they create jobs too) -> `BpmnTaskSpec(id, taskDefinition@type)`;
   a task without a `taskDefinition` gets `BpmnTaskSpec(id, null)` so the wiring check names it;
   `userTask` -> `BpmnTaskSpec.userTask(id, type ?? "user-task-type")`; `receiveTask` is a message
   catch, not a job. Messages: which are referenced by a start event and which by catch elements
   (intermediate, boundary, receive task, event sub-process start). Timer start events. End events of
   the TOP-LEVEL process only (not inside sub-processes). Multi-instance elements with the chain of
   enclosing multi-instance elements. Concurrent-token elements: parallel and inclusive gateways with
   more than one outgoing flow, activities with parallel `multiInstanceLoopCharacteristics`,
   non-interrupting boundary events, non-interrupting event sub-processes. Expression identifiers:
   every attribute value starting with `=` (conditions, timer definitions, `inputCollection`,
   `assignee`, `correlationKey`, io mappings) run through `ZenBpmFeelIdentifiers` (a conservative
   scanner: dotted identifier paths, skipping string literals and FEEL keywords).
4. `readBpmn` of the deployment service delegates here and returns `Map.Entry(plainProcessId, model)`.

**Tests** (`ZenBpmModelReaderTest`, fixtures in `core/src/test/resources/models/`): one fixture per
element kind, a two-process file refused, a `zeebe:`-namespaced and a `zenbpm:`-namespaced fixture
reading identically, a DOCTYPE fixture refused, the stream left open (a spy stream).

**Acceptance criteria**

- [ ] Every collection of the record is filled by a fixture and asserted.
- [ ] Models from the Camunda Modeler (`zeebe:` namespace) read without changes.

### S4.1.2 Refuse what the engine cannot run

- [ ] **Depends on:** S4.1.1.

**Instructions**

`ZenBpmUnsupportedConstructs.check(model)` throws `IllegalStateException` (from `prepareBpmn`, so the
deployment-failure policy applies) for: `signalEventDefinition`, `conditionalEventDefinition`,
`escalationEventDefinition`, `compensateEventDefinition`, `cancelEventDefinition`, `scriptTask`,
`manualTask`, plain `task`, `complexGateway`, a `receiveTask` with `instantiate="true"`, a conditional
`sequenceFlow` leaving an activity (not a gateway), a standard loop (`standardLoopCharacteristics`).
ONE message per file listing every offending element as `<processId>/<elementId> (<construct>)`,
naming GAPS 13/14/19 and saying the engine rejects them (`400`) or would run them without an aggregate.

**Tests**: one fixture per construct; a fixture with three offences reported in one message.

**Acceptance criteria**

- [ ] The message names file, process and every element; the boot ends before the engine is asked.

---

## F4.2 Rewriting

### S4.2.1 Scoping and the name-clash modes

- [ ] **Depends on:** S4.1.1.

**Instructions**

1. `ZenBpmScoping.apply(document, module, plainProcessId, scoping, adapterId)` (from `PeaScoping`):
   under `USE_PREFIX` rewrite `process@id`, `message@name`, `error@errorCode`,
   `escalation@escalationCode` (kept for completeness though refused above), `taskDefinition@type`
   through `scopedTaskDefinition` (per process by default), `calledElement@processId` through
   `scopedProcessId`, `calledDecision@decisionId` through `scopedIdentifier`, `userTask`'s
   `taskDefinition@type` as well. In `NONE` and `BY_ADAPTER` untouched. Rewrite once per FILE: the
   context remembers rewritten filenames (`prepareBpmn` is called per process).
2. `ZenBpmDeploymentService.defaultNameClashAvoidance()` returns `USE_PREFIX` (decision 2) and
   `warnAboutUnscopedIdentifiers` names ZenBPM's alternatives: `use-prefix`, or one engine per
   module (`rest-address` per adapter id). In `prepareBpmn` call
   `scoping.validateNativeIsolationSupported(adapterId, module, "ZenBPM")` so `by-adapter` ends the
   boot; there is no `by-adapter`-only key, so `validateNoneNameClashStrategy` is not called.
3. `readDmn`: `DmnDecisionIds.bytesOf(dmn)` rewritten with the module prefix under `USE_PREFIX`
   (the core's half), kept in the context per filename.
4. `deployResources` calls `scoping.validateNoCollidingProcessIds(adapterId, deployedProcesses)`
   before the first POST.

**Tests**: `ZenBpmScopingTest` with a fixture carrying every rewritable attribute, asserting the
XML under `use-prefix` and its identity under `none`; `ZenBpmDeploymentServiceTest#byAdapterIsRefused`
naming both ways out; `#noneWarnsNamingTheAlternatives` (CapturedOutput).

**Acceptance criteria**

- [ ] The rewritten XML deploys to the container (asserted in S4.3.2's IT under `use-prefix`).
- [ ] Nothing configured -> `use-prefix` applies and no warning is written.

### S4.2.2 Correlation keys and the start/catch collision

- [ ] **Depends on:** S4.2.1.

**Instructions**

`ZenBpmCorrelationKeys.apply(model, aggregateIdName)`: for every `bpmn:message` a catch element of
the process references and which has no `subscription` extension, add
`<zenbpm:extensionElements><zenbpm:subscription correlationKey="=<aggregateIdName>"/>` (a modelled
subscription stays untouched, so a correlation-id expression the modeller wrote survives). A message
referenced by a start event gets none. A message name in BOTH `startMessages` and `catchMessages` ->
`IllegalStateException` naming process, message and both elements, citing GAPS 4 (the engine falls
back from an unmatched correlation key to the start subscription and would start a second workflow on
every correlation). The aggregate id name comes from `workflowTaskWiring().resolveWorkflowAggregateIdName`;
a process no workflow service claims has none - such a process is left untouched and reported through
`validateTaskWiring` like any other (ZenBPM, unlike Camunda 8, accepts a message without a
subscription, so the file is not refused).

**Tests**: injected where missing, untouched where present, none on a start message, collision refused.

**Acceptance criteria**

- [ ] `Camunda8`-style version-1 models with modelled keys deploy byte-identically apart from namespace
  declarations.

### S4.2.3 Task-parameter input mappings

- [ ] **Depends on:** S4.2.1.

**Instructions**

`ZenBpmTaskParameters.apply(model, module, plainProcessId, wiring)`: for every job-producing task,
ask `taskParameterNames(module, process, taskDefinition ?? activityId)` and add one
`<zenbpm:ioMapping><zenbpm:input source="=<name>" target="<name>"/>` per name not already mapped by the
modeller. Rationale: a job's input variables are EMPTY without input mappings, and the adapter fetches
nothing else (the aggregate comes from the application's database). The marker tasks of E9 add their
own mappings there.

**Tests**: mapping added, existing mapping kept, idempotent on a second `prepareBpmn` of the same
document (the context guard), and `ZenBpmRestClientContractIT`-style check in S4.3.2 that a job of
such a task carries the variable.

**Acceptance criteria**

- [ ] A `@TaskParam` name reaches the job; a name nobody reads does not.

---

## F4.3 Wiring and deploying

### S4.3.1 The wiring calls

- [ ] **Depends on:** S4.2.3.

**Instructions**

`wireBpmn(module, filename, plainProcessId, model, context)`:

1. `validateTaskWiring(module, process, tasks + userTasks)` for EVERY executable process (an unclaimed
   process is reported by the core, not refused).
2. `reportConcurrentTokenElements(module, process, model.concurrentTokenElements())`.
3. `unsharedWorkflowAggregatePaths(module, process, model.expressionIdentifiers(), FULL)` -> for each
   verdict which `pathIsCut()` a WARN naming process, path, segment and owner (the core decides, the
   adapter words it like Camunda 8 does).
4. Call activities: `workflowsShareTheWorkflowAggregate(module, process, calledPlainId)` -> if true,
   inject `<zenbpm:in businessKey="=<aggregateIdName>"/>` on the call activity and an input mapping of
   the id variable into the called instance (the child gets the parent's business key and variable,
   so its tasks find the aggregate); if false, nothing.
5. `registerProcessVersions(adapterId, module, process, catalog)` with the catalog of E10 (until E10
   exists, register nothing).
6. Remember per process in the context: scoped id, job types of its tasks, user-task types.

**Tests**: `ZenBpmDeploymentServiceTest` with `TestCollaborators` (copy Camunda 8's test double
pattern): every call made once per process with the expected arguments; a task without a method ends
`wireBpmn` with the core's message (the double throws); the call-activity injection both ways.

**Acceptance criteria**

- [ ] Every wiring call of `ADAPTER-AUTHORS.md` section 3.2 the mapping marks SUPPORTED is made.

### S4.3.2 Deploy resources and report versions

- [ ] **Depends on:** S4.3.1, S2.4.1.

**Instructions**

`deployResources(module, context)`:

1. `ZenBpmEngineWait` once per adapter id (skipped for a module with nothing to deploy).
2. `validateNoCollidingProcessIds`.
3. Per BPMN file: serialise the rewritten `Document` (UTF-8, no pretty print changes beyond what the
   engine normalises), `deployBpmn(bytes)`; 200 or 201 -> remember the key; `ZenBpmApiException` 400
   -> `IllegalStateException` naming file, process and the engine's message (the engine's `400` says
   which element it rejects); anything else -> rethrow wrapped with file and address.
4. Per DMN file: `deployDmn`.
5. Per process: `processDefinition(key)` -> `registerDeployedVersion(adapterId, module,
   plainProcessId, String.valueOf(version))`, and record `ZenBpmDeployedProcesses.record(module,
   plainProcessId, scopedId, key, version, versionTag, documentAsDeployed)`.
6. Log one INFO per module: files deployed, which were unchanged (200), keys and versions.

`ZenBpmDeployedProcesses` (per adapter id, shared by both services through a registry like PEA's):
`scopedIdsOf(module)`, `keyOf(module, plainProcessId)`, `versionOf(...)`, `xmlOf(key)`,
`ownsScopedProcessId(module, scopedId)`.

**Tests**

- Spring `ZenBpmDeploymentIT` (Docker, own module id `deploy-it`): boots a JPA application with one
  BPMN and one DMN, asserts through the REST client that the definition exists under the prefixed id
  with version 1 and the decision exists; a second context start with the same files deploys nothing
  new (200) and still registers version 1; a changed file yields version 2.
- Quarkus `ZenBpmDeploymentPipelineTest` (no Docker, `QuarkusExtensionTest`): a BPMN under the
  configured `resources-location` and a `rest-address` on a closed port; the boot fails inside the
  engine wait (set `startup-wait: PT2S`) with the message naming the address - proving the pipeline
  ran `readBpmn` and `prepareBpmn` on Quarkus (assert the parse happened by a log line the reader
  writes at DEBUG, captured).
- `ZenBpmDeploymentServiceTest#aRejectedModelNamesTheFile` with the fake server answering 400.

**Acceptance criteria**

- [ ] Version is registered on an unchanged deployment as well.
- [ ] A rejected file ends the boot naming file, process and the engine's reason; with
  `deployment-failure: warn` on a nowhere-first id the application boots degraded.

### S4.3.3 Start and stop processing (skeleton)

- [ ] **Depends on:** S4.3.2, S2.3.1.

**Instructions**

`startWorkflowProcessing(module, context)`: for every job type of the module's deployed processes
(scoped), `stream.subscribe(type)` with a `JobDispatcher` which, until E6 lands, logs at WARN that
a job arrived for a type without a handler and leaves it (the lock lapses). Register the module's
types in the context. `stopWorkflowProcessing`: unsubscribe them; the drain arrives in E6. Register
the factory hook which stops leftover modules on `close()`.

**Tests**: `ZenBpmJobStreamTest`-style in-process server asserting subscribe per type on start and
unsubscribe on stop; the Spring `ZenBpmDeploymentIT` gains a case which starts an instance over REST
and asserts the WARN about the unhandled type (proves the subscription reached the engine).

**Acceptance criteria**

- [ ] A module which deployed nothing subscribes nothing and starts without touching the engine.
- [ ] Types shared by two modules under `none` stay subscribed until the second module stops.

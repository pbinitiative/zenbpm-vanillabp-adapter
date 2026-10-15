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

A process no workflow service claims is not supported by the platform (platform decision 120): the
core ends the start over it unless the application marks it with
`vanillabp.workflow-modules.<module>.workflows.<process>.implemented-externally=true` (or the same
key under `adapters.<id>.`). Such a process belongs to somebody else, so this adapter leaves it as
it was modelled, like Camunda 8 (Camunda 8 decision 73): every rewrite, wiring call, subscription
and marker of this epic and of E9 first asks `isClaimedByAWorkflowService(module, process)`. What
belongs to the whole FILE still applies to it: the prefix of `use-prefix` and the refusals of
constructs the engine itself rejects.

---

## F4.1 Reading models

### S4.1.1 `ZenBpmModelReader` and `ZenBpmBpmnModel`

- [ ] **Depends on:** E3.

**Instructions**

1. `org.pbinitiative.zenbpmadapter.model.ZenBpmBpmnModel`: record of `filename`, `Document document`,
   `bpmnProcessId` (plain), `List<BpmnTaskSpec> tasks`, `List<BpmnTaskSpec> userTasks`,
   `Set<String> catchMessages`, `Set<String> startMessages`, `boolean startMessagesAreKnown` (false
   where a message start event's name is an expression), `List<StartEvent> startEvents` (element id
   and `BpmsStartTrigger.Kind` - `NONE`, `MESSAGE` or `TIMER` - of EVERY start event of the
   top-level process), `List<String> endEventIds`, `List<MultiInstanceElement>` (element id, input
   collection, input element, enclosing chain), `List<CallActivity>` (element id, called process id
   plain), `Set<String> concurrentTokenElements`, `List<ModelExpression> expressions`.
2. `ZenBpmModelReader.read(filename, InputStream)`: hardened `DocumentBuilderFactory` (no DOCTYPE,
   no external entities, namespace aware), reads the stream WITHOUT closing it. One entry per
   `bpmn:process[isExecutable=true]`; two or more executable processes -> `BpmnParseException`
   naming both ids and saying the engine deploys one process per file; zero -> empty list.
3. Element reads by local name in either extension namespace: job-producing tasks are `serviceTask`,
   `sendTask`, `businessRuleTask` WITHOUT `calledDecision`, and `intermediateThrowEvent`/`endEvent`
   with a `messageEventDefinition` (they create jobs too) -> `BpmnTaskSpec(id, taskDefinition@type)`;
   a task without a `taskDefinition` gets `BpmnTaskSpec(id, null)` so the wiring check names it;
   `userTask` -> `BpmnTaskSpec.userTask(id, type ?? "user-task-type")`; `receiveTask` is a message
   catch, not a job. Every spec carries the element's `name` attribute (`BpmnTaskSpec.name`, which
   `ADAPTER-AUTHORS.md` asks adapters to fill; Camunda 8 does not yet). Messages: which are
   referenced by a start event of the top-level process and which by catch elements (intermediate,
   boundary, receive task, event sub-process start). Start events: every start event of the
   TOP-LEVEL process with its kind, because `validateBpmsInitiatedStarts` needs all of them and the
   markers of E9 hang after each; the start events of embedded and event sub-processes are not
   workflow starts and are not collected. End events of the TOP-LEVEL process only (not inside
   sub-processes). Multi-instance elements with the chain of enclosing multi-instance elements and
   whether each names an `inputElement`. Concurrent-token elements: parallel and inclusive gateways
   with more than one outgoing flow, activities with parallel `multiInstanceLoopCharacteristics`,
   non-interrupting boundary events, non-interrupting event sub-processes. Expressions: every
   attribute value starting with `=` which reads the data of the workflow, as a `ModelExpression`
   (element id, place, the expression as written, the body without the `=`). The places:
   `SEQUENCE_FLOW_CONDITION` (`conditionExpression`), `TIMER`, `MULTI_INSTANCE_COLLECTION`
   (`inputCollection`), `MULTI_INSTANCE_COMPLETION_CONDITION`, `MESSAGE_CORRELATION_KEY`,
   `INPUT_OR_OUTPUT_MAPPING` (the `source` of a mapping), and `SOMEWHERE_ELSE` for the rest (for
   example `assignee`). ZenBPM has no cardinality and no loops, so `MULTI_INSTANCE_CARDINALITY` and
   `LOOP_CONDITION` never occur. The reader collects them from the document as the modeller wrote
   it, BEFORE `prepareBpmn` adds the adapter's own correlation keys and mappings (Camunda 8 decision
   63), so the core judges only what the modeller wrote. The adapter does not extract identifiers
   from FEEL: it does not call `unsharedWorkflowAggregatePaths` or
   `declaredTypesOfWorkflowAggregatePaths`, like Camunda 8 (deviation 14), so no FEEL scanner is
   written.
4. `readBpmn` of the deployment service delegates here and returns `Map.Entry(plainProcessId, model)`.

**Tests** (`ZenBpmModelReaderTest`, fixtures in `core/src/test/resources/models/`): one fixture per
element kind, a two-process file refused, a `zeebe:`-namespaced and a `zenbpm:`-namespaced fixture
reading identically, a DOCTYPE fixture refused, the stream left open (a spy stream); a fixture with
a plain, a message and a timer start event plus a start event inside a sub-process (three collected,
the fourth not); one expression per place; a message start named by an expression
(`startMessagesAreKnown` false).

*Note from S3.1.1, 2026-10-15.* `model.ZenBpmBpmnModel` and `ZenBpmProcessingContext` exist as
empty final classes, because the deployment service of S3.1.1 names them as its type parameters
(`getModelType()`, `getProcessContextType()`). This story turns `ZenBpmBpmnModel` into the record of
instruction 1; whoever first fills the processing context does the same with it.

*Note from the review of S3.1.1, 2026-10-15.* Not decided yet: what an id without clients (no
`rest-address`, validated UNCONFIGURED) does with a workflow module which has BPMN files. In S3.1.1
every step of the deployment service throws, and an id without clients throws its validation message
first, so with the default `deployment-failure: fail` such an application ends its start at
`readBpmn`. That conflicts with the rule that an unconfigured application boots and names the next
key to set. Before `readBpmn` is built, decide whether an unconfigured id skips its deployment with
the warning the startup report already holds, or ends the start naming `rest-address`, and test the
choice with a workflow module that has BPMN files.

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

A construct the engine rejects is refused in every process of the file, a process nobody claims
included: the engine would refuse the file whoever serves it (Camunda 8 decision 73). A construct
refused because it would run without an aggregate (the instantiating receive task, GAPS 19) is
refused only in a claimed process; a process nobody claims belongs to somebody else. Where a
refusal is about one task, its message ends with `ImplementedExternally.howToMark(...)`, the
property line which marks that task as served elsewhere, so the developer learns the way out; ask
`isImplementedExternally(adapterId, module, process, task)` first and do not refuse a task the
application marked.

**Tests**: one fixture per construct; a fixture with three offences reported in one message; an
unclaimed process with a construct the engine rejects is refused, the same process with an
instantiating receive task is not.

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
2. `ZenBpmDeploymentService.defaultNameClashAvoidance()` keeps the SPI's default `BY_ADAPTER`
   (decision 24): `ADAPTER-AUTHORS.md` section 2.1 tells an adapter whose BPMS has no isolation of
   its own to keep it and refuse it while deploying. In `prepareBpmn` call
   `scoping.validateNativeIsolationSupported(adapterId, module, "ZenBPM")`, so `by-adapter`, the
   default included, ends the boot with the core's message naming `use-prefix` and `none` and the
   key (`vanillabp.adapters.<id>.name-clash-avoidance`; one line per adapter id is enough, and the
   same key may be set per workflow module and workflow). `warnAboutUnscopedIdentifiers` names
   ZenBPM's alternatives: `use-prefix`, or one engine per module (`rest-address` per adapter id).
   There is no `by-adapter`-only key, so `validateNoneNameClashStrategy` is not called.
   `ownIsolationSeparatesWorkflowModules` and `getAddressedSystem` keep their defaults: ZenBPM has
   no isolation which could separate two modules, and no other adapter type works on a ZenBPM
   engine.
3. `readDmn`: `DmnDecisionIds.bytesOf(dmn)` rewritten with the module prefix under `USE_PREFIX`
   (the core's half), kept in the context per filename.
4. `deployResources` calls `scoping.validateNoCollidingProcessIds(adapterId, deployedProcesses)`
   before the first POST, as Camunda 7, Camunda 8 and the Process-Engine-API adapter do, so a
   colliding module is refused and never deployed. (`ADAPTER-AUTHORS.md` section 2.1 says "after
   your deploy command returned"; that sentence disagrees with the three adapters and with platform
   decision 41, and was reported to the VanillaBP maintainers.)
5. `accept-unscoped-identifiers` (adapter and module level, default `false`) is the application
   saying that its identifiers are unique across all its workflow modules. Add the adapter level as
   a field of `ZenBpmAdapterConfiguration`; the module level comes from the E3 overlay, and it wins
   where both are set. Where it resolves to `true`, `warnAboutUnscopedIdentifiers` writes a DEBUG
   line naming the key which accepted `none` instead of the WARN, like
   `Camunda8DeploymentService.warnAboutUnscopedIdentifiers`.

**Tests**: `ZenBpmScopingTest` with a fixture carrying every rewritable attribute, asserting the
XML under `use-prefix` and its identity under `none`; `ZenBpmDeploymentServiceTest#byAdapterIsRefused`
naming both ways out, once configured and once by nothing configured;
`#noneWarnsNamingTheAlternatives` (CapturedOutput);
`#acceptedUnscopedIdentifiersAreNotWarnedAbout`, once with the key at the adapter level and once at
the module level, and once with `false` on the module overriding `true` on the adapter.

**Acceptance criteria**

- [ ] The rewritten XML deploys to the container (asserted in S4.3.2's IT under `use-prefix`).
- [ ] Nothing configured -> the boot ends with the core's message naming `use-prefix`, `none` and
  the key, because `by-adapter` is the default and ZenBPM cannot serve it.
- [ ] `accept-unscoped-identifiers: true` turns the `none` warning into a DEBUG line naming the key;
  the module level wins over the adapter level.

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
every correlation). Only a claimed process gets keys (`isClaimedByAWorkflowService`); its aggregate
id name comes from `workflowTaskWiring().resolveWorkflowAggregateIdName`. A process nobody claims is
left as it was modelled (ZenBPM, unlike Camunda 8, accepts a message without a subscription, so the
file is not refused); a message element the file shares with a claimed process gets the key of the
claimed one.

**Tests**: injected where missing, untouched where present, none on a start message, collision
refused, nothing written into an unclaimed process.

**Acceptance criteria**

- [ ] `Camunda8`-style version-1 models with modelled keys deploy byte-identically apart from namespace
  declarations.

### S4.2.3 Task-parameter input mappings

- [ ] **Depends on:** S4.2.1.

**Instructions**

`ZenBpmTaskParameters.apply(model, module, plainProcessId, wiring)`: for every job-producing task of
a claimed process, ask `taskParameterNames(module, process, taskDefinition ?? activityId)` and
`extensionTaskParameterNames(module, process, [activityId, taskDefinition])` (what the methods of
every extension read, platform decision 122; ZenBPM hands a job to ONE stream client, so an
extension reads the same job as the adapter) and add one
`<zenbpm:ioMapping><zenbpm:input source="=<name>" target="<name>"/>` per name of the union not
already mapped by the modeller. Rationale: a job's input variables are EMPTY without input
mappings, and the adapter fetches nothing else (the aggregate comes from the application's
database). The marker tasks of E9 add their own mappings there.

*Note from S2.2.1, 2026-10-13:* at the pinned engine the rationale does not hold. A job is created
with a copy of its scope's variables and the targets of its input mappings set on top
(`pkg/bpmn/jobs.go` and `runtime.NewVariableHolder` at `bdd795a3`);
`ZenBpmRestClientContractIT#aStartedInstanceIsFoundAndItsJobIsActive` sees the instance variable
`orderId` on a job of a task without mappings. So the mappings decide the names a job carries on
top, not which ones it carries at all, and the second half of the acceptance criterion below ("a
name nobody reads does not") cannot be met by mappings. Decide before implementing whether the story
is still needed and what it promises.

*Note, 2026-10-15:* that holds for a plain element only. A job inside a multi-instance body is
created in a child instance whose variables are `{inputElement: item}` and nothing else
(`pkg/bpmn/multi_instance.go`, `NewVariableHolder` with the item only), plus its input mappings. So
inside a multi-instance body the mappings DO decide what a job carries, and the union of both
parameter calls is needed there whatever the decision for plain elements is.

**Tests**: mapping added, existing mapping kept, idempotent on a second `prepareBpmn` of the same
document (the context guard), and `ZenBpmRestClientContractIT`-style check in S4.3.2 that a job of
such a task carries the variable.

**Acceptance criteria**

- [ ] A `@TaskParam` name reaches the job, inside a multi-instance body as well; so does a name only
  an extension's method reads.

---

## F4.3 Wiring and deploying

### S4.3.1 The wiring calls

- [ ] **Depends on:** S4.2.3.

**Instructions**

`wireBpmn(module, filename, plainProcessId, model, context)`:

0. `isClaimedByAWorkflowService(module, process)` first. For a process nobody claims `wireBpmn`
   returns at once and makes NO call (platform decision 120, Camunda 8 decision 73): the core has
   already ended the start over it unless the application marked it `implemented-externally`, and
   then somebody else serves it.
1. `reportModelExpressions(module, process, model.expressions())`, from what the reader collected
   before `prepareBpmn` wrote anything (S4.1.1); the core judges each expression's form (platform
   decision 103).
2. `validateTaskWiring(adapterId, module, process, tasks + userTasks)`, the four-argument form,
   with the specs carrying their `name`. Every task needs a method or `implemented-externally`, a
   user task included (platform decision 119).
3. `reportStartMessages(adapterId, module, process, plainNames)` with the plain names of the message
   start events of the top-level process (event sub-process starts do not start a workflow): an
   empty collection for a process without a message start, and NO call at all where
   `startMessagesAreKnown` is false (a name is an expression), as `ADAPTER-AUTHORS.md` and Camunda 8
   do. The core then refuses a `startWorkflowByMessage` whose message does not start that process,
   before phase one (platform decision 109).
4. `validateBpmsInitiatedStarts(module, process, specs)` with one `BpmsInitiatedStartSpec(elementId,
   kind, null)` per start event of the top-level process, NONE and MESSAGE included: the core ends
   the boot where a `@WorkflowStartedByBpms` method names a start event nobody reported, and where a
   timer start has no method. A called process is reported as well, so the core can refuse a timer
   start in it (platform decision 124).
5. `startsAWorkflowOfItsOwn(module, process)` and `workflowEndedHandlerExists(module, process)`:
   asked once and remembered in the context for the markers of S9.1.1 (no start marker in a called
   process; no end marker where nobody listens).
6. `reportConcurrentTokenElements(module, process, model.concurrentTokenElements())`.
7. Multi-instance items: for every task inside a multi-instance chain ask
   `multiInstanceElementNames(module, process, taskDefinitionOrActivityId)` with the task
   definition and with the activity id (as Camunda 8 does), and refuse while deploying where an
   answered element of THIS model names no `inputElement`: a method wants the item, and the engine
   would never hand one over. An answered id which is no element of this model is not a problem (the
   chain crosses a call activity). The message names element, task and the fix.
8. `reportCompensation` is not called: S4.1.2 refuses compensation events before.
9. Call activities: `workflowsShareTheWorkflowAggregate(module, process, calledPlainId)` -> if true,
   inject `<zenbpm:in businessKey="=<aggregateIdName>"/>` on the call activity and an input mapping of
   the id variable into the called instance: a task of a called process is bound by its own process
   and loads the caller's aggregate (platform decision 123), so the child needs the caller's name;
   if false, nothing.
10. No `unsharedWorkflowAggregatePaths` and no `declaredTypesOfWorkflowAggregatePaths` (deviation
    14).
11. `registerProcessVersions(adapterId, module, process, catalog)` waits for the catalog of E10.
    Until then nothing is registered, which the core reads as "not asked yet" (platform decision
    60), not as "this BPMS counts no versions". That is acceptable while E10 is open; before a
    release the catalog has to exist (`ADAPTER-AUTHORS.md` section 8, item 3: "You may not do
    neither").
12. Remember per process in the context: scoped id, job types of its tasks, user-task types, the
    answers of step 5.

**Tests**: `ZenBpmDeploymentServiceTest` with `TestCollaborators` (copy Camunda 8's test double
pattern; it implements `NameClashAvoidanceSupport` and `BpmsInitiatedStartInvoker`, whose new
abstract methods it must carry): every call made once per process with the expected arguments; an
unclaimed process gets no call at all; a task without a method ends `wireBpmn` with the core's
message (the double throws); no `reportStartMessages` for a process with an expression as a start
message name, an empty one for a process without a message start; every start event reported, a
called process's too; a method wanting the item of an element without `inputElement` refused; the
call-activity injection both ways.

**Acceptance criteria**

- [ ] Every wiring call of `ADAPTER-AUTHORS.md` section 3.2 the mapping marks SUPPORTED is made, for
  claimed processes only.
- [ ] The expressions reported are the ones the modeller wrote, none of the adapter's own.

### S4.3.2 Deploy resources and report versions

- [ ] **Depends on:** S4.3.1, S2.4.1.

**Instructions**

`deployResources(module, context)`:

1. `ZenBpmEngineWait` once per adapter id (skipped for a module with nothing to deploy).
2. `validateNoCollidingProcessIds`, before the first file is sent (S4.2.1).
3. Per BPMN file: serialise the rewritten `Document` (UTF-8, no pretty print changes beyond what the
   engine normalises), `deployBpmn(bytes)`; 200 or 201 -> remember the key; `ZenBpmApiException` 400
   -> `IllegalStateException` naming file, process and the engine's message (the engine's `400` says
   which element it rejects); anything else -> rethrow wrapped with file and address.
4. Per DMN file: `deployDmn`.
5. Per process: `processDefinition(key)` -> `registerDeployedVersion(adapterId, module,
   plainProcessId, String.valueOf(version))`, and record `ZenBpmDeployedProcesses.record(module,
   plainProcessId, scopedId, key, version, versionTag, documentAsDeployed)`.
6. Log one INFO per module: files deployed, which were unchanged (200), keys and versions.
7. Identifiers (platform decision 40). `reportIdentifiersTheModelsDeclare(adapterId, module,
   declared)` with one `ModelIdentifier(kind, plainIdentifier, bpmnProcessId)` per identifier
   `ZenBpmScoping` scoped: `BPMN_PROCESS_ID`, `MESSAGE_NAME`, `ERROR_CODE`, `TASK_DEFINITION`
   (ZenBPM job types are cluster-wide, as Camunda 8's are) and `DMN_DECISION_ID`. Collect them while
   scoping, so nothing is read twice. `reportIdentifiersTheBpmsAlreadyHolds(adapterId, module,
   found)` asks what the engine held before this deployment, and ZenBPM limits what it can tell: a
   process definition can be searched by `bpmnProcessId`, but it carries no resource name or owner,
   so a definition of this application's own earlier deployment and one of another application look
   the same; and the decision definitions can be filtered by `dmnResourceDefinitionId` and name, not
   by decision id. Decide at the start of the story between (a) reporting an empty collection and
   writing the limit down as a gap, and (b) reporting every definition under a scoped id about to be
   deployed which this boot did not deploy, with `certainlyForeign = false` and the holder sentence
   "process definition key N, version M". (b) warns about the application's own older versions at
   every boot, so (a) is recommended. Either way a failed query never fails the deployment: it is
   logged at DEBUG, as `ADAPTER-AUTHORS.md` asks.

*Note from S2.4.1, 2026-10-15.* Step 1 is `ZenBpmEngineWait.awaitReady(factory)`, followed at once
by `ZenBpmEnvironmentInfo.logOnce(factory, startupReport)`: no other story calls the second, and
it has to run while the startup report still takes findings. Both remember their answer in the
factory, so a second module neither waits nor asks again. After a failed wait a later module does
not wait `startup-wait` again: it asks once, bounded by `request-timeout`, takes an engine which
became ready meanwhile, and otherwise fails at once with the first failure as its cause. A refusal
no repetition changes is final; an interrupt is not remembered. A factory closed while the
application stops ends every later wait the same way, at its first attempt. The INFO line and the engine's version for the health check come from that one
call.

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
- `ZenBpmDeploymentIT#aDeployedVersionWithoutAMethodEndsTheStart`: a task whose only method serves
  `version = ">1"`, in a model the engine keeps as version 1, ends the start after
  `registerDeployedVersion` (platform decision 126). The test asserts the core's message by the
  phrase the platform publishes for it (`DeployedProcessVersionsCheck.A_VERSION_OF_A_PROCESS` and
  its siblings, in `migration-adapter/runtime`, a test dependency), not by a copy of the text.
- `#theDeclaredIdentifiersAreReported`: every scoped identifier of the fixture in
  `reportIdentifiersTheModelsDeclare`; a failing definition search leaves the deployment green.

**Acceptance criteria**

- [ ] Version is registered on an unchanged deployment as well.
- [ ] A rejected file ends the boot naming file, process and the engine's reason; with
  `deployment-failure: warn` on a nowhere-first id the application boots degraded.

### S4.3.3 Start and stop processing (skeleton)

- [ ] **Depends on:** S4.3.2, S2.3.1.

**Instructions**

`startWorkflowProcessing(module, context)`: for every job type of the module's deployed CLAIMED
processes (scoped; a process nobody claims gets no subscription, somebody else serves its jobs),
`stream.subscribe(type, lockDuration, maxActiveJobs)` (until E6 lands: the engine defaults,
`0` and `0`) with a `JobDispatcher` which, until E6 lands, logs at WARN that a job arrived for a type
without a handler and leaves it (the lock lapses; closing the stream would release it at once). Register the module's
types in the context. `stopWorkflowProcessing`: unsubscribe them; the drain arrives in E6. Register
the factory hook which stops leftover modules on `close()`.

**Tests**: `ZenBpmJobStreamTest`-style in-process server asserting subscribe per type on start and
unsubscribe on stop; the Spring `ZenBpmDeploymentIT` gains a case which starts an instance over REST
and asserts the WARN about the unhandled type (proves the subscription reached the engine).

*Note from the review of S2.4.1, 2026-10-15.* Register the factory hook
(`factory.workflowModuleStarted(module, hook)`) before the first `subscribe`, and close the
registration after the last `unsubscribe`. Once the factory's `close()` began, `workflowModuleStarted`
throws an `IllegalStateException` naming the module, because nothing would run the hook; registering
first means such a module has subscribed nothing. The hook runs without a lock of the factory held,
so it may wait for the module's own stop. The factory's WARN about a module it had to stop ends with
"on both platforms the lifecycle stops the modules first": that sentence is a promise about this
story's wiring, so the criterion below proves it.

**Acceptance criteria**

- [ ] Stopping the application stops every started module before its factory closes: a boot test
  per platform (Spring Boot and Quarkus) stops a context with a started module and finds no WARN of
  the factory about a module it had to stop.
- [ ] A module which deployed nothing subscribes nothing and starts without touching the engine.
- [ ] No job type of a process nobody claims is subscribed.
- [ ] Types shared by two modules under `none` stay subscribed until the second module stops.

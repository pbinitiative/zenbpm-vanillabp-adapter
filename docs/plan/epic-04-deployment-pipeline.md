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

- [x] **Depends on:** E3.

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

*Fit check, 2026-10-16.* The story was checked against `main` (`e4977c1`), `ADAPTER-AUTHORS.md`,
Camunda 8's reader and the engine at the pinned `bdd795a3`. The SPI has what instruction 1 names
(`BpmnTaskSpec(id, type, false, name)`, `BpmnTaskSpec.userTask(id, type, name)`, `ModelExpression`
and `ExpressionPlace`, `BpmsStartTrigger.Kind`). Four things did not fit, and the maintainer decided
two of them:

1. **One process per file, not one executable process.** The engine never reads `isExecutable`,
   and `TRootElementsContainer.Process` is one struct, so Go's `encoding/xml` reads every
   `bpmn:process` of a file into it. A scratch program against the engine's `bpmn20` package (the
   model code at `bdd795a3` and at the local `main` is the same) read a file with an executable
   `executable` and a non-executable `documentation` as ONE definition with the id
   `documentation` and the start events of both, and `ResolveReferences` accepted it. Decided: a
   file with an executable process and ANY other `bpmn:process` is refused, naming every process;
   a file whose processes are all non-executable still yields nothing. GAPS 18 says so now.
2. **What is an expression.** The engine evaluates FEEL in `evaluateExpression` only, and only
   where a value starts with `=`; it is called for sequence-flow conditions, the correlation key
   of a message subscription, the `source` of input and output mappings, `inputCollection`,
   `outputElement`, the assignee of a user task, the `retries` and `retryBackoff` of a task
   definition and the `businessKey` of a call activity's `in`. Timers are parsed as ISO 8601 only
   (`pkg/bpmn/timer.go`), `completionCondition` is parsed and never read, and message names are
   always taken literally. Decided: the reader reports only what the engine evaluates
   (`SEQUENCE_FLOW_CONDITION`, `MULTI_INSTANCE_COLLECTION`, `MESSAGE_CORRELATION_KEY` per catch
   element, `INPUT_OR_OUTPUT_MAPPING`, and `SOMEWHERE_ELSE` for the other five), so `TIMER` and
   `MULTI_INSTANCE_COMPLETION_CONDITION` never occur, like cardinality; and
   `startMessagesAreKnown` is false only where a message start event's name cannot be read (no
   `messageRef`, an unknown message, a message without a name), because `=x` is the literal name
   `=x`. A timer written as `=...` (fails when the token arrives), the ignored `completionCondition`
   and the none intermediate throw event (fails at runtime, `pkg/bpmn/events.go`) go to the fit
   check of S4.1.2 as refusal candidates.
3. Conditions, timers and completion conditions are element TEXT, not attributes; the reader reads
   both. Extension elements are matched by local name in ANY namespace, as the engine matches them.
4. `read` takes the workflow module id as well, `read(workflowModuleId, filename, stream)`: the
   core does not wrap a `BpmnParseException`, so the message names file and module itself, as
   Camunda 8's does. A start event of the process itself with a signal or a conditional event
   definition is reported as `SIGNAL` or `CONDITIONAL` (S4.1.2 refuses both).

**The open question of the S3.1.1 review, decided 2026-10-16: Camunda 8's way.** Reading, preparing
and wiring need no engine, so an adapter id without clients (no `rest-address`, or degraded since
S3.3.1) reads its files like a configured one, and the checks of reading speak before the
configuration does. The first step which needs the engine ends the start with the message of the
validation, which names `rest-address`; until S4.3.2 that is the first step not built yet. The
`deployment-failure` policy applies, so an id which is nowhere first and set to `warn` still lets
the application boot. This is what Camunda 8's `getClient()` does and what
`ZenBpmClientFactory.rest()` already does. Held by `ZenBpmStartupValidationBootTest`
(`#anUnconfiguredAdapterIdWithBpmnFilesEndsTheStart`,
`#aDegradedAdapterIdWithBpmnFilesBootsOnTheFirstAdapter`) on Spring Boot and by
`ZenBpmUnconfiguredWithBpmnTest` and `ZenBpmDegradedWithBpmnTest` on Quarkus, with a module whose
BPMN file is the process of the sample workflow service (the degraded case beside the BPMS double,
which is first).

**Acceptance criteria**

- [x] Every collection of the record is filled by a fixture and asserted.
  `ZenBpmModelReaderTest` (15 tests, fixtures in `core/src/test/resources/models/`): tasks and
  user tasks with their task definitions and names, a decision-calling business rule task and a
  receive task left out, the default `user-task-type` (`job-tasks-zeebe.bpmn`); start and catch
  messages (`messages.bpmn`) and unknown start messages (`message-start-without-name.bpmn`); the
  three start events of the process itself without the two of its sub-processes, and its end
  events only (`start-and-end-events.bpmn`); multi-instance elements with collection, item and
  enclosing chain, and call activities (`multi-instance.bpmn`); concurrent-token elements beside
  their sequential and interrupting siblings (`concurrent-tokens.bpmn`); one expression per place
  and none for literals, timers, completion conditions, names and job types (`expressions.bpmn`).
  Refused: a second process, executable or not, and a DOCTYPE; a file which is no XML or no BPMN
  is refused naming file and module; a file without an executable process yields nothing; the
  stream is read to its end and not closed.
- [x] Models from the Camunda Modeler (`zeebe:` namespace) read without changes.
  `#bothExtensionNamespacesReadAlike` reads `job-tasks-zeebe.bpmn` and the same model in the
  `zenbpm:` namespace (`http://zenbpm.pbinitiative.org/1.0`, the namespace of the engine's own
  models) to the same tasks, user tasks, messages, start and end events.

**Implemented 2026-10-16.** Core: `model.ZenBpmBpmnModel` is the record of instruction 1, with
`StartEvent`, `MultiInstanceElement` and `CallActivity` as nested records and every collection
copied in file order; `model.ZenBpmModelReader.read(workflowModuleId, filename, stream)` reads the
bytes first (a parser closes what it reads), parses with DOCTYPEs refused, no external access and
the JDK's `DefaultHandler`, so nothing is written to standard error, and walks the one process
in document order. `ZenBpmDeploymentService.readBpmn` delegates to it, for an adapter id with or
without clients; every later step throws as before. Tests: `ZenBpmModelReaderTest` (15) with 13
fixtures in `core/src/test/resources/models/`, two new cases in `ZenBpmDeploymentServiceTest`
(now 11), two boots in `ZenBpmStartupValidationBootTest` (now 10; fixture
`smoke-test/src/test/resources/with-bpmn/`), and `ZenBpmUnconfiguredWithBpmnTest` and
`ZenBpmDegradedWithBpmnTest` in `quarkus/deployment` (`SmokeApplication.withBpmnFiles()` and
`withTheBpmsDoubleAndBpmnFiles()`). `TZ=Etc/UTC mvn clean install` on OpenJDK 25.0.2 builds green
with every integration test run and none skipped (core 488 unit and 34 integration tests, Spring
Boot 8, smoke test 23, Quarkus runtime 8, Quarkus deployment 34, coverage gate 33). The gate
measures 100 % of 11,668 instructions for Spring Boot and 100 % of 11,553 for Quarkus, and
`bin/check-deploy-safety.sh` passes both runs (13 files deployed, nothing after a red gate). The
first full build measured 99.89 %: the branch of a single quoted process, which cannot be reached,
and the error handler's methods for non-fatal errors were removed, and the fixtures gained a
receive task without a message, an `extensionElements` of the process, and a signal and a
conditional start event.

Seen to fail, each restored afterwards (13 of 13 mutants). Each fails `ZenBpmModelReaderTest`
unless named otherwise:
- a second, non-executable process accepted;
- DOCTYPEs allowed;
- the stream handed to the parser, which closes it;
- the root element not checked;
- a value without `=` reported as an expression;
- a business rule task which calls a decision counted as a job;
- a start message without a readable name counted as known;
- the start events of sub-processes collected;
- a non-interrupting boundary event not counted as a second token;
- a gateway with one outgoing flow counted;
- the enclosing chain of a multi-instance element dropped;
- the correlation key of a start event reported (also fails the message split);
- `readBpmn` refusing an adapter id without clients: fails `ZenBpmDeploymentServiceTest`.

### S4.1.2 Refuse what the engine cannot run

- [x] **Depends on:** S4.1.1.

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

*Fit check, 2026-10-16.* Every construct was run through the engine's own parser
(`bpmn20.TDefinitions`, whose `UnmarshalXML` calls `ResolveReferences`) at the model code of
`bdd795a3`, in a scratch Go program. The engine refuses with a `400`: signal, conditional,
escalation, compensation and cancel definitions on intermediate catch, throw and boundary events;
`scriptTask`, `manualTask`, `task` and `complexGateway` with flows; a condition leaving an
event-based gateway. It ACCEPTS and runs something else: a signal or conditional start event and a
signal, escalation, compensation or cancel end event (`UnmarshalXML` keeps only message, timer and
error definitions on a start event and terminate, message and error on an end event, so the event
runs as a plain one); a condition on a flow leaving anything but an exclusive or inclusive gateway
(read nowhere else); a standard loop (runs once). It accepts and fails later: a timer written as
`=...` (`timer.go` reads ISO 8601 only), and an intermediate throw event without a definition
(`events.go`). A `completionCondition` is parsed and never read. So the story's message ("the
engine rejects them (`400`) or would run them without an aggregate") missed a third reason. Two
more things did not fit: `AGENTS.md` lets code cite DECISIONS.md only, so the message cannot name
GAPS 13/14/19; and no refusal here is about a task which marking it `implemented-externally` would
change, because the engine refuses or ignores the construct whoever serves it, and an instantiating
receive task is no job a method serves. GAPS 19 was missing from `GAPS.md`.

Decided by the maintainer:
- Refused are the story's list, the start and end event definitions the engine drops, and three
  more: a timer written as an expression, a `completionCondition`, and an intermediate throw event
  without a definition. All of them in every process of the file, a process nobody claims
  included, because the adapter deploys the whole file and whoever serves that process would get
  something other than its model. Only the instantiating receive task (GAPS 19) is refused in a
  claimed process alone.
- The message gives each element's reason in its own words, under one of three headings (refused
  by the engine; deployed but run differently, saying how; would start a workflow without an
  aggregate), cites no GAPS number, and has no `howToMark` and no `isImplementedExternally`
  exemption.

**Acceptance criteria**

- [x] The message names file, process and every element; the boot ends before the engine is asked.
  `ZenBpmUnsupportedConstructsTest` (27 tests): one case per construct (24) asserts the heading of
  its reason and the line `<process>/<element> (<construct>)`; `three-offences.bpmn` asserts the
  whole message for one offence of each reason, grouped and in the order of the file; what ZenBPM
  runs as modelled passes (conditions at exclusive and inclusive gateways, a literal timer, message
  and link throw events, a waiting receive task, terminate and error end events); a process nobody
  claims is refused for the constructs the engine cannot run as modelled, but not for an
  instantiating receive task. The check runs in `prepareBpmn`, before anything reaches the
  engine, for an adapter id without clients as well, once per file
  (`ZenBpmDeploymentServiceTest#preparingRefusesWhatZenBpmCannotRunOncePerFile`); the message is an
  `IllegalStateException`, so the `deployment-failure` policy applies.

**Implemented 2026-10-16.** Core: `model.ZenBpmUnsupportedConstructs.check(workflowModuleId,
model, claimed)` walks the one process of the file, its sub-processes included, and collects
every offence by its reason; `ZenBpmDeploymentService.prepareBpmn` calls it once per file, asking
`isClaimedByAWorkflowService` for the process, and rewrites nothing yet. `ZenBpmProcessingContext`
is a class now: the workflow module and the files it prepared (`firstPreparationOf`), which the
rewriting of S4.2.1 needs as well. `wireBpmn` is now the first step not built yet, so the boot
tests of the S4.1.1 decision end there with the message of the validation. Tests:
`ZenBpmUnsupportedConstructsTest` (27) with the fixture `three-offences.bpmn`, two new cases in
`ZenBpmDeploymentServiceTest` (now 13; `TestCollaborators.of(adapterId, workflowTaskWiring)`).
`TZ=Etc/UTC mvn clean install` on OpenJDK 25.0.2 builds green with every integration test run and
none skipped (core 517 unit and 34 integration tests, Spring Boot 8, smoke test 23, Quarkus
runtime 8, Quarkus deployment 34, coverage gate 33). The gate measures 100 % of 12,291
instructions for Spring Boot and 100 % of 12,176 for Quarkus, and `bin/check-deploy-safety.sh`
passes both runs. Two earlier full builds were red or short: one on a case added for a condition
leaving no element, whose source the check looked up in a `Set.of` before testing it for
`null`; one at 99.85 %, because a search for the process could not fail and a helper was left
unused. Both were fixed.

Seen to fail, each restored afterwards (11 of 11 mutants), against a green unmutated run (a first
round ran after the case above was added and before it passed, so it was repeated):
- the instantiating receive task refused in a process nobody claims, a construct the engine
  refuses, or one it runs differently, refused only in a claimed process: each fails
  `#aProcessNobodyClaimsIsRefusedOnlyForWhatTheEngineCannotRun`;
- a condition at an exclusive or inclusive gateway refused, a literal timer refused, a throw
  event with any definition refused: each fails `#whatZenBpmRunsPasses`;
- the sub-processes not walked: fails the cases of an event sub-process start and of an end event
  inside a sub-process;
- end events not checked: fails the four end-event cases;
- only the first offence named: fails `#severalOffencesAreNamedInOneMessage`;
- `prepareBpmn` checking nothing, or checking a file again for each of its processes: each fails
  `ZenBpmDeploymentServiceTest#preparingRefusesWhatZenBpmCannotRunOncePerFile`.

**Answers to two reviews of F4.1, 2026-10-16** (one by Claude, 13 findings; one more, 6
findings). Every claim was checked against the engine at `bdd795a3`, its model code by a scratch Go
program against `pkg/bpmn/model/bpmn20` (unchanged on the local `main`), the rest in the source.

Valid and fixed:

- **Implicit forks** (Claude 1). `handleDefaultElementTransition` leaves a non-gateway element on
  all its flows, so every element except an exclusive or event-based gateway with more than one
  outgoing flow is a concurrent-token element now (fixture element `Split`). Camunda 8 misses it
  as well.
- **Boolean attributes** (second review 1). The engine decodes them with Go's
  `strconv.ParseBool` after trimming: `1`, `t`, `TRUE`, `True` are true. An attribute which is
  there but empty is false whatever the default (`cancelActivity=""` is non-interrupting), and a
  blank or unknown spelling makes the engine refuse the file. `instantiate="1"` passed the check,
  and `cancelActivity="0"`, `triggeredByEvent="1"`, `isSequential="1"` gave wrong concurrency.
  `model.ZenBpmBooleans` reads them as the engine does; the reader uses it for every flag and
  `isExecutable` (an unreadable one ends `readBpmn`), the check refuses every flag the engine
  cannot read.
- **Whitespace kept** (second review 2). The engine keeps a job type, a user task type and a
  message name as written; the reader trimmed them. Attributes are read raw now; a blank user task
  type stays a type, an empty one is the default.
- **An empty `calledDecision`** (second review 3) makes the business rule task an external job in
  the engine (`decisionId=" "` makes it a local decision); the reader now leaves a task out only
  where `decisionId` is not empty.
- **Expressions where the engine keeps them** (second review 4). A plain, terminate or error end
  event without definition id keeps no mappings, a start or boundary event only outputs, retries
  and backoff count for job elements only, the assignee for user tasks, `in businessKey` for call
  activities and sub-processes. A correlation key counts only where it starts with `=` as written
  (the engine checks it before trimming), and a condition in CDATA or with a comment is text to the
  engine (it reads `innerxml`).
- **Gateway conditions without `=`** (Claude 2) are refused: an exclusive gateway fails on them,
  an inclusive one never takes the flow; so is a CDATA condition.
- **Flows without a condition** (Claude 5) of an exclusive or inclusive gateway with more than one
  flow, other than the default, are refused: the engine never takes them.
- **Multi-instance** (Claude 4): a collection which is no expression, a missing one included, fails
  when reached; a `loopCardinality` is never read. Both refused.
- **What the engine refuses with `400`** (Claude 6): an unknown element with sequence flows (ad-hoc
  sub-process, transaction, ...), a sub-process with more than one start event, a non-interrupting
  error start event, an event-based gateway leading to anything but an intermediate catch event.
  A timer on a throw event, which the engine fails on later, is refused as well. Found while
  checking: an error end event whose definition has no `id` runs as a plain end event, an
  instantiating event-based gateway and a receive task inside a sub-process never start a workflow,
  and a business key which is no expression fails; all four refused under "runs differently".
- **`=` values ZenBPM takes literally** (Claude 3), decided by the maintainer: a message name, the
  `processId` of `calledElement`, the `type` of `taskDefinition`, the `decisionId` of
  `calledDecision` and the `candidateGroups` of `assignmentDefinition` starting with `=` are
  refused, like the `=` timer. The reader still reports such a name literally.
- **Boot tests of the refusal** (Claude 7, second review 6). `ZenBpmUnsupportedModelBootTest`
  (Spring Boot, 2) and `ZenBpmUnsupportedModelTest` / `ZenBpmUnsupportedModelWarnTest` (Quarkus)
  boot a module whose file has a script task and `instantiate="1"`: the start ends with the whole
  message, and a server standing in for the engine counted no request (a mutant asking the engine
  in `prepareBpmn` fails both platforms); with `warn` on an id which is nowhere first the
  application boots beside the BPMS double and the block holds the refusal.
- **Event sub-process start** (Claude 8): the message now says the event sub-process never starts;
  the javadoc no longer says a timer start fails "when a workflow reaches it".
- **Per-file guard** (Claude 9): the context's javadoc says the guard keeps the SPI's contract and
  cannot fire while the reader allows one process per file; a note under S4.2.1 says the same.
- **Camunda Modeler acceptance** (Claude 10): the paired fixtures carry a mapping, a multi-instance
  element and a call activity, and the test compares every collection; `camunda-modeler.bpmn` is a
  model in the shape the Modeler 5 exports for Camunda 8 (`modeler:` attributes, `zeebe:userTask`,
  form, subscription, diagram), written by hand, not exported.
- **A process without an id** (Claude 11) ends `readBpmn` with a message naming file and module;
  **the root-element message** (Claude 12) names the namespace found.
- **Nits** (Claude 13): the record constructor call one argument per line, the long javadoc line
  rewrapped, the double blank line removed, `String#formatted` instead of `+` in the tests, draft
  rows 13 and 14 in step with `GAPS.md`, and "as written" says "without the surrounding
  whitespace". GAPS 14 now names every construct of this list.

Not valid at first, then decided otherwise: **diagram interchange in the fixtures** (second review
5). The rule that a BPMN fixture carries a `BPMNDiagram` belonged to the engine's Go tests, as
answered under S2.2.1. On 2026-10-16 the maintainer made it a rule of this repository as well:
decision 29, held by `BpmnDiagramTest` in the coverage gate. Every `.bpmn` file got a diagram
generated by `bin/add-bpmn-diagram.py` (the hand-written diagram of `camunda-modeler.bpmn`, which
drew no element of its sub-process, was replaced); `broken.bpmn` stays without one, because it is
not well-formed on purpose, and `doctype.bpmn` names its entity in a `documentation` now, because
XML forbids an external entity in an attribute value, so the file was not well-formed either. BPMN
written inline in Java tests is exempt.

Evidence: `TZ=Etc/UTC mvn clean install` on OpenJDK 25.0.2 builds green with every integration test
run and none skipped (core 546 unit and 34 integration tests, Spring Boot 8, smoke test 25, Quarkus
runtime 8, Quarkus deployment 36, coverage gate 33); the gate measures 100 % of 13,309 instructions
for Spring Boot and 100 % of 13,194 for Quarkus, and `bin/check-deploy-safety.sh` passes both runs.
`ZenBpmModelReaderTest` has 20 tests and `ZenBpmUnsupportedConstructsTest` 51 (47 constructs), on
the 15 fixtures of `core/src/test/resources/models/`. Seen to fail, each restored afterwards, against a green unmutated run: 24 of 24
mutants in core (`1` not true; an empty flag taking the default; no flag unreadable; implicit forks
not counted; any `calledDecision` a decision; attributes trimmed; mappings not gated by kind; an
error end without definition id keeping mappings; a correlation key trimmed; markup in a condition
ignored; a process without id accepted; a literal gateway condition passing; the default flow
refused; `=` literals passing; unknown flow nodes passing; two sub-process starts passing; the
event-based gateway's targets not checked; a nested receive task taken for one of the process; a
literal collection passing; dropped end event definitions passing; an event sub-process start
called a plain start; the standard loop passing; sub-processes not walked; unreadable flags
passing), and `prepareBpmn` asking the engine once before it checks, which fails
`ZenBpmUnsupportedModelBootTest` (both cases) and `ZenBpmUnsupportedModelTest` with the server
counting one request. Two full builds before were short: one at 99.98 % (a blank condition and an
unknown element without flows were never read), and `checkChild` and `checkCondition` were
rewritten with early returns, because the formatter mangles an `else if` whose condition wraps.

**F4.1 done 2026-10-16**, merged to `main` as `091e9f6` (pull request #30, after two reviews and
CodeRabbit's review, whose three comments were fixed in the pull request). On that commit (GitHub
keeps run logs for 90 days, so what they showed is written here as well):

- `Checks`, run
  [38937712209](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/actions/runs/38937712209):
  green on both JDKs. `bin/check-engine-copies.sh` reported both copies as the engine's files of
  `bdd795a3...`. `ZenBpmModelReaderTest` ran 20 tests, `ZenBpmUnsupportedConstructsTest` 51,
  `ZenBpmDeploymentServiceTest` 13, `ZenBpmStartupValidationBootTest` 10,
  `ZenBpmUnsupportedModelBootTest` 2, `ZenBpmUnsupportedModelTest` 1,
  `ZenBpmUnsupportedModelWarnTest` 1 and `BpmnDiagramTest` 2, none skipped (core: 546 unit tests
  and 34 integration tests; Spring Boot 8, smoke test 25, Quarkus runtime 8, Quarkus deployment 36,
  coverage gate 35). The gate measured 100 % on both platforms: of 13,332 instructions for Spring
  Boot and 13,217 for Quarkus on JDK 25, of 13,324 and 13,209 on JDK 21.
- `Publish snapshots`, run
  [38937712124](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/actions/runs/38937712124):
  green. The core snapshot it published to GitHub Packages, `2.0.0-20261016.171113-26`, contains
  `model/ZenBpmModelReader`, `model/ZenBpmBpmnModel` with its nested `StartEvent`,
  `MultiInstanceElement` and `CallActivity`, `model/ZenBpmUnsupportedConstructs` and
  `model/ZenBpmBooleans`, and holds no test class, fixture or BPMN file. Both report pages
  (`spring-boot-report/index.html`, `quarkus-report/index.html`) answer HTTP 200 and show 0 of
  13,332 (Spring Boot) and 0 of 13,217 (Quarkus) instructions missed.

---

## F4.2 Rewriting

### S4.2.1 Scoping and the name-clash modes

- [x] **Depends on:** S4.1.1.

**Instructions**

1. `ZenBpmScoping.apply(document, module, plainProcessId, scoping, adapterId)` (from `PeaScoping`):
   under `USE_PREFIX` rewrite `process@id`, `message@name`, `error@errorCode`,
   `escalation@escalationCode` (kept for completeness though refused above), `taskDefinition@type`
   through `scopedTaskDefinition` (per process by default), `calledElement@processId` through
   `scopedProcessId`, `calledDecision@decisionId` through `scopedIdentifier`, `userTask`'s
   `taskDefinition@type` as well. In `NONE` and `BY_ADAPTER` untouched. Rewrite once per FILE: the
   context remembers rewritten filenames (`prepareBpmn` is called per process).
   (*Note from the review of F4.1, 2026-10-16:* the reader allows one process per file, so the
   guard cannot fire today; it keeps the SPI's contract.)
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

- [x] The rewritten XML deploys to the container (asserted in S4.3.2's IT under `use-prefix`).
  `ZenBpmScopingTest#usePrefixScopesWhatTheEngineResolvesAcrossFiles` compares the whole rewritten
  `models/scoping.bpmn` with the file prefixed by hand. *Closed by S4.3.2, 2026-10-17:*
  `ZenBpmDeploymentServiceIT#theFileRewrittenUnderUsePrefixDeploys` reads, prepares, wires and
  deploys `models/scoping.bpmn` and a DMN file under `use-prefix` to the pinned engine: the engine
  holds one definition under `scoping-it__Orders`, version 1, whose BPMN carries the job type
  `scoping-it__Orders__charge` and the called decision `scoping-it__rating`, and the version is
  registered. In the clean build of S4.3.2 (core 39 integration tests, none skipped).
- [x] Nothing configured -> the boot ends with the core's message naming `use-prefix`, `none` and
  the key, because `by-adapter` is the default and ZenBPM cannot serve it.
  `ZenBpmStartupValidationBootTest#nothingConfiguredEndsTheStartNamingBothWaysOut` (Spring Boot)
  and `ZenBpmByAdapterRefusedTest` (Quarkus) boot a module with a BPMN file and compare the whole
  message of the platform, "(nothing is configured, so the default applies)" included;
  `#byAdapterConfiguredEndsTheStartNamingBothWaysOut` does the same with `by-adapter` written.
  `ZenBpmDeploymentServiceTest#byAdapterIsRefused` holds the order (constructs first, then the
  mode, the file not rewritten), `#byAdapterOfAWorkflowIsRefused` the workflow's key.
- [x] `accept-unscoped-identifiers: true` turns the `none` warning into a DEBUG line naming the key;
  the module level wins over the adapter level.
  `ZenBpmDeploymentServiceTest#acceptedUnscopedIdentifiersAreNotWarnedAbout`: the adapter's key,
  the module's key, `false` on the module over `true` on the adapter (a WARN) and nothing set (a
  WARN); `#noneWarnsNamingTheAlternatives` holds the WARN, and
  `ZenBpmStartupValidationBootTest#noneIsWarnedAboutWhileTheModuleIsPrepared` shows the platform
  calling it while a module is prepared. Both platforms bind the key at both levels
  (`VanillaBpZenBpmPropertiesTest#acceptUnscopedIdentifiersOfAWorkflowModuleWins` in `spring-boot`
  and `quarkus/runtime`), and a booted Quarkus application accepts and resolves it
  (`ZenBpmOverlayResolutionTest#acceptUnscopedIdentifiersThroughItsTwoLevels`).

**Implemented 2026-10-16.** Core: `model.ZenBpmScoping.apply(document, module, process, scoping,
adapterId)` rewrites the identifiers listed in the fit check through the platform's scoping, the
pool and the diagram pointing at the process included; `ZenBpmDeploymentService.prepareBpmn`
refuses constructs (once per file), then `by-adapter` for the module and for the workflow, then
scopes the file (once per file) and records the process; `readDmn` keeps each DMN file in the
context, its decision ids scoped under `use-prefix` (a file which is no XML then ends the start
naming file and module); `deployResources` hands the module's processes to
`validateNoCollidingProcessIds` and throws as before; `defaultNameClashAvoidance` answers
`by-adapter` (decision 24); `warnAboutUnscopedIdentifiers` names ZenBPM's alternatives or writes the
DEBUG line. `ZenBpmAdapterConfiguration` has `accept-unscoped-identifiers` at the adapter and the
module level, and both platform overlays bind them. `ZenBpmInstanceIdentity` names the modes ZenBPM
serves for a group which shares `by-adapter` (the note of S3.3.1;
`ZenBpmInstanceIdentityTest#oneEngineWithTheDefaultModeNamesTheModesZenBpmServes`). Decision 24 is in
`DECISIONS.md`, GAPS 11 is no longer *planned*, and the README says what is built. Tests:
`ZenBpmScopingTest` (5) with the fixture `models/scoping.bpmn`, 12 new cases in
`ZenBpmDeploymentServiceTest` (now 25; `TestScoping` is the platform's scoping for tests), 3 new
boot tests in `ZenBpmStartupValidationBootTest` (now 13) and `ZenBpmByAdapterRefusedTest` on
Quarkus; the boot tests of the S4.1.1 decision set `use-prefix` now. `TZ=Etc/UTC mvn clean install`
on OpenJDK 25.0.2 builds green with every integration test run and none skipped (core 564 unit and
34 integration tests, Spring Boot 9, smoke test 28, Quarkus runtime 9, Quarkus deployment 38,
coverage gate 35). The gate measures 100 % of 13,945 instructions for Spring Boot and 100 % of
13,819 for Quarkus, and `bin/check-deploy-safety.sh` passes both runs. The first full build
measured 99.99 %: a pool of another process was never read; `ZenBpmScopingTest` has one now.

Seen to fail, each restored afterwards (17 of 17 mutants), against a green unmutated run. Each
fails `ZenBpmDeploymentServiceTest` unless named otherwise:
- a file scoped for each of its processes (`#preparingScopesAFileOnce`);
- `by-adapter` of the module not refused, or refused before the constructs (`#byAdapterIsRefused`);
- `by-adapter` of a workflow not refused (`#byAdapterOfAWorkflowIsRefused`);
- an empty value scoped, the references to the process not followed, extensions searched outside
  `extensionElements`, a `zeebe:` task definition not scoped: each fails `ZenBpmScopingTest`;
- the adapter's `accept-unscoped-identifiers` winning over the module's, and an accepted `none`
  warned about (`#acceptedUnscopedIdentifiersAreNotWarnedAbout`);
- a DMN file rewritten under every mode, and a file which is no XML not named;
- the module's processes not handed to the collision check;
- a group sharing `by-adapter` told to give each id its own mode: fails `ZenBpmInstanceIdentityTest`;
- the module's `accept-unscoped-identifiers` not handed on: fails `VanillaBpZenBpmPropertiesTest` on
  Spring Boot, and on Quarkus;
- `prepareBpmn` without the platform's check, installed and booted: fails both cases of
  `ZenBpmStartupValidationBootTest` and `ZenBpmByAdapterRefusedTest`.

*Note from S3.3.1, 2026-10-16.* `ZenBpmInstanceIdentity` (S3.3.1) refuses two ids on one
`rest-address` with the same mode before anything is deployed, so before the refusal of
`by-adapter` this story adds to `prepareBpmn`. Two ids on one address which set no mode both
resolve to the default `by-adapter`, and its message says "the same name-clash-avoidance
'by-adapter' ... give each id its own mode". An operator who then sets `use-prefix` on the new id
only passes that check and is refused here for the old id's `by-adapter`: two restarts. Once this
story refuses `by-adapter`, the message of a `by-adapter` group says that ZenBPM serves only
`use-prefix` and `none`, so each of the ids needs one of them (one more case in
`ZenBpmInstanceIdentityTest`). Not before: until then the message would promise a refusal which
does not exist.

*Fit check, 2026-10-16, decided by the Implementor.* The story was checked against `main`
(`4d18277`), `ADAPTER-AUTHORS.md` sections 2.1 and 3.2, the platform's `NameClashAvoidanceService`
(the source of the snapshot `2.0.0-20261015.045823-21` the build resolves), Camunda 8's
`Camunda8Scoping` and `readDmn`, PEA's `PeaScoping` and the engine at `bdd795a3`. The SPI has
every call instruction 1 to 5 names, and `validateNativeIsolationSupported` ends the start with a
message naming `use-prefix`, `none`, `vanillabp.adapters.<id>.name-clash-avoidance` and the
module's key. The engine reads `taskDefinition`, `calledElement`, `calledDecision` and
`subscription` as children of `extensionElements` in any namespace and takes an empty attribute
as none, so the scoping does the same: it rewrites only a value which is there, and a user task
without a task definition keeps the engine's default `user-task-type` unscoped (decision 12 lists
user tasks by process, not by type). Signals are refused by S4.1.2 and not rewritten. Five things
did not fit or were left open:

1. **A mode per workflow.** The core resolves `validateNativeIsolationSupported` for the MODULE,
   while `scopedProcessId` and `scopedTaskDefinition` resolve the mode per workflow
   (`vanillabp.workflow-modules.<module>.workflows.<process>.adapters.<id>.name-clash-avoidance`).
   A workflow set to `by-adapter` in a module of `use-prefix` passes the core's check and would be
   deployed with plain identifiers, isolated by nothing. Options: accept it, or refuse it in
   `prepareBpmn`. Decided: refused, with a message naming that key and the two ways out, because
   the mode promises an isolation ZenBPM does not have.
2. **Which message comes first.** `prepareBpmn` refuses constructs (S4.1.2) and now the mode. The
   decision of S4.1.1 is that the checks of reading speak before the configuration, so the
   constructs are refused first, then the mode, then the file is rewritten. The boot tests of that
   decision (an unconfigured and a degraded adapter id with BPMN files, both platforms) set
   `name-clash-avoidance: use-prefix` now, so they still end at the first step which needs the
   engine; a new boot test per platform holds criterion 2 with nothing configured.
3. **Instruction 4 lands before `deployResources` does.** The collision check needs no engine, so
   `deployResources` makes it now, with the plain process ids the context remembered, and then
   throws as before (for an adapter id without clients, the message of its validation). S4.3.2 keeps
   it as its step 2.
4. **`accept-unscoped-identifiers` below the adapter level.** The overlay of E3 knows no
   module-level key of its own: `retry-backoff`, `max-redeliveries` and `history-time-to-live` are
   shared by module and workflow. The key is added at the adapter level and at the module level
   only, on both platforms; a workflow level would mean nothing, because the core reports `none`
   once per module. `ZenBpmAdapterConfiguration` resolves it (the module wins), and the DEBUG line
   names the key which decided.
5. **`fromDefault` of `warnAboutUnscopedIdentifiers`** is never true here: the default is
   `by-adapter`, which is refused before the core could report `none` as a default. The message
   does not use it.

Decision 24 says "supersedes decision 2", but decision 2 is a draft which never entered
`DECISIONS.md`; the entry says that in its own words. It also promises a sentence on the first page
of the wiki, which holds a placeholder only, so the entry says the user documentation will carry it
once it is written. `GAPS.md` row 11 loses its *planned*.

### S4.2.2 Correlation keys and the start/catch collision

- [x] **Depends on:** S4.2.1.

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

*Fit check, 2026-10-16, decided by the Implementor.* Checked against `main` with S4.2.1, Camunda
8's `wireMessageSubscriptions`, and the engine at `bdd795a3`: its source, and a scratch Go program
running the engine's in-memory `bpmn.Engine` at that commit. GAPS 4 holds:
`publishCorrelatedMessageByName` in `internal/cluster/node.go` falls back from a correlation key no
subscription matches to the definition-level subscription of a start event of the same name, with
a warning. GAPS 4 was missing from `GAPS.md` and is added. The engine reads the key from
`TMessage.Extension`, `xml:"extensionElements>subscription"`, and evaluates it only where it starts
with `=` (`evaluateMessageCorrelationKey` in `pkg/bpmn/events.go`); a start event's subscription is
definition-level and ignores it. Five things did not fit:

1. **Where the subscription goes.** The story writes `<zenbpm:extensionElements>`; the BPMN element
   is `bpmn:extensionElements`, which the engine matches by local name either way. Built as a
   `zenbpm:subscription` inside the message's `bpmn:extensionElements`, reused where the message has
   one, with the `zenbpm` namespace declared on the root where the file does not declare it.
2. **A catch element inside a multi-instance body sees only the item.** The engine runs a
   multi-instance body in a child scope whose local variables are the item alone
   (`pkg/bpmn/multi_instance.go`), and a scope copies the local variables of its parent only
   (`runtime.NewVariableHolder`). The scratch program showed it: an intermediate catch event, a
   receive task, a boundary event and the start of an event sub-process inside a multi-instance
   sub-process fail with "result of correlation key evaluation is not a string: got <nil>" when the
   key is `=orderId`, and subscribe with `o-1` once the multi-instance sub-process carries the input
   mapping `=orderId -> orderId`; inside two nested multi-instance sub-processes both need it, either
   alone fails. A multi-instance receive task subscribes without it. Options: inject keys and let
   such workflows fail, refuse the shape, or carry the id into the body. Decided: wherever the
   adapter injects a key, every multi-instance sub-process around a catch element of that message
   gets the input mapping of the aggregate id, unless the modeller mapped that target there. A key
   the modeller wrote stays the modeller's: what it reads inside a body is theirs to map.
3. **The collision is a matter of the workflow module, not of one process.** Under one prefix, a
   message which starts process B and which process A waits for collides as much as one which
   starts and is caught by A itself. Decided: the context collects, per workflow module, the start
   messages of every process and the caught messages of the claimed ones, and the file which
   completes a pair is refused, naming the message and both elements with their processes. The
   catch of a process nobody claims does not count, because the application does not correlate it.
   Across workflow modules the names differ under `use-prefix`; under `none` the warning of S4.2.1
   covers every identifier two modules share. The message explains in words and cites no GAPS
   number, because code cites `DECISIONS.md` only.
4. **The reader gives names, not elements.** The model gains the message start events and the
   message catch elements with their message (`messageStarts`, `messageCatches`) and, for every
   element inside a multi-instance sub-process, the ids of the sub-processes around it
   (`enclosingMultiInstances`), which S4.2.3 needs as well.
5. **A key has to be a string.** The engine refuses a key whose expression gives anything else.
   E5 instruction 1 already writes the aggregate id as a String; the marker job of decision 25
   (E9) has to write `result.variables()` the same way where it holds the id.

Found while checking, not refused: the engine stores at most one active subscription per message
name and correlation key, engine-wide (the unique index `unique_name_correlation_key_waiting` on
`message_subscription_pointer(name, correlation_key) WHERE state = 1` in
`internal/sql/migrations/0001_schema.up.sql`, kept by `0005`; the in-memory storage refuses the
same). So two waits of one workflow for the same message at the same time, in parallel branches or
in the iterations of a parallel multi-instance body, cannot both subscribe under the aggregate id:
the scratch program saw the second iteration fail to store its subscription. The shapes in which
two waits overlap cannot all be told from a model, so this is GAPS 23, new, and nothing is refused
for it.

The acceptance criterion holds where the adapter writes nothing: a version-1 model whose caught
messages all carry a modelled key and which has no multi-instance body around them gets no key and
no mapping, and under `none` (the only mode which leaves process ids as version 1 deployed them)
its document stays equal to the file. Whether the serialised bytes are identical is S4.3.2's
question, which serialises.

**Acceptance criteria**

- [x] `Camunda8`-style version-1 models with modelled keys deploy byte-identically apart from namespace
  declarations. *Closed by S4.3.2, 2026-10-17, without the exception for namespace declarations:*
  `ZenBpmDeploymentServiceIT#aVersion1ModelDeploysByteForByte` deploys `models/version-1.bpmn` for a
  claimed process under `none` to the pinned engine, and the BPMN the engine hands back equals the
  file byte for byte (a mutant which writes every document out fails it). A file the pipeline left as
  it was written is deployed as its bytes (`model.ZenBpmModelWriter`, fit-check item 3 of S4.3.2). `ZenBpmDeploymentServiceTest#aVersion1ModelWithItsKeysStaysAsItWasWritten` prepares
  `models/version-1.bpmn` (the shape the Camunda Modeler exports for Camunda 8, a key on each of
  its two messages) for a claimed process under `none`: the document stays equal to the file, and
  the platform is asked nothing but whether the process is claimed.
  `ZenBpmCorrelationKeysTest#aFileWithEveryKeyModelledIsLeftAsItIs` holds the same for the rewrite
  alone.

**Implemented 2026-10-16.** Core: the reader gives `messageStarts`, `messageCatches` and
`enclosingMultiInstances`; `model.ZenBpmCorrelationKeys.apply(model, aggregateIdName)` writes the
keys and carries the aggregate id into the multi-instance bodies through the new
`model.ZenBpmInputMappings`, which adds an extension element in the `zenbpm` namespace (declared on
the root, with a free prefix where the file uses `zenbpm` otherwise) and input mappings in front of
the outputs, never twice for one target;
`ZenBpmCorrelationKeys.refuseMessagesWhichStartAndAreAwaited` refuses the collision over the
messages the context collects for the module. `prepareBpmn` makes, once per file: the collision
check, the scoping, and in a claimed process the keys, asking the platform for the aggregate id's
name only where a key is written. `GAPS.md` has GAPS 4 and the new GAPS 23; the README says what
is built. Tests: `ZenBpmCorrelationKeysTest` (7) with the fixture `models/correlation-keys.bpmn`, two
more cases in `ZenBpmModelReaderTest` (still 20), 4 new cases in `ZenBpmDeploymentServiceTest` (now
29, `TestWiring` is the platform's wiring for tests) with the fixture `models/version-1.bpmn`.
`TZ=Etc/UTC mvn clean install` on OpenJDK 25.0.2 builds green with every integration test run and
none skipped (core 575 unit and 34 integration tests, Spring Boot 9, smoke test 28, Quarkus runtime
9, Quarkus deployment 38, coverage gate 35). The gate measures 100 % of 14,682 instructions for
Spring Boot and 100 % of 14,556 for Quarkus, and `bin/check-deploy-safety.sh` passes both runs. The
first full build measured 99.99 %: an element without a namespace prefix was never extended;
`#additionsToAFileInTheDefaultNamespaceHaveNoPrefix` covers it.

Seen to fail, each restored afterwards (15 of 15 mutants), against a green unmutated run. Each
fails `ZenBpmCorrelationKeysTest` unless named otherwise:
- a modelled key overwritten (also fails `ZenBpmDeploymentServiceTest`);
- a start message given a key; the aggregate id not carried into multi-instance bodies;
- the aggregate id asked where no key is written (also fails `ZenBpmDeploymentServiceTest`);
- a target the modeller maps mapped again; an input added after the outputs; `extensionElements`
  put in front of the documentation; the prefix `zenbpm` taken although the file binds it
  elsewhere;
- the catch elements of a process nobody claims counted; only the first collision named;
- `prepareBpmn` without the collision check, with it after the scoping, or writing keys into a
  process nobody claims: each fails `ZenBpmDeploymentServiceTest`;
- the reader without the multi-instance sub-processes around an element, or without the message
  start elements: each fails `ZenBpmModelReaderTest`.

### S4.2.3 Task-parameter input mappings

- [x] **Depends on:** S4.2.1.

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

*Fit check, 2026-10-16, decided by the Implementor.* Checked against `main` with S4.2.1 and S4.2.2,
the SPI (`taskParameterNames`, `extensionTaskParameterNames`: "pass the element id first, then the
task definition, and fetch the union"), Camunda 8's `fetchVariablesOf`, which asks
`taskParameterNames` with the element id AND the task definition and adds the aggregate id, and
the engine at `bdd795a3`, its source and a scratch Go program running its in-memory engine. Open
question 2 was left to this fit check; four things did not fit:

1. **Open question 2: where mappings matter.** The scratch program read the variables of the jobs
   the engine created for an instance started with `orderId` and `items`. A plain service task, and
   one inside a plain sub-process, got every variable of the scope without any mapping. A parallel
   and a sequential multi-instance service task got `{item}` alone, and `orderId` only with an input
   mapping on the task. A task inside a multi-instance sub-process got `orderId: null` from an input
   mapping on the task, because the engine evaluates it in the body's scope, which holds the item
   only; it got `o-1` from the same mapping on the sub-process. Options: map every job element (the
   story as written), map nothing and drop the promise, or map where the engine needs it. Decided:
   only a job element inside a multi-instance body gets mappings, and they go where the engine
   evaluates them: on the element itself where it is a multi-instance element, and on every
   multi-instance sub-process around it (S4.2.2 proved that two nested ones both need it). A plain
   element gets nothing, so a model without a multi-instance body stays as it was written, and the
   criterion holds for both: outside a body the engine's copy carries a name, inside one the
   mappings do. The rationale of the story ("input variables are EMPTY without input mappings")
   holds inside a body only.
2. **The aggregate id travels with the names.** Inside a body a job carries no variable it is not
   given, and a workflow the engine started (decision 25) can be named by its aggregate id variable
   only, so the id is part of the names, as Camunda 8 fetches it with every job.
3. **An item is mapped only into the elements inside the one which declares it.** *Corrected
   after the reviews of F4.2: this item first said that no item is mapped, because "a mapping of it
   would read the scope around the element, where it is not", which holds for the declaring element
   only.* The engine sets an element's own item inside its body, and the items of the elements
   inside it do not exist yet where its mappings are evaluated, so neither is mapped on it. The item
   of an outer element is a variable of the scope around every element inside it, so where a method
   reads it as a `@TaskParam` it is mapped into each of them: a scratch program at `bdd795a3` gave a
   job of an inner multi-instance task the item `order` of the sequential multi-instance
   sub-process around it, mapped on the inner task only. How an iteration reaches a method through
   `@MultiInstanceElement` stays E9's.
4. **How a name is written.** The scratch program evaluated `=order-id`, `=order id` and `=if` as
   the variables of those names, and failed on a name in backticks; a source without `=` is taken
   literally. So every mapping is `source="=<name>" target="<name>"`, whatever the name.

The `ZenBpmRestClientContractIT`-style check that a job of such a task carries the variable belongs
to S4.3.2, which deploys; until then the criterion is held by the model and by the scratch program
above.

**Acceptance criteria**

- [x] A `@TaskParam` name reaches the job, inside a multi-instance body as well; so does a name only
  an extension's method reads. *Closed by S4.3.2, 2026-10-17:*
  `ZenBpmDeploymentServiceIT#aTaskParameterReachesAJobInsideAMultiInstanceBody` deploys
  `models/task-parameters.bpmn` under `use-prefix` with a method reading `customerName` by the task
  definition `ship` and an extension's method reading `trackingSystem` by the element id `Ship`,
  starts an instance on the pinned engine and completes its jobs over REST up to the body: the job of
  `Ship`, inside the multi-instance sub-process, carries `customerName`, `trackingSystem` and
  `orderId` (and `address`, the modeller's mapping), and not the workflow's `parcels`; the job of
  the multi-instance send task carries its item and `orderId`. What holds besides: the model carries
  the mappings the engine needs
  (`ZenBpmTaskParametersTest`: the names of the methods, by element id and by task definition, and
  of the extensions, with the aggregate id, into a multi-instance task and into every
  multi-instance sub-process around a job; nothing for a job outside a body, whose names the
  engine copies from its scope), and the scratch program of the fit check showed the engine hand a
  job exactly these names: everything of the scope outside a body, the item and the mapped names
  inside one.

**Implemented 2026-10-16.** Core: `model.ZenBpmTaskParameters.apply(model, module, wiring)`, called
by `prepareBpmn` once per file for a claimed process, after the correlation keys; it uses
`ZenBpmInputMappings` of S4.2.2, so a target the modeller maps is left and the inputs stand in
front of the outputs. The README and the architecture say what is built, and S9.4.1 has a note on
the items of outer iterations. Tests: `ZenBpmTaskParametersTest` (5) with the fixture
`models/task-parameters.bpmn`, and `ZenBpmDeploymentServiceTest#aClaimedProcessGetsItsTaskParameterMappingsOnce`
(now 30 cases): a second preparation of the same file adds nothing, a process nobody claims stays as
it was written. `TZ=Etc/UTC mvn clean install` on OpenJDK 25.0.2 builds green with every
integration test run and none skipped (core 581 unit and 34 integration tests, Spring Boot 9, smoke
test 28, Quarkus runtime 9, Quarkus deployment 38, coverage gate 35). The gate measures 100 % of
14,840 instructions for Spring Boot and 100 % of 14,714 for Quarkus on the first full build, and
`bin/check-deploy-safety.sh` passes both runs.

Seen to fail, each restored afterwards (11 of 11 mutants), against a green unmutated run. Each
fails `ZenBpmTaskParametersTest` unless named otherwise:
- a job outside every body mapped; a multi-instance task not mapping into itself; only the
  innermost body around a job mapped;
- the names not asked by the task definition, by the element id, or of the extensions; the
  aggregate id not mapped; the items mapped; the aggregate id's name asked for every job;
- `prepareBpmn` without the task parameters, or writing them into a process nobody claims: each
  fails `ZenBpmDeploymentServiceTest`.

**F4.2 built 2026-10-16** on the branch `feature/f4.2-rewriting`, one commit per story, not pushed.

**Answers to two reviews of F4.2, 2026-10-16** (one by Claude, 9 findings; one by Copilot, 1
finding, the same as Claude 5). Every claim about the engine was checked in its source at
`bdd795a3`, the one about nested items again with a scratch program against its in-memory runtime.

Valid and fixed:

- **A decision id the engine splits** (Claude 1, Major). For the bindings `latest` and none,
  `FindAndEvaluateDRD` in `pkg/dmn/dmn_engine.go` reads a decision id holding one `.` as
  `<DMN definitions id>.<decision id>` and fails on more than one; the binding `deployment` and any
  unknown one always fail; `versionTag` looks the whole id up. Under `use-prefix` the scoping
  prefixes the ids of the decisions, not those of the DMN definitions, so `pricing.rating` became
  `shop__pricing.rating`, which names DMN definitions that do not exist. The review did not see the
  second way into the same failure: the platform forbids only `__` in a workflow module id, so in a
  module `com.shop` even `rating` became `com.shop__rating`, which the engine splits. Decided by
  recommendation, because scoping the id of the DMN definitions as well would mean rewriting what
  the platform's `DmnDecisionIds` does not touch, for a form which is only useful for two decisions
  of one id in one workflow module: `ZenBpmScoping` refuses, before it rewrites anything, every
  called decision whose id holds a `.` once it is scoped, unless it is bound by `versionTag`,
  naming each task, what the id becomes and the three ways out. `ZenBpmUnsupportedConstructs`
  refuses, in every mode and every process, a binding other than `latest`, `versionTag` or none,
  and a decision id with more than one `.` under `latest` or none, as GAPS 14 now lists. Tests:
  `ZenBpmScopingTest#aDecisionIdTheEngineWouldSplitIsRefusedUnderUsePrefix`, two more cases of
  `ZenBpmUnsupportedConstructsTest#everyConstructIsRefused` and two more elements in
  `#whatZenBpmRunsPasses`.
- **The items of outer multi-instance elements** (Claude 2). The engine evaluates the input
  mappings of an element in the scope around it, and the multi-instance instance of an inner
  element copies the variables of the outer iteration, the outer item included. The scratch
  program gave the job of an inner multi-instance task `order`, the item of the sub-process around
  it, mapped on the inner task only. So an element now maps in the items of the elements around it
  and leaves out only its own and those of the elements inside it. Fit-check item 3 of S4.2.3, the
  S9.4.1 note, the javadoc and the architecture say so; `ZenBpmTaskParametersTest` maps `parcel`
  into `EveryItem` and not into `EveryParcel`.
- **A modelled key inside a body** (Claude 3). The guard leaving a body without the aggregate id
  where the message it waits for carries the modeller's key had no test; now
  `ZenBpmCorrelationKeysTest#aBodyAroundAModelledKeyGetsNoMapping` holds it.
- **`by-adapter` set for one workflow** (Claude 4). `ZenBpmWorkflowByAdapterRefusedTest` boots
  Quarkus and `ZenBpmStartupValidationBootTest#byAdapterOfOneWorkflowEndsTheStartNamingItsKey`
  Spring Boot with `use-prefix` on the adapter id and `by-adapter` on the workflow; both end the
  start with the message naming the workflow's key. That Quarkus accepts the key is proven by it.
- **"Every rewrite is idempotent"** (Claude 5, Copilot 1). The architecture now says that the file
  is rewritten once because the scoping is not idempotent, and that every boot rewrites the file
  as read, so the same file gives the same deployment. Draft decision 4 is left as it is: its
  "idempotently" is that same-file-same-deployment promise, which holds, and a decision is not
  edited.
- **Lines the formatter did not rewrap** (Claude 6). The javadoc of `ZenBpmProcessingContext` and
  the README paragraph are rewrapped. The formatter never wraps the arguments of a constructor call
  (`alignment_for_arguments_in_allocation_expression` is 0) and joins them, so the call building
  `ZenBpmBpmnModel` is kept one argument per line between `@formatter:off` and `@formatter:on`.
- **Three ids on one engine** (Claude 7). Both messages of `ZenBpmInstanceIdentity` tell more than
  two ids to set `use-prefix` on one, `none` on another, and give every further id an engine of its
  own; `ZenBpmInstanceIdentityTest#threeIdsOnOneEngineAreToldAboutTheTwoModes` for `by-adapter`
  and `none`.
- **"No DMN"** (Claude 8). `DmnDecisionIds` refuses what is no XML only; the javadoc, the test and
  this plan say "no XML" now.
- **The context's maps** (Claude 9). `ZenBpmProcessingContext` keeps its message maps and hands out
  read-only views; `addMessagesOf(model, claimed)` adds a file's messages and refuses a collision.

Claude's notes for later stories are no findings: that a call activity in a body needs the
aggregate id carried as well is now a note under instruction 9 of S4.3.1; that an unconfigured
application meets two refusals one start after the other is what fit-check item 2 of S4.2.1
decided.

Seen to fail, each restored afterwards (10 of 10 mutants), against a green unmutated run: the
binding check off, the two-dot check off, the two-dot check applied under `versionTag` (each fails
`ZenBpmUnsupportedConstructsTest`); the scoping's refusal off, a `versionTag` binding refused, an
unscoped id refused, the refusal after the rewrite (each fails `ZenBpmScopingTest`); the items of
outer elements left out, an element's own item mapped (`ZenBpmTaskParametersTest`); the guard of a
modelled key off (`ZenBpmCorrelationKeysTest`); three ids treated as two
(`ZenBpmInstanceIdentityTest`); the context's map handed out for writing
(`ZenBpmDeploymentServiceTest`). `TZ=Etc/UTC mvn clean install` on OpenJDK 25.0.2 builds green with
every integration test run and none skipped (core 587 unit and 34 integration tests, Spring Boot 9,
smoke test 29, Quarkus runtime 9, Quarkus deployment 39, coverage gate 35). The gate measures 100 %
of 15,059 instructions for Spring Boot and 100 % of 14,933 for Quarkus, and
`bin/check-deploy-safety.sh` passes both runs.

**Answer to CodeRabbit on pull request #32, 2026-10-16** (one finding). Two files of one workflow
module declaring the same process id reached `deployResources` as one process, because the context
kept the ids in a set, so the platform's collision check could not see them. Valid, but not by the
suggested fix: the platform's `validateNoCollidingProcessIds` skips one process of one module handed
over twice on purpose (one file with several processes, or one module deployed to several
adapters), and the core keeps only the first file of a process id (`putIfAbsent`) without refusing
the second. On ZenBPM, which deploys every file on its own, the second file would replace the first
as a new version of it; Camunda 8 refuses such a deployment. So `ZenBpmProcessingContext.recordProcess`
now keeps each process id with its file and refuses a second file declaring it, naming both files,
before that file is rewritten. Test: `ZenBpmDeploymentServiceTest#twoFilesDeclaringOneProcessAreRefused`.
Seen to fail, each restored afterwards (3 of 3 mutants): the refusal off, the same file prepared
again refused, the process recorded after the rewrite. `TZ=Etc/UTC mvn clean install` builds green
with every integration test run and none skipped (core 588 unit and 34 integration tests, Spring
Boot 9, smoke test 29, Quarkus runtime 9, Quarkus deployment 39, coverage gate 35); the gate
measures 100 % of 15,094 instructions for Spring Boot and of 14,968 for Quarkus, and
`bin/check-deploy-safety.sh` passes both runs.

**F4.2 done 2026-10-16**, merged to `main` as `0fa554c` (pull request #32, after two reviews and
CodeRabbit's review, whose one comment was answered in the pull request). On that commit (GitHub
keeps run logs for 90 days, so what they showed is written here as well):

- `Checks`, run
  [38964208056](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/actions/runs/38964208056):
  green on both JDKs. `bin/check-engine-copies.sh` reported both copies as the engine's files of
  `bdd795a3...`. `ZenBpmScopingTest` ran 6 tests, `ZenBpmCorrelationKeysTest` 8,
  `ZenBpmTaskParametersTest` 5, `ZenBpmUnsupportedConstructsTest` 53, `ZenBpmModelReaderTest` 20,
  `ZenBpmDeploymentServiceTest` 31, `ZenBpmInstanceIdentityTest` 11,
  `ZenBpmStartupValidationBootTest` 14, `ZenBpmByAdapterRefusedTest` 1,
  `ZenBpmWorkflowByAdapterRefusedTest` 1, `ZenBpmOverlayResolutionTest` 5 and `BpmnDiagramTest` 2,
  none skipped (core: 588 unit tests and 34 integration tests; Spring Boot 9, smoke test 29,
  Quarkus runtime 9, Quarkus deployment 39, coverage gate 35). The gate measured 100 % on both
  platforms: of 15,094 instructions for Spring Boot and 14,968 for Quarkus on JDK 25, of 15,086 and
  14,960 on JDK 21.
- `Publish snapshots`, run
  [38964208035](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/actions/runs/38964208035):
  green. The core snapshot it published to GitHub Packages, `2.0.0-20261016.204258-28`, contains
  `model/ZenBpmScoping`, `model/ZenBpmCorrelationKeys`, `model/ZenBpmTaskParameters`,
  `model/ZenBpmInputMappings`, `model/ZenBpmBpmnModel` with its nested `MessageReference`, and
  `ZenBpmProcessingContext`, and holds no test class, fixture or BPMN file. Both report pages
  (`spring-boot-report/index.html`, `quarkus-report/index.html`) answer HTTP 200 and show 0 of
  15,094 (Spring Boot) and 0 of 14,968 (Quarkus) instructions missed.

---

## F4.3 Wiring and deploying

### S4.3.1 The wiring calls

- [x] **Depends on:** S4.2.3.

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
   `startMessagesAreKnown` is false (a name cannot be read, see the fit check of S4.1.1: ZenBPM takes
   every message name literally), as `ADAPTER-AUTHORS.md` and Camunda 8
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
   if false, nothing. *Note from the reviews of F4.2:* a call activity inside a multi-instance body
   sees only the body's variables, as a job does, so the aggregate id it hands on must also be
   carried into every multi-instance element around it, with `ZenBpmInputMappings.carry` as S4.2.2
   does for correlation keys.
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

*Fit check, 2026-10-16, decided by the Implementor.* Checked against `main` (`ac8dd7c`), all of
`ADAPTER-AUTHORS.md` and the SPI of the snapshot `2.0.0-20261015.045823-21` the build resolves (the
platform's `f9505797`), Camunda 8's `wireBpmn` and `refuseHandlersWantingAnItemTheModelHasNot`, the
SPI mapping in `docs/analysis/02-spi-requirements-mapping.md`, and the engine at `bdd795a3`: its
source, and a scratch Go program running its in-memory `bpmn.Engine` (a copy of the commit with the
program in `cmd/probe`, so it can set a business key through `internal/appcontext`). Steps 0 to 6, 8
and 10 fit as written. Five things did not fit or were left open:

1. **Step 9 asks for less than the engine already does.** The scratch program started a caller with
   business key `agg-1` and the variables `orderId`, `items`, and read the job of a service task in
   the called process: a call activity of the process itself handed over every variable and the
   business key (`{items, orderId}`, `agg-1`); one inside a multi-instance sub-process, and a
   multi-instance call activity, handed over the business key and the item alone (`{item}`,
   `agg-1`), and `orderId` as well only where the multi-instance element carried the input mapping
   `=orderId -> orderId`. The business key is inherited in every case (`resolveChildBusinessKey`,
   `newChildProcessInstanceData` in `pkg/bpmn/sub_process.go`); `zenbpm:in businessKey` only
   replaces it. Options: write `in businessKey` and the mapping on every call activity (the story),
   write nothing, or carry the id only where the engine drops it. Decided: where the called process
   shares the aggregate, the aggregate id is carried into every multi-instance element the call
   activity runs in, itself included, with `ZenBpmInputMappings.carry` (`model.ZenBpmCallActivities`),
   and nothing is written outside a body, so a version-1 model with a call activity stays as it was
   written. `workflowsShareTheWorkflowAggregate` is asked only about a call activity inside a body,
   because nowhere else does the answer change the model.
2. **A process called on an aggregate of its own** gets the caller's business key and variables from
   the engine as well; the same program showed `in businessKey=""` and `in businessKey="=orderId"`
   replacing the inherited key. The story writes nothing for it, and nothing is written: how a
   called workflow of its own gets its aggregate is the start the engine reports, decision 25 and
   E9. A note under S9.1.1 says so.
3. **Step 7: which items are judged.** Camunda 8 judges the multi-instance elements the task runs in
   and asks by element id and by task definition; the SPI says an id the model does not know belongs
   to a caller. Decided the same way: the elements of `ZenBpmBpmnModel.multiInstanceChainOf` (the
   multi-instance sub-processes around the task and the task itself) without `inputElement`, for
   tasks and user tasks (`model.ZenBpmMultiInstanceItems`). An answered element which is in this
   model but not in the task's chain is not judged. `BpmnTaskSpec.multiInstanceElementsWithoutAnItem`
   is not filled: the platform reads it only for a version the engine holds (platform decision 94),
   which is E10's catalog.
4. **Step 12: which types are remembered.** Job types are the scoped task definitions of the tasks,
   without the tasks the application marked `implemented-externally` (`isImplementedExternally` is
   asked per task), because a subscription to such a type would take its jobs from whoever serves
   them. User-task types are not remembered: E8 decides how user tasks reach the application, and
   the engine's default type `user-task-type` is not scoped and is shared by every application on
   the engine, so subscribing it would take the user tasks of all of them. The context keeps
   `WiredProcess(bpmnProcessId, scopedBpmnProcessId, jobTypes, reportsItsStart, reportsItsEnd)`,
   where the two answers of step 5 are `false` without the platform's invoker.
5. **The test doubles.** The story asks for a `TestCollaborators` in Camunda 8's shape implementing
   `NameClashAvoidanceSupport` and `BpmsInitiatedStartInvoker`. The doubles of this repository are
   proxies which write down every call (`TestScoping`, `TestWiring`), so `TestWiring` answers the
   wiring calls as well and a new `TestStarts` stands for the two invokers of the lifecycle. The
   tests are in `ZenBpmDeploymentWiringTest`, beside `ZenBpmDeploymentServiceTest`, which is long
   enough already.

Instruction 3 of S9.1.1 (every start event to `validateBpmsInitiatedStarts`, a called process's too)
and the first paragraph of S9.4.1 (the item refusal) are built by this story; notes there say so.

**Acceptance criteria**

- [x] Every wiring call of `ADAPTER-AUTHORS.md` section 3.2 the mapping marks SUPPORTED is made, for
  claimed processes only.
  `ZenBpmDeploymentWiringTest#aClaimedProcessGetsEveryWiringCallOnce` wires `models/wiring.bpmn`
  and compares every call: `reportModelExpressions`, `validateTaskWiring` in its four-argument form
  with every task and user task and their names, `reportStartMessages` with the plain name,
  `validateBpmsInitiatedStarts` with the plain, the message and the timer start event,
  `startsAWorkflowOfItsOwn`, `workflowEndedHandlerExists`, `reportConcurrentTokenElements`, and
  `multiInstanceElementNames` by element id and task definition for the one task in a body whose
  element names no item; the context remembers the scoped process id and the scoped job types
  without the task served elsewhere. `#aProcessNobodyClaimsGetsNoCall`: nothing is asked but
  whether the process is claimed. `#aTaskWithoutAMethodEndsWiring` (the platform's refusal ends
  `wireBpmn` before anything else is reported), `#startMessagesAreReportedOnlyWhereEveryNameCanBeRead`
  (an empty collection; no call where a name cannot be read), `#theStartEventsOfACalledProcessAreReported`,
  `#withoutTheInvokersNothingIsReportedAboutStartsAndEnds`, `#aTaskWithoutATaskDefinitionGivesNoJobType`
  (since the answers to the reviews of F4.3 `#aTaskServedElsewhereWithoutATaskDefinitionGivesNoJobType`),
  `#aMethodWantingAnItemNoElementHandsOverIsRefused` (by task definition and by element id, the whole
  message), `#aMethodWantingAnItemAnElementHandsOverPasses`,
  `#theAggregateIdIsCarriedToAProcessCalledOnTheSameAggregate` (into the sub-process and the
  multi-instance call activity around a shared call, not for a process on its own aggregate, a call
  outside a body or a call naming no process) and `#wiringNeedsNoEngine`. Booted: a task nothing
  serves ends the start with the platform's message on Spring Boot (`ZenBpmWiringBootTest`) and on
  Quarkus (`ZenBpmUnwiredTaskTest`).
- [x] The expressions reported are the ones the modeller wrote, none of the adapter's own.
  `ZenBpmDeploymentWiringTest#theReportedExpressionsAreTheModellers`: preparing wrote the
  correlation key `=orderId` and the input mapping of the aggregate id into the file, and the call
  reports the two collections the modeller wrote and nothing else.

**Implemented 2026-10-16.** Core: `ZenBpmDeploymentService.wireBpmn` makes the calls of the fit
check for a claimed process and returns at once for one nobody claims; the new
`model.ZenBpmMultiInstanceItems` refuses an item no element hands over, `model.ZenBpmCallActivities`
carries the aggregate id to a process called on the same aggregate from inside a body, and
`ZenBpmBpmnModel.multiInstanceChainOf` gives the multi-instance elements an element runs in, which
`ZenBpmTaskParameters` uses now as well. `ZenBpmProcessingContext` remembers a `WiredProcess` per
claimed process. `deployResources` is the first step not built yet, so the boot tests of the S4.1.1
decision end there with the message of the validation, and
`ZenBpmStartupValidationBootTest#noneIsWarnedAboutWhileTheModuleIsPrepared` ends at it. Tests:
`ZenBpmDeploymentWiringTest` (12) with the fixture `models/wiring.bpmn`; `TestWiring` answers the
wiring calls and the new `TestStarts` stands for the two invokers; boot tests `ZenBpmWiringBootTest`
(Spring Boot) and `ZenBpmUnwiredTaskTest` (Quarkus) with the fixture `with-unwired-task/` of each
platform. `TZ=Etc/UTC mvn clean install` on OpenJDK 25.0.2 builds green with every integration test
run and none skipped (core 600 unit and 34 integration tests, Spring Boot 9, smoke test 30, Quarkus
runtime 9, Quarkus deployment 40, coverage gate 35). The gate measures 100 % of 15,584 instructions
for Spring Boot and 100 % of 15,458 for Quarkus, and `bin/check-deploy-safety.sh` passes both runs
(13 files deployed, nothing after a red gate). The first full build measured 99.99 %: a task
without a task definition was never wired; `#aTaskWithoutATaskDefinitionGivesNoJobType` covers it.

Seen to fail, each restored afterwards (25 of 25 mutants), against a green unmutated run. Each fails
`ZenBpmDeploymentWiringTest`:
- a process nobody claims wired; the expressions not reported; the user tasks not handed to the
  check of the tasks; the check not made; start messages reported where a name cannot be read; the
  start events not reported; the start remembered as reported without the invoker; the concurrent
  tokens not reported;
- the items not checked; an element with an item judged; the tasks outside a chain without items
  asked; items not asked by task definition, or by element id; items asked by a missing task
  definition; elements of other models judged;
- the call activities not carried; a call outside a body carried; a call naming no process asked; a
  process on its own aggregate carried; only the innermost body carried; the aggregate id's name
  asked per call (only after a second shared call was added to the case);
- a task served elsewhere given a job type; a task without a task definition given one;
- the chain of an element without the bodies around it, or without the element itself.
`wireBpmn` wiring nothing, installed and booted, fails `ZenBpmWiringBootTest` and
`ZenBpmUnwiredTaskTest`.

### S4.3.2 Deploy resources and report versions

- [x] **Depends on:** S4.3.1, S2.4.1.

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

*Note from S4.1.1, 2026-10-16.* An adapter id without clients reaches `deployResources` (it reads,
prepares and wires like a configured one, see the decision under S4.1.1), and step 1 has to end it
with the message of its validation: `awaitReady` asks `factory.rest()`, which throws exactly that,
so nothing has to be added, but nothing may catch it either. `ZenBpmStartupValidationBootTest`,
`ZenBpmUnconfiguredWithBpmnTest` and `ZenBpmDegradedWithBpmnTest` hold it.

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

*Fit check, 2026-10-17, decided by the Implementor.* Checked against `main` with S4.3.1, all of
`ADAPTER-AUTHORS.md` (sections 2.1, 3.1 on the identifier reports, 3.2 on `registerDeployedVersion`
and 8), the SPI of the snapshot the build resolves, the platform's `DeploymentService` and
`DeployedProcessVersionsCheck` (`f9505797`), Camunda 8's `deployResources` and
`Camunda8IdentifiersTheClusterHolds`, and the engine at `bdd795a3`: its source, and its pinned image,
run as a scratch container. The notes of S4.1.1 and S2.4.1 fit as written: step 1 is
`ZenBpmEngineWait.awaitReady(factory)` and then `ZenBpmEnvironmentInfo.logOnce(factory,
startupReport)`, nothing catches what the wait throws for an adapter id without clients, and the boot
tests of the S4.1.1 decision hold that. GAPS 4 and 23, which the F4.2 stories cite, are in `GAPS.md`.
Seven things did not fit or were left open:

1. **Open question 3: what the engine held before.** Asked of the source, not assumed: the REST
   handler deploys every BPMN file under the constant resource name `process.bpmn`
   (`internal/rest/server.go`, `const resourceName = "process.bpmn"`), and a process definition
   carries key, version, process id, name, version tag and BPMN, but no owner. So the marker Camunda
   8 uses, the resource name of this application's own files, does not exist: an earlier version of
   this application and a definition of another application look alike. (b) would warn about the
   application's own older versions at every boot. The plan's (a) said "report an empty
   collection", which the SPI reads as "asked, and the engine holds none of it"; silence is what
   says "not asked". Decided: (a) without the call, `reportIdentifiersTheBpmsAlreadyHolds` is not
   called, and the limit is the new GAPS 24. `reportIdentifiersTheModelsDeclare` is called once per
   module after the deployment, with what the scoping read (`ZenBpmScoping.apply` hands it back) and
   the decision ids of the DMN files.
2. **Which identifiers the models declare.** The story names `BPMN_PROCESS_ID` among them. The SPI
   says process ids have their own check (`validateNoCollidingProcessIds`), and Camunda 8 reports
   none. Decided: message names, error and escalation codes, task definitions (of tasks and user tasks,
   with their process) and decision ids, in every mode, the files of processes nobody claims
   included, because the whole file is deployed. A called process or decision is declared by another
   file and not reported here. Reading the decision ids means reading every DMN file as XML, so a DMN
   file which is no XML now ends `readDmn` in every mode, not only under `use-prefix`.
3. **"Byte-identical" needs the bytes.** The engine stores and returns what it was sent: the
   scratch container answered `bpmnData` equal to the deployed `version-1.bpmn` byte for byte, and
   answered a second deployment of the same bytes with `200` and the same key. Writing a DOM back
   out does not give the file: a scratch Java program showed the serializer dropping the line breaks
   between attributes and sorting the namespace declarations. Options: always serialise (the engine
   compares content after normalising the format, so versions would not move, but no file would
   reach the engine as it was written), or send the file's own bytes where nothing was changed.
   Decided: the model keeps the bytes it was read from (`ZenBpmBpmnModel#content`), and
   `model.ZenBpmModelWriter` sends them where the document still equals a fresh read of them, and
   otherwise writes the document out with DOM Load and Save (UTF-8, no indentation, no
   `standalone`).
4. **`aDeployedVersionWithoutAMethodEndsTheStart` cannot pass before E10.** The platform runs the
   check of the version this boot deployed (platform decision 126) only for processes with a
   registered `ProcessVersionCatalog`: `DeployedProcessVersionsCheck#check` walks
   `registeredCatalogs`, and the catalog is E10's (instruction 11 of S4.3.1). Decided:
   `registerDeployedVersion` is called for every deployed process, the unchanged ones included, and
   the test moves to S10.1.1 with a note there.
5. **Where the Spring integration test lives.** The architecture puts the Spring integration tests
   into `spring-boot`, and a Maven module of its own would need its own report wiring. Decided: the
   test application, its workflow module `deploy-it` and `ZenBpmDeploymentIT` are test sources of
   `spring-boot`, run by failsafe against the pinned image. The test asserts what the engine holds and
   what the start logged: with S4.3.2 alone the start then ends at `startWorkflowProcessing`, the
   step not built yet, and S4.3.3 turns it into a booted application.
6. **The Quarkus pipeline test** asserts the parse by "a log line the reader writes at DEBUG"; the
   reader writes none. Decided: the INFO line `wireBpmn` writes per process (as Camunda 8 does)
   proves that reading, preparing and wiring ran on Quarkus; the start then ends in the engine wait
   naming the address and `startup-wait`. The `warn` case of criterion 2 runs on Quarkus with an
   address nothing listens on, because a booted Quarkus test runs its class in the application's own
   class loader, where no server of the test can be started; the refusal with the engine's reason is
   held on Quarkus with `fail`, and with `warn` on Spring Boot.
7. **`ZenBpmDeployedProcesses` is kept by the client factory** of the adapter id, as Camunda 8
   keeps its deployed processes in its client factory: both services of an adapter id hold that
   factory, so no registry of its own is needed.

The order of instruction 2 stays as S4.2.1 built it: the collision check runs first, before the
engine wait, because it needs no engine. A file the engine refuses as it is (`ZenBpmErrors.permanentFailure`,
so `400`, `413` or `415`, read in one place as decision 10 asks) ends the deployment with the
engine's answer, naming file, workflow module and process; any other failure names the address the
file did not reach. Files are sent one by one in the order they were prepared, the DMN files first
(BPMN first as built, changed by the answers to the reviews of F4.3); a refusal stops before the
next file.

**Acceptance criteria**

- [x] Version is registered on an unchanged deployment as well.
  Against the pinned engine, `ZenBpmDeploymentServiceIT#anUnchangedFileKeepsItsVersion` deploys a
  file twice and a changed copy once: `registerDeployedVersion` with `1`, `1` and `2`, and the engine
  holds two versions. Booted on Spring Boot, `ZenBpmDeploymentIT#aWorkflowModuleWithBpmnAndDmnDeploys`
  deploys the module `deploy-it` (one BPMN and one DMN file, `use-prefix`) three times: version 1, the
  same file again kept as version 1 "(unchanged)", a changed file version 2; the engine holds the
  process under `deploy-it__DeployIt` and the decision `deploy-it__rating` in one DMN resource
  definition (unchanged at the second and third start). `ZenBpmDeployingTest#everyFileIsDeployedAndItsVersionRegistered`
  holds the order of the requests (ready, status, the DMN file, the BPMN file, its definition; the
  DMN file came last until the answers to the reviews of F4.3), the version registered, what
  `ZenBpmDeployedProcesses` remembers, the INFO line and the declared identifiers reported.
- [x] A rejected file ends the boot naming file, process and the engine's reason; with
  `deployment-failure: warn` on a nowhere-first id the application boots degraded.
  `ZenBpmDeploymentServiceIT#aFileTheEngineRefusesEndsTheDeployment`: the pinned engine refuses a
  call activity whose `version` is no number, and the whole message names file, workflow module,
  process and the engine's reason. `ZenBpmDeployingTest#aRejectedModelNamesTheFile` (since the
  answers to the reviews of F4.3 the second of two BPMN files is refused, and nothing is registered
  or remembered for the first), `#aRejectedDecisionNamesTheFile`,
  `#anotherFailureNamesTheFileAndTheAddress`. Booted against a server standing in for the engine
  which refuses every file: `ZenBpmRefusedFileBootTest` on Spring Boot ends the start with that
  message, and with `warn` on an adapter id behind the BPMS double the application boots and the
  log holds the refusal; `ZenBpmRefusedFileTest` on Quarkus ends the start with that message, and
  `ZenBpmDeploymentFailureWarnTest` boots Quarkus with `warn` where the deployment fails (an address
  nothing listens on, fit-check item 6). `ZenBpmDeploymentPipelineTest` shows the pipeline running on
  Quarkus up to the engine wait.

**Implemented 2026-10-17.** Core: `ZenBpmDeploymentService.deployResources` waits for the engine,
logs its version once, deploys every BPMN file (`model.ZenBpmModelWriter`: the bytes as read where
nothing was changed) and every DMN file (the DMN files first since the answers to the reviews of
F4.3), registers the version of every process (since then once the engine took every file),
remembers it in the new `ZenBpmDeployedProcesses` of the client factory, logs one INFO line per
module and reports the identifiers the files declare; `ZenBpmBpmnModel` keeps the bytes it was read
from; `ZenBpmScoping.apply` hands back the plain identifiers; `readDmn` keeps the decision ids and
reads every DMN file as XML. `GAPS.md` has GAPS 24; the README and the architecture say what is
built; S10.1.1 has the note of fit-check item 4. Tests: `ZenBpmDeployingTest` (5, against
`FakeEngine`), `ZenBpmModelWriterTest` (2), `ZenBpmDeployedProcessesTest` (1), two more cases in
`ZenBpmModelReaderTest` (now 22) and `ZenBpmScopingTest` (now 8), `ZenBpmDeploymentServiceTest`
(still 31: the non-XML DMN case holds in every mode now), the Docker tests
`ZenBpmDeploymentServiceIT` (5, core) and `ZenBpmDeploymentIT` (1, `spring-boot`, with the test
application `DeployItApplication` and the workflow module `deploy-it` in its test sources), the boot
tests `ZenBpmRefusedFileBootTest` (2, Spring Boot), `ZenBpmRefusedFileTest`,
`ZenBpmDeploymentFailureWarnTest` and `ZenBpmDeploymentPipelineTest` (Quarkus), with a
`StandInEngine` in each platform's test sources;
`ZenBpmStartupValidationBootTest#noneIsWarnedAboutWhileTheModuleIsPrepared` now ends in the engine
wait. `TZ=Etc/UTC mvn clean install` on OpenJDK 25.0.2 builds green with every integration test run
and none skipped (core 612 unit and 39 integration tests, Spring Boot 9 and 1 integration test,
smoke test 32, Quarkus runtime 9, Quarkus deployment 43, coverage gate 35). The gate measures 100 %
of 16,233 instructions for Spring Boot and 100 % of 16,107 for Quarkus, and
`bin/check-deploy-safety.sh` passes both runs (13 files deployed, nothing after a red gate). The
first full build failed once in `ZenBpmJobStreamTest#aConnectionNobodyAnswersIsNoReturn`, whose
`TcpRelay` binds the port it cut a moment before again ("Address already in use"); the test and the
relay are not touched by this story, and the next two full builds passed it.

Seen to fail, each restored afterwards (21 of 21 mutants in core, against a green unmutated run;
one of them was first written wrong, still reporting the identifiers, and was replaced by one which
drops the call). Each fails `ZenBpmDeployingTest` unless named otherwise:
- an unchanged file written out, a rewritten one sent as its bytes (each also
  `ZenBpmModelWriterTest`); the model's bytes handed out for writing (`ZenBpmModelReaderTest`);
- the engine not waited for; its version not logged; the version not registered; the deployed
  process not remembered; a definition not found by key; the DMN files not deployed; the declared
  identifiers not reported;
- a refusal treated as any other failure, and any failure as a refusal;
- the files not kept while preparing; the identifiers of the scoping dropped; the decision ids not
  remembered, or read only under `use-prefix` (`ZenBpmDeploymentServiceTest`);
- message names, error codes, escalation codes or task definitions not declared, empty values
  declared (`ZenBpmScopingTest`, the first two also `ZenBpmDeployingTest`).
Installed and booted, or against the engine: wiring logging nothing fails
`ZenBpmDeploymentPipelineTest` on Quarkus (so its check of the log runs); a refusal treated as any
other failure fails `ZenBpmRefusedFileBootTest` on Spring Boot and `ZenBpmRefusedFileTest` on
Quarkus (a first run of the Spring half was void: the install skipped no integration test, failed
on the mutant, and left the unmutated core in place; repeated with `-DskipITs`); every file written
out fails `ZenBpmDeploymentServiceIT#aVersion1ModelDeploysByteForByte`.

### S4.3.3 Start and stop processing (skeleton)

- [x] **Depends on:** S4.3.2, S2.3.1.

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

*Fit check, 2026-10-17, decided by the Implementor.* Checked against `main` with S4.3.1 and S4.3.2,
`ADAPTER-AUTHORS.md` (sections 2.1 and 5 on the shutdown), the platform's `DeploymentService`
(`startWorkflowProcessing` once per module after every adapter deployed, `stopWorkflowProcessing`
extensions first and adapters last, and never a call with a `null` context), `ZenBpmJobStream` and
`ZenBpmClientFactory` of E2. The stream already keeps a type subscribed until the last subscriber of
it unsubscribes, and the factory's hook does what the note of the review of S2.4.1 asks. Four
things did not fit or were left open:

1. **No `JobDispatcher` class yet.** E6 builds it per adapter id (S6.1.1). Until then the stream's
   consumer is a private method of the deployment service which logs a job at WARN, naming type and
   key, and leaves it to its lock; a note under S6.1.1 says what it replaces.
2. **"A module which deployed nothing"** never reaches the adapter: the platform skips an adapter for
   a module without an executable process, and never calls it with a `null` context. What can reach
   it is a module whose claimed processes give no job type (a model of start and end events, or tasks
   all served elsewhere). Decided: such a module registers no hook and subscribes nothing, and asks
   neither the stream nor the factory for anything, so an adapter id without clients starts it as
   well; criterion 2 is held for that case.
3. **Which types are subscribed** follows the decision of S4.3.1: the scoped job types of the tasks
   of claimed processes, without the tasks served elsewhere and without user tasks (E8 polls them).
4. **A subscription which fails.** The stream refuses a type another subscriber of the adapter id
   holds with other settings (an extension later). Decided: the module is stopped again (what it
   subscribed so far is unsubscribed, the hook removed) and the failure ends the start, which the
   platform does not soften by `deployment-failure`.

The stop is idempotent, because the platform and a closing factory may both stop a module: the
context hands out what started once (`ZenBpmProcessingContext#processingStopped`). The boot test of
criterion 1 runs on Spring Boot against the pinned engine (`ZenBpmDeploymentIT`, which boots now that
the processing starts) and on Quarkus against a server standing in for the engine
(`ZenBpmStopOrderTest`, with a workflow service of its own whose task gives the module a job type,
and the factory heard on its own logger while the application stops, as `ZenBpmCompleteAdapterIdTest`
does).

**Acceptance criteria**

- [x] Stopping the application stops every started module before its factory closes: a boot test
  per platform (Spring Boot and Quarkus) stops a context with a started module and finds no WARN of
  the factory about a module it had to stop.
  `ZenBpmDeploymentIT#aStartedModuleSubscribesItsJobTypeAndStopsFirst` boots Spring Boot against the
  pinned engine: the module subscribes `deploy-it__DeployIt__ship`, the job of an instance started
  over REST arrives and is logged at WARN as nothing handles it yet (so the subscription reached the
  engine), and closing the context unsubscribes the type and closes the REST client without the
  factory's WARN. `ZenBpmStopOrderTest` boots Quarkus with a module which subscribes
  `smoke-app__Shipping__ship` (the module is open at the factory while it runs) and hears the factory
  close without that WARN. A hook left in place when the module stops fails both.
  `ZenBpmWorkflowProcessingTest#theClosingFactoryStopsAModuleThePlatformDidNotStop` shows the other
  side: a module nobody stopped is stopped by the closing factory, which says so.
- [x] A module which deployed nothing subscribes nothing and starts without touching the engine.
  Fit-check item 2: `ZenBpmWorkflowProcessingTest#aModuleWithoutAJobTypeSubscribesNothing` starts and
  stops a module without a job type on an adapter id without clients, and the stream of the engine
  sees no call.
- [x] No job type of a process nobody claims is subscribed.
  `ZenBpmWorkflowProcessingTest#aModuleSubscribesTheJobTypesOfItsClaimedProcesses` prepares and wires
  `models/wiring.bpmn` (claimed, one task served elsewhere) and a file of a process nobody claims in
  one module: the three types of the claimed tasks are subscribed with the engine's default lock and
  cap, the hook registered, and the stop unsubscribes them and removes the hook, once.
- [x] Types shared by two modules under `none` stay subscribed until the second module stops.
  `ZenBpmWorkflowProcessingTest#aSharedJobTypeStaysSubscribedUntilBothStopped`: two modules share
  `charge`; it is unsubscribed only when the second stops, with the type of its second process.
  Since the answers to the reviews of F4.3 a third module starts between the two stops, and its
  subscription is the next frame, so no frame of the first stop reached the stream.
  `#aModuleCannotStartOnAClosedFactory`, `#aSubscriptionWhichFailsStopsTheModule` and
  `#aJobNothingHandlesIsLeftToItsLock` hold fit-check item 4 and the WARN.

**Implemented 2026-10-17.** Core: `ZenBpmDeploymentService.startWorkflowProcessing` registers the
factory's hook and subscribes the module's job types (`ZenBpmProcessingContext#jobTypes`), with the
engine's default lock and cap, and hands every job to a WARN which leaves it to its lock;
`stopWorkflowProcessing` unsubscribes them and removes the hook, once
(`ZenBpmProcessingContext#processingStarted` and `#processingStopped`); the last step which said "not
built yet" is gone. `FakeJobStreamEngine` of the client tests is public for the tests of the
deployment package. The README says what is built, S6.1.1 has a note. Tests:
`ZenBpmWorkflowProcessingTest` (7, against `FakeJobStreamEngine` over TCP); `ZenBpmDeploymentIT` boots
now and has the second case (now 2); `ZenBpmStopOrderTest` on Quarkus with
`SmokeApplication#withAModuleWhichSubscribes`, `ShippingWorkflowService`, the fixture
`with-shipping/Shipping.bpmn` and `StandInEngine#addressOfOneTakingEveryFile`;
`ZenBpmDeploymentServiceTest` lost the case of the step not built yet (now 30). `TZ=Etc/UTC mvn
clean install` on OpenJDK 25.0.2 builds green with every integration test run and none skipped (core
618 unit and 39 integration tests, Spring Boot 9 and 2 integration tests, smoke test 32, Quarkus
runtime 9, Quarkus deployment 44, coverage gate 35). The gate measures 100 % of 16,397 instructions
for Spring Boot and 100 % of 16,271 for Quarkus on the first full build, and
`bin/check-deploy-safety.sh` passes both runs (13 files deployed, nothing after a red gate).

Seen to fail, each restored afterwards (12 of 12 mutants in core, against a green unmutated run).
Each fails `ZenBpmWorkflowProcessingTest`, the second-last also `ZenBpmDeploymentServiceTest`:
- a module without a job type asking the stream; the hook registered after the subscriptions, or not
  at all; a failed subscription leaving the module started; another lock or another cap than the
  engine's default; the jobs not taken from the stream;
- a stopped module stopped again; the job types not unsubscribed; the hook not removed; the start not
  forgotten when it stops; the job types of the first process only.
Installed and booted: the hook not removed when the module stops fails
`ZenBpmDeploymentIT#aStartedModuleSubscribesItsJobTypeAndStopsFirst` on Spring Boot (the factory's
WARN about `deploy-it` appears) and `ZenBpmStopOrderTest` on Quarkus.

**Answers to two reviews of F4.3, 2026-10-17** (one by Claude, 7 findings; one by Copilot, 2
findings, the first the same as Claude 1). Every claim about the engine was checked in its source at
`bdd795a3`, the one about the aggregate id in a called process with a test against the pinned image.

Valid and fixed:

- **An element which creates a job and names no task definition** (Claude 1 and Copilot 1, Major).
  `GetTaskType` of the service and send task and of the message end and throw events returns the
  empty `TypeName`, and `pkg/bpmn/jobs.go` creates the job with it, so the engine accepts such a
  model and hands out a job of the type `''`. The platform matches the task to a method by its
  element id, so the start passed, but no job type was subscribed, and a workflow would have waited
  at the task for good; Camunda 8 never gets there, because Zeebe refuses the model. Copilot
  offered subscribing the type `''` as the other way out: it was not taken, because the type is
  not scoped by anything, so every application on the engine would take the jobs of the others, as
  with the default `user-task-type` of fit-check item 4 of S4.3.1. `wireBpmn` now refuses, before
  the check of the tasks, every such element of a claimed process, naming file, process and each
  element with the fix, unless the application marks the task `implemented-externally`; a process
  nobody claims is left as modelled. A business rule task without `calledDecision` and without a
  task definition is refused with them. GAPS 25 records it. Tests:
  `ZenBpmDeploymentWiringTest#anElementCreatingAJobOfNoTypeIsRefused`,
  `#aProcessNobodyClaimsMayHoldJobsOfNoType` and `#aTaskServedElsewhereWithoutATaskDefinitionGivesNoJobType`
  (renamed from `#aTaskWithoutATaskDefinitionGivesNoJobType`, which locked the old behaviour in).
  Two fixtures of `ZenBpmDeploymentServiceIT` hold such an element (`Label` of
  `task-parameters.bpmn`, `Notify` of `scoping.bpmn` with `type=""`); their tests mark it served
  elsewhere. The filter of tasks without a task definition among the job types went, because only
  a task served elsewhere is left without one.
- **The test of shared job types could not fail** (Claude 2, Major). It compared the frames after
  both stops, which are the same where the first stop already unsubscribes the shared type. A third
  module now starts between the two stops; its subscription must be the next frame.
- **BPMN before DMN** (Claude 3, Minor). A new process version could call a decision the engine
  does not hold yet (it checks the called decision only at runtime), and a refused DMN file left
  that version in place. The DMN files now go first, and the versions are registered and
  remembered only once the engine took every file. A module's deployment stays not atomic, because
  the engine takes one file per request: a file refused leaves the files before it deployed, which
  the README and the architecture now say. Tests: `ZenBpmDeployingTest#aRejectedDecisionNamesTheFile`
  (no BPMN file sent after a refused DMN file) and `#aRejectedModelNamesTheFile` (two BPMN files,
  the second refused, nothing registered or remembered for the first); `FakeEngine#answers` with a
  handler is public for it.
- **GAPS 24** (Claude 4, Minor): the sentence ends with "warned about", and the evidence names
  `CreateProcessDefinition`.
- **No test against the engine for the aggregate id carried to a called process** (Claude 5,
  Minor). `ZenBpmDeploymentServiceIT#theAggregateIdReachesAProcessCalledFromInsideAMultiInstanceBody`
  deploys `call-in-a-body.bpmn` and `called-in-a-body.bpmn` under `use-prefix` and finds `orderId`
  on the job of the called process. The redelivery after the lock (the README's "after which the
  engine hands it out again") stays with E6, which serves the job.
- **Two blank lines in a row** in `ZenBpmProcessingContext` (Claude 7, Nit): one.

Answered with a note, not built here:

- **No Quarkus test against the engine** (Copilot 2, Major) and **no Quarkus test with a DMN file**
  (Claude 6, Nit). E4's "Done when" asks the Quarkus pipeline test, which runs without the engine;
  the plan builds the `QuarkusProdModeTest` against the engine in S5.3.1
  (`ZenBpmWorkflowLifecycleTest`, "This class grows with every later epic"). A note under S5.3.1
  now asks it to deploy one BPMN and one DMN file and to assert the scoped ids, the reported
  version and a subscribed job type, so the packaged extension is proved for deploying as well.

Seen to fail, each restored afterwards (6 of 6 mutants in core, each against a green unmutated run
of the test named): the refusal of a job of no type switched off
(`#anElementCreatingAJobOfNoTypeIsRefused`); a task served elsewhere refused too
(`#aTaskServedElsewhereWithoutATaskDefinitionGivesNoJobType`); the DMN files sent after the BPMN files
(`#aRejectedDecisionNamesTheFile`); the versions registered where a later file is refused
(`#aRejectedModelNamesTheFile`); the subscriber the adapter id instead of the workflow module, the
mutant of Claude 2 (`#aSharedJobTypeStaysSubscribedUntilBothStopped`); and the aggregate id not
carried, against the pinned engine
(`ZenBpmDeploymentServiceIT#theAggregateIdReachesAProcessCalledFromInsideAMultiInstanceBody`).
`TZ=Etc/UTC mvn clean install` on OpenJDK 25.0.2 builds green with every integration test run and
none skipped (engine test support 12 unit and 3 integration tests, core 620 unit and 40 integration
tests, Spring Boot 9 unit and 2 integration tests, smoke test 32, Quarkus runtime 9, Quarkus
deployment 44, coverage gate 35). The gate measures 100 % of 16,485 instructions for Spring Boot and
100 % of 16,359 for Quarkus, and `bin/check-deploy-safety.sh` passes both runs (13 files deployed,
nothing after a red gate).

**E4 "Done when" is met, 2026-10-17.** A workflow module with BPMN and DMN deploys to the container
from a booted Spring application: `ZenBpmDeploymentIT` (module `deploy-it`, one BPMN and one DMN file,
the engine holding both under their prefixed ids, versions 1, 1 and 2). The Quarkus pipeline test
proves the pipeline runs on Quarkus: `ZenBpmDeploymentPipelineTest` reads, prepares and wires a file
and ends in the engine wait naming the address, `ZenBpmRefusedFileTest` deploys to a server standing
in for the engine and ends with its refusal, and `ZenBpmStopOrderTest` deploys, starts and stops a
module. Every unsupported construct is refused with a message naming file, process and element:
`ZenBpmUnsupportedConstructsTest` (S4.1.2 and the answers to its reviews), booted by
`ZenBpmUnsupportedModelBootTest` and `ZenBpmUnsupportedModelTest`. Left for later epics, each with a
note at its story: the version catalog and the check of the deployed version (S10.1.1), task
delivery (E6), user tasks (E8), the start and end markers and a called process on its own aggregate
(S9.1.1).

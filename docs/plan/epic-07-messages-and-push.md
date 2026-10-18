# E7 - Messages and the pushed aggregate

**Goal.** `correlateMessage`, `startWorkflowByMessage` and `aggregateChanged` work with the engine's
message semantics: no buffering, one active subscription per name and key, a 404 when nobody waits,
variables at the process scope, child instances as the scope of a task.

**Done when.** A waiting workflow continues on a correlation published before AND after its
subscription exists, a conditional gateway behind a pushed aggregate takes the new branch, and both
run on Quarkus as well.

Template: `Camunda8ProcessService.correlateMessage*`, `Camunda8MessageDeclarationTest`, the
`aggregateChanged` section of Camunda 8's README. Contract: `PhaseOperation.CORRELATE_MESSAGE`
(activation-scoped key), `AGGREGATE_CHANGED` (no key), `PhaseTwoRetryLater`,
`PhaseTwoRequest.workflowId()` and `taskRecord()`, `ADAPTER-AUTHORS.md` sections 3.3 and 4.1.

---

## F7.1 Messages

### S7.1.1 `CORRELATE_MESSAGE`

- [x] **Depends on:** E6.

**Instructions**

1. Phase one: the message name must be in `catchMessages` of a model of the module this application
   deployed (through `ZenBpmDeployedProcesses`); a name only in `startMessages` fails with the hint
   to use `startWorkflowByMessage`; a name nowhere fails naming the models; where the module
   deployed no process (an older definition still runs) stay silent. No engine call (a subscription
   may not exist yet, and the engine has no TTL to wait it out). This check stays the adapter's: the
   core's check behind `reportStartMessages` (platform decision 109) covers starts only.
2. Phase two: `rest.publishMessage(scopedName, correlationKey = correlationId ?? aggregateId,
   variables = shared values + id variable)`; `ZenBpmErrors.nobodyWaits` -> throw
   `PhaseTwoRetryLater("no subscription yet ...", workflow-visibility-timeout)` naming module,
   process, aggregate, message and `vanillabp.outbox.wait-for-visibility-at-most` (GAPS 5). A
   `PhaseTwoRetryLater` is not counted as an attempt; the outbox repeats it until that window (about
   four hours by default) has passed and then blocks the entry (platform decision 49). Throw it only
   while nothing reached the engine, which a `404` from publishing guarantees. Anything permanent
   rethrown; the rest repeated by the outbox.
3. Deduplication: the engine has none; the outbox key (with the activation) is the only net, which the
   README says. A correlation id is the correlation key, so the modeller's subscription expression has
   to read it (the injected default reads the aggregate id); document as on Camunda 8.

**Tests**: `ZenBpmMessageDeclarationTest` (three cases), `ZenBpmProcessServiceTest#nobodyWaitsIsRetryLater`,
Spring `ZenBpmMessageIT`: `correlateMessageResumesTheWaitingWorkflow`,
`aMessagePublishedBeforeTheSubscriptionIsRepeatedUntilItCorrelates` (start a workflow whose first
element is a 5-second timer before the catch; correlate at once; assert the workflow continues after
the timer), `aCorrelationIdSelectsTheWaitingOccurrence` (two receive tasks with modelled keys).

**Acceptance criteria**

- [x] A 404 on publish leaves the outbox entry due after the visibility window, never blocks it at once.
  `ZenBpmProcessServiceTest#nobodyWaitsIsRetryLater` (at the SPI: a `PhaseTwoRetryLater` whose
  `retryAfter` is `workflow-visibility-timeout`, read through a wrapping exception as a store reads
  it, repeatable, with the engine's `404` as its cause) and, against the pinned engine and the
  platform's own JDBC outbox, `ZenBpmMessageIT#aMessagePublishedBeforeTheSubscriptionIsRepeatedUntilItCorrelates`:
  the correlation is made while the workflow waits at its five-second timer, the outbox logs
  "cannot run yet - the outbox entry ... is dispatched again in PT2S" with the adapter's message at
  least twice, the workflow continues after the timer, and the entry ends `DISPATCHED` with one
  attempt, so no repetition was counted. `#aPublicationNothingEverWaitsForIsBlockedOnceTheWindowRanOut`
  shows the other end: with `wait-for-visibility-at-most: PT15S` the entry is blocked only once
  those fifteen seconds passed.
- [x] Phase one never calls the engine. Every case of `ZenBpmMessageDeclarationTest` runs against a
  fake engine and ends with its request list empty; the models are read while deploying
  (`ZenBpmDeployingTest#theMessagesOfEveryHeldVersionAreRemembered`). *Narrowed by the answers to
  the two reviews below:* phase one never asks the engine whether something waits; on its way to a
  refusal, and only then, it reads the models again, as Camunda 8 does (decision 42).
  `ZenBpmMessageDeclarationTest#aRefusalRestsOnTheModelsReadAgain` counts the readings: none where
  a name passes.

*Fit check, 2026-10-18, decided by the Implementor.* Checked against `main` (`825c0ac`),
`ADAPTER-AUTHORS.md` (3.3 and 4.1), the platform as the adapter resolves it
(`2.0.0-20261015.045823-21`: `PhaseOperation.CORRELATE_MESSAGE` with its idempotency key
`correlationKey`, `PhaseOneRequest#messageName`/`#correlationId`, `PhaseTwoRequest#activationId`,
`PhaseTwoRetryLater(String, Duration)`, `MigrationProcessService#addressWorkflow` and
`#addressWorkflowPhaseTwo`, `#stillNotVisible`, `JdbcPhaseTwoOutboxDispatcher#waitForTheReadModel`,
`JdbcPhaseTwoOutboxStore`, `PhaseTwoOutboxReader` of `test-utils`; signatures checked in the
resolved jars), Camunda 8's `Camunda8ProcessService#preflightCorrelateMessage`,
`#validateMessageIsDeclared` and `#correlateMessage`, and the engine at the pin `bdd795a3` (source
exported with `git archive`: `ZenNode.PublishMessage`, `publishCorrelatedMessageByName`,
`publishDefinitionMessageByName` and `publishMessageBySubscriptionKey` of
`internal/cluster/node.go`, `Server.PublishMessage` of `internal/rest/server.go` and of
`internal/cluster/server/server.go`, `PublishMessageByKey` and `PublishMessageOnToken` of
`pkg/bpmn/events_api.go`, `propagateAndSaveReceiveTaskOutputVariables` of `pkg/bpmn/events.go`,
`POST /v1/messages` of `openapi/api.yaml`; the image run by every new IT). What did not fit, and
what was decided:

1. **Which models count** (beyond the story). "A model of the module this application deployed"
   would refuse a correlation a workflow on an older version waits for, where the deployed version
   renamed or dropped the message, and the application could never correlate it. Camunda 8 reads
   the models its cluster holds for that (its decision 19), partly in phase one. Options: this
   start's models alone (refuses such correlations); an engine read in phase one (breaks the
   acceptance); the models every version the engine holds, read while deploying. Decided the last,
   recorded as decision 42: `ZenBpmHeldMessages` reads, once the engine took every file, the
   deployed version from the answer of the deployment and every other version (one request for the
   list, one per version; a first version asks nothing more), and `ZenBpmDeployedProcesses`
   remembers the names as the engine holds them (`ModuleMessages`). Measured against the engine in
   `ZenBpmDeploymentServiceIT#aMessageAnEarlierVersionWaitsForPassesPhaseOne`.
2. **Where the check stays silent** (brief question 4). Silent where the module deployed no process
   in this start, where a version could not be read (a WARN names it, `ZenBpmDeployingTest#versionsWhichCannotBeReadAreNamed`),
   and where the module serves a process it brought no model for, a declared id of decision 41,
   because the reading does not cover those models. One case each in
   `ZenBpmMessageDeclarationTest#theCheckSaysNothingWhereAModelIsUnknown`.
3. **How fast the outbox repeats a retry-later** (brief question 1). The platform decides it from
   the retry-later alone: `JdbcPhaseTwoOutboxDispatcher#waitForTheReadModel` reschedules the entry at
   now plus `retryAfter` without counting (`rescheduleEntryWithoutCounting`), and the poller sleeps
   until the earliest due entry; `vanillabp.outbox.poll-interval` only bounds that sleep. So the IT
   needs no outbox setting: the adapter's default `workflow-visibility-timeout` (`PT2S`) is the
   cadence, which the IT reads from the platform's own log line. Nothing in main code is shortened.
4. **Which window** (brief question 2). The constructor takes a message and the retry-after; its
   javadoc names `wait-for-visibility-at-most` as the store's bound. So the retry-after is
   `workflow-visibility-timeout` and the message names the bound. A zero timeout did not fit: a
   zero retry-after would make the store dispatch the entry again at once, for hours. The core
   answers a zero window of its own (`#stillNotVisible`) with an ordinary failure and the store's
   backoff, so the adapter does the same: the `404` is wrapped in an `IllegalStateException` naming
   the key, repeatable, counted, blocked after `block-after-attempts`
   (`ZenBpmProcessServiceTest#nobodyWaitsWithAZeroWindowIsAnOrdinaryFailure`).
5. **A correlation to a workflow which ended** (brief question 3). The core probes the workflow
   before phase one and again before phase two, and a `COMPLETED` answer is a WARN and nothing else
   in both (`addressWorkflow`, `addressWorkflowPhaseTwo`), so an ended workflow gets no
   publication. What stays is a workflow which still runs but passed the waiting element, or waits
   under another key: the engine answers `404` as for "not yet", so the entry is repeated until the
   window ran out and then blocked. The retry-later's message says so, as do the README and GAPS 5.
   Measured: `#aPublicationNothingEverWaitsForIsBlockedOnceTheWindowRanOut`.
6. **"Throw it only while nothing reached the engine."** At the pin a `404` comes from the two
   subscription lookups (`FindActiveMessageSubscriptionPointer`, and `FindMessageSubscriptionByKey`
   on the partition's leader when a pointer named a subscription which ended meanwhile), both before
   anything is published. *Corrected by the answers to the two reviews below:* it also comes after a
   publication which correlated, where the workflow fails right behind the waiting element on a
   not-found (GAPS 29, decision 43), so a `404` does not guarantee that nothing reached the engine.
   The pointer is read from the database of the node asked, so a follower
   which lags behind answers `404` for a subscription just created as well; the repetition covers it.
7. **Deduplication** (story item 3). The engine deduplicates nothing. The platform's idempotency key
   of a correlation exists only with a correlation id (module, process, aggregate, message,
   correlation id and activation), and the JDBC store deduplicates only against an entry which still
   waits (`DEDUP_KEY` is freed when the entry is done). So the README says: a second correlation
   with the same correlation id is not planned while the first waits, one without an id is never
   deduplicated, and a repeated publication may correlate twice; held by
   `ZenBpmMessageIT#aSecondCorrelationWithTheSameIdIsNotPlannedWhileTheFirstWaits`. The README
   sentence on a message correlated twice ("Which phase-two failures are repeated") agrees.
8. **The correlation key** (story item 3). Phase two publishes the correlation id as the key where
   one was passed, else the aggregate id, which the key the deployment injects reads; a modeller's
   subscription has to read the correlation id. Held by `ZenBpmMessageIT#aCorrelationIdSelectsTheWaitingOccurrence`
   (two message elements of the same name under `=firstReviewer` and `=secondReviewer`, so GAPS 23's
   one subscription per name and key is not met) and `ZenBpmProcessServiceTest#aCorrelationIdIsTheCorrelationKey`.
   GAPS 4 (the fallback to a start subscription) is why a start-only message is refused in phase one;
   its row loses the *planned*.
9. **GAPS 5** is entered under the plan's number with the evidence at the pin; the draft's
   `node.go:844,867` were lines of an older engine and are replaced by the function names. The
   variables of a publication reach the process scope where the receive task maps no output
   (`PropagateMappedOutputsOrAll`), measured by `#correlateMessageResumesTheWaitingWorkflow` (the
   status the aggregate shares at the correlation is the instance's variable afterwards).
10. **The engine's `404` stays the cause** of the retry-later (`initCause`, the constructor has no
   cause), so the log shows the engine's answer and `isPhaseTwoFailureRepeatable` still reads it.
11. **Names and places.** `ZenBpmMessageCorrelation` in `processservice` (both phases, the shape of
   `ZenBpmTaskOutcomes`), `ZenBpmHeldMessages` in `deployment`; the Spring IT application
   `it/message` (`MessageItApplication`, `Letter`, `LetterRepository`, `LetterWorkflow`), one process
   `Letters` with a path per scenario in `message-it/letters.bpmn` (with its diagram, decision 29),
   `message-it.yaml`.

**Implemented 2026-10-18.** Core: `ZenBpmMessageCorrelation` (both phases of `CORRELATE_MESSAGE`,
registered by `ZenBpmProcessService`), `ZenBpmHeldMessages` (the messages of every held version,
read by `ZenBpmDeploymentService#deployResources`), `ZenBpmDeployedProcesses#recordMessages`,
`#messagesOf`, `#servesAProcessItDeployedNoModelFor` and `ModuleMessages`. Spring Boot tests:
`ZenBpmMessageIT` with `it/message` (`MessageItApplication`, `Letter`, `LetterRepository`,
`LetterWorkflow`), `message-it.yaml` and `message-it/letters.bpmn`. Decision 42; GAPS 5 (entered
under the plan's number) and GAPS 4 (built); the README, architecture 4.4 and the mapping row.

New tests: `ZenBpmMessageDeclarationTest` (7: the three the story names, the name compared as
written under `none`, and the three silent cases); `ZenBpmProcessServiceTest`
`#nobodyWaitsIsRetryLater`, `#phaseTwoPublishesTheMessageUnderTheAggregateId`,
`#aCorrelationIdIsTheCorrelationKey`, `#nobodyWaitsWithAZeroWindowIsAnOrdinaryFailure`,
`#everyOtherRefusalOfAPublicationIsThrownAsItIs`; `ZenBpmDeployingTest`
`#theMessagesOfEveryHeldVersionAreRemembered`, `#aFirstVersionIsTheOnlyOne`,
`#versionsWhichCannotBeReadAreNamed`; `ZenBpmDeployedProcessesTest#aModuleServesAProcessWithoutAModelOnlyForAnIdItDidNotDeploy`;
`ZenBpmDeploymentServiceIT#aMessageAnEarlierVersionWaitsForPassesPhaseOne`; Spring `ZenBpmMessageIT`
(6: the three the story names, `#aSecondCorrelationWithTheSameIdIsNotPlannedWhileTheFirstWaits`,
`#aPublicationNothingEverWaitsForIsBlockedOnceTheWindowRanOut`,
`#aMessageNoModelWaitsForIsRefusedWhereTheApplicationCalls`).

Seen to fail, each restored afterwards (17 of 17 mutants, against green unmutated runs, a kill
counted only where a test failed): an awaited name refused; a start-only name not told apart; a
declared-only id not silent; an unread version not silent; `servesAProcessItDeployedNoModelFor`
ignoring the deployed ids (survived at first, killed by the test added for it); a `404` not handed
back (unit, and against the engine: `#aMessagePublishedBeforeTheSubscriptionIsRepeatedUntilItCorrelates`);
the correlation id ignored (unit, and against the engine: `#aCorrelationIdSelectsTheWaitingOccurrence`);
a zero window turned into a retry-later; the retry-later without its cause; older versions never
read (unit, and against the engine: `ZenBpmDeploymentServiceIT`); a first version reading the list;
the deployed version read twice; an unread version not named; the messages not recorded (against
the engine).

`TZ=Etc/UTC mvn clean install` on OpenJDK 25.0.2 builds green with every integration test run and
none skipped (engine test support 12 unit and 5 integration tests, core 971 unit and 79 integration
tests, Spring Boot 11 unit and 42 integration tests, smoke test 37, Quarkus runtime 10, Quarkus
deployment 51, Quarkus integration tests 10, coverage gate 37). The gate measures 100 % of 25,472
instructions for Spring Boot and 100 % of 25,346 for Quarkus, and `bin/check-deploy-safety.sh`
passes (13 files on a green build, nothing after a red gate).

**Answers to two reviews of S7.1.1, 2026-10-18.** Claude (approve after one fix of the
documentation: 1 Major, 2 Minor, 1 Nit) and Copilot (changes requested: 3 Major) reviewed `131240e`
in their own worktrees; both built it green, and both probed the pinned image. Every finding was
checked against the engine at the pin, the resolved platform and the Camunda 8 adapter. Six
distinct findings, all valid:

1. **A `404` after a correlation** (Claude 1, Copilot 2, Major). Valid: `PublishMessageOnToken` of
   `pkg/bpmn/events_api.go` flushes the correlated tokens and then runs the instance on, and
   `Server.PublishMessage` of `internal/cluster/server/server.go` turns any error which wraps
   `storage.ErrNotFound` into `NotFound`; both reviewers saw a receive task completed, an incident on
   a call activity of a process nobody deployed, and a `404`. The status cannot tell it from a
   message nothing waits for (decision 30), and a counted failure would block the main case, a
   message published before its workflow waits, after a few attempts without removing the second
   receipt. So the `404` stays a retry-later (decision 43), and what it costs is said: GAPS 29
   entered, GAPS 5's evidence, item 6 above, the class's javadoc and comment, architecture 4.4 and
   the README corrected, and the retry-later's message names an incident behind the waiting element
   as the other reading. `ZenBpmRestClientContractIT#aMessageWhichWasReceivedIsA404WhereTheWorkflowFailsBehindIt`
   (fixture `contract-received.bpmn`) holds the engine's answer, so an engine which answers otherwise
   is noticed when the pin moves.
2. **A name an older version waits for and the newest version starts with passed** (Copilot 1,
   Major). Valid: only the newest version holds start subscriptions (`ImportProcessDefinition` of
   `pkg/bpmn/definition_import.go` removes those of the version before), and
   `publishCorrelatedMessageByName` falls back to them ignoring the key, so such a correlation made
   before the workflow waits starts a second workflow, which Copilot reproduced. `ModuleMessages#starting`
   now holds only the newest version's start messages, and a name in it is refused also where a
   model waits for it, naming the clash. Tests: `ZenBpmMessageDeclarationTest#aMessageTheNewestVersionStartsWithIsRefusedAlthoughAModelWaitsForIt`,
   `ZenBpmDeploymentServiceIT#aMessageTheNewestVersionStartsWithIsRefused` (against the engine),
   `ZenBpmDeployingTest#theMessagesOfEveryHeldVersionAreRemembered` (an older version's start no
   longer counts).
3. **The picture read at boot refused a name a version deployed later by another node waits for**
   (Copilot 3, Major). Valid by the counterpart's code: `Camunda8ProcessService#validateMessageIsDeclared`
   reads the cluster's models again before it refuses ("A refusal may only rest on models read
   now"). Phase one now does the same through `ZenBpmDeployedProcesses#messagesReadAgain`
   (`ZenBpmHeldMessages#again`: every version the engine holds now, the newest one's start
   messages), remembers what it read, and says nothing where the reading is incomplete. A name which
   passes reads nothing. Decision 42 says so (it is not merged yet, so it was corrected in place).
   Tests: `ZenBpmMessageDeclarationTest#aRefusalRestsOnTheModelsReadAgain`, the new silent case of
   `#theCheckSaysNothingWhereAModelIsUnknown`, `ZenBpmDeployingTest#readAgainEveryVersionTheEngineHoldsNowIsRead`,
   `#versionsWhichCannotBeReadAgainAreNamed`, `ZenBpmDeployedProcessesTest`, and against the engine
   `ZenBpmDeploymentServiceIT#aVersionAnotherNodeDeployedLaterIsReadBeforeARefusal`. What stays: a
   name which passes on the kept picture is not read again, so a newer version deployed by another
   node which starts with an awaited name is seen only at the next refusal or start.
4. **"A correlation without one is never deduplicated" had no test** (Claude 2, Minor). Valid.
   `ZenBpmMessageIT#aCorrelationWithoutAnIdIsNeverDeduplicated` plans two entries while the first
   waits.
5. **Every start reads every held version** (Claude 3, Minor). Valid as a cost, not a defect:
   decision 42 now says that the requests grow with the versions of each process.
6. **A javadoc line of 128 characters** in `ZenBpmProcessService` (Claude 4, Nit). Reflowed.

Seen to fail, each restored afterwards (10 of 10 mutants of the fixes, against green unmutated
runs, a kill counted only where a test failed): a name the newest version starts with passing where
a model waits for it (unit, and against the engine: `ZenBpmDeploymentServiceIT`); a refusal on the
picture kept from the start, without reading again (unit, and against the engine); a reading again
where the kept picture passes; an incomplete reading again which refuses; the clash of start and
wait worded as a start-only message; every version's start messages counted when read again; an
older version's start messages counted while deploying; a reading again which is not remembered.

`TZ=Etc/UTC mvn clean install` on OpenJDK 25.0.2 after the fixes builds green with every
integration test run and none skipped (engine test support 12 unit and 5 integration tests, core 976
unit and 82 integration tests, Spring Boot 11 unit and 43 integration tests, smoke test 37, Quarkus
runtime 10, Quarkus deployment 51, Quarkus integration tests 10, coverage gate 37). The gate
measures 100 % of 25,730 instructions for Spring Boot and 100 % of 25,604 for Quarkus, and
`bin/check-deploy-safety.sh` passes.

**S7.1.1 done 2026-10-18**, merged to `main` as `cf922f5` (pull request #53, after two reviews,
whose six findings were all valid and fixed in `2a9c6ac`, and CodeRabbit's review, which left no
inline comment and accepted the two limits its summary named, decision 43 and the picture kept
where a name passes). F7.1 goes on with S7.1.2 after S9.1.1 and S9.3.1. On `cf922f5` (GitHub keeps
run logs for 90 days, so what they showed is written here as well):

- `Checks`, run
  [39145448732](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/actions/runs/39145448732):
  green on both JDKs. `bin/check-engine-copies.sh` (in the JDK 25 job) reported both copies as the
  engine's files of `bdd795a3...`. On both JDKs `ZenBpmMessageIT` ran 7 tests,
  `ZenBpmMessageDeclarationTest` 10, `ZenBpmProcessServiceTest` 17, `ZenBpmDeployingTest` 10,
  `ZenBpmDeployedProcessesTest` 4, `ZenBpmDeploymentServiceIT` 10 and `ZenBpmRestClientContractIT`
  19, none skipped (engine test support 12 unit and 5 integration tests, core 976 unit and 82
  integration tests, Spring Boot 11 unit and 43 integration tests, smoke test 37, Quarkus runtime
  10, Quarkus deployment 51, Quarkus integration tests 10, coverage gate 37). The gate measured
  100 % on both platforms: of 25,730 instructions for Spring Boot and 25,604 for Quarkus on JDK 25,
  of 25,720 and 25,594 on JDK 21.
- `Publish snapshots`, run
  [39145448713](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/actions/runs/39145448713):
  green. The core snapshot it published to GitHub Packages, `2.0.0-20261018.163230-49`, holds the
  story's new main classes (`ZenBpmMessageCorrelation`, `ZenBpmHeldMessages`,
  `ZenBpmDeployedProcesses$ModuleMessages` and `$KeptMessages`) and no test class, fake, fixture or
  BPMN file; `quarkus/integration-tests`, the smoke test, the engine test support and the coverage
  modules skipped deployment. Both report pages (`spring-boot-report/index.html`,
  `quarkus-report/index.html`) answer HTTP 200 and show 0 of 25,730 (Spring Boot) and 0 of 25,604
  (Quarkus) instructions missed.

### S7.1.2 `START_WORKFLOW_BY_MESSAGE` and `message-start-lookup` (engine-gated)

- [ ] **Depends on:** S7.1.1, S9.1.1 (the start marker) and S9.3.1 (its handler, which reports the
  instance key). **Engine twin:** E13.4.

A workflow started by a message carries no business key: `POST /v1/messages` takes none, and it
answers "Message sent" without the key of an instance it started (GAPS 1). So phase two cannot
report the started workflow, as on Camunda 8, which reports nothing there either. The start marker
of decision 26 does it instead: it runs after the message start event, and its report writes the
instance key into the start row (`BpmsInitiatedStartContext.getNativeInstanceId()`). From then on
the core hands that key to every election about the workflow (decision 25).

**Instructions**

1. Phase one: the core already refused a message which starts no process of this process service
   (`reportStartMessages` of S4.3.1, platform decision 109). The adapter checks what the core
   cannot: the message is in `startMessages` of a model of the module this application deployed.
   Where `message-start-lookup` is `refuse`, fail with a guiding message naming GAPS 1 and the other
   two values.
2. Phase two: `rest.publishMessage(scopedName, null, shared values + id variable)`; no
   `reportStartedWorkflow`; 404 -> permanent (no start event deployed: the message names
   `deployment-failure`).
3. `message-start-lookup` has three values. The merged
   `ZenBpmAdapterConfiguration.MessageStartLookup` (`REFUSE`, `SCAN`, default `REFUSE`) gains
   `START_ROW` and makes it the default:
   - `start-row` (default): the start is allowed; the workflow is found through the start row the
     marker wrote, and nothing else.
   - `scan`: as `start-row`, and in addition `awarenessOfWorkflow` and the redispatch probe gain a
     third step for processes of the scope which have a message start event: page `GET
     /process-instances?bpmnProcessId=&state=active` (size 200) and compare `variables[idName]`;
     bounded by `message-start-lookup-max-instances` (default 1000, then `BPMS_UNAVAILABLE` with a
     WARN naming the bound). A completed workflow of this kind answers `UNKNOWN_TO_BPMS`, which the
     contract does not allow (`ADAPTER-AUTHORS.md` 4.1); that is why `scan` is an opt-in and goes
     away with E13.4.
   - `refuse`: no start by message at all.
4. What the start row cannot cover, named in GAPS 1 and the wiki:
   - an application without a delivery log writes no start row;
   - between publishing and the commit of the marker's report only the dispatching node's election
     cache knows the workflow, so a correlation or a push dispatched on another node, or after a
     restart, in that window finds nothing;
   - the redispatch probe (`awarenessOfWorkflowForRedispatch`) gets no key, and a business-key
     search never finds a message-started workflow: a start re-dispatched after a lost answer
     publishes the message again and may start a second instance. `scan` closes this for an active
     workflow.

   `vanillabp.delivery.workflow-start-retention` (default 30 days, `0` keeps rows for good) and
   `vanillabp.delivery.keep-workflow-start-while-aggregate-exists` decide how long the start row
   lives; the wiki names both.
5. Every mode logs once per module at startup which processes are message-started and what applies.

*Note from S9.3.1, 2026-10-18.* The start marker is built: a message start event gets one like
every start event of a process which starts a workflow of its own (decisions 26 and 44). Its job
hands the core the job's instance key as `nativeInstanceId`, and the core writes it into the start
row of the aggregate the instance names, or of the one the application's method built; the adapter
writes the id into the instance only where the instance does not carry it yet, read from the
instance and not from the job, whose variables are those of its creation (decision 46).
Where this story's message carries the id variable, as the start by request does, the marker of
such a start writes nothing, and the start row holds the instance key all the same.

**Tests**: `ZenBpmProcessServiceTest` for the three modes; Spring
`ZenBpmMessageIT#aMessageStartedWorkflowIsFoundThroughItsStartRow` (start by message, then correlate
a message to the same aggregate: it reaches the workflow),
`#startWorkflowByMessageIsRefusedWithRefuse`, `#scanFindsTheWorkflowOfARedispatchedStart` (with
`scan`: a re-dispatched start publishes nothing twice while the first instance is active).

**Acceptance criteria**

- [ ] By default, a message-started workflow is found after its start marker ran.
- [ ] `refuse` refuses with a message naming the setting and the gap; `scan` works within the bound.
- [ ] E13.4's twin replaces the scan by the business-key search, and `scan` is removed then.

## F7.2 The pushed aggregate

### S7.2.1 `AGGREGATE_CHANGED`, global and task-scoped

- [ ] **Depends on:** S7.1.1.

**Instructions**

1. Global (no task id): phase one nothing (the probe already located the workflow); phase two:
   `PhaseTwoRequest.workflowId()` first - the instance key of the start row, where that row names
   this adapter (decision 25) - then the business-key search where it is null or the instance is not
   `active`; load the shared values, `rest.patchVariables(key, values)`. UNKNOWN -> a warned no-op
   (the core consumes the entry). Whether variables written to the root instance are seen by a
   gateway inside a child instance (a sub-process, a multi-instance body) is not verified: the IT
   proves it, or this story pushes into the child instances as Camunda 8 pushes into called ones.
2. Task-scoped: `rest.job(taskId)` -> its `processInstanceKey` IS the scope the task runs in (a
   sub-process or multi-instance iteration is a child instance, decision 11's cache tells the
   parent); `patchVariables(thatKey, values)`; NOT the root instance additionally.
   `PhaseTwoRequest.taskRecord()` is the task's own delivery row: where it says a handler holds the
   task right now, skip the push (the handler's outcome writes the values anyway); the job read
   stays, because only the job names the child instance.
3. The engine evaluates conditions on its own variables, so a gateway behind the pushed values reads
   them; conditional EVENTS do not exist (no marker variable needed).

**Tests**: fake-server cases; Spring `ZenBpmAggregateChangedIT`: `aGlobalPushReachesTheGateway` (a
workflow waiting at a timer, then an exclusive gateway on a pushed boolean),
`aGlobalPushReachesAGatewayInsideASubProcess`, `aTaskScopedPushWritesTheChildInstance` (a task
inside a sub-process; the sub-process' child instance carries the value, the root does not).

**Acceptance criteria**

- [ ] A task-scoped push leaves the root instance's variables untouched.

## F7.3 Quarkus twins

### S7.3.1 Messages and push on Quarkus

- [ ] **Depends on:** S7.2.1.

`ZenBpmWorkflowLifecycleTest` gains `correlateMessageResumesTheWorkflow`, `aGlobalPushReachesTheGateway`,
`aTaskScopedPushReachesTheChildScope`.

**Acceptance criteria**

- [ ] The Quarkus report covers the correlation and push handlers.

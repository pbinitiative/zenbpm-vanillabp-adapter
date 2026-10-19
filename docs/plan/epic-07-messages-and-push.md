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

- [x] **Depends on:** S7.1.1, S9.1.1 (the start marker) and S9.3.1 (its handler, which reports the
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
     away with E13.4. (The reviews of the batch found that `start-row` answers the same for a
     completed workflow, because the start row's key finds a running instance only; see the answers
     to the two reviews below.)
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

- [x] By default, a message-started workflow is found after its start marker ran.
  `ZenBpmMessageIT#aMessageStartedWorkflowIsFoundThroughItsStartRow`, against the pinned engine
  under the default `start-row`: the start row the marker wrote holds the key of the instance, which
  carries no business key, the marker sent no update of the instance's variables (the proxy in
  front of the engine saw no `PATCH`), and a correlation reaches the workflow and ends it; the
  start of the application named `Postcards` and the value in force. At the SPI:
  `ZenBpmProcessServiceTest#underStartRowAMessageStartsTheWorkflowAndNoProbeReadsTheInstances`.
- [x] `refuse` refuses with a message naming the setting and the gap; `scan` works within the bound.
  `ZenBpmMessageStartRefusedIT#startWorkflowByMessageIsRefusedWithRefuse` (the refusal names
  `vanillabp.adapters.zenbpm.message-start-lookup`, the gap and the other two values, the
  transaction rolls back and nothing is planned) and
  `ZenBpmProcessServiceTest#underRefuseNoWorkflowIsStartedByAMessage`;
  `ZenBpmMessageStartScanIT#scanFindsTheWorkflowOfARedispatchedStart` (a start sent again after its
  answer was lost, before any start row, publishes once and is skipped as `ACTIVE`),
  `ZenBpmWorkflowProbesTest#aLookupStopsAtTheBound` and `ZenBpmProcessServiceIT#aStartByAMessageStartsAWorkflowTheScanFinds`.
- [ ] E13.4's twin replaces the scan by the business-key search, and `scan` is removed then.
  *Left open:* E13 is out of the pipeline's scope; whoever lands E13.4 ticks it (note there).

*Fit check, 2026-10-19, decided by the Implementor.* Checked against `main` (`c809df4`),
`ADAPTER-AUTHORS.md` (3.3 and 4.1), the platform as the adapter resolves it
(`2.0.0-20261015.045823-21`, read at the platform's commit `f9505797` it was built from:
`MigrationProcessService#startWorkflowByMessage`, `#startWorkflowPhaseTwo`,
`#skipRedispatchedStart`, `#writtenForThisEntry`, `DeliveryRecords#recordWorkflowStart`,
`DispatchLanes`, `PhaseTwoOutboxProperties`; signatures checked in the resolved jars), Camunda 8's
`Camunda8ProcessService#preflightStartByMessage` and `#startWorkflowByMessage`, and the engine at
the pin `bdd795a3` (source exported with `git archive`: `ZenNode.PublishMessage`,
`publishDefinitionMessageByName` and `publishMessageBySubscriptionKey` of
`internal/cluster/node.go`, `Server.PublishMessage` of `internal/rest/server.go` and of
`internal/cluster/server/server.go`, `PublishMessageByKey` of `pkg/bpmn/events_api.go`,
`handleStartEventInstanceCreation`, `publishMessageOnInstanceCreation` and
`renewStartEventTrigger` of `pkg/bpmn/start_event_instance_creation_handler.go`,
`createMessageStartEventMessageSubscriptions` of `pkg/bpmn/engine.go`,
`SaveMessageSubscription` of `internal/cluster/partition/partition_persistence.go` and the pointer
upsert of `internal/sql/message_subscription_pointer.sql.go`; probed with `curl` against the pinned
image, then held by the ITs below). What did not fit, and what was decided:

1. **The `404` of a start message** (brief question 1, instruction 2). The plan has it permanent.
   The probes show three causes: no start subscription of the name; a workflow the engine created
   and failed right behind the start event (the twin of GAPS 29; the instance exists, `failed`);
   and a start of the same name at the same moment (item 2). A permanent answer would block a start
   a repetition makes; a retry-later would wait out a missing deployment for hours. Decided an
   ordinary, counted failure with the engine's answer as its cause, recorded as decision 47 (new,
   because it differs from the plan). The second cause cannot happen to a process the adapter wired,
   because its start marker stands right behind the start event and the engine stops at the
   marker's job: measured, a start event followed by a service task is answered `201`, one followed
   by a call activity of a process nobody deployed `404`
   (`ZenBpmRestClientContractIT#aStartMessageIsA404AlsoWhereItsInstanceFailsBehindTheStart`).
2. **The engine loses starts of one name at the same moment** (found by the probe, beyond the
   story). 40 start messages of one name sent at once: 26 answered `201`, 14 `404`, 5 workflows;
   sent one after the other, 40 of 40. The engine consumes the start subscription and renews it
   under a lock of the old key, and a publication which waited for that lock returns without error.
   Options: nothing (lose starts silently); a lock per name in the adapter (closes it on one node);
   asking the engine for a new instance after each publication (impossible without a key, GAPS 1).
   Decided the lock, held for the publication only, one per scoped name, shared by the adapter ids
   of the application (decision 47); the outbox dispatches different aggregates on parallel lanes
   (`DispatchLanes`), so one node needs it. GAPS 30 entered for what remains across nodes, with the
   contract test `ZenBpmRestClientContractIT#startMessagesOfOneNamePublishedAtOnceLoseStarts`
   (rounds of 40 until a lost `201` and a `404` were seen, at most five), and
   `ZenBpmProcessServiceIT#startsByOneMessageAtOnceEachStartTheirWorkflow` holds the lock against
   the engine. A note under E13.4 names the engine change which would close it.
3. **Which versions phase one judges by** (brief question 2). Decision 42's rule, unchanged: only
   the start messages of the newest version the engine holds count, read again before a refusal;
   silent where the picture is incomplete. A name a model waits for is refused naming
   `correlateMessage`. No new decision. `ZenBpmKnownMessages` holds what the correlation and the
   start share (the complete-picture rule, the name as written), moved out of
   `ZenBpmMessageCorrelation`, so there is one copy.
4. **What the scan reads** (instruction 3). The engine's largest page is 100 per partition, not
   200 (`InstanceFilter.MAXIMUM_SIZE`). `state=active` leaves out a workflow with an incident, which
   the probes count as running, so the election's lookup reads `active` and then `failed`. The
   probe before a start is sent again reads every state from `createdFrom = plannedAt`, as its
   business-key search does (a workflow which ended quickly is found too), and only what runs where
   nobody knows that moment. The processes read are those of the scope of which a version the
   engine holds has a message start event, as this start's deployment read them
   (`ModuleMessages#startedByMessage`, new; corrected after the reviews, which found that a
   newest-only rule lost the running workflows of an older version); a module which deployed
   nothing in this start is not read. The bound counts every instance
   read, processes and pages together; a workflow found within it is found. The WARN of a lookup
   which reached it is logged once until a lookup stays within the bound again.
5. **When the core asks the redispatch probe.** In the resolved core,
   `#startWorkflowPhaseTwo` asks `#skipRedispatchedStart` only for an entry dispatched before: the
   start row written for this entry first, then `awarenessOfWorkflowForRedispatch`. Phase two of a
   start by message reports no id, and `DeliveryRecords#recordWorkflowStart` writes nothing for a
   `null` id, so the marker writes the row. `ZenBpmMessageStartScanIT` keeps the marker from writing
   it (the proxy refuses every read of one instance) and loses the answer of the first publication,
   so the redispatch probe alone decides.
6. **The S9.3.1 note under this story** was proved and stays as it is: the message carries the id
   variable, the marker's job sees it (its variables are the message's, `#aStartMessageIsAnsweredWithoutTheInstanceItStarted`),
   the marker writes nothing, and the start row holds the instance key
   (`ZenBpmMessageIT#aMessageStartedWorkflowIsFoundThroughItsStartRow`).
7. **GAPS 4 and 23 seen from the start.** A start message is published without a key, which the
   engine looks up under the start subscriptions only. A wait whose key evaluates to an empty text
   fails at the wait instead of subscribing (probed), and the engine refuses to deploy a second
   process whose start event takes a name another process starts with (probed), so no waiting
   workflow takes a start message. Both entries are extended; GAPS 5 and 29 as well.
8. **Shared values.** Camunda 8 publishes the id variable alone; the story asks for the shared
   values and the id, as the start by request sends them. Built as the story says.
9. **Instruction 5** (brief question 5). One INFO per workflow module with processes a message
   starts, naming them, the key and the value, and under `scan` a WARN naming the bound and that a
   workflow which ended stays unknown to the election's probe; asserted in
   `ZenBpmDeployingTest#theProcessesAMessageStartsAreNamedOnce` (each value) and on Spring Boot in
   the three ITs. The refusal names the gap in words and `GAPS.md`, not by number, because code
   cites decisions only.
10. **The Quarkus side** (brief question 3). Both keys bind and are validated on Quarkus
   (`VanillaBpZenBpmPropertiesTest#eachValueOfTheMessageStartLookupBindsAsWritten`,
   `ZenBpmMessageStartKeysTest`) as on Spring Boot (`ZenBpmStartupValidationBootTest#theBoundOfTheLookupOfAMessageStartIsValidated`);
   the Quarkus twin of the start-row IT is left to S7.3.1 (note there).
11. **Test classes.** Each value of `message-start-lookup` is a Spring context of its own, so the
   story's `#startWorkflowByMessageIsRefusedWithRefuse` and `#scanFindsTheWorkflowOfARedispatchedStart`
   live in `ZenBpmMessageStartRefusedIT` and `ZenBpmMessageStartScanIT`, on the same application
   `it/message`, which gained `Postcard`, `PostcardRepository`, `PostcardWorkflow` and
   `message-it/postcards.bpmn`. `RefusingEngineProxy` can lose an answer now and records every
   request; `ZenBpmMessageIT` reaches the engine through it.
12. **The changed default.** `MessageStartLookup` gained `START_ROW`, the new default, so
   `ZenBpmAdapterConfigurationTest#aRestAddressAloneIsComplete` expects `START_ROW` where it expected `REFUSE`;
   no assertion was weakened.

**Implemented 2026-10-19.** Core: `ZenBpmMessageStart` (both phases of `START_WORKFLOW_BY_MESSAGE`,
registered by `ZenBpmProcessService` in place of the handler which was not built yet),
`ZenBpmMessageStartScan` (the lookup of `scan`, the third step of both workflow probes),
`ZenBpmKnownMessages` (what correlation and start share, moved out of `ZenBpmMessageCorrelation`),
`ZenBpmDeployedProcesses.ModuleMessages#startedByMessage` (filled by `ZenBpmHeldMessages`), the
startup report per workflow module in `ZenBpmDeploymentService#deployResources`, and
`ZenBpmAdapterConfiguration`: `MessageStartLookup.START_ROW`, the new default, and
`message-start-lookup-max-instances` (`1000`, at least `1`), bound by `ZenBpmAdapterKeys` (Spring
Boot) and `VanillaBpZenBpmProperties` (Quarkus). Spring Boot tests: `ZenBpmMessageIT` gained the
start-row case and reaches the engine through `RefusingEngineProxy`, which can lose an answer and
records every request now; `ZenBpmMessageStartRefusedIT` and `ZenBpmMessageStartScanIT` run the same
application `it/message`, which gained `Postcard`, `PostcardRepository`, `PostcardWorkflow` and
`message-it/postcards.bpmn` (with its diagram, decision 29). Decision 47; GAPS 30 new; GAPS 1, 4, 5,
23 and 29 extended; the README, architecture 4.2, 4.4 and the configuration table, the deviations
rows 1 and 17, the mapping row 48, open question 12, and notes under S5.2.2, S7.3.1, E12 and
E13.4.

New tests: `ZenBpmProcessServiceTest` (6: one per value of `message-start-lookup`,
`#aStartMessageNoStartEventTakesIsAnOrdinaryFailure`, `#everyOtherRefusalOfAStartMessageIsThrownAsItIs`,
`#startMessagesOfOneNameArePublishedOneAfterTheOther`); `ZenBpmStartMessageDeclarationTest` (8);
`ZenBpmWorkflowProbesTest` (4: `#underScanAWorkflowAMessageStartedIsFoundByItsIdVariable`,
`#theScanReadsOnlyWhatTheSearchLeavesOpen`, `#aLookupStopsAtTheBound`,
`#theRedispatchLookupCountsFromThePlannedMoment`); `ZenBpmDeployingTest` (5:
`#theProcessesAMessageStartsAreNamedOnce` for each value, `#aModuleNoMessageStartsSaysNothingAboutTheLookup`,
`#readAgainAProcessIsStartedByAMessageWhereAnOlderVersionIs`, renamed after the reviews);
`ZenBpmAdapterConfigurationTest`
(the bound's defect); against the pinned engine `ZenBpmRestClientContractIT` (3:
`#aStartMessageIsAnsweredWithoutTheInstanceItStarted`,
`#aStartMessageIsA404AlsoWhereItsInstanceFailsBehindTheStart`,
`#startMessagesOfOneNamePublishedAtOnceLoseStarts`, fixtures `contract-started.bpmn` and
`contract-start-fails.bpmn`) and `ZenBpmProcessServiceIT` (2:
`#aStartByAMessageStartsAWorkflowTheScanFinds`, `#startsByOneMessageAtOnceEachStartTheirWorkflow`);
Spring Boot `ZenBpmMessageIT#aMessageStartedWorkflowIsFoundThroughItsStartRow`,
`ZenBpmMessageStartRefusedIT#startWorkflowByMessageIsRefusedWithRefuse`,
`ZenBpmMessageStartScanIT#scanFindsTheWorkflowOfARedispatchedStart`,
`VanillaBpZenBpmPropertiesTest#eachValueOfTheMessageStartLookupBindsAsWritten` (3) and
`ZenBpmStartupValidationBootTest#theBoundOfTheLookupOfAMessageStartIsValidated`; Quarkus
`VanillaBpZenBpmPropertiesTest#eachValueOfTheMessageStartLookupBindsAsWritten` (3) and
`ZenBpmMessageStartKeysTest`.

Seen to fail, each restored afterwards (16 of 16 mutants, against green unmutated runs, a kill
counted only where a test failed): the lock per name switched off (unit, and against the engine:
`ZenBpmProcessServiceIT#startsByOneMessageAtOnceEachStartTheirWorkflow` got the engine's `404`);
the scan switched off in the redispatch probe (unit, and against the engine:
`ZenBpmMessageStartScanIT`); `refuse` not refused (unit, and against the engine:
`ZenBpmMessageStartRefusedIT`); the start row's key ignored by the probe (against the engine:
`ZenBpmMessageIT#aMessageStartedWorkflowIsFoundThroughItsStartRow`); every name passing phase one;
a refusal without reading again; a `404` thrown as the engine answered it; the bound never reached;
incidents not read; the lookup under every value; every process of the scope read; the bound's
WARN never reset; no WARN under `scan`; an older version's message start counted when read again;
a bound of `0` accepted; the redispatch lookup reading only what runs.

`TZ=Etc/UTC mvn clean install` on OpenJDK 25.0.2 builds green with every integration test run and
none skipped (engine test support 12 unit and 5 integration tests, core 1,035 unit and 89
integration tests, Spring Boot 14 unit and 53 integration tests, smoke test 38, Quarkus runtime 13,
Quarkus deployment 52, Quarkus integration tests 10, coverage gate 37). The gate measures 100 % of
28,397 instructions for Spring Boot and 100 % of 28,267 for Quarkus, and `bin/check-deploy-safety.sh`
passes.

**Answers to two reviews of S7.1.2, 2026-10-19.** Claude (approve after two documentation fixes: 2
Minor, 1 Nit) and Copilot (changes requested: 2 Major) reviewed `f5e2786` in their own worktrees;
both built it green with 100 % coverage on both platforms, and Copilot probed the pinned image
through the adapter's own deployment. Every finding was checked against the engine at the pin, the
resolved platform and the code. Five distinct findings, all valid:

1. **A message start event which maps outputs dropped the aggregate id** (Copilot 1, Major).
   Valid: the engine applies the output mappings of a message start event to the message before it
   creates the instance, and where any exist, only the mapped variables reach it
   (`mapStartEventTriggerVariables` of `pkg/bpmn/start_event_instance_creation_handler.go`,
   `PropagateMappedOutputsOrAll` of `pkg/bpmn/runtime/varholder.go`). The marker then saw a start
   nobody named and ended in an incident, and the aggregate got no start row. Fixed: while wiring,
   every message start event of a process whose start is heard which maps outputs, but none to the
   id variable, gets an output of it behind the modeller's (`ZenBpmMessageStarts`, decision 48).
   `ZenBpmRestClientContractIT#aMessageStartWhichMapsOutputsHandsOnTheMappedVariablesOnly` (fixture
   `contract-start-mapped.bpmn`) holds the two engine facts the fix relies on: an unmapped variable
   is dropped, and a source the message does not carry maps to nothing and starts the instance all
   the same, so a message sent past VanillaBP still starts. `message-it/postcards.bpmn` now maps
   `status` on its start event, so `ZenBpmMessageIT#aMessageStartedWorkflowIsFoundThroughItsStartRow`
   is the end-to-end regression; `ZenBpmMessageStartsTest` (3) covers the rewrite.
2. **The scan forgot the running workflows of an older version** (Copilot 2, Major). Valid:
   `ZenBpmHeldMessages` filled `startedByMessage` from the newest version only, so a newer version
   without a message start stopped the lookup from reading the process at all, which Copilot
   reproduced (`ACTIVE` before, `UNKNOWN_TO_BPMS` after, the old instance still running). Which
   version takes a NEW start message is a newest-only question (decision 42); which processes may
   HOLD a workflow a message started is not. Fixed: every held version counts for
   `startedByMessage`, at boot and when read again; the startup INFO names the processes whose model
   this start deployed has a message start event, so it does not claim that a message starts a
   process whose newest version dropped the event. Item 4 of the fit check corrected in place.
   Tests: `ZenBpmDeployingTest#theMessagesOfEveryHeldVersionAreRemembered`,
   `#readAgainAProcessIsStartedByAMessageWhereAnOlderVersionIs` (renamed; it had pinned the defect),
   `#aModuleNoMessageStartsSaysNothingAboutTheLookup` (an older version with a message start, no
   INFO) and `#theProcessesAMessageStartsAreNamedOnce` (it deploys a model with a message start
   now). The Implementor's mutant "an older version's message start counted when read again" is
   the intended behaviour now; its inverse is killed below.
3. **An ended message-started workflow is unknown under `start-row` as well** (Claude 1, Minor).
   Valid: `ZenBpmProcessService#theEngineRunsTheWorkflowOfTheKey` keeps a running instance only,
   and the business-key search never finds a workflow without a key; the merged
   `ZenBpmWorkflowProbesTest#whateverElseTheKeyLeadsToFallsThroughToTheSearch` pins it. So the limit
   belongs to every mode which starts by a message, not to `scan`. The README, GAPS 1, the INFO of
   `start-row` and `scan`, the WARN of `scan` and instruction 3 say so now. The key is not made to
   answer `COMPLETED`: that would supersede decision 25 for one case, and E13.4 is the cure.
4. **The scan's offset paging can skip a running workflow while others end** (Claude 2, Minor).
   Valid from the engine's source (`FindProcessInstancesPage` filters by state, then pages by
   offset); not reproduced. Named as a limit in the README, GAPS 1 and architecture 4.2: a miss
   answers what `start-row` answers. Keyset paging without a state filter would read the ended
   instances against the bound, so it is not built.
5. **The README tied phase one's check to `start-row`** (Claude 3, Nit). Reworded: "Unless `refuse`
   is set".

Seen to fail, each restored afterwards (7 of 7 mutants of the fixes, against green unmutated runs):
the deployment's call of the rewrite switched off (against the engine:
`ZenBpmMessageIT#aMessageStartedWorkflowIsFoundThroughItsStartRow`); an output the modeller maps to
the id ignored; an event without outputs given one; every start event rewritten instead of the
message starts; older versions not counted at boot; older versions not counted when read again;
the startup INFO for every deployed model.

`TZ=Etc/UTC mvn clean install` on OpenJDK 25.0.2 after the fixes builds green with every
integration test run and none skipped (engine test support 12 unit and 5 integration tests, core
1,038 unit and 90 integration tests, Spring Boot 14 unit and 53 integration tests, smoke test 38,
Quarkus runtime 13, Quarkus deployment 52, Quarkus integration tests 10, coverage gate 37). The gate
measures 100 % of 28,494 instructions for Spring Boot and 100 % of 28,364 for Quarkus, and
`bin/check-deploy-safety.sh` passes.

**S7.1.2 done 2026-10-19**, merged to `main` as `46a16c8` (pull request #57, after two reviews,
whose five findings were all valid and fixed in `8807d7b`, and CodeRabbit's review, which left no
inline comment and withdrew the two limits its summary retained, GAPS 30 and GAPS 1, once they were
shown recorded; the maintainer merged it, because the pipeline's merge was refused by its safety
check). F7.1 is complete; E7 goes on with S7.2.1. On `46a16c8` (GitHub keeps run logs for 90 days,
so what they showed is written here as well):

- `Checks`, run
  [39208629713](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/actions/runs/39208629713):
  green on both JDKs. `bin/check-engine-copies.sh` (in the JDK 25 job) reported both copies as the
  engine's files of `bdd795a3...`. On both JDKs `ZenBpmMessageIT` ran 8 tests,
  `ZenBpmMessageStartRefusedIT` 1, `ZenBpmMessageStartScanIT` 1, `ZenBpmMessageStartsTest` 3,
  `ZenBpmStartMessageDeclarationTest` 8, `ZenBpmProcessServiceTest` 23, `ZenBpmProcessServiceIT` 4,
  `ZenBpmWorkflowProbesTest` 33, `ZenBpmDeployingTest` 15, `ZenBpmRestClientContractIT` 23 and
  `ZenBpmMessageStartKeysTest` 1, none skipped (engine test support 12 unit and 5 integration tests,
  core 1,038 unit and 90 integration tests, Spring Boot 14 unit and 53 integration tests, smoke
  test 38, Quarkus runtime 13, Quarkus deployment 52, Quarkus integration tests 10, coverage gate
  37). The gate measured 100 % on both platforms: of 28,494 instructions for Spring Boot and 28,364
  for Quarkus on JDK 25, of 28,484 and 28,354 on JDK 21.
- `Publish snapshots`, run
  [39208629665](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/actions/runs/39208629665):
  green. The core snapshot it published to GitHub Packages, `2.0.0-20261019.061313-53`, holds the
  story's new main classes (`ZenBpmMessageStart`, `ZenBpmMessageStartScan` and `$BoundReached`,
  `ZenBpmKnownMessages`, `ZenBpmMessageStarts`) and no test class, fake, fixture or BPMN file;
  `quarkus/integration-tests`, the smoke test, the engine test support and the coverage modules
  skipped deployment. Both report pages (`spring-boot-report/index.html`,
  `quarkus-report/index.html`) answer HTTP 200 and show 0 of 28,494 (Spring Boot) and 0 of 28,364
  (Quarkus) instructions missed.

## F7.2 The pushed aggregate

### S7.2.1 `AGGREGATE_CHANGED`, global and task-scoped

- [x] **Depends on:** S7.1.1.

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

- [x] A task-scoped push leaves the root instance's variables untouched.
  `ZenBpmAggregateChangedIT#aTaskScopedPushWritesTheChildInstance`, against the pinned engine: the
  task inside the sub-process `Inspection` is left open, the application approves the shipment and
  pushes into the task's scope; the sub-process's instance carries `approved = true`, the root still
  carries `false`, and the proxy in front of the engine saw no write of the root between the start
  and the completion; the completion then writes the values everywhere and the gateway inside the
  sub-process takes the approved branch. At the SPI:
  `ZenBpmAggregatePushTest#aTaskScopedPushWritesTheInstanceTheTaskWaitsInAndNothingElse` (one read
  of the job, one write of its instance, nothing else).

*Fit check, 2026-10-19, decided by the Implementor.* Checked against `main` (`c328ace`),
`ADAPTER-AUTHORS.md` (3.3 and 4.1), the platform as the adapter resolves it
(`2.0.0-20261015.045823-21`, read at the platform's commit `f9505797` it was built from:
`PhaseOperation.AGGREGATE_CHANGED`, `PhaseTwoRequest#workflowId`, `#taskRecord` and `#taskId`,
`TaskDelivery`, `TaskDeliveryLog#recordOfTask`, `MigrationProcessService#aggregateChanged`,
`#addressWorkflowPhaseTwo`, `#elect`, `#probe`, `#taskRowDeliveredBy`, `#workflowIdStartedBy`,
`DeliveryRecords#locate` and `#taskRowOf`; signatures checked in the resolved jars, and
`ProcessService#aggregateChanged(A)` and `(A, String)` in the resolved `spi-for-java`
`2.0.0-20261013.234025-8`), Camunda 8's `Camunda8ProcessService#pushChangedAggregate`,
`#pushedIntoTheInstanceOfTheStartRow`, `#pushIntoTheCalledInstances` and
`#theAdapterLeftTheTaskOpen`, and the engine at the pin `bdd795a3` (source exported with
`git archive`: `Server.UpdateProcessInstanceVariables` of `internal/rest/server.go`,
`ZenNode.UpdateProcessInstanceVariables` of `internal/cluster/node.go`, `Engine.ModifyInstance` of
`pkg/bpmn/engine_api.go`, `Engine.NewEngineBatch` of `pkg/bpmn/engine_batch.go`,
`handleMultiInstanceActivity`, `startParallelMultiInstance` and `startSequentialMultiInstance` of
`pkg/bpmn/multi_instance.go`, `createSubProcess` of `pkg/bpmn/sub_process.go`, `NewVariableHolder`
of `pkg/bpmn/runtime/varholder.go` and the loading of an instance in
`internal/cluster/partition/partition_persistence.go`; probed with `curl` against the pinned image,
then held by the tests below). What did not fit, and what was decided:

1. **The handler was not one of the `requiredOfEveryAdapter` loop.** `AGGREGATE_CHANGED` is not
   required of every adapter in the resolved platform, so `ZenBpmProcessService` had left it out
   and the core answered it as not supported (its javadoc said so). The handler is added after the
   loop, in a class of its own, `ZenBpmAggregatePush`, as `ZenBpmMessageCorrelation` and
   `ZenBpmMessageStart` were. `ZenBpmProcessServiceTest#everyRequiredOperationIsServed` pinned the
   push as absent; it now pins that the push is served and a signal is not.
2. **Does a push of the root reach a child instance?** (instruction 1, brief question 1, open
   question 1) Measured on the pin, first with probes and then by the IT written before the code:
   a value written into the root does not reach a gateway inside a running embedded sub-process,
   whose instance holds a copy taken when it started (decision 35); a write into the sub-process's
   instance does. An iteration of a multi-instance sub-process holds its item and what the element
   maps in, and once it waited it reads nothing else of its workflow, not even a value the root
   carried from the start: the engine saves an iteration with its own variables and reads an
   instance back without its parent's (GAPS 31, entered). A write into one parallel iteration is
   seen by that iteration alone and is not lost; a write into the body's instance reaches a
   sequential iteration which starts later only through an input mapping of the element. Decided
   the recommended answer, recorded as decision 49: write the root, then every instance below it
   which is `active` or `failed`, at any depth, each before the instances below it are read; do not
   enter a called instance which names another aggregate (decision 45). Against a push of the root
   alone (the mutant below), `#aGlobalPushReachesAGatewayInsideASubProcess` and
   `#aGlobalPushReachesTheGatewayOfEveryIteration` fail with the gateway on its held branch.
3. **What `PATCH /v1/process-instances/{key}/variables` answers** (brief). `204` for an instance
   which is `active` or `failed`; `409` for one which ended, a child which ended while its root
   runs included (the REST server checks the state first); `404` for a key the node asked does not
   hold; `502` for a key of a partition the engine does not have. The write takes the instance's
   lock (`NewEngineBatch`), so it is not lost against a job completion in the same instance. A `409`
   passes the instance over, with everything below it, as decision 34 reads it; a `404` is thrown,
   because a node which lags behind answers it, and the outbox repeats the push.
   `ZenBpmRestClientContractIT#anIterationWhichWaitedReadsOnlyItsOwnVariables` holds the `409` of a
   child and the iteration's variables.
4. **Which instance is the root** (instruction 1). The start row's key is used only through the
   probe's own check, now shared by the probe and the push (`ZenBpmRunningWorkflows`, moved out of
   `ZenBpmProcessService`): the instance runs, belongs to the process and names the aggregate;
   anything else falls through. The business-key search reads root instances only, because the
   engine hands a business key on to every instance it starts below the root, and reads `active`,
   then `failed`. Beyond the story, under `message-start-lookup: scan` the push also finds a workflow
   a message started by its id variable, as the probes do (`ZenBpmMessageStartScan#running`, the
   scan's loop shared with `#find`), so a push reaches every workflow the election found. The scope
   is the request's process, as on Camunda 8.
5. **A task-scoped push** (instruction 2, brief question 2). As recommended: the job's own
   `processInstanceKey` and nothing else; a task id which is no key of the engine, a job the engine
   does not know, and an instance which ended are each a warned no-op, and the root is not written
   instead. `ZenBpmAggregateChangedIT#aTaskScopedPushWritesTheChildInstance` holds the acceptance
   criterion through the proxy: the sub-process's instance carries the value, the root keeps
   `false`, and no write of the root was sent.
6. **"A handler holds the task"** (instruction 2, brief question 3). No field of the task's row says
   so. The row exists only for a task the adapter left open (`TaskDeliveryLog#recordOfTask`: the
   record of the delivery which reported `COMPLETION_PENDING`), `taskClosedAt` says whether the
   application closed it since, and a missing row says nothing (no delivery log, the retention, a
   row of another adapter, or a handler which runs now). Camunda 8 reads the row to decide whether
   it may ASK its engine about the task, because its question shortens the lock of a job a handler
   may hold; ZenBPM's `GET /v1/jobs/{key}` is a plain read. Decided not to skip: a skip on a missing
   row would lose the push of every application without a delivery log, and writing while a handler
   holds the job harms nothing, because the handler's completion writes the values of its own
   commit afterwards (decision 35) and the push writes the values of the moment it runs. The row is
   not read either, because it names the workflow (decision 28) and not the instance the task waits
   in. A job keeps the variables of its creation, so a handler which runs, or a task handed out
   again, does not see a push; `ZenBpmRestClientContractIT#aJobKeepsTheVariablesItWasCreatedWith`
   holds that engine fact, which the README states.
7. **UNKNOWN** (brief question 4). The core warns and consumes the entry where its own election of
   phase two finds the workflow ended or unknown. Between that election and the write, the push
   finds no running root: a WARN names the process, the module, the aggregate, the adapter id and
   that the entry is consumed, and nothing is retried.
8. **Instruction 3.** Conditional events are refused while deploying (GAPS 14); the exclusive
   gateways of the IT read the pushed value.
9. **Names and places.** `processservice/ZenBpmAggregatePush`, `processservice/ZenBpmRunningWorkflows`;
   the Spring IT application `it/push` (`PushItApplication`, `Shipment`, `ShipmentRepository`,
   `ShipmentWorkflow`), `push-it.yaml`, one process `Shipments` with a path per scenario in
   `push-it/shipments.bpmn` (with its diagram, decision 29). Beyond the story the IT has
   `#aGlobalPushReachesTheGatewayOfEveryIteration`, because the walk is only proved where a copy
   exists to reach.
10. **Documentation.** The README gained the push; architecture 4.5 and the mapping row 53 say what
   is built; the deviations draft gained item 17 for GAPS 31. Open question 1 keeps what was found
   and planned then, and a dated note says that the default `start-row` reaches a workflow a message
   started.

**Implemented 2026-10-19.** Core: `ZenBpmAggregatePush` (both phases of `AGGREGATE_CHANGED`,
registered by `ZenBpmProcessService` after the handlers every adapter must have),
`ZenBpmRunningWorkflows` (the key check of the probes, moved out of `ZenBpmProcessService`, and the
search for the root instance), `ZenBpmMessageStartScan#running` (the scan's loop shared with
`#find`). Spring Boot tests: `ZenBpmAggregateChangedIT` with the application `it/push`
(`PushItApplication`, `Shipment`, `ShipmentRepository`, `ShipmentWorkflow`), `push-it.yaml` and
`push-it/shipments.bpmn` (with its diagram, decision 29). Decision 49; GAPS 31 new; the README,
architecture 4.5, the mapping row 53, open question 1 and item 17 of the deviations draft.

New tests: `ZenBpmAggregatePushTest` (16: the walk below the root, the five ways the key leads
elsewhere, a root with an incident, nothing which runs, a root and an instance below which ended
meanwhile, a `404` repeated, the scan, no scan without it, the task-scoped write, the three gone
scopes, phase one); `ZenBpmProcessServiceTest` (the push served, refused without clients; three
cases changed, none weakened); against the pinned engine `ZenBpmRestClientContractIT` (2:
`#anIterationWhichWaitedReadsOnlyItsOwnVariables`, fixture `contract-iterations.bpmn`, and
`#aJobKeepsTheVariablesItWasCreatedWith`); Spring Boot `ZenBpmAggregateChangedIT` (4:
`#aGlobalPushReachesTheGateway`, `#aGlobalPushReachesAGatewayInsideASubProcess`,
`#aGlobalPushReachesTheGatewayOfEveryIteration`, `#aTaskScopedPushWritesTheChildInstance`).

Seen to fail, each restored afterwards (9 of 9 mutants, against green unmutated runs, a kill counted
only where a test failed): a push of the root alone (against the engine: the sub-process and the
iterations take the held branch); a task-scoped push which writes the root as well (against the
engine: the root carries `true`, and the proxy saw its write); a called instance of another
aggregate entered; ended instances written; a `409` thrown instead of passed over; the root
search taking an instance below the root; the root search not reading instances with an incident;
the scan switched off in the push; the children of an instance read without its write first.

`TZ=Etc/UTC mvn clean install` on OpenJDK 25.0.2 builds green with every integration test run and
none skipped (engine test support 12 unit and 5 integration tests, core 1,054 unit and 92
integration tests, Spring Boot 14 unit and 57 integration tests, smoke test 38, Quarkus runtime 13,
Quarkus deployment 52, Quarkus integration tests 10, coverage gate 37). The gate measures 100 % of
29,064 instructions for Spring Boot and 100 % of 28,934 for Quarkus, and `bin/check-deploy-safety.sh`
passes.

## F7.3 Quarkus twins

### S7.3.1 Messages and push on Quarkus

- [ ] **Depends on:** S7.2.1.

`ZenBpmWorkflowLifecycleTest` gains `correlateMessageResumesTheWorkflow`, `aGlobalPushReachesTheGateway`,
`aTaskScopedPushReachesTheChildScope`.

*Note from S7.1.2, 2026-10-19.* `ZenBpmWorkflowLifecycleTest` also gains
`startWorkflowByMessageIsFoundThroughItsStartRow`, the Quarkus twin of
`ZenBpmMessageIT#aMessageStartedWorkflowIsFoundThroughItsStartRow`: start a workflow by a message
under the default `message-start-lookup: start-row`, wait for its start row, and correlate a message
which reaches it. The keys `message-start-lookup` and `message-start-lookup-max-instances` are bound
and validated on Quarkus already (`VanillaBpZenBpmPropertiesTest`, `ZenBpmMessageStartKeysTest`).

**Acceptance criteria**

- [ ] The Quarkus report covers the correlation and push handlers.

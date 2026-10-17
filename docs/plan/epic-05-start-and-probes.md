# E5 - Workflow start and the election

**Goal.** `startWorkflow` creates an instance after the commit and never after a rollback; every probe
of `MigratableProcessService` answers honestly, scoped, and never advances anything; the instance cache
which every later inbound story reads exists.

**Done when.** The exemplary end-to-end start runs on both platforms against the container, and the
probes are pinned by unit tests with the fake server and by ITs.

Template: `Camunda8ProcessService` (handler map, probes, redispatch probe, visibility delay),
`Camunda8DeployedProcesses` for the ownership comparison, `Camunda8VariableFilters` is NOT needed (no
variable search). Contract: `MigratableProcessService` type javadoc, `ADAPTER-AUTHORS.md` section 4.

---

## F5.1 Starting a workflow

### S5.1.1 `START_WORKFLOW` handler and the shared values

- [x] **Depends on:** E4.

**Instructions**

1. `ZenBpmSharedValues.forCommand(module, plainProcessId, aggregateIdName, aggregateId, sharedValues)`:
   a `Map<String, Object>` of the shared values plus `aggregateIdName -> aggregateId as String`, the
   id last so it wins over a same-named attribute. Used by every command (decision 25).
2. `ZenBpmProcessService.phaseOperations()` gains `START_WORKFLOW`:
   - phase one: resolve the aggregate id (`aggregatePersistence.getAggregateId(aggregate)` as String)
     and assert `factory.isConfigured()`, else the unconfigured message; nothing else (a start has
     nothing to ask).
   - phase two: `workflowAggregateSync().syncedValues(loaded aggregate, FULL)` - load the aggregate
     through the persistence in phase two, the request carries the id only - then
     `rest.createInstance(scopedProcessId, businessKey = aggregateId, variables, historyTimeToLive
     resolved for module/workflow)`. Log the instance key at DEBUG. A `ZenBpmApiException` 400 (the
     process id is unknown to the engine: nothing deployed under that scoped id) is rethrown with a
     message naming module, process, scoped id and `deployment-failure`. Then
     `request.reportStartedWorkflow(String.valueOf(key), String.valueOf(version))` with the `key`
     and `version` the create answers (platform decisions 108 and 110): the core writes them into
     the start row, which later hands the key back to the probes (S5.2.1). The version is written in
     the same form `TaskInvocationContext.getProcessVersion()` reports (`ADAPTER-AUTHORS.md` 2.2).
     The instance a start creates is a root instance, so its key is the workflow id of decision 28.
3. `isPhaseTwoFailureRepeatable(t)` = `!ZenBpmErrors.permanentFailure(t)`.
4. `deliversTasksAtLeastOnce()` = `true`.
5. `getAdapterId()`, and the rest of the required handlers still throwing "later story" so the boot
   accepts the map.
6. `whatThisBpmsDoesWith(type, direction)` through `ZenBpmValueTypes` (platform decision 57): the
   engine keeps every number as a float64 (S2.2.1 measured `2^53+1` coming back as `2^53`, and
   `1000.0` coming back as `1000`). So per type and direction: `String`, `Boolean` and enums
   `survives()`; `Integer`, `Short` and `Byte` `survives()` (every value fits a float64 exactly);
   `Long`, `BigInteger` and `BigDecimal` `changed(...)` naming the precision lost above 2^53 or
   after the 17th digit; `Double` and `Float` `survives()` TO the engine and `changed(...)` FROM it
   where the value is whole (it comes back without its fraction, so as an integer type);
   `Date` and `Instant` travel as the instant; anything else `cannotSay(...)`. Platform decision 55
   applies on the way back: a number reaches a handler as the engine reported it, the adapter
   converts nothing. Check each answer against the engine in the contract IT before relying on it.

The engine takes `historyTimeToLive` as a Go-style text (`30d`, `1M5d8h`): `ParseTTL` in
`internal/cluster/types/ttl.go`, described in `openapi/api.yaml`. It keeps whole seconds, cut off
by `int64(historyTTL.Seconds())` in `internal/cluster/partition/partition_persistence.go` into the
column `history_ttl_sec` of `internal/sql/migrations/0001_schema.up.sql` (all at the pinned commit
`bdd795a3`). Convert the resolved `Duration` to that form, and decide at startup what a
value below one second and `PT0S` mean (the configuration accepts both, it refuses only negative
values), checking against the engine what an explicit `0` does.

*Found by S2.2.2's `ZenBpmErrorsIT`, 2026-10-14.* A start of a process the engine does not hold is
answered `404`, not `400` as instruction 2 says, so the rethrow has to look for a `404` of
`POST /v1/process-instances`. `ZenBpmErrors` reads that `404` as repeatable, as any `404` which is
not a job's or a message's, so the outbox repeats such a start until it is blocked. Camunda 8 makes
it permanent with `Camunda8RefusedStart`; whether this adapter does the same is this story's
decision, and a new row of the README's table "Which phase-two failures are repeated" and of
decision 10.

*Note from a review of S2.3.1, 2026-10-14.* The pinned engine refuses a start whose variables are
above 4 MiB, and a variable update which would bring an instance above 4 MiB, with `500
TECHNICAL_ERROR` ("grpc: received message larger than max (5242957 vs. 4194304)"). The node which
takes the request passes it on over the gRPC between the nodes. That gRPC keeps its default limit of
4 MiB: neither `Server.Open` in `internal/cluster/server/server.go` nor `ClientManager.newClient` in
`internal/cluster/client/client_manager.go` sets another. `ZenNode.CreateInstance` and
`ZenNode.ModifyProcessInstance` in `internal/cluster/node.go` turn the failed call into
`zenerr.TechnicalError`. REST itself takes bodies up to 10 MiB, the default of
`HttpServer.MaxRequestBodyBytes` in `internal/config/config.go` (all at the pinned commit
`bdd795a3`). The adapter's `ZenBpmErrors`
(`core/src/main/java/org/pbinitiative/zenbpmadapter/client/ZenBpmErrors.java`) reads a `500` as
repeatable, so such a start would be repeated for as long as the outbox keeps it, refused the same
way every time. Decide here whether the adapter measures the variables before it sends them; E13.13
asks the engine for a status which says what happened.

**Tests**: `ZenBpmProcessServiceTest` (fake server): phase one contacts nothing (zero requests),
phase two sends business key, id variable and shared values, the 400 message, and reports the
started workflow with key and version; `ZenBpmSharedValuesTest` for the id winning and
`@NoSyncWithBPMS` still carrying the id; `ZenBpmValueTypesTest` per type and direction, and a
contract-IT case per class of answer against the pinned engine.

*Fit check, 2026-10-17, decided by the Implementor.* Checked against `main` (`ee9d1cc`),
`ADAPTER-AUTHORS.md` (section 2.2 and its table of switches), the platform's code
(`MigrationProcessService`, `PortableValuesCheck`, `ValueConversion` of platform decisions 55 and 57),
Camunda 8's `Camunda8ProcessService`, `Camunda8ValueTypes` and `Camunda8RefusedStart`, and the
engine at the pin `bdd795a3`: its source exported with `git archive`, and the pinned image run by
three new integration tests. The three open questions were answered by the recommendations of the
pipeline's brief, which the engine's code and image confirmed; every new decision says that the
pipeline took it in place of the maintainers. What did not fit, and what was decided:

1. **The `404` of a start (open question 1) is permanent, read by method and path.** The engine
   starts an instance on the leader of a partition (`ZenNode.CreateInstance` picks a partition
   leader, `Server.GetRandomEngine` takes only engines of partitions the node leads) and reads the
   process from that leader's own store; a deployment writes every partition through its leader and
   answers only after each took it. So the `404` is no follower lagging behind a deployment (GAPS
   20 does not apply); what remains is a leader change in the seconds after a deployment, which the
   decision accepts. Decision 30 supersedes decision 10, restating it with the new row; decision 10
   keeps its text and got the "superseded" line, and the code's citations of decision 10 now name 30.
   Unlike Camunda 8, no wrapping exception is needed: `ZenBpmApiException` carries method and path,
   so `ZenBpmErrors.permanentFailure` reads a `404` of `POST /v1/process-instances` itself, and the
   handler rethrows it as an `IllegalStateException` naming module, process, scoped id and
   `vanillabp.adapters.<id>.deployment-failure`, with the engine's answer as its cause. New README
   row, `ZenBpmErrorsTest` row (and two `404`s on other instance paths which stay repeatable),
   `ZenBpmErrorsIT#aStartOfAProcessTheEngineDoesNotHoldIsPermanent` (was "...IsRepeatable").
2. **`history-time-to-live` (open question 2): whole seconds, `PT0S` sent, fractions refused.** The
   probe (`ZenBpmTimeToLiveIT`) started an instance which ends at once with `0s`, `500ms`, `86400s`
   and nothing: the engine takes all four, removes the `0s` and the `500ms` instance at its next
   clean-up (every 30 s), and keeps the other two. So an explicit zero is a meaningful value of its
   own (without the key the engine's default applies, which may keep the instance forever) and is
   sent as `0s`; a fraction of a second is cut without a word (`500ms` behaves as `0s`) and is
   refused while the application starts, at every level, naming the key and what the engine would
   keep; so is a value above `PT2562047H47M16S`, the most whole seconds a duration of nanoseconds
   holds, which the engine refuses with `400`. Decision 31, because the validation and the start
   rely on it. The per-workflow resolution existed only in the platform overlays
   (`VanillaBpZenBpmProperties#historyTimeToLiveFor` on both platforms); the process service now gets
   it as a resolver (`ZenBpmProcessService.HistoryTimeToLive`), and `historyTimeToLiveOf(module,
   process)` says what a start sends.
3. **Variables above 4 MiB (open question 3) are not measured.** Recorded as GAPS 26 (`GAPS.md`, the
   architecture draft with item 15 of the `Deviations` page) with the evidence of the notes above;
   `ZenBpmErrorsIT#aStartLargerThanTheEnginePassesOnIsRepeatable` pins the `500` of a 5 MiB start
   against the image, and a note under E13.13 says the twin starts by turning that test round.
4. **Instruction 1's signature.** The module and the plain process id are not needed to build the
   variables, so it is `forCommand(aggregateIdName, aggregateId, sharedValues)`.
5. **Instruction 2, phase one, resolves no id.** The core resolves the aggregate id and refuses a
   null or blank one before it calls phase one (`MigrationProcessService`), as Camunda 8's preflight
   says; phase one only refuses an adapter id without clients, with its validation message, and asks
   the engine nothing.
6. **An aggregate gone by phase two** starts its workflow with the id alone and a WARN, as Camunda 8
   does (the request carries the id; the aggregate is loaded in phase two).
7. **Instruction 6 did not fit for `Double` and `Float` from the engine.** A whole double comes back
   as a `Long` (`1000.0` as `1000`), but the platform turns it back into the declared type wherever
   it is the same number (platform decision 55, `ValueConversion`), so nothing is changed. Answering
   `changed(...)` there would have ended the start of every application with a `@TaskParam Double`,
   because `PortableValuesCheck` reads `CHANGED` from the BPMS as a defect. Both survive in both
   directions, as on Camunda 8. Added to the plan's list: `Character`/`char`, changed both ways (a
   text of one character, which the platform does not turn back). Every wrapper and its primitive
   has an answer; everything else is "cannot say". `ZenBpmValueTypesIT` sends a value of every
   answer through the pinned engine and reads it back (a `BigDecimal` of 19 digits comes back
   rounded, `2^60 + 1` as a `BigInteger` and `2^53 + 1` as a `Long` rounded, a `Character` as a
   text).
8. **The version** is the `int` of the create answer, reported as `String.valueOf`; a note under
   S6.1.1 asks a delivery to write `processVersion` the same way.
9. **The boot tests** which used phase one of a start as "a use which is not built yet"
   (`ZenBpmStartupValidationBootTest`, `ZenBpmCompleteAdapterIdTest`) now hold that it passes.

**Acceptance criteria**

- [x] The create request carries `businessKey`, `variables[<idName>]` and the shared values, and
  `historyTimeToLive` only where configured.
  `ZenBpmProcessServiceTest#phaseTwoStartsTheWorkflowAndReportsIt` compares the whole body the fake
  engine received: the scoped id `shop__Order`, the business key, the shared `amount` and then the
  id as text (winning over an attribute of its name), and no `historyTimeToLive` key;
  `#phaseTwoSendsTheTimeToLiveOfTheWorkflow` sends `2592000s` for `P30D` and `0s` for `PT0S`;
  `#anAggregateGoneByPhaseTwoStartsWithItsIdAlone` sends the id alone and warns.
  `ZenBpmProcessServiceIT#aStartReportsTheInstanceTheEngineHolds` finds the instance by its business
  key in the pinned engine, with the id and the shared value as variables. The resolution of the
  key per workflow, module and adapter id reaches the process service on both platforms
  (`ZenBpmHistoryTimeToLiveBootTest` on Spring Boot, `ZenBpmOverlayResolutionTest` on Quarkus), and a
  value with a fraction of a second ends the start on both (`ZenBpmHistoryTimeToLiveBootTest`,
  `ZenBpmFractionOfASecondTest`).
- [x] Phase one makes no HTTP request (asserted).
  `ZenBpmProcessServiceTest#phaseOneOfAStartAsksNothing`: the fake engine receives no request;
  `#anAdapterIdWithoutClientsRefusesWithItsValidationMessage` refuses both phases with the message of
  the validation. The boot tests on both platforms run phase one on a complete adapter id.
- [x] `reportStartedWorkflow` is called once per start, with the instance key and its version.
  `ZenBpmProcessServiceTest#phaseTwoStartsTheWorkflowAndReportsIt` records exactly one call of the
  form with two arguments (`4503599627370501`, `3`); a refused start reports nothing
  (`#aStartOfAProcessTheEngineDoesNotHoldIsPermanent`); against the engine,
  `ZenBpmProcessServiceIT` compares the reported pair with the key and the version of the instance
  the engine holds.

**Implemented 2026-10-17.** Core: `ZenBpmProcessService` serves `START_WORKFLOW` (phase one refuses
only an adapter id without clients, phase two loads the aggregate, builds the variables with
`ZenBpmSharedValues`, starts the latest version under the scoped id, rethrows a `404` with the
message of fit-check item 1, reports key and version), answers `deliversTasksAtLeastOnce` with
`true`, `isPhaseTwoFailureRepeatable` through `ZenBpmErrors` and `whatThisBpmsDoesWith` through
`ZenBpmValueTypes`, and takes a `HistoryTimeToLive` resolver from the platform modules
(`ZenBpmAdapterBeanRegistrar`, `ZenBpmProcessServiceProducer`); `ZenBpmTimeToLive` writes the
engine's form and names what the validation refuses; `ZenBpmErrors` reads a `404` of a start as
permanent. Decisions 30 (supersedes 10) and 31, GAPS 26, the README's status and table, the
architecture's class table and gap draft, notes under S6.1.1 and E13.13. Tests: new
`ZenBpmSharedValuesTest` (3), `ZenBpmValueTypesTest` (63), `ZenBpmTimeToLiveTest` (5),
`ZenBpmValueTypesIT` (15), `ZenBpmProcessServiceIT` (2), `ZenBpmTimeToLiveIT` (1, about 35 s for
the engine's clean-up), Spring Boot `ZenBpmHistoryTimeToLiveBootTest` (3), Quarkus
`ZenBpmFractionOfASecondTest` (1); `ZenBpmProcessServiceTest` now 12, `ZenBpmAdapterConfigurationTest`
105, `ZenBpmErrorsTest` 41 (one row and two cases more), `ZenBpmErrorsIT` 9 (the `404` of a start
turned permanent, the `500` above 4 MiB), `ZenBpmOverlayResolutionTest` 6. `TZ=Etc/UTC mvn clean
install` on OpenJDK 25.0.2 builds green with every integration test run and none skipped (engine
test support 12 unit and 3 integration tests, core 707 unit and 59 integration tests, Spring Boot 9
and 2 integration tests, smoke test 35, Quarkus runtime 9, Quarkus deployment 46, coverage gate
35). The gate measures 100 % of 17,039 instructions for Spring Boot and 100 % of 16,917 for
Quarkus, and `bin/check-deploy-safety.sh` passes both runs (13 files deployed, nothing after a red
gate).

Seen to fail, each restored afterwards (25 of 25 mutants, against a green unmutated run of the same
tests):
- `ZenBpmErrors`: the `404` of a start not read as permanent (fails `ZenBpmErrorsTest` and
  `ZenBpmProcessServiceTest`); every `404` read as a start, whatever its method and path
  (`ZenBpmErrorsTest`, four cases).
- `ZenBpmTimeToLive`: no upper bound; fractions let through; `engineForm` without its guard, and
  without its negative half (`ZenBpmTimeToLiveTest`, `ZenBpmAdapterConfigurationTest`).
- `ZenBpmAdapterConfiguration`: a negative fraction named twice; the whole-seconds check applied to
  every scoped duration, a `retry-backoff` included.
- `ZenBpmProcessService`: phase one letting an adapter id without clients through; every refusal
  wrapped, and none; an aggregate gone by phase two loaded anyway; `deliversTasksAtLeastOnce` false;
  every failure repeatable; no time to live resolved, and a resolved one not sent.
- `ZenBpmSharedValues`: the id not moved behind an attribute of its name.
- `ZenBpmValueTypes`: enums, large whole numbers, decimals and characters each left unanswered; one
  explanation for both ways; a missing type not answered.
- Platform wiring: the resolver of `ZenBpmAdapterBeanRegistrar` and of
  `ZenBpmProcessServiceProducer` asked for another process (fails
  `ZenBpmHistoryTimeToLiveBootTest` on Spring Boot and `ZenBpmOverlayResolutionTest` on Quarkus).

**Answers to two reviews of F5.1, 2026-10-17** (one by Claude, 5 findings; one by Copilot, 1
finding, the same as Claude 4). Neither found a Blocker or a Major. Every claim was checked in the
code of the branch, the platform's `ValueConversion` and the engine's source at `bdd795a3`.

Valid and fixed:

- **A text of another type than `String` was promised to come back** (Claude 4 and Copilot 1,
  Minor). `ZenBpmValueTypes` answered `survives()` in both directions for every `CharSequence`, as
  `Camunda8ValueTypes` does. The engine hands a text back as a `String`, and the platform's
  `ValueConversion` turns it into no `StringBuilder` (both reviewers probed it, Copilot through the
  pinned image), so a `@TaskParam StringBuilder` passed the start and failed at its first delivery.
  Now `String` and `CharSequence` itself survive both ways, and any other text type survives TO the
  engine and is `changed(...)` FROM it, naming its type. Tests, red before the fix:
  `ZenBpmValueTypesTest` holds `StringBuilder` and `StringBuffer` per direction and `CharSequence`
  among the surviving types (now 68 cases), `#aTextOfAnotherTypeIsNamedWithItsType` the
  explanation; `ZenBpmValueTypesIT#aTextOfAnotherTypeComesBackAsAString` sends a `StringBuilder`
  through the image and reads a `String` back. The javadoc of the class and the README say it.
- **A javadoc link to a test which sends no time to live** (Claude 1, Minor).
  `ZenBpmTimeToLiveTest` named `ZenBpmRestClientContractIT`; it names `ZenBpmTimeToLiveIT`, which
  has the engine read `86400s` and `0s`.
- **GAPS 26 named only the start** (Claude 2, Minor). `ZenNode.ModifyProcessInstance` sends a
  variable update over the same gRPC between the nodes, as the architecture draft and the note
  above say; the row of `GAPS.md` now names both and cites both methods.
- **The README's status paragraph** (Claude 3, Nit): re-wrapped at 100 columns; a time to live with
  a fraction of a second "is refused while the application starts" instead of "ends the start",
  which right after "A workflow is started" read as the workflow's start; and it names the
  `BigDecimal`, the character and a text of another type than `String` as what comes back changed,
  not "a decimal", which a `Double` is too.
- **A key built with `+`** (Claude 5, Nit): `ZenBpmHistoryTimeToLiveBootTest` builds it with
  `String#formatted`.

Answered without a change: "ends the start" stays in the display names of
`ZenBpmHistoryTimeToLiveBootTest` and `ZenBpmFractionOfASecondTest`. It is the repository's phrase
for a refused start of the application, in more than 40 tests and documents on both platforms, and
there no workflow start stands next to it.

Seen to fail, each restored afterwards (4 of 4 mutants of the new guard, against a green
unmutated run of `ZenBpmValueTypesTest`): `CharSequence` itself no longer surviving (2 failures),
the directions swapped (6), every text type surviving both ways (3), the type not named in the
explanation (2).

After the answers `TZ=Etc/UTC mvn clean install` on OpenJDK 25.0.2 builds green with every
integration test run and none skipped (engine test support 12 unit and 3 integration tests, core
712 unit and 60 integration tests, Spring Boot 9 and 2 integration tests, smoke test 35, Quarkus
runtime 9, Quarkus deployment 46, coverage gate 35; `ZenBpmValueTypesTest` 68,
`ZenBpmValueTypesIT` 16). The gate measures 100 % of 17,063 instructions for Spring Boot and
100 % of 16,941 for Quarkus, and `bin/check-deploy-safety.sh` passes both runs.

**F5.1 done 2026-10-17**, merged to `main` as `a494e79` (pull request #36, after two reviews and
CodeRabbit's review, which left no comment). On that commit (GitHub keeps run logs for 90 days, so
what they showed is written here as well):

- `Checks`, run
  [39029737535](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/actions/runs/39029737535):
  green on both JDKs. `bin/check-engine-copies.sh` reported both copies as the engine's files of
  `bdd795a3...`. `ZenBpmProcessServiceTest` ran 12 tests, `ZenBpmProcessServiceIT` 2,
  `ZenBpmSharedValuesTest` 3, `ZenBpmValueTypesTest` 68, `ZenBpmValueTypesIT` 16,
  `ZenBpmTimeToLiveTest` 5, `ZenBpmTimeToLiveIT` 1, `ZenBpmErrorsTest` 41, `ZenBpmErrorsIT` 9,
  `ZenBpmAdapterConfigurationTest` 105, `ZenBpmHistoryTimeToLiveBootTest` 3,
  `ZenBpmStartupValidationBootTest` 14, `ZenBpmFractionOfASecondTest` 1,
  `ZenBpmOverlayResolutionTest` 6 and `ZenBpmCompleteAdapterIdTest` 2, none skipped (engine test
  support 12 unit and 3 integration tests, core 712 unit and 60 integration tests, Spring Boot 9
  unit and 2 integration tests, smoke test 35, Quarkus runtime 9, Quarkus deployment 46, coverage
  gate 35). The gate measured 100 % on both platforms: of 17,063 instructions for Spring Boot and
  16,941 for Quarkus on JDK 25, of 17,055 and 16,933 on JDK 21.
- `Publish snapshots`, run
  [39029737539](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/actions/runs/39029737539):
  green. The core snapshot it published to GitHub Packages, `2.0.0-20261017.114255-32`, contains
  `client/ZenBpmTimeToLive`, `processservice/ZenBpmSharedValues`, `processservice/ZenBpmValueTypes`
  (with the answer for a text of another type than `String`) and `ZenBpmProcessService` with its
  nested `HistoryTimeToLive`, and holds no test class, fake, fixture or BPMN file. Both report pages
  (`spring-boot-report/index.html`, `quarkus-report/index.html`) answer HTTP 200 and show 0 of
  17,063 (Spring Boot) and 0 of 16,941 (Quarkus) instructions missed.

## F5.2 The probes

### S5.2.1 `ZenBpmInstanceCache` and the workflow probes

- [x] **Depends on:** S5.1.1.

**Instructions**

1. `ZenBpmInstanceCache(rest, maxEntries)`: `facts(instanceKey)` -> `InstanceFacts(key, scopedProcessId,
   version, definitionKey, businessKey, parentKey, processType, variables)` from `rest.instance(key)`,
   LRU bounded by `instance-cache-max-entries`, an `Optional.empty()` for 404 which is NOT cached (the
   instance may appear on a follower a moment later). Thread-safe. Variables are cached only for the
   id variable. The cache also answers `workflowIdOf(instanceKey)`, the root key of decision 28: it
   walks up `parentKey` while the instance's `processType` is `subprocess` or `multiInstance` (a
   child instance of the same process) and stops at `default` or `callActivity`: a task of a called
   process names the called instance (platform decision 124). The walk is cached with the facts.
   E13.14 puts the root key on the job and makes the walk unnecessary.
2. `awarenessOfWorkflow(scope, persistence, id, workflowId)`, the four-argument form (platform
   decision 112, merged into platform decision 5). The fourth argument is the instance key the core
   learned about this aggregate: from the start row (S5.1.1's `reportStartedWorkflow`, the start
   marker of E9), from a task's delivery record, or from the election cache (`ADAPTER-AUTHORS.md`
   4.1). It is the only way to find a workflow without a business key, which is every workflow the
   engine started itself (decision 25). The order:
   1. Where `workflowId` is not null: `rest.instance(workflowId)`. Answer ACTIVE where the instance
      is `active` or `failed`, its `bpmnProcessId` is one of the scope's scoped ids, and its id
      variable or business key equals `id`. Anything else - another state, another process,
      another id, a `404`, a `502` (a key of a partition this engine does not have; the core hands
      the same key to every adapter of its list) or a failure - falls through to step 2. The key may
      only make a YES shorter and may never turn an answer into UNKNOWN (`ADAPTER-AUTHORS.md` 4.1):
      a stale key may name an earlier, ended workflow of the same aggregate, so `COMPLETED` is never
      answered from the key. This is what Camunda 8 does (`theEngineHoldsTheInstance`).
   2. The business-key search per scoped process id of the scope (`size=1`, read `totalCount` and
      the first item's state). State mapping `active|failed -> ACTIVE`, `completed|terminated ->
      COMPLETED`.
   3. UNKNOWN. There is no lookup "instance key where `id` parses as a long" any more: the id of a
      workflow is never its instance key (decision 25).
   `ZenBpmErrors.isUnavailable` -> BPMS_UNAVAILABLE; any other exception in step 2 ->
   BPMS_UNAVAILABLE as well, logged once per adapter id (never UNKNOWN on a failure).
3. `awarenessOfWorkflowForRedispatch(scope, persistence, id, plannedAt)`, the four-argument form
   (platform decision 116). Keeping the default is a contract violation (`ADAPTER-AUTHORS.md`:
   "override the form with four arguments"): an aggregate may carry a second workflow once its first
   ended, and only workflows started at or after `plannedAt` count. So: the business-key search
   without a state filter and with `createdFrom = plannedAt` (S2.2.1 found the filter inclusive and
   in milliseconds); an instance found -> ACTIVE or COMPLETED by its state; a `null` `plannedAt`
   counts every instance; nothing found -> UNKNOWN; a failed query -> BPMS_UNAVAILABLE, never
   ACTIVE. A message-started workflow has no business key and is never found here; S7.1.2 says what
   that means for a re-dispatched message start.
4. `workflowVisibilityDelay()` = `WorkflowVisibilityDelay.of(workflow-visibility-timeout, PT0.25S)`.
   The per-id form `workflowVisibilityDelay(workflowId)` keeps its default, which answers the same:
   a `404` for a key the engine just answered must not shorten the window, because a follower may
   lag (GAPS 20).
5. `canLocateWorkflows()` = `true`.

**Tests**: `ZenBpmProcessServiceTest` cases with the fake server: found by business key; found by
the workflow id with matching process and id -> ACTIVE; a workflow id naming a completed instance
falls through to the search; a workflow id with a process outside the scope, another id, `404` or
`502` falls through; empty -> UNKNOWN; 503 -> BPMS_UNAVAILABLE; timeout -> BPMS_UNAVAILABLE; the
redispatch probe counts an instance created at `plannedAt` and not one created a millisecond before;
`ZenBpmInstanceCacheTest` for bound, eviction, the uncached miss and the root walk (stops at a call
activity).

**Acceptance criteria**

- [x] A workflow of another module of the same engine with the same aggregate id is UNKNOWN.
  `ZenBpmWorkflowProbesTest#aWorkflowOfAnotherModuleIsUnknown`: the fake engine holds a running
  `crm__Order` of `order-1`, and the probe of the scope `shop` searches only `shop__Order` and
  `shop__Invoice` and refuses the key of the `crm__Order` instance; against the pinned engine,
  `ZenBpmProbesIT#aWorkflowOfAnotherProcessIsUnknown` answers UNKNOWN for a running `probe-order`
  of the same aggregate id asked with the scope `probe-invoice`, by business key and by key.
- [x] No probe makes more than `scope.processIds().size() + 1` requests. The unit cases count what
  the fake engine received: one request where the first process runs
  (`#aRunningWorkflowIsFoundByItsBusinessKey`), one where the key answers
  (`#theKeyOfARunningWorkflowIsActiveAtOnce`), two for a scope of two processes and nothing found
  (`#noWorkflowIsUnknown`), three with a key which falls through (`#whateverElseTheKeyLeadsToFallsThroughToTheSearch`,
  `#aWorkflowOfAnotherModuleIsUnknown`). The one exception, more instances of one aggregate and
  process than a page holds with none of those read running, costs two more and is pinned by
  `#moreInstancesThanAPageAreCountedByTheEngine` (fit-check item 1). The walk of the cache costs one
  request per instance it does not hold and none for a second walk
  (`ZenBpmInstanceCacheTest#theWorkflowIdIsTheRootKey`).
- [x] No answer other than ACTIVE comes from the workflow id; a `404` or `502` for it is never
  UNKNOWN by itself. `ZenBpmWorkflowProbesTest#whateverElseTheKeyLeadsToFallsThroughToTheSearch`
  holds twelve keys which are no answer (a completed and a terminated instance, another process,
  another aggregate, no business key and no id variable, `404`, `502`, `500`, an answer which cannot
  be read, no key, an id of another BPMS, `0`): each answers COMPLETED where the search finds an
  ended instance and UNKNOWN only where the search finds nothing;
  `#aKeyOfAnEndedWorkflowFindsTheSecondOne` finds the running second workflow behind a key of the
  ended first. Against the pinned engine, `ZenBpmProbesIT#anEndedWorkflowIsCompleted` answers
  COMPLETED with the key of the ended instance, and `#aKeyOfNoPartitionFallsThroughToTheSearch`
  answers ACTIVE and UNKNOWN as the search does for a key the engine answers `502` for.

**Implemented 2026-10-17.** Core: `ZenBpmInstanceCache` (root package, built and held by the
client factory as `ZenBpmClientFactory#instances`, bounded by `instance-cache-max-entries`) keeps
the facts of an instance without its state, the id variable only once present, no `404`, and walks
to the workflow id of decision 28. `ZenBpmProcessService` answers `awarenessOfWorkflow` in both
forms (the key may only shorten an ACTIVE, then one business-key search per process of the scope),
`awarenessOfWorkflowForRedispatch` in both forms (the search alone, from the millisecond the start
was planned in), `canLocateWorkflows` with `true` and `workflowVisibilityDelay()` with
`workflow-visibility-timeout` asked every quarter of a second; a failed search is BPMS_UNAVAILABLE,
logged at WARN once until a probe is answered again. Decisions 25 and 28 and GAPS 20 and 22 entered
the logs; the README's status paragraph and the architecture's section 4.2, class table and layout
say what is built. Tests: new `ZenBpmInstanceCacheTest` (10), `ZenBpmWorkflowProbesTest` (28, of
them 12 keys which are no answer and 4 failed searches), `ZenBpmProbesIT` (7, against the pinned
image; fixtures `probes/probe-order.bpmn` with the task in an embedded sub-process and
`probes/probe-caller.bpmn` calling it, both with their diagrams), `ZenBpmClientFactoryTest` 17 (the
cache's bound, and the cache refused without clients), `ZenBpmProcessServiceTest` 12 (the workflow
probe no longer among the calls which are not built yet). `TZ=Etc/UTC mvn clean install` on OpenJDK
25.0.2 builds green with every integration test run and none skipped (engine test support 12 unit
and 3 integration tests, core 751 unit and 67 integration tests, Spring Boot 9 and 2 integration
tests, smoke test 35, Quarkus runtime 9, Quarkus deployment 46, coverage gate 35). The gate measures
100 % of 17,728 instructions for Spring Boot and 100 % of 17,606 for Quarkus, and
`bin/check-deploy-safety.sh` passes both runs (13 files deployed, nothing after a red gate).

Seen to fail, each restored afterwards (25 of 25 mutants, against a green unmutated run of the same
tests):
- Step 1 of `awarenessOfWorkflow`: an instance of any state, of any process, of any aggregate
  answering ACTIVE; the id variable not read; a failure of the key answering ACTIVE; an id which is
  no positive number asked anyway (`ZenBpmWorkflowProbesTest`).
- The search: going on after a process which runs; a COMPLETED forgotten for a later process; a
  page taken as complete although it is not; a `failed` instance beyond the page not counted; an
  incident not read as running; a failure answered UNKNOWN instead of BPMS_UNAVAILABLE.
- The redispatch probe: a moment inside a millisecond sent as it is; the moment ignored.
- The log and the window: every failure a WARN; an answer not resetting the WARN; another interval.
- `ZenBpmInstanceCache`: a kept entry answering an id variable it does not hold; every variable
  kept; the eldest inserted instead of the eldest used forgotten; no bound; a multi-instance child
  read as a root; the walk going on through a call activity; no parent followed
  (`ZenBpmInstanceCacheTest`).
- `ZenBpmClientFactory`: the cache built with a fixed bound instead of `instance-cache-max-entries`
  (`ZenBpmClientFactoryTest`).


*Note from S2.2.1, 2026-10-13:* an instance key of a partition the engine does not have is answered
502, not 404 (see the note under S2.2.2). Settled by step 2.1 above: the workflow-id lookup falls
through to the search on a 502.

*Fit check, 2026-10-17, decided by the Implementor.* Checked against `main` (`179401e`),
`ADAPTER-AUTHORS.md` (section 4, 4.1 and 4.2), the type javadoc and the probes of the platform's
`MigratableProcessService`, its `WorkflowLocator` (the walk over the prioritized adapters), the
probes of Camunda 8's `Camunda8ProcessService`, and the engine at the pin `bdd795a3`: its source
exported with `git archive`, and the pinned image run by the new `ZenBpmProbesIT`. The open
questions 2 and 3 of the pipeline's brief were answered by its recommendations. What did not fit,
and what was decided:

1. **Step 2 reads a page, not `size=1` and the first item.** The engine applies `size` to every
   partition and sorts by key (`ZenBpmRestClient#instances`), and an aggregate may carry a second
   workflow of the same process once its first ended (`MigratableProcessService`). The first item
   of a page of one is then the OLDER workflow, so "the first item's state" answers `COMPLETED`
   while the second one runs, and the next operation is consumed as too late. The search asks for
   100 per partition (`InstanceFilter.MAXIMUM_SIZE`), and any instance which runs makes the answer
   `ACTIVE`, as Camunda 8's `anyMatch` does; where every match ended it is `COMPLETED`. Only where
   the page does not hold every match (more than 100 instances of one aggregate and one process on
   a partition) and none of those read runs, two more searches with `state=active` and
   `state=failed` and `size=1` read the engine's count. The search asks no further process once one
   runs. So a probe sends at most `scope.processIds().size() + 1` requests, except in that case,
   which costs two more per such process (`ZenBpmWorkflowProbesTest#moreInstancesThanAPageAreCountedByTheEngine`).
2. **`failed` counts as running in both steps.** The engine marks an instance `failed` where a run
   ended with an error, and still cancels it (`CancelInstanceByKey` in `pkg/bpmn/engine_api.go`
   takes `active` and `failed`) and resumes it when the incident is resolved, so an operation sent
   to it is taken.
3. **Where the cache lives, and its two `facts` forms.** `ZenBpmInstanceCache` is in the root
   package beside `ZenBpmDeployedProcesses`, and the client factory of the adapter id builds and
   holds it (`ZenBpmClientFactory#instances`, bounded by `instance-cache-max-entries`, `0` keeping
   nothing), so the job handler of S6.1.1 reads the same cache without new wiring. `facts(key)` keeps
   no variable; `facts(key, idVariableName)` keeps the id variable, and a kept entry answers it only
   where the variable is present, because a workflow the engine starts gets its id written by a
   `PATCH` after its start (decision 25) and an id once there never changes. `workflowIdOf(key)`
   answers an `OptionalLong`, empty where an instance on the way is unknown, so S6.1.1 can use it as
   it is. The walk keeps no result of its own: it reads the kept facts, so a second walk asks
   nothing.
4. **Open question 2 (state not among the facts), taken.** The facts hold no state, so step 1 reads
   the instance with `rest.instance(key)` itself and does not go through the cache.
5. **Open question 3 (logged once per adapter id), taken.** The first failure of a probe after an
   answer is a WARN, the next ones DEBUG until a probe is answered again; the process service is
   built once per adapter id, and the state is shared by every probe which asks the engine
   (`#theFirstFailureAfterAnAnswerIsAWarn`).
6. **The key nobody holds.** The brief and the note under S2.2.1 name `1 << 12` for the key the
   engine answers `502` for. `1 << 12` is the oldest key of partition 1, which exists and answers
   `404` (`ContractFixtures.KEY_NOBODY_HOLDS`); the `502` comes for `1`, a key of partition 0, which
   holds what every partition shares (`ContractFixtures.KEY_OF_NO_PARTITION`, `zenflake.GetPartitionId`).
   `ZenBpmProbesIT` uses `1`.
7. **An id which is no positive number** (a key of another BPMS, `0`) skips step 1 without a request:
   every key the engine hands out is positive.
8. **The moment of the redispatch probe.** The engine compares whole milliseconds
   (`createdFrom.UnixMilli()` in `GetProcessInstances` of `internal/rest/server.go`), so a
   `plannedAt` inside a millisecond is moved to the next one: an instance created earlier in that
   millisecond does not count, which costs a duplicate at worst and never a lost workflow. The answer
   is `ACTIVE` or `COMPLETED` by state, as step 3 says (Camunda 8 answers `ACTIVE` for both; the core
   skips the start on either). The probe never asks the key.
9. **`workflowVisibilityDelay()`.** The SPI has no `WorkflowVisibilityDelay.of`: the probe answers
   `new WorkflowVisibilityDelay(workflow-visibility-timeout, PT0.25S)`, and a timeout of zero gives
   a window which does not wait. The engine reads instances, jobs and searches on a follower of the
   partition (`GetPartitionFollower` in `ZenNode.GetProcessInstance` of `internal/cluster/node.go`,
   `ConsistencyLevel_NONE` in `partition_persistence.go`), which is GAPS 20.
10. **An adapter id without clients** answers every probe with the message of its validation, as
    the handlers which are not built yet do, and not with `BPMS_UNAVAILABLE`: no engine is
    configured which could be away, and the message names the keys to set.
11. **The tests of the probes** are a class of their own, `ZenBpmWorkflowProbesTest`, beside
    `ZenBpmProcessServiceTest`, which would otherwise mix the start with every probe.
12. **Decisions and gaps.** Decisions 25 and 28 and GAPS 20 and 22 enter `DECISIONS.md` and
    `GAPS.md` under the plan's numbers; decision 25 says what is true at the pin and marks the
    start marker of E9 as planned. No open pull request claimed a number (`gh pr list --state
    open` is empty).

### S5.2.2 Task probes, `openTaskCount`

- [x] **Depends on:** S5.2.1.

**Instructions**

1. `awarenessOfTask(scope, aggregateId, taskId)`: `taskId` not a long -> UNKNOWN with a DEBUG line
   (a Camunda key handed to the wrong adapter); `rest.job(key)` empty -> UNKNOWN; job's instance via
   the cache; instance's `bpmnProcessId` not in the scope -> UNKNOWN; job `active` -> ACTIVE,
   `completed|terminated|failed`... `failed` means an incident on an OPEN task, so ACTIVE;
   `completed|terminated` -> COMPLETED; failure -> BPMS_UNAVAILABLE. Non-advancing (a GET).
2. `awarenessOfUserTask` = the same (a user task is a job). The core knows which kind a task id is
   from the delivery record (`TaskKind` `TASK` or `USER_TASK`, platform decision 106), which S6.1.1
   and S8.1.1 report; the probes need not tell the kinds apart.
3. `openTaskCount(module, plainProcessId)`: `rest.processDefinitionStatistics(key)` for every version
   key `ZenBpmDeployedProcesses` and the catalog know, summing active element instances of job-producing
   elements; `null` where nothing was deployed. Never a job page (decision 14).

**Tests**: fake-server cases per outcome; `ZenBpmProbesIT` (Docker): a started instance's waiting job
is ACTIVE for its scope and UNKNOWN for a scope naming another process; after completion COMPLETED.

*Fit check, 2026-10-17, decided by the Implementor.* Checked against `main` with S5.2.1
(`9a009bb`), `ADAPTER-AUTHORS.md` (section 4 and its paragraph on the records of deliveries), the
platform's `MigratableProcessService#openTaskCount`, `MigrationProcessService`, `WorkflowLocator`
and `DeliveryRecords`, Camunda 8's `openTaskCount`, `awarenessOfTask` and `awarenessOfUserTask`,
and the engine at the pin `bdd795a3` (source, and the pinned image in `ZenBpmProbesIT`). What did
not fit, and what was decided:

1. **Open question 1: other adapters' task ids do reach the probe, so decision 32.** The platform
   asks every adapter of a workflow's list with the same task id: `MigrationProcessService#probe`
   (line 2474 of `migration-adapter/runtime/.../MigrationProcessService.java`) hands the call's
   task id to `awarenessOfTask` or `awarenessOfUserTask`, and `WorkflowLocator#walk` (line 612 of
   `WorkflowLocator.java`) runs that probe for each prioritized adapter until one is not
   UNKNOWN. A record of a delivery routes the question only where it exists, and "where no record
   exists, the walk runs exactly as it always did" (`ADAPTER-AUTHORS.md`, line 1022). So a key of
   another engine reaches this one, and a `502` read as unavailable would stop the walk for good.
   Decided as the brief recommends, with one change: the partitions are read only AFTER a `502`
   for the job, not before the job is asked, so a task id of this engine costs one request as
   before, and a foreign one two. A key of a partition `GET /system/status` does not report is
   UNKNOWN; a `502` for a partition it reports, and a status which does not come, stay
   BPMS_UNAVAILABLE. Decision 32, decided by the pipeline in place of the maintainers.
   `EngineStatus` gained the partition ids, which `ZenBpmRestClientContractIT` reads as `[1]` from
   the pinned image; `ZenBpmProbesIT#aTaskIdTheEngineCannotPlaceIsUnknown` sees the `502` and the
   UNKNOWN for a key of partition 2 and for `1` (partition 0).
2. **`openTaskCount` counts every version the engine holds, not only the version keys
   `ZenBpmDeployedProcesses` knows.** That record holds the one version this start deployed per
   process. The count exists for the tasks which were open before this application ever ran
   (`MigratableProcessService#openTaskCount`, `DeliveryRecords#reportOpenTasksNobodyRemembers`),
   and those wait mostly on an older version, so the brief's recommendation would hide the very
   number the platform asks for. The probe lists the versions under the scoped id (`GET
   /v1/process-definitions`), and per version reads its model and its statistics. The platform asks
   in its startup checks (`validatePersistedAdapterIdsAtStartup`, on both platforms), before the
   application deployed anything, so `ZenBpmDeployedProcesses` is still empty and every model is
   read from the engine. The requests grow with the versions, which decision 14 allows ("those
   questions are what the check is for"), and never with instances; no job page is fetched. E10's
   version catalog may share the models.
3. **Open question 4: what counts, and what a failure answers.** An element counts where the model
   reader of E4 classifies it as giving a worker a job (`ZenBpmBpmnModel#tasks` and `#userTasks`:
   service, send and business rule tasks with a job, message throw and end events, user tasks),
   read from the model of the same version, and its active element instances are summed. A task
   with an incident is not counted: the engine's statistics report incidents apart, for a token
   which is no longer active. A failed call answers `null` with a DEBUG line, as Camunda 8 does
   (line 515 of `Camunda8ProcessService`): the platform calls `openTaskCount` while the
   application starts and does not catch (`DeliveryRecords#reportOpenTasksNobodyRemembers`), so a
   failure thrown would end the start over a diagnostic. The brief's "a failure the caller sees"
   contradicts that code, which decides. A process the engine holds no version of answers `0`
   rather than `null`: the engine holds nothing open, and the platform is silent on both.
4. **Open question 2: the cost of a task probe.** A cold cache costs the job and its instance, two
   requests; a warm one the job alone; a `502` the job and the status. That is at most
   `scope.processIds().size() + 1` for every scope with a process. The task probe walks nothing: a
   child instance of an embedded sub-process or an iteration names the process of its definition,
   which is the workflow's own, so the instance the job waits in answers the scope question; a task
   of a called process is compared with the called process, as Camunda 8 compares a job's
   `processDefinitionId`. `ZenBpmProbesIT#aWaitingJobIsActiveForItsScopeOnly` asks about a job
   which waits in an embedded sub-process.
5. **A job whose instance the engine does not answer** is BPMS_UNAVAILABLE, not UNKNOWN: the engine
   holds the job, and only whose it is cannot be said now (a node which lags behind, GAPS 20). A
   `404` for the job itself is UNKNOWN_TO_BPMS, although it may come from such a node too: a key of
   another engine or of Camunda 8 which falls on a partition this engine has is answered `404` as
   well, and it must let the core ask the next adapter. The core gives a task no visibility window,
   so a job created a moment ago and asked about on a follower which lags is reported gone (GAPS
   20). Camunda 8 answers a job it does not find the same way.
6. **The aggregate id is not compared**, as on Camunda 8: the scope decides.
7. **The tests of the task probes** are `ZenBpmTaskProbesTest`, beside `ZenBpmWorkflowProbesTest`.
8. **The startup question reaches the engine before a refused model ends the start.** Because the
   platform asks before anything is deployed (item 2), an application whose BPMN file the adapter
   refuses now sends the engine one read, the list of versions of `openTaskCount`, before the
   refusal ends its start, which an engine that
   answers nothing turns into `null`. The three boot tests which held that the engine is asked
   nothing (`ZenBpmUnsupportedModelBootTest` on Spring Boot, `ZenBpmUnsupportedModelTest` on
   Quarkus, and the javadoc of `ZenBpmUnsupportedModelWarnTest`) now hold what the refusal is for:
   nothing is deployed, and the one request is that read. The engine is not waited for before it
   (that is the deployment's `startup-wait`), so an engine which is not ready yet loses the number
   at that boot, a DEBUG line.

**Acceptance criteria**

- [x] The task probes never send a command (fake server asserts GET only).
  `ZenBpmTaskProbesTest#aJobOfTheScopeAnswersByItsState` compares the whole list of requests the
  fake engine received for each job state (`GET /v1/jobs/{key}`, `GET /v1/process-instances/{key}`,
  and the job again for the second probe), `#everyOtherFailureIsUnavailable` holds that every request
  of six failures is a `GET`, `#aKeyOfAPartitionTheEngineDoesNotHaveIsUnknown` lists the job and
  `GET /system/status`, and `#theOpenTasksOfEveryVersionAreCounted` lists the four reads of the
  count and no job page. Against the pinned engine, `ZenBpmProbesIT#aWaitingJobIsActiveForItsScopeOnly`
  asks both probes about a waiting job for two scopes, with the same answers before the test
  completes the job itself.

**Implemented 2026-10-17.** Core: `ZenBpmProcessService` answers `awarenessOfTask` and
`awarenessOfUserTask` with one probe: no positive number is UNKNOWN without a request, the job by key
(`404` UNKNOWN, `active` and `failed` ACTIVE, `completed` and `terminated` COMPLETED), the process of
its instance through the instance cache compared with the scope, and a `502` compared with the
engine's partitions (decision 32); `openTaskCount` sums the active instances of the job elements of
every version under the scoped id, each read with its own model, `null` where the engine does not
answer. `EngineStatus` carries the partition ids of `GET /system/status`. Decision 32, the README's
status paragraph and its note on the `502`, the architecture's section 4.2, notes under S6.1.1 and
E10. Tests: new `ZenBpmTaskProbesTest` (23), `ZenBpmProbesIT` 10 (a waiting job ACTIVE for its scope
and UNKNOWN for another, COMPLETED once it ended; a task id the engine cannot place; the open tasks
of two versions of the new fixture `probes/probe-count.bpmn`, a service task and a user task beside
a timer, with its diagram), `ZenBpmRestClientContractIT` (the image reports partition 1),
`ZenBpmRestClientTest`, `ZenBpmEnvironmentInfoTest`, `ZenBpmProcessServiceTest` 12 (no probe among
the calls which are not built yet), and the three boot tests of fit-check item 8. `TZ=Etc/UTC mvn
clean install` on OpenJDK 25.0.2 builds green with every integration test run and none skipped
(engine test support 12 unit and 3 integration tests, core 774 unit and 70 integration tests, Spring
Boot 9 and 2 integration tests, smoke test 35, Quarkus runtime 9, Quarkus deployment 46, coverage
gate 35). The gate measures 100 % of 18,071 instructions for Spring Boot and 100 % of 17,949 for
Quarkus, and `bin/check-deploy-safety.sh` passes both runs (13 files deployed, nothing after a red
gate).

Seen to fail, each restored afterwards (15 of 15 mutants of the code as committed, against a green
unmutated run of `ZenBpmTaskProbesTest` and `ZenBpmRestClientTest`):
- The task probes: a task id which is no positive number asked anyway; a `404` answered ACTIVE; the
  scope not compared; an incident answered COMPLETED; a job whose instance the engine does not know
  answered UNKNOWN.
- Decision 32: no `502` read as a key of another engine; every `502` read so; any failure read so
  (killed after `#onlyA502IsComparedWithThePartitions` was added: it survived the first run); a
  status which does not come read so; the partition taken from the wrong bits.
- `openTaskCount`: every element counted, a timer included; the user tasks left out; one version
  only; a failure thrown.
- `ZenBpmRestClient#status`: the partitions not read.

Two mutants led to code being removed rather than to a test: a guard which answered `null` for an
adapter id without clients before the engine was asked survived, because `factory.rest()` throws
inside the `try` and the count answers `null` all the same, so the guard went; and the reuse of the
model of the version this start deployed went with fit-check item 2, because the platform asks before
anything is deployed.


**Answers to two reviews of F5.2, 2026-10-17** (one by Claude, 5 findings; one by Copilot, 3
findings, its first the same as Claude 2). Every claim was checked in the code of the branch, the
platform's code and the engine's source at `bdd795a3`.

Valid and fixed:

- **A status naming no partition turned every `502` for a job into UNKNOWN_TO_BPMS** (Copilot 1,
  Major; Claude 2, Minor). `ZenBpmRestClient#status` reads a missing or `null` `partitions` as an
  empty list, and the task probe then found the key's partition "not reported" for every key, so a
  `502` of a leaderless partition let the core ask the next adapter. An engine always has a
  partition, so a status without one says nothing about the key: the failure stays
  BPMS_UNAVAILABLE. Copilot asked to keep a valid empty map apart from a missing one; not taken,
  because an empty inventory never proves a key foreign either. Decision 32 says so. Test, red
  before the fix: `ZenBpmTaskProbesTest#everyOtherFailureIsUnavailable` with a status without
  `partitions`, with `null` and with `{}` (now nine failures, both probes).
- **A cached `null` id variable was never read again** (Copilot 2, Minor). `InstanceFacts` kept
  `orderId: null`, and `facts(key, "orderId")` took it as present, so the id written after the
  start would not have been seen until the entry was evicted. Only an id with a value is kept now.
  Test, red before the fix: `ZenBpmInstanceCacheTest#anIdVariableWhichIsNullIsNotKept`.
- **An ACTIVE from the key did not end the failures** (Copilot 3, Minor). The key's fast path of
  `awarenessOfWorkflow` returned without marking the probe answered, so the first failure of the
  next outage was a DEBUG line instead of a WARN. Test, red before the fix:
  `ZenBpmWorkflowProbesTest#anAnswerFromTheKeyAloneEndsTheFailures`.
- **A job `404` from a node which lags was not recorded** (Claude 1, Minor). The answer stays
  UNKNOWN_TO_BPMS, because a key of another BPMS on a partition this engine has is answered `404`
  too and must let the core ask the next adapter; Camunda 8 answers a job it does not find the same
  way. GAPS 20 and fit-check item 5 of S5.2.2 now say that a task has no visibility window and what
  that costs.
- **The probe's WARN named no property key** (Claude 5, Nit): it names
  `vanillabp.adapters.<id>.rest-address` and `vanillabp.adapters.<id>.request-timeout`; the two
  captured-output tests assert the text.
- **Wording** (Claude 3 and 4, Nit): two javadoc lines of `ZenBpmProcessService` and one line of
  fit-check item 8 re-wrapped; the architecture's class table says the walk keeps the facts on the
  way, not the walk.

Seen to fail, each restored afterwards (3 of 3 mutants of the new guards, against a green unmutated
run of `ZenBpmTaskProbesTest`, `ZenBpmWorkflowProbesTest` and `ZenBpmInstanceCacheTest`): the empty
partition list read as an answer (killed by `#everyOtherFailureIsUnavailable`); the key's ACTIVE
without marking the probe answered (`#anAnswerFromTheKeyAloneEndsTheFailures`); a `null` id kept
(`#anIdVariableWhichIsNullIsNotKept`).

`TZ=Etc/UTC mvn clean install` on OpenJDK 25.0.2 on the review fixes builds green with every
integration test run and none skipped (engine test support 12 unit and 3 integration tests, core
779 unit and 70 integration tests, Spring Boot 9 and 2 integration tests, smoke test 35, Quarkus
runtime 9, Quarkus deployment 46, coverage gate 35; `ZenBpmTaskProbesTest` 26,
`ZenBpmWorkflowProbesTest` 29, `ZenBpmInstanceCacheTest` 11, `ZenBpmProbesIT` 10). The gate
measures 100 % of 18,092 instructions for Spring Boot and 100 % of 17,970 for Quarkus, and
`bin/check-deploy-safety.sh` passes. The run before it failed in `ZenBpmEngineWaitIT` alone,
because the pinned engine's container did not start ("failed to bootstrap cluster: boot timeout",
as under S3.2.1); nothing of this batch runs there, and the next run passed.

**F5.2 done 2026-10-17**, merged to `main` as `0db8ea4` (pull request #38, after two reviews and
CodeRabbit's review, which left no comment). On that commit (GitHub keeps run logs for 90 days, so
what they showed is written here as well):

- `Checks`, run
  [39040857073](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/actions/runs/39040857073):
  green on both JDKs. `bin/check-engine-copies.sh` reported both copies as the engine's files of
  `bdd795a3...`. `ZenBpmInstanceCacheTest` ran 11 tests, `ZenBpmWorkflowProbesTest` 29,
  `ZenBpmTaskProbesTest` 26, `ZenBpmProbesIT` 10, `ZenBpmProcessServiceTest` 12,
  `ZenBpmRestClientTest` 96, `ZenBpmRestClientContractIT` 18, `ZenBpmEnvironmentInfoTest` 10,
  `ZenBpmClientFactoryTest` 17, `ZenBpmUnsupportedModelBootTest` 2 and `ZenBpmUnsupportedModelTest`
  1, none skipped (engine test support 12 unit and 3 integration tests, core 779 unit and 70
  integration tests, Spring Boot 9 unit and 2 integration tests, smoke test 35, Quarkus runtime 9,
  Quarkus deployment 46, coverage gate 35). The gate measured 100 % on both platforms: of 18,092
  instructions for Spring Boot and 17,970 for Quarkus on JDK 25, of 18,084 and 17,962 on JDK 21.
- `Publish snapshots`, run
  [39040857084](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/actions/runs/39040857084):
  green. The core snapshot it published to GitHub Packages, `2.0.0-20261017.142805-34`, contains
  `ZenBpmInstanceCache` with its nested `InstanceFacts`, `client/api/EngineStatus` and
  `processservice/ZenBpmProcessService`, and holds no test class, fake, fixture or BPMN file. Both
  report pages (`spring-boot-report/index.html`, `quarkus-report/index.html`) answer HTTP 200 and
  show 0 of 18,092 (Spring Boot) and 0 of 17,970 (Quarkus) instructions missed.

## F5.3 Exemplary end-to-end start

### S5.3.1 The two-phase start on both platforms

- [x] **Depends on:** S5.2.2.

**Instructions**

- Spring `ZenBpmDeploymentAndStartIT` (module `start-it`, JPA with the platform's JDBC outbox, the
  default since gruelbox left the platform (platform decision 102), `@DirtiesContext`, own
  container; the test aggregate marks what it does not share with `@NoSyncWithBPMS`, or its workflow
  sets `allow-full-sync-with-bpms: true`, because an aggregate which shares everything ends the
  start (`FullSyncCheck`, platform decision 66)): `instanceAppearsOnlyAfterCommit` (inside the
  transaction the business-key search is empty; after the commit it finds the instance with state
  `active`), `noInstanceAfterRollback` (the transaction rolls back; after two outbox cycles nothing
  exists and no outbox entry remains), `aWorkflowNobodyStartedIsUnknownToBothProbes`,
  `theProbeFindsTheStartedWorkflow` through `MigratableProcessService` directly.
- Quarkus `quarkus/integration-tests`: `ZenBpmWorkflowLifecycleTest` (`QuarkusProdModeTest`, JVM
  args from `testCoverageJavaAgent(quarkusProdModeTestDefaults())`, `quarkus.http.port` free, the
  container's addresses passed as system properties), introspection endpoints
  `introspect/start/{id}`, `introspect/rollback/{id}`, `introspect/instances/{id}`; cases
  `aStartCreatesTheInstanceAfterTheCommit`, `aRolledBackStartCreatesNothing`. This class grows with
  every later epic (one boot per class, the Camunda 8 rule).

  *Note from the reviews of F4.3, 2026-10-17.* E4 proves the pipeline on Quarkus without the engine
  (`ZenBpmDeploymentPipelineTest`, `ZenBpmRefusedFileTest` and `ZenBpmStopOrderTest` against a
  server standing in for it), as its "Done when" asks; no Quarkus test hands a DMN file to the
  adapter yet. Give the workflow module of this test one BPMN and one DMN file, and assert after the
  boot that the engine holds both under their scoped ids, the reported process version, and a job
  type subscribed over the engine's gRPC API, so the packaged extension is proved against the real
  engine for deploying as well as for starting.

*Fit check, 2026-10-17, decided by the Implementor.* Checked against `main` (`bcdca37`),
`ADAPTER-AUTHORS.md` (sections 2 and 4), the platform's code (`JdbcPhaseTwoOutboxStore`,
`JdbcPhaseTwoOutboxDispatcher`, `FullSyncCheck`, `WorkflowModuleAutoConfiguration`,
`MigrationAdapterProperties`, `PhaseTwoOutboxReader` of `test-utils`), Camunda 8's
`Camunda8DeploymentAndStartIT`, `Camunda8DecisionTableIT` and its Quarkus `integration-tests`, and
the engine at the pin `bdd795a3` (its source exported with `git archive`, its image run by both new
test classes). The open questions were answered by the recommendations of the pipeline's brief,
which the code confirmed. What did not fit, and what was decided:

1. **No module `start-it`.** The Spring Boot IT is `ZenBpmDeploymentAndStartIT` in the package
   `springboot.it` of the `spring-boot` module, beside `ZenBpmDeploymentIT`, where the JPA and H2
   test dependencies already are, as Camunda 8 keeps its IT next to its other ones; a new module
   would have needed its place in the Spring Boot report and in `bin/check-deploy-safety.sh`'s
   view for nothing. It boots an application of its own, `it/start/StartItApplication`
   (`Order`, `OrderRepository`, `OrderWorkflow`), configured by `start-it.yaml`. A
   `@SpringBootApplication` scans its package and every package below, so `DeployItApplication`,
   `Shipment` and `ShipmentWorkflow` moved from `springboot.it` to `springboot.it.deploy`;
   otherwise either application would have picked up the other's workflow service. The test
   classes are one classpath directory with one `META-INF/workflow-module`, so the platform puts
   `OrderWorkflow` into the workflow module `deploy-it`, too
   (`WorkflowModuleAutoConfiguration#registerProcessServices`); its process is `deploy-it__Ordering`
   at the engine.
2. **One engine per test class** (open question 2). The Spring IT declares `EngineUnderTest` as a
   `@Container` and hands its addresses over with `@DynamicPropertySource`; `@DirtiesContext`
   keeps the context from being reused against another class's engine. The Quarkus test starts
   its engine in a static initializer, because a prod-mode test reads its runtime properties while
   its field is initialized, and passes both addresses and a free `quarkus.http.port`
   (`OneFreePortPerJvm`) as runtime properties.
3. **The Quarkus persistence is Camunda 8's** (open question 3): `quarkus-hibernate-orm`,
   `quarkus-agroal` and `quarkus-jdbc-h2`, the aggregate a JPA entity, a hand-written
   `AggregatePersistenceAware` saving in the caller's JTA transaction, the introspection endpoints
   opening their transaction with `UserTransaction`, and the platform's JDBC outbox, its default.
   `test-utils` is a test dependency without Logback, as there.
4. **The aggregate shares all but one attribute**: `@NoSyncWithBPMS` on `note` puts it into the
   mode "share everything else" (`FullSyncCheck` ends the start of an aggregate which shares
   everything), so `allow-full-sync-with-bpms` is not needed. Its id is a text the test chooses,
   so the plan's endpoints `introspect/start/{id}` and `introspect/rollback/{id}` take it, and a
   test knows the business key of a workflow which was never started. Both tests assert that
   `item` reaches the instance and `note` does not.
5. **"Inside the transaction the business-key search is empty"** (open question 4): both
   platforms search the engine through the adapter's own client between `startWorkflow` and the
   commit; after it, the instance found by its business key is `ACTIVE`
   (`ProcessInstanceRef.State`), carries the id and the shared value, and runs the scoped process.
6. **Two outbox cycles without a fixed wait.** The platform's JDBC outbox wakes its poller after
   every commit (`JdbcPhaseTwoOutboxStore` is given `dispatcher::triggerPoll`), and a poll claims
   every due entry. So after the rolled-back start, both tests commit two more starts, one after
   the other, and wait (a poll with a deadline) for the instance of each: two dispatch runs after
   the rollback, in either of which an entry the rollback had left would have been dispatched.
   Then the rolled-back aggregate id has no instance. The Spring IT also reads the outbox through
   `PhaseTwoOutboxReader.ofTheVanillaBpOutbox`: no entry of that id, and the entries of the two
   later starts, so the reader is known to read the table the application writes. The Quarkus
   application keeps its database inside its own JVM, so the Quarkus case asserts what Camunda 8's
   asserts there instead: the aggregate is gone and no instance appeared.
7. **The resources location is the platform's default**, `classpath*:<module>/processes/<adapter
   id>` (`MigrationAdapterProperties`): the files are under `deploy-it/processes/zenbpm` and
   `zenbpm-e2e/processes/zenbpm`. An explicit `resources-location` without the adapter id makes the
   platform report in its startup block that the location is not specific to the adapter.
8. **The DMN file is called, before the task** (the F4.3 note). `StartProcess` evaluates the
   decision `rating` in business rule tasks and then waits in the task `Prepare`, so a started
   instance shows at the engine that the process found the decision under the id the adapter
   rewrote it to (`zenbpm-e2e__rating`); the binding is the default one, and the id has no `.`
   (GAPS 14). The test asserts the definitions as `ZenBpmDeploymentIT` reads them
   (`/v1/process-definitions`, `/v1/dmn-resource-definitions` by the DMN definitions id), the
   version 1 the adapter registered (`introspect/versions/StartProcess`), and the log line of the
   subscription.
9. **At the pin a decision's result reaches the process only through an output mapping:** GAPS 27
   (new, recorded by the pipeline in place of the maintainers). The first run found no `rating`
   among the instance's variables. The business rule task hands its result to
   `PropagateOnlyMappedOutputs` of `pkg/bpmn/runtime/varholder.go`, which propagates nothing where
   the task has no output mapping, and the engine's own `test/e2e/business_rule_variables_test.go`
   pins that; Camunda 8 sets the result variable in the process, which its
   `Camunda8DecisionTableIT` relies on (`resultVariable="rating"` and no output mapping, read as
   `=rating` by the next task). So `StartProcess` evaluates the decision twice: `Rate` maps its
   result (`source="=rating" target="rating"`), `RateUnmapped` names the result variable
   `unmappedRating` and maps nothing, and the test asserts `rating` and the absence of
   `unmappedRating`. `=rating.grade` was not used: the platform reports an expression which reads
   more than the name of one variable. Left to the
   maintainers: whether the adapter should warn about, refuse or rewrite a business rule task
   with a result variable and no output mapping while it reads the model (E4's
   `ZenBpmUnsupportedConstructs` is where such a check would live).
10. **"A job type subscribed over the engine's gRPC API" is proven by a job arriving.** The engine
    lists no subscriptions (nothing in `openapi/api.yaml` or the protos at the pin), so the Quarkus
    test waits for the job of the started instance, which arrives over the stream only because
    the job type was subscribed: the adapter logs it at WARN ("arrived, which nothing handles
    yet"), and the test reads the application's log file (`quarkus.log.file`). The line it waits
    for names the key of the job the engine holds for that instance (`introspect/jobs/{key}`), so
    the job of another instance never satisfies it; the instance's variables are read once that job
    exists, because the engine shows an instance before it ran the decisions in front of the task.
    E6 replaces that line with the handler's call.
11. **There is no method `ZenBpmProcessService.phaseTwo`**: the phase-two handler of a start is the
    private `startWorkflow(PhaseTwoRequest)`. The Quarkus report adds the core's own coverage data,
    where the unit tests already cover it, so the report alone cannot show that the booted
    application ran it. The evidence is a report of the coverage data of `quarkus/integration-tests`
    alone, and the line of the aggregate report which loads that file.
12. **Three Quarkus cases, not two**: `theBootDeploysTheModelAndTheDecision` holds the deployment
    assertions of the F4.3 note, beside `aStartCreatesTheInstanceAfterTheCommit` and
    `aRolledBackStartCreatesNothing`. The application (`E2eAggregate`, `E2ePersistence`,
    `E2eWorkflow`, `E2eIntrospection` in `quarkus/test`, outside the coverage report) is shaped so
    that a later story adds a workflow service, a model and an endpoint without touching the boot.
    The README now says that the instance is created only after the commit and never after a
    rollback, which both classes pin.

**Acceptance criteria**

- [x] Both platforms prove the two-phase start against the real engine.
  Spring Boot: `ZenBpmDeploymentAndStartIT` (4 tests) against its own container of the pinned image.
  `#instanceAppearsOnlyAfterCommit` searches the engine by the business key inside the transaction
  (empty) and after the commit (one instance, `ACTIVE`, process `deploy-it__Ordering`, business key,
  `id` and the shared `item`, no `note`); `#noInstanceAfterRollback` finds neither an instance nor
  an outbox entry of the rolled-back id after the outbox dispatched two later starts, whose entries
  the reader does see; `#aWorkflowNobodyStartedIsUnknownToBothProbes` and
  `#theProbeFindsTheStartedWorkflow` ask the booted application's `ZenBpmProcessService` as a
  `MigratableProcessService` (UNKNOWN_TO_BPMS twice; ACTIVE by business key, by key and before a
  repeated start). Quarkus: `ZenBpmWorkflowLifecycleTest` (3 tests, `QuarkusProdModeTest`, the
  application in a JVM of its own): `#theBootDeploysTheModelAndTheDecision` finds
  `zenbpm-e2e__StartProcess` in version 1, the DMN definitions `zenbpm-e2e-rating` holding
  `zenbpm-e2e__rating`, version 1 registered by the adapter and the subscription of
  `zenbpm-e2e__StartProcess__prepare`; `#aStartCreatesTheInstanceAfterTheCommit` sees no instance
  inside the transaction, then one `ACTIVE` instance of version 1 with business key, `id`, `item`,
  no `note`, the mapped `rating` and no `unmappedRating`, and the job of that instance arriving over
  the subscribed type; `#aRolledBackStartCreatesNothing` finds no aggregate and no instance of the
  rolled-back id after two later starts were dispatched.
- [x] The Quarkus coverage report shows `ZenBpmProcessService.phaseTwo` covered.
  The handler is `startWorkflow(PhaseTwoRequest)` (fit-check item 11). The `report-aggregate` of
  `test-coverage-report/quarkus` loads
  `quarkus/integration-tests/target/jacoco.exec` beside the files of the runtime and deployment
  modules, and a report of that file alone (JaCoCo CLI 0.8.15 over the classes of `core` and
  `quarkus/runtime`, from the clean build below) shows `startWorkflow(PhaseTwoRequest)` 100 %
  covered, 0 of its 21 lines missed: the JVM of the booted application ran it, because the test
  JVM only builds that application.

**Implemented 2026-10-17.** No production code changed: the story proves on both platforms what
F5.1 and F5.2 built. Spring Boot module, test tree: `ZenBpmDeploymentAndStartIT`, the application
`it/start` (`StartItApplication`, `Order`, `OrderRepository`, `OrderWorkflow`), `start-it.yaml`,
`deploy-it/processes/zenbpm/Ordering.bpmn`; `DeployItApplication`, `Shipment` and
`ShipmentWorkflow` moved to `it/deploy` (fit-check item 1). `quarkus/integration-tests`: its POM
(REST, Jackson, YAML configuration, Hibernate ORM, Agroal, H2; tests with `quarkus-junit-internal`,
REST Assured, `test-utils` and the engine test support; the JaCoCo agent handed to the prod-mode
JVM), the application in `quarkus/test` (`E2eAggregate`, `E2ePersistence`, `E2eWorkflow`,
`E2eIntrospection`), `application.yaml`, the module descriptor, `StartProcess.bpmn` and
`rating.dmn`, and `ZenBpmWorkflowLifecycleTest`. GAPS 27 (new, in `GAPS.md` and in the
architecture's gap and deviation drafts), one sentence of the README's status.

Seen to fail, each restored afterwards (3 of 3 mutants, against a green unmutated run of the two
classes, the mutated core installed for the run):
- the instance created in phase one, inside the caller's transaction (`ZenBpmProcessService`):
  fails `ZenBpmDeploymentAndStartIT#instanceAppearsOnlyAfterCommit` and `#noInstanceAfterRollback`,
  and `ZenBpmWorkflowLifecycleTest#aStartCreatesTheInstanceAfterTheCommit` and
  `#aRolledBackStartCreatesNothing`;
- the called decision's id left unscoped (`ZenBpmScoping`): the instance fails at the decision and
  no job arrives (`#aStartCreatesTheInstanceAfterTheCommit`);
- the module's job types logged as subscribed but never subscribed (`ZenBpmDeploymentService`): no
  job arrives (`#aStartCreatesTheInstanceAfterTheCommit`), while the log line alone would have
  passed.

`TZ=Etc/UTC mvn clean install` on OpenJDK 25.0.2 builds green with every integration test run and
none skipped (engine test support 12 unit and 3 integration tests, core 779 unit and 70
integration tests, Spring Boot 9 unit and 6 integration tests, smoke test 35, Quarkus runtime 9,
Quarkus deployment 46, Quarkus integration tests 3, coverage gate 35; `ZenBpmDeploymentAndStartIT`
4, `ZenBpmWorkflowLifecycleTest` 3, `ZenBpmDeploymentIT` 2). The gate measures 100 % of 18,092
instructions for Spring Boot and 100 % of 17,970 for Quarkus (the classes of the test applications
are outside both reports), and `bin/check-deploy-safety.sh` passes both runs (13 files deployed,
nothing after a red gate).

**E5's "Done when" is met.** "The exemplary end-to-end start runs on both platforms against the
container": `ZenBpmDeploymentAndStartIT` (4) on Spring Boot and `ZenBpmWorkflowLifecycleTest` (3)
on Quarkus, both against the pinned image. "The probes are pinned by unit tests with the fake
server and by ITs": `ZenBpmWorkflowProbesTest` (29), `ZenBpmTaskProbesTest` (26) and
`ZenBpmInstanceCacheTest` (11) with the fake engine, `ZenBpmProbesIT` (10) against the pinned
image, and the probes of the booted Spring Boot application in `ZenBpmDeploymentAndStartIT` (2 of
its 4).

**Answers to two reviews of F5.3, 2026-10-17** (one by Claude, 5 findings; one by Copilot, 2
findings, its second the same as Claude 1). Every claim was checked in the code of the branch, the
platform's code and the engine's source at `bdd795a3`.

Valid and fixed:

- **Without Docker the Quarkus test failed the build** (Claude 1, Major; Copilot 2, Major). The
  README says the tests which start the engine are skipped without a Docker environment; the class
  started the engine in a static initializer, which JUnit runs while it registers the prod-mode
  extension, before any condition, so it ended in `ExceptionInInitializerError`. The class now
  carries `@Testcontainers(disabledWithoutDocker = true)` and starts the engine, and reads its
  addresses, only where `DockerClientFactory` finds Docker. With the Docker socket hidden in a mount
  namespace its 3 tests are skipped and the build is green. The promise is held by a new check,
  `EngineTestsWithoutDockerTest` in the coverage gate: every test class which creates an
  `EngineUnderTest` carries the annotation.
- **The decision's result was read from a snapshot taken before the decision ran** (Copilot 1,
  Major). The engine shows an instance as soon as it created it, before it ran the two business
  rule tasks in front of `Prepare`, and the case read the variables from the first answer it got.
  It now waits for the job the engine holds for that instance (a new endpoint
  `introspect/jobs/{instanceKey}`), which exists only after both decisions ran, and reads the
  instance again for its assertions.
- **The job wait matched any job of the type** (Claude 2, Minor): it now waits for the WARN line
  naming the key of that instance's job; fit-check item 10 and the note under S6.5.1 say so.
- **The engine's lines were dropped in the Quarkus test JVM** (Claude 3, Minor): the module logs
  through JBoss LogManager, which the `logback-test.xml` of the other modules does not reach, so the
  test sets the `zenbpm-engine` logger to DEBUG itself. A forced failure replayed 0 lines of the
  engine before and 112 after.
- **GAPS 27 did not name GAPS 7** (Claude 4, Minor): the business rule task drops an unmapped result
  through the same `PropagateOnlyMappedOutputs` as job completion. Row 27 and its draft name GAPS 7
  and the maintainers' leaning (open question 4); E13.2 says that a change taken up there covers
  the business rule task or says that it does not.
- **Engine answers checked by substring** (Claude 5, Nit): the Quarkus test reads the engine with
  a status-checked GET, parses the answer and asserts `totalCount`, the version and the decision's
  key as values. Moving that read into `engine-test-support` for `ZenBpmDeploymentIT` as well is
  left to E6 (note under S6.5.1), where the engine is read more often.

Seen to fail, each restored afterwards, against a green unmutated run: the annotation removed from
`ZenBpmWorkflowLifecycleTest` (killed by
`EngineTestsWithoutDockerTest#everyTestClassStartingTheEngineIsSkippedWithoutDocker`, naming the
file); the level of `zenbpm-engine` left alone (a forced failure replays no engine line). The
unguarded static start is the code the reviews ran without Docker: it errors the class.

`TZ=Etc/UTC mvn clean install` on OpenJDK 25.0.2 on the review fixes builds green with every
integration test run and none skipped (engine test support 12 unit and 3 integration tests, core
779 unit and 70 integration tests, Spring Boot 9 unit and 6 integration tests, smoke test 35,
Quarkus runtime 9, Quarkus deployment 46, Quarkus integration tests 3, coverage gate 37;
`ZenBpmDeploymentAndStartIT` 4, `ZenBpmWorkflowLifecycleTest` 3, `EngineTestsWithoutDockerTest`
2). The gate measures 100 % of 18,092 instructions for Spring Boot and 100 % of 17,970 for Quarkus,
and `bin/check-deploy-safety.sh` passes both runs.

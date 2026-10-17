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

- [ ] **Depends on:** S5.2.1.

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

**Acceptance criteria**

- [ ] The task probes never send a command (fake server asserts GET only).

## F5.3 Exemplary end-to-end start

### S5.3.1 The two-phase start on both platforms

- [ ] **Depends on:** S5.2.2.

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

**Acceptance criteria**

- [ ] Both platforms prove the two-phase start against the real engine.
- [ ] The Quarkus coverage report shows `ZenBpmProcessService.phaseTwo` covered.

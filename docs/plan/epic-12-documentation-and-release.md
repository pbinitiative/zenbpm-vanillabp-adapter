# E12 - Documentation and release

**Goal.** A user finds everything on the wiki, a contributor everything in the README and the decision
log, the blueprints run on ZenBPM, Renovate keeps the engine pin honest, and `2.0.0` is published.

Rule of the `vanillabp-conventions` skill: every sentence promising behaviour names the test which
holds it or says it is an assumption and what would disprove it.

## S12.1.1 Wiki and README

- [x] **Depends on:** E11.

Wiki (`zenbpm-vanillabp-adapter.wiki`): `Home` (getting started per platform, minimal configuration
= one `rest-address`, the outbox requirement, what a handler may assume about its thread with the
job lock the adapter sizes by `job-timeout` and renews, and the leader-change residual, the engine version tested), `Configuration` (every key of the architecture table
with level and default; the BPMN model for ZenBPM: `zenbpm:taskDefinition type` names the task
definition, user tasks by type, no correlation key has to be modelled, what reaches the engine;
keeping workflow modules apart: `by-adapter` stays the default and is refused while deploying, so
every application chooses `use-prefix` or `none` - one line per adapter id, e.g.
`vanillabp.adapters.zenbpm.name-clash-avoidance: use-prefix` (decision 24); `Home` says so too,
because it is the first thing an application runs into; timeouts and worker settings; boot
behaviour; what an operator gets to see), `Deviations` (from
`architecture/03-deviations-and-gaps.md`, each entry linking the README section), `Engine-APIs`
(every REST endpoint and the stream the adapter uses, when and why, what it never calls - the page
to hand to whoever puts a proxy in front), `_Footer`.

README: status, supported engine version (tested, not newer), coordinates, configuration, behaviour
per section of the C8 README structure with ZenBPM's facts (deployment, two-phase start, the probes:
the instance key from the start row, which may only confirm an active workflow, then the
business-key search, decision 25; the start marker and what it costs, decision 26; task processing
with PATCH-then-complete, the lock and the slots, failures, retries and incidents, decision 27; the
workflow id, decision 28; messages without buffering, user-task polling, markers, versions, viewer,
testing), the decision log pointer, known deviations with the `GAPS.md` numbers (GAPS 1, 5, 6, 9,
11, 15 and 22 as the draft now reads them; deviation 14), building, test coverage.
`DECISIONS.md` completed with every entry of the draft the code cites; `GAPS.md` completed;
`UPGRADE.md` gets its first entry when something breaks.

*Note from S3.1.1, 2026-10-15.* The `Configuration` page says the difference between the platforms
for a misspelt key under `vanillabp.adapters.<id>.`: Spring Boot ignores it, so the adapter id
stays unconfigured and the start warns about the key it misses
(`ZenBpmStartupValidationBootTest#aMisspeltKeyIsIgnored`); Quarkus fails the start naming it
(S3.2.1's `ZenBpmUnknownKeyTest`), unless another extension of the application declares a
build-time configuration root under `vanillabp`, as the Camunda 8 adapter does: then Quarkus too
ignores the key, warning only that it does, and the start warns about the key the adapter misses
(found in the reviews of S3.2.1 with a scratch boot beside Camunda 8's extension). The page says
so, because an application moved from Camunda 8 to ZenBPM has both extensions while it migrates.
Until the page exists, the javadoc of `VanillaBpZenBpmProperties` says it.

*Note from S3.2.1, 2026-10-16.* Two more differences of the platform belong on the same page. On
Quarkus an application whose classpath holds the adapter but which configures adapter ids of
another type only does not start: the platform wants an id of every adapter extension on the
classpath and names `vanillabp.adapters.*.type=zenbpm` (`ZenBpmWithoutAdapterIdsTest`); Spring Boot
starts it, and the adapter has nothing. And on Quarkus every configured id has to be listed in
`vanillabp.prioritized-adapters`. An application which configures nothing starts on both platforms
with the derived id `zenbpm`.

*Note from S3.3.1, 2026-10-16.* The `Configuration` page says when two adapter ids of type
`zenbpm` may name one `rest-address`: only where their `name-clash-avoidance` at the adapter level
differs, which is the setup of a migration from one mode to the other (new id first in
`prioritized-adapters`). With the same mode they reach the engine with the same identifiers, and
the start ends naming both ids, the engine and the keys `rest-address` and `name-clash-avoidance`
of each (`ZenBpmInstanceIdentityTest`, `ZenBpmDistinctInstancesBootTest`,
`ZenBpmOneEngineOneModeTest`). There is no `shared-engine` key; Camunda 8 compares the same way.
The page also says what is not compared: an id without a valid `rest-address` (an id which boots
degraded because of another key is compared), a mode set for a workflow module only, and two
addresses of one engine, such as an alias or a proxy, which the check cannot know to be one.

*Note from S7.1.2, 2026-10-19.* The `Configuration` page names `message-start-lookup` (`start-row`,
the default, `scan`, `refuse`) and `message-start-lookup-max-instances` (`1000`), and the
`Deviations` page names, under GAPS 1, what the start row does not cover: an application without a
delivery log, the moments between publishing a start message and the commit of its marker's
report, and a start sent again after its answer was lost; and how long the row lives,
`vanillabp.delivery.workflow-start-retention` (`P30D` by default, `0` keeps rows for good) and
`vanillabp.delivery.keep-workflow-start-while-aggregate-exists`. The README says it until the
pages exist. GAPS 30 (start messages of one name at the same moment lose starts, decision 47)
belongs on the same page: an application on several nodes may lose such a start unseen.

*Note from S11.4.1, 2026-10-21.* The wiki gets a page `Native images`, which takes its content
from the README's section "Native image" (written by S11.4.1, because no batch before this story
writes the wiki): that an application needs nothing of its own, that on Quarkus the job stream
runs on `grpc-netty` over Quarkus' Netty extension and Netty in the version of the Quarkus platform
(decision 61), what the extension registers, how the native build of `quarkus/native-image-tests`
is run, and that the meters are tested in the JVM only. Each claim keeps the test the README names
for it (`ZenBpmNativeImageIT`, `ZenBpmNativeImageProcessorTest`, `QuarkusNettyTest`).

**Acceptance criteria**: every claim names its test; a reviewer following the wiki alone boots an
application against a fresh engine container.

*Fit check, 2026-10-22, decided by the Implementor where it says so.* Checked against `main` at
`dc2ba7f`: the README, `DECISIONS.md`, `GAPS.md`, `AGENTS.md`, the drafts
`architecture/02-design-decisions.md` and `03-deviations-and-gaps.md`, section 6 of
`architecture/01-architecture.md`, the configuration overlays of both platforms with the core's
`ZenBpmAdapterConfiguration`, `ZenBpmRestClient` with the two copies of the engine's contract, and
the coverage gate's tests which read the README; the Camunda 8 adapter's README and wiki (`Home`,
`Configuration`, `Deviations`, `Cluster-APIs`, `_Footer`) as the template; the pages of the VanillaBP
wiki the new pages link, whose anchors were checked by hand; the platform's
`MigrationAdapterProperties#normalize` and `PhaseTwoOutboxProperties`; and the engine at the pin
`bdd795a3`, exported from the checkout, for the gaps 3, 6, 7, 15 and 17. What did not fit, and what
was decided:

1. **Where the pages live** (open question 1). As the brief answered in place of the maintainer:
   the pages are written in `wiki/` of this repository, one file per page under the wiki's name
   (`Home.md`, `Configuration.md`, `Deviations.md`, `Engine-APIs.md`, `Native-images.md`,
   `_Footer.md`), reviewed and checked in the pull request, and after the merge the orchestrator
   copies exactly the merged `wiki/` into `pbinitiative/zenbpm-vanillabp-adapter.wiki` and pushes
   it. Decision 63. The wiki checkout was not touched. The README's new section "The wiki", a
   section of `AGENTS.md` and the footer of every page say how the pages are published. Links
   between pages use the wiki's form, a link into the repository a full address of `main`.
2. **"Every claim names its test", made mechanical** (open question 2). Decided by the Implementor
   as the brief recommends: `DocumentationTest` in the coverage gate (10 tests) reads the README and
   every page and fails where a test they name (`SomeTest`, `SomeIT`, `SomeTest#method`, or a
   backticked `#method` of a class named before it in the same paragraph) is declared by no source
   of the repository; where a link leads to no page, file or heading (the anchors as GitHub makes
   them); where `Deviations` has no heading `### GAPS <n>:` for a row of `GAPS.md`, in its order, or
   a document names a gap `GAPS.md` has no row of; where the table on `Configuration` and the keys
   the Spring Boot overlay reads differ in a key or a level (the walk of the overlays moved from
   `QuarkusOverlayTest` into a helper `OverlayKeys`, which both tests use, so `QuarkusOverlayTest`
   keeps the Quarkus overlay equal to the same set); where `Engine-APIs` misses a call of the REST
   client, an endpoint of the engine's contract copy or the job stream's call, or names a call the
   client does not make; where the coordinates, the version and the Java of `Home` are not the
   build's, or the Spring Boot artifact does not hand an application the core and VanillaBP's
   Spring Boot integration; and where a page names a meter the README's table (which
   `MicrometerZenBpmMetricsTest` holds to the code) does not. `EnginePinTest#theWikiNamesThePin`
   holds `Home` to the pin. What it does not check: whether a sentence says what the test it names
   holds, and whether a sentence which promises something names a test at all (both read by hand,
   see the acceptance below); links outside this repository (the VanillaBP wiki, checked by hand on
   2026-10-22); the VanillaBP keys the pages name.
3. **The reviewer's boot, held by a test** (open question 3). Decided as the brief recommends, on
   both platforms: `ZenBpmHomePageIT` (Spring Boot) and `ZenBpmHomePageTest` (Quarkus, prod mode)
   read the engine command of `Home`'s section "An engine to try it against" and the YAML of its
   section "Minimal configuration" from `wiki/Home.md`, start the engine with the command's ports
   and no other setting, and boot an application of the module with nothing but that YAML (the
   Quarkus one beside its own database settings), which deploys its workflow module, starts a
   workflow after the commit and runs its task over the job stream. Two departures, both said on
   `Home` and in the tests' javadoc: the image is the build's (`EngineUnderTest.image()`), so the
   nightly's `latest-engine` run tests the newest engine with it (decision 62), and
   `EnginePinTest` holds the page's image to the pin; and the tests point the page's
   `rest-address` at the port Testcontainers publishes and set `grpc-address`, because the
   published gRPC port is not 9090. The Quarkus boot showed what the S3.2.1 note above left open:
   one adapter id needs no `prioritized-adapters` on Quarkus either. The platform defaults the list
   where exactly one adapter is configured (`MigrationAdapterProperties#normalize`, the same code on
   both platforms), so the note's "every configured id has to be listed" holds for several ids, on
   both platforms; `Configuration` says so under "Several adapter ids".
4. **The engine version the pages name** (open question 4). `Home` names the pinned commit and the
   image digest, "tested, not newer", and says nowhere that a released engine version is supported;
   `EnginePinTest#theWikiNamesThePin` fails where `Home` misses the pin or any page names another
   commit or image. Step 5 of moving the pin in the README names `wiki/Home.md`. The version and the
   Java of `Home` come from the root POM (`DocumentationTest#theHomePageNamesTheBuildsVersions`).
5. **The VanillaBP wiki** (open question 5). The pages link its concepts; they say nothing about a
   row on `BPMS-adapters`. No VanillaBP repository or wiki was changed.
6. **Licence of the engine copies** (open question 6). The README's licence section is unchanged and
   says the question is open.
7. **Decisions.** Read against the code: draft 4 (the model rewrite) holds, apart from two items
   of the draft (an input mapping per `@TaskParam` for every job, a `zenbpm:in businessKey` on a
   call of the same aggregate), which the code writes only into a multi-instance body, because the
   engine hands a job the variables of its scope and a called instance its caller's business key by
   itself; draft 7 (shared values before the completion) holds, with decisions 34 and 35 saying
   where and how often; draft 11 (ownership by the scoped process id) holds for every probe and for
   the routing of a job. All three are entered under their numbers as the code works, marked as
   entered by the pipeline from the plan's draft; no supersession was needed, because none of them
   was ever entered. Drafts 2, 5, 6 and 8 stay drafts, superseded by 24 to 27, and the draft file got
   a note saying so. Every decision any code cites is entered (checked with a search of every
   source file). The example `see decision 7` of `AGENTS.md` resolves now and stays. Decision 63 is
   new; the next free number is 64.
8. **Gaps.** At the pin: the gaps 3 (ten active jobs per client) and 6 (no retries) are closed by
   the engine commits `071460cc` and `bc3384b3`, which the pin contains (`max_active_jobs` per
   subscription counted per client and type in `capacityLocked`; `JobFailByKey` with `retries` and
   `retryBackoff`), and are entered struck through and marked closed; 7 (job outputs only through
   mappings: `JobCompleteByKey` hands them to `PropagateOnlyMappedOutputs`), 15 (`WaitingJob` names
   no process, version, business key or element instance) and 17 (no request id on a start, no
   unique index on `business_key`) are true and entered with the evidence, 7 and 17 also measured
   by two new tests of `ZenBpmRestClientContractIT`,
   `#aCompletionWithoutAnOutputMappingWritesNothingIntoTheInstance` and
   `#twoStartsUnderOneBusinessKeyCreateTwoInstances`. Three rows still said *planned* for merged
   code and lost it: 12 (the wiki names the proxy: `Deviations` and `Engine-APIs` do now), 13
   (`SEND_SIGNAL` is not offered, `ZenBpmProcessServiceTest#everyRequiredOperationIsServed`) and 22
   (every delivery reports the root as the workflow id,
   `ZenBpmTaskProcessingIT#aTaskInASubProcessNamesTheRootAndTheGatewayBehindItSeesTheValues`). The
   header of `GAPS.md` says where users read the list and that a closed gap keeps its row. The next
   free number is still 34.
9. **The keys.** Section 6 of the architecture matches the code: `shared-engine` and
   `max-redeliveries` are gone from it already, and every key of the overlays stands there with its
   levels and default. The users' table on `Configuration` is compared with the code, not with the
   architecture (`DocumentationTest#everyKeyStandsOnTheConfigurationPage`); section 6 got a line
   naming it.
10. **The javadoc of `VanillaBpZenBpmProperties`** (the note of S3.1.1). Decided by the Implementor:
    it stays on both platforms. It says what the class's own binding does with a key it does not
    know, which is code reasoning a contributor needs where the overlay is changed, and its
    sentences are held by the same tests as the page (`ZenBpmStartupValidationBootTest#aMisspeltKeyIsIgnored`,
    `ZenBpmUnknownKeyTest#aMisspeltKeyFailsTheStart`). The users' version is on `Configuration`,
    "A key the adapter does not know", the case beside the Camunda 8 extension included, which the
    page marks as seen in a boot and held by no test of this repository.
11. **The README.** Its section `Status` (lines 20 to 402 of `main`) became a short `Status`, and its
    content moved, sentence by sentence, into `Configuration` and the subsections of `Behavior`,
    which follow the sections of the Camunda 8 README with ZenBPM's facts: deployment, two-phase
    start, the probes, the workflow id, the start marker, task processing, tasks left open, a process
    declared without a model, the shutdown, user tasks, the end of a workflow, multi-instance,
    messages, starting by a message, pushing, versions, viewing, which phase-two failures are
    repeated (moved under `Behavior`) and testing. New are `Documentation`, `Dependencies`,
    `Configuration`, `Decision log`, `Known deviations` (the gaps a user meets first with their
    numbers, and the deviation of expressions which is no gap) and `The wiki`. Every test the old
    README named is still named; six citations of a method alone, which `DocumentationTest` reads as
    a method of a class named before it in the same paragraph, are written out with their class now,
    because four of them named a method of `ZenBpmLifecycleIT` after a citation of
    `ZenBpmWorkflowLifecycleTest` or `ZenBpmMarkerTasksIT`. The gate's tests of the README
    (`EnginePinTest`, `NightlyWorkflowTest`, `WorkflowPermissionsTest`, `PublicationSafetyTest`,
    `EngineTestsWithoutDockerTest`) and the core's (`ZenBpmErrorsTest`, `MicrometerZenBpmMetricsTest`)
    stayed green. The line saying the user documentation is not written is gone.
12. **The notes for this story elsewhere in the plan**, each now a sentence with its test: the keys
    and the javadoc stand-in of epic 3 (`Configuration`, "Every key" and "A key the adapter does not
    know"); decision 24's sentence for the first page, epic 4 (`Home`, "Minimal configuration");
    GAPS 26 as an entry of `Deviations`, epic 5; the two VanillaBP settings which decide the cost of
    the open-task probe, epic 6 (`Configuration`, the table of VanillaBP's keys), and the incident at
    the first exception without `retries` (`Configuration`, the BPMN model; `Deviations`, GAPS 6);
    `message-start-lookup`, what the start row does not cover and how long it lives, epic 7
    (`Configuration`, "Starting a workflow by a message"; `Deviations`, GAPS 1 and 30); the window of
    a crash between the aggregate's commit and the write of its id, which Camunda 8 has too, epic 9
    (`Deviations`, GAPS 8); no page quotes a message of the adapter, epic 11; the page `Native
    images` with the tests of the README's section, epic 11; the notes of S3.1.1, S3.2.1, S3.3.1,
    S7.1.2 and S11.4.1 above (`Configuration` and `Native images`); `DECISIONS.md` decision 24 and
    the header of `GAPS.md`.
13. **`UPGRADE.md`**: nothing is released, so no entry.
14. **The policy items.** The pages name the snapshot coordinates `2.0.0-SNAPSHOT` and promise no
    release (S12.4.1); they say nothing about the blueprints (S12.2.1) or a row on VanillaBP's wiki
    (S12.3.1); `Deviations` describes each gap with the adapter's behaviour today and says what would
    close it, without an engine story or a date (E13).

**Acceptance evidence, 2026-10-22.** Everything here ran on this machine (OpenJDK 25.0.2, Maven
3.9.12, Docker 29.1, 14 cores).

- [x] Every claim names its test. Mechanically: `DocumentationTest` (10 tests, new) finds every test
  the README and the six pages name, and `EnginePinTest#theWikiNamesThePin` (new) the pin on
  `Home`, both green in the clean build below. By hand: every page was read sentence by sentence;
  a sentence which promises behaviour names its test, apart from four which say why they cannot:
  the unknown key beside the Camunda 8 extension (needs that extension), the leader change of GAPS
  2 (one node of the engine has one leader), the files a refused deployment leaves behind (follows
  from one file per request) and what the adapter does not detect (GAPS 23, 27). Six citations of
  the old README were written out with their class (fit-check point 11), and several citations of
  the pages' first drafts which named a method under the wrong class were found by looking each one
  up and corrected before the pages were committed.
- [x] A reviewer following the wiki alone boots an application against a fresh engine container:
  `ZenBpmHomePageIT` (2 tests, Spring Boot) and `ZenBpmHomePageTest` (1 test, Quarkus prod mode)
  start a fresh engine with the ports of `Home`'s command and boot an application with the YAML of
  its section "Minimal configuration", read from the page; the workflow runs its task over the job
  stream (Spring Boot) and to its end (Quarkus).
- [x] `DECISIONS.md` completed: 4, 7 and 11 entered, 63 new (fit-check points 1 and 7). `GAPS.md`
  completed: 3 and 6 closed, 7, 15 and 17 entered, two new contract ITs, no *planned* left for merged
  code (point 8). `UPGRADE.md` unchanged (point 13).
- Mutants, all killed (29): of `DocumentationTest`, a cited method renamed, a cited class renamed, a
  bare method of a class not before it, a key row dropped, a stale key `max-redeliveries` added, a
  level dropped from `retry-backoff`, the entry of GAPS 33 dropped, `GAPS 99` named, a page link,
  a heading anchor of a page, an anchor of the README, a file of the repository, an anchor of the
  README's own, an anchor of `DECISIONS.md` and a wiki address of the README broken, the row of
  `POST /v1/messages` dropped, an endpoint of the never-list dropped, a call the client does not
  make added, the job stream dropped, the version `2.0.0` and Java 17 on `Home`, an unknown meter,
  `vanillabp-spring-boot-integration` in scope `provided`, a page more in `wiki/`; of `EnginePinTest`,
  another commit on `Home` and another image on `Native-images`; of the boot tests, `by-adapter` in
  `Home`'s YAML (both platforms end the start) and port 9091 in its command (Spring Boot). Two link
  mutants survived the first version of the link check, which removed code spans with a pattern
  reaching from one span to the next and swallowing links between them; fixed, then killed. The
  unchanged pages pass.
- Clean build (`TZ=Etc/UTC mvn clean install`, Docker ITs included, none skipped, 18:07 min): engine
  test support 12 + 5, core 1,275 + 106 (two new contract ITs), Spring Boot 14 + 97 (two of
  `ZenBpmHomePageIT`), smoke test 42 + 1, election integration test 8, Quarkus runtime 13,
  deployment 60, integration tests 26 (`ZenBpmHomePageTest`), gate 63 (`DocumentationTest` 10 and
  `EnginePinTest#theWikiNamesThePin` new); coverage 100 % on both platforms (0 of 38,473
  instructions missed on Spring Boot, 0 of 38,470 on Quarkus, unchanged: no production code
  changed).
- `bin/check-deploy-safety.sh` (the gate module changed): green at the first run, 13 files deployed
  by the green build, nothing by the red gate.

**Answers to two reviews of e12-s12.1.1, 2026-10-22** (one by Claude, approve with minor fixes: 5
Minor, 5 Nit; one by Copilot, changes requested: 1 Major). Every claim was checked in the code of
the branch, in the platform's code and against the pages; all eleven are valid, Claude's ninth for
two of its four links. No decision and no GAPS row was needed.

- **`Home`'s engine took the application's port** (Copilot 1, Major). The command published the
  REST API on port 8080 of the host and `rest-address` pointed there, where Spring Boot and Quarkus
  serve an application by default; Copilot started the pinned image as the page says and a Quarkus
  application of the page test beside it, which ended with "Port 8080 seems to be in use", and with
  `18080:8080` the same jar booted and ran a workflow. The boot tests could not see it:
  Testcontainers publishes on ports of its own, and the Quarkus test sets `quarkus.http.port`. Now
  the command is `-p 18080:8080 -p 9090:9090`, `rest-address` is `http://localhost:18080`, and the
  page says why.
  `DocumentationTest#theEngineOfThePageHomeLeavesTheApplicationItsPort` (new) holds the command: it
  publishes the container's 8080 and 9090, no host port 8080, the job stream on host port 9090
  (where the adapter derives it), and the minimal configuration's `rest-address` names the host port
  of 8080. `ZenBpmHomePageIT` maps the page's address through the command to the container's port.
  Mutants, all killed: the old `8080:8080` with its address, `rest-address` on 18081, the job
  stream on host port 9091.
- **One custom adapter id was said to need `prioritized-adapters`** (Claude 1, Minor). The
  platform's `MigrationAdapterProperties#normalize` sets the list to the only configured id, as
  `Configuration` and fit-check point 3 say. `Home` now asks for `type`, and for the list once
  several ids are configured, and links `Configuration`; no test boots one custom id without the
  list, so the example still writes it.
- **`Engine-APIs` named wrong occasions** (Claude 2, Minor). Read from the call sites:
  `GET /v1/process-definitions/{key}/statistics` serves only the open-task count
  (`ZenBpmProcessService#openTaskCount`); the start counts the workflows of an older version with
  `GET /v1/process-instances` and a page of one (`ZenBpmProcessVersions`); the lock renewal calls
  only `extend-lock`. `GET /v1/jobs/{key}` was missing the open-task probe after a delivery, a push
  from a task and the look after a BPMN error; `GET /v1/process-definitions/{key}` the read after
  each deployment and the viewer; `GET /v1/process-instances/{key}` the lookup by the start row's
  key and the viewer; and the job stream's `max_active_jobs` counts the parked tasks too (decision
  37, `ZenBpmJobStreamTest#aParkedJobRaisesTheCapOfItsType`). The six rows are rewritten.
- **`Deviations` cited tests of other claims** (Claude 3, Minor). GAPS 24 cited a refusal of two
  files of one module; it now cites
  `ZenBpmDeploymentServiceTest#deployingChecksForCollidingProcessIdsFirst`, which holds that the
  declared processes go to VanillaBP's check for colliding identifiers. GAPS 33 cited the engine's
  contract only; it now cites the viewer's
  `ZenBpmWorkflowViewerTest#aMultiInstanceTaskAloneAndACancelledWorkflow` beside it.
- **GAPS 26 put a handler's answer into the outbox** (Claude 4, Minor). A `@WorkflowTask` method's
  answer which the engine refuses leaves the job to its lock (`ZenBpmJobHandler`,
  `ZenBpmJobHandlerTest#anUpdateOrAFailureRefusedForGoodLeavesTheJobToItsLock`); only a start and a
  task completed later go through the outbox. The entry says both.
- **GAPS 8 put an end marker in front of every end event** (Claude 5, Minor). The entry takes the
  scope of `GAPS.md` and decision 52 (none, message and terminate end events of the top-level
  process, where the application listens to the end), and its two sentences without a test now cite
  `ZenBpmMarkerTasksTest#aMessageEndEventGetsItsMarkerInFront` and
  `ZenBpmWorkflowEndedHandlerTest#branchesWhichEndAtOnceReportTheEnd`.
- **Nits** (Claude 6 to 10). The intro of `Deviations` no longer promises a closing sentence in
  every entry: `GAPS.md` names one for every gap. GAPS 1 says what the viewer does not know before
  the `scan` sentence, which now names the probe's and the viewer's tests. GAPS 7 costs one update
  per instance up to the workflow, not one request per task. GAPS 12 links the README's "Boot
  behavior" and GAPS 15 "The workflow id"; the links of GAPS 24 and 27 to "Deployment" stay, because
  that section discusses identifiers and business rule tasks. The contract IT of GAPS 7 also asserts
  that the instance it reads holds the start's `orderId`.

Builds after the fixes (OpenJDK 25.0.2, Docker ITs, none skipped):

- `TZ=Etc/UTC mvn clean install`, 18:23 min, green: engine test support 12 + 5, core 1,275 + 106
  (`ZenBpmRestClientContractIT` 34), Spring Boot 14 + 97 (`ZenBpmHomePageIT` 2), smoke test
  42 + 1, election integration test 8, Quarkus runtime 13, deployment 60, integration tests 26
  (`ZenBpmHomePageTest` 1), gate 64 (`DocumentationTest` 11); coverage 100 % on both platforms (0
  of 38,473 instructions missed on Spring Boot, 0 of 38,470 on Quarkus).
- `bin/check-deploy-safety.sh`: green at the first run, 13 files deployed by the green build,
  nothing by the red gate.

## S12.2.1 Blueprints profile `-Pzenbpm` (a pull request to the VanillaBP project)

- [ ] **Depends on:** S12.1.1 (a released or snapshot artifact a foreign build can resolve).

`blueprints` belongs to `vanillabp-blueprints`, so this story is a pull request there, prepared and
tested in the workspace checkout: a fourth profile next to `camunda7`, `camunda8`,
`process-engine-api` selecting `org.pbinitiative.zenbpm:zenbpm-vanillabp-adapter-spring-boot`
resp. the Quarkus pair, `resources-location` `<module>/processes/zenbpm` where a model differs (most
blueprint models use `zeebe:` extensions which the engine reads; a model with a signal or a
conditional event needs a ZenBPM variant or is documented as not runnable there), a
`bin/zenbpm_engine.sh` starting the container for CI like `camunda8_cluster.sh`, and the CI matrix
entry. `blueprints/README.md` and `CONTRIBUTING.md` name the profile;
`blueprints-organisation-page/AGENTS.md` if it lists BPMS. The blueprints' CI has to read
`pbinitiative`'s packages (or Maven Central once S12.4.1 publishes there), which the pull request
says.

**Acceptance criteria**: `./mvnw install -Pzenbpm` is green in the blueprints CI after the pull
request is merged; until then, green locally against the workspace checkout.

*Note from S11.6.1, 2026-10-22.* The pipeline's policy turns this story into an overlay kept in this
repository and a job of the adapter's nightly, `.github/workflows/nightly.yaml`. The nightly is
shaped for it: its jobs do not depend on one another, and `NightlyWorkflowTest` in the coverage
gate asks every job for a description in the README's section "What CI runs" (a bullet naming the
job) and for a last step with `if: always()` which appends a line to `$GITHUB_STEP_SUMMARY`. The
new job adds both, reads with the workflow's `GITHUB_TOKEN` only (the test refuses any other secret
and any write permission), and must not set `continue-on-error`, which the test allows for
`latest-engine` only, unless this story decides otherwise and changes the test with the README.

## S12.3.1 Renovate, local workspace notes and the VanillaBP wiki row

- [ ] **Depends on:** S12.1.1.

`renovate.json` reviewed against the finished dependency tree (the VanillaBP platform version, the
Spring Boot and Quarkus BOMs which have to move together with the platform). Once the engine pin is
a release tag (S12.4.1), a `customManagers` regex entry follows `pbinitiative/zenbpm`'s GitHub
releases (datasource `github-releases`) for the pin, and the pull request it opens is completed by
hand with the two contract copies of S1.2.1. The local workspace notes of S1.1.2 (root `AGENTS.md`,
the two skill edits) re-read against the finished adapter; they stay local, because the workspace
superproject belongs to the VanillaBP project and no pull request to it is planned. One request to
the VanillaBP project: a row for this adapter on the platform wiki's `BPMS-adapters` page linking
`pbinitiative/zenbpm-vanillabp-adapter`.

## S12.4.1 Release workflow and the first release

- [ ] **Depends on:** S12.2.1, S12.3.1. **Open question 17** decides the target.

Before the first release the engine pin leaves `main`: the maintainers tag an engine release
containing the pinned commit (or a newer one, re-pinned by the S1.2.1 steps), and the root POM gets
`zenbpm.version` = that tag with `zenbpm.image` = `ghcr.io/pbinitiative/zenbpm:${zenbpm.version}`;
`zenbpm.commit` stays as the commit the tag points at. An adapter release never names a `dev` digest
as its supported engine.

`.github/workflows/release.yaml`, `on: push: {tags: ['v*']}`: checks out the tag, sets `-Drevision`
from the tag (`v2.0.0` -> `2.0.0`), runs the full build with Docker ITs, `mvn deploy` to the release
target - GitHub Packages of `pbinitiative` at least, Maven Central under `org.pbinitiative` if the
organisation's namespace and signing key are available (the Java client is published there, so the
process exists) - and creates the GitHub release with the tag's notes. The wiki and README name
`2.0.0` and the engine version tested; the blueprints' `zenbpm-vanillabp-adapter.version` points at
it. A `UPGRADE.md` header says there is nothing to upgrade from.

**Acceptance criteria**: an application depending on
`org.pbinitiative.zenbpm:zenbpm-vanillabp-adapter-spring-boot:2.0.0` boots against the pinned
engine RELEASE image; the release workflow is the only path a release takes.

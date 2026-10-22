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

**Answers to CodeRabbit on pull request #83, 2026-10-22** (two inline comments, one nitpick, its
generic docstring-coverage warning not acted on, as in every batch before). All three are valid.

- **`EnginePinTest` did not read the image of `Home`'s command** (nitpick). Its pattern matches an
  image by digest only, so a command starting `ghcr.io/pbinitiative/zenbpm:latest` passed while the
  pinned digest stood elsewhere on the page. `EnginePinTest#theWikiNamesThePin` now also asserts
  that every `docker run` of `Home` starts the pinned image. Mutant: the command on `:latest`;
  killed.
- **The README's `Status` named no test** (README line 29). The paragraph sums up what the sections
  below describe; it now says that each item is described under `Behavior` and `Native image` with
  the tests which hold it, rather than repeating them.
- **"A start message no start event takes"** (README line 511) reads as broken English; now "which
  no start event takes".
- Builds: `TZ=Etc/UTC mvn clean install`, 17:57 min, green, the same counts as above, none
  skipped, coverage 100 % on both platforms; `bin/check-deploy-safety.sh` green at the first run.

**S12.1.1 done 2026-10-22**, merged to `main` as `42b3fb6` (pull request #83: story `6d4004e`,
review fixes `2bca20f`, CodeRabbit fixes `e3aa12f`). GitHub keeps run logs for 90 days, so what the
runs showed is written here as well:

- `Checks` on `42b3fb6`, run
  [39758279637](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/actions/runs/39758279637):
  green on both JDKs at the first attempt. `bin/check-engine-copies.sh` reported both copies as the
  engine's files of `bdd795a3...`. On both JDKs every module ran its tests, none skipped: engine
  test support 12 + 5, core 1,275 + 106 (`ZenBpmRestClientContractIT` 34), Spring Boot 14 + 97
  (`ZenBpmHomePageIT` 2), smoke test 42 + 1, election integration test 8, Quarkus runtime 13,
  deployment 60, integration tests 26 (`ZenBpmHomePageTest` 1), gate 64 (`DocumentationTest` 11,
  `EnginePinTest` 4). The gate measured 100 %: 0 of 38,473 (Spring Boot) and 0 of 38,470 (Quarkus)
  instructions missed on JDK 25, 0 of 38,461 and 0 of 38,458 on JDK 21.
- `Publish snapshots` on `42b3fb6`, run
  [39758279449](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/actions/runs/39758279449):
  green. The snapshot it published, `2.0.0-20261022.094849-79`, holds the core's 266 classes as
  before (the story adds no production class) and no test class, fake, fixture or BPMN file.
- Both report pages (`spring-boot-report/index.html`, `quarkus-report/index.html`) answer HTTP 200
  and show 0 of 38,473 (Spring Boot) and 0 of 38,470 (Quarkus) instructions missed.
- The wiki: the six pages of `wiki/` at `42b3fb6` were copied into
  `pbinitiative/zenbpm-vanillabp-adapter.wiki` and pushed as its commit `4f1397b` (decision 63),
  replacing the placeholder `Home`. `Home`, `Configuration`, `Deviations`, `Engine-APIs` and
  `Native-images` answer HTTP 200 on github.com, and `Home` shows the engine command on port 18080.

## S12.2.1 Blueprints profile `-Pzenbpm` (an overlay in this repository, built by the nightly)

- [x] **Depends on:** S12.1.1 (a released or snapshot artifact a foreign build can resolve).

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

*Fit check, 2026-10-22, decided by the Implementor where it says so.* Checked against `main` at
`bcb3c8e`: the nightly workflow with `NightlyWorkflowTest` and `WorkflowPermissionsTest`, the
README's "What CI runs", `EnginePinTest`, `DocumentationTest` and `BpmnDiagramTest`; the blueprints
at their `main` HEAD `7acfef0`, cloned fresh into a scratch directory (the workspace submodule is
at `a0c8e5f` and was only read): the root POM, the profiles of all 142 POMs which have one, the
142 `application-camunda8.yaml`, the 86 models and 4 decisions under `processes/camunda8`, their
`build.yaml` with `bin/camunda8_cluster.sh` as the model of the engine script, and the index
`blueprints.yaml` of `vanillabp-blueprints/.github` which says which BPMS a blueprint runs on; the
adapter's refusals of GAPS 13 and 14 and its reading of models of any namespace
(`ZenBpmModelReader`); and the engine at the pin `bdd795a3`, exported from the checkout, for the
decision results (point 6) and its boot (point 8). What did not fit, and what was decided:

1. **No pull request to VanillaBP** (the policy). The plan asked for a pull request to
   `vanillabp-blueprints`; the maintainers' policy for the autonomous run forbids it, because this
   repository's maintainers are not VanillaBP's. So the profile `zenbpm`, the engine script and the
   ZenBPM variants live in this repository as an overlay, `blueprints-overlay/`, and a new job
   `blueprints` of the nightly clones the blueprints fresh, applies the overlay and builds them
   with `-Pzenbpm` against the pinned engine. Decision 64, decided by the pipeline in place of the
   maintainers. The story's heading, row 59 of the roadmap, the milestone M6 and the ownership
   table of `docs/README.md` say so now. Nothing of the workspace's `blueprints` submodule was
   changed or built. The script's first runs installed the overlaid blueprints into the local
   Maven repository; since the reviews it builds a twin with `verify`, so it installs nothing of
   VanillaBP (see the answers to the reviews below).
2. **A generator, not a patch** (decided by the Implementor). A patch over the 142 POMs would stop
   applying at every change of a line near a hunk, also one which changes nothing for ZenBPM, and
   hand-edited copies would drift. `ZenBpmOverlay.java`, run with the build's Java as a source file,
   derives what ZenBPM needs from what each blueprint has for Camunda 8: the profile `camunda8` of
   every POM is copied as `zenbpm` (its comments and its activation by default dropped, the
   Camunda 8 adapter replaced by `zenbpm-vanillabp-adapter-spring-boot` or
   `zenbpm-vanillabp-adapter-quarkus`, `bpms` and `blueprint.bpms` set to `zenbpm`), the property
   `zenbpm-vanillabp-adapter.version` is declared beside `camunda8-adapter.version` with the
   adapter's `revision`, every `application-camunda8.yaml` gets an `application-zenbpm.yaml`, every
   other file named for Camunda 8 a copy named for ZenBPM where it names no Camunda (the one there
   is, `persistence-liquibase`'s Quarkus changelog `db/changelog-camunda8.xml` "for a build with a
   REMOTE engine", which its `application.yaml` names as `db/changelog-@bpms@.xml`; without the
   copy the twin's application did not start), and every `processes/camunda8` a
   `processes/zenbpm`. The plan's `resources-location
   <module>/processes/zenbpm` is the platform's conventional location, so nothing has to be
   configured for it. What the generator does not know ends it with a message naming the file and
   the fix (decision 64 lists the cases), so a change upstream it cannot follow turns the nightly
   red instead of building less. The 142 profiles have six shapes, all of which it translates;
   `module-bpms-migration`'s, which also names the Camunda 7 adapter, is the one it refuses.
3. **Where the pieces live** (decided by the Implementor). The plan's `bin/zenbpm_engine.sh` of
   the blueprints became this repository's `bin/zenbpm-engine.sh` (the dash of `bin/`, linted by
   `workflow-lint` like every script there), and the job's steps are one script,
   `bin/build-blueprints-on-zenbpm.sh`, which a person runs on a laptop as the job does. The pull
   request trigger of the nightly names the two scripts and `blueprints-overlay/**` besides the
   workflow, so a change of the overlay is seen working in its pull request;
   `NightlyWorkflowTest` and the README changed together. The plan's "the READMEs name the
   profile" and `blueprints-organisation-page/AGENTS.md` are VanillaBP's files; this adapter's
   README (sections "What CI runs" and "The blueprints on ZenBPM") and a new wiki page `Blueprints`
   say it instead.
4. **Which blueprints run on ZenBPM** (decided by the Implementor). The blueprints' repository does
   not say which blueprint runs on which BPMS; their CI reads the `bpms` lists of the index. The
   overlay keeps its own list, `blueprints.txt`, one line per blueprint directory, and the page
   `Blueprints` gives the reason of every blueprint which does not run;
   `BlueprintsOverlayTest#theListAndThePageBlueprintsAgree` holds the two together. Of the 35
   blueprints, 30 run (59 twins: `persistence-active-record` has a Quarkus twin only). Five do not,
   each seen failing in the run of point 9 before it was listed: `bpmn-signals` (signals, GAPS 13),
   `bpmn-bpms-initiated-start` (its signal start event is refused and its test broadcasts a signal,
   GAPS 13; its timer half alone would not be the blueprint), `bpmn-error-escalation` (escalation
   events, GAPS 14), `bpmn-adhoc-subprocess` (the ad-hoc sub-process, GAPS 14; its
   `application-camunda8.yaml` also sets `retry-backoff`, which the generator refuses to guess) and
   `module-bpms-migration` (a migration from Camunda 7 to Camunda 8, which needs both Camunda
   adapters from registries behind a token). A ZenBPM variant would cost each of them its point, so
   none was written.
5. **Two variants and one translated convention** (decided by the Implementor). A variant carries
   an XML comment saying why, and `variants.txt` the SHA-256 of the Camunda 8 model it was written
   from, so a change of that model ends the generator instead of being lost. A variant is a copy of
   a model of the blueprints, which are Apache-2.0, so its first comment names its origin and
   `SPDX-License-Identifier: Apache-2.0`, and `NOTICE` names `vanillabp-blueprints/blueprints`, as
   decision 17 has it for code. The convention: `bpmn-user-task`, `persistence-audited-aggregate`
   and `persistence-parallel-branches` name their user tasks by the `externalReference` of
   `zeebe:formDefinition` only, which the Camunda 8 adapter takes as the task definition
   (`Camunda8TaskWiring`) and this adapter does not read, so their start ended with "Task wiring
   ... is incomplete" for the task definition `user-task-type`. Instead of three more copied models,
   the generator gives such a user task the reference as its `taskDefinition type`
   (`BlueprintsOverlayTest#aUserTaskNamedByItsFormGetsItsTaskDefinition`), and `Configuration` now
   says that a form names no user task here (`ZenBpmModelReaderTest#aFormNamesNoUserTask`, new). The
   variants: `bpmn-business-rule-task`'s business rule task
   sets `resultVariable="approval"` and nothing else, so on ZenBPM the result never reached the
   process (GAPS 27) and the test waited for `approval` in vain; the variant adds
   `<zeebe:output source="=approval.approval" target="approval" />` (`24d16d09...`).
   `bpmn-multi-instance-subprocess`'s inner loop over the partners reads `=partnerIds`, a variable
   of the workflow, inside an iteration of the outer loop over the regions, which on ZenBPM starts
   with its item and what the element maps in only (GAPS 31): the inner loop ran no iteration, and
   the region was summarised without offers. The variant maps
   `<zeebe:input source="=partnerIds" target="partnerIds" />` into the sub-process (`174ad82f...`).
6. **GAPS 34, new.** The first variant mapped `=approval` and the process got
   `{"approval": "APPROVED"}`: at the pin a decision table whose one output has a name hands a
   context of its outputs, and only a table whose one output has no name hands the bare value
   (`pkg/dmn/dmn_hit_policy_evaluator.go`, `evaluateFirstRuleOutput`, `evaluateCollectOutput`,
   `collectAggregationOutput`), where Camunda 8 hands the value. Entered as GAPS 34 with its
   `Deviations` entry, measured by the new
   `ZenBpmRestClientContractIT#aDecisionTableWithOneNamedOutputHandsAContext` (fixture
   `contract-decided.bpmn` against the existing `contract-decision.dmn`). GAPS 27's entry on
   `Deviations` and the paragraph of `Configuration` point at it, because their example
   `source="=rating"` maps the whole context for such a table.
7. **Three attempts for a task without `retries`** (decided by the Implementor). The blueprints'
   models are Camunda 8 models, which give such a task three attempts; the engine's default is one,
   so `bpmn-service-task`'s test of a provider which refuses once ended in an incident. The engine
   script starts the engine with `JOBS_DEFAULT_RETRIES=3`, as `Configuration` advises for such
   models (decision 38), instead of a variant per model; the page `Blueprints` says so and
   `BlueprintsOverlayTest#theEngineGivesATaskWithoutRetriesThreeAttempts` holds the script to it.
   The other settings are those of `EngineUnderTest`; the REST API is published on host port 18080
   as on `Home`, the job stream on 9090.
8. **The engine's boot gives up now and then** (decided by the Implementor). One start in about ten
   ended with "failed to bootstrap cluster: boot timeout" after the node's first call to itself
   timed out, and the container stopped. `bin/zenbpm-engine.sh start` notices a container which
   ended and starts a fresh one, three attempts at most, as `camunda8_cluster.sh` does for its
   exporter. Not entered as a gap: it is a race of a node starting, which a fresh container does not
   meet, and the adapter's own tests have not met it.
9. **One engine per twin, and the time it takes.** The twins of a blueprint deploy the same
   processes under the same identifiers, so every twin gets an engine of its own (about five
   seconds to ready). The 59 twins took 52 minutes one after the other on this machine (3,113 s,
   OpenJDK 25, 14 cores, between 35 and 77 seconds a twin); GitHub's runner is slower, so the job
   runs them in one job with `timeout-minutes: 150` rather than a matrix,
   which would install the adapter and download every blueprint's dependencies once per matrix
   job. A first full run was spoiled by a mutant of this story's build script, which built
   twins on the same engine and ports at the same time; its results from that time were thrown
   away and the run of the acceptance below was made with nothing else on the machine's engine.
10. **No secret, no Camunda registry** (verified). With `-Pzenbpm`, Maven leaves every
    `activeByDefault` profile of a POM which has the profile `zenbpm` off: `mvn help:active-profiles
    -Pzenbpm` in `bpmn-service-task/springboot` lists `zenbpm` alone for both modules, besides the
    profile of the settings. `blueprints-overlay/settings.xml` names Maven Central and its snapshot
    repository and nothing else, so a build which still asked for a Camunda adapter would fail,
    and none did. The adapter comes from the local repository, installed from the checkout by the
    job, so open question 17 (where releases are published) does not touch the job; the job uses
    the workflow's `GITHUB_TOKEN` to pull the engine image only, as the other jobs do.
11. **The engine image is the pin.** `bin/zenbpm-engine.sh` reads `zenbpm.image` from the root POM
    on every call and names no image of its own; `EnginePinTest#theBlueprintsEngineIsThePin` runs
    `bin/zenbpm-engine.sh image` and compares it with the POM's pin, and fails on an image digest
    written into the script.
12. **The generator is formatted like the modules.** Spotless now includes
    `blueprints-overlay/*.java`, and a last step removes the blank line the formatter puts above
    the licence header of a source without a package (the source launcher of Java 25 wants the path
    of a source to match its package, so the program has none). It compiles with
    `--release 21`, the Java of the second check.

**Acceptance evidence, 2026-10-22.** Everything here ran on this machine (OpenJDK 25.0.2, Maven
3.9.12, Docker 29.1, 14 cores). The policy turns the plan's "`./mvnw install -Pzenbpm` green in the
blueprints CI" into three proofs:

- [x] The overlay applied to a fresh clone of `vanillabp-blueprints/blueprints` at its `main` HEAD
  `7acfef0827cbd7d88022aef40a9bfcf7bf7eef9d`: `ZenBpmOverlay.java` listed 59 twins of the 30
  blueprints which run and ended with 0; the five others are on the page `Blueprints` with their
  reasons (fit-check point 4).
- [x] Those twins built green with `-Pzenbpm` against the pinned engine
  (`ghcr.io/pbinitiative/zenbpm@sha256:d7808e30...`, commit `bdd795a3`), one fresh engine each:
  `bin/build-blueprints-on-zenbpm.sh` built all 59 green and ended with 0, "0 of 59 twins
  failed.", in 54 minutes (3,210 s, between 26 and 92 seconds a twin); every engine answered ready
  at its first start. The run before the last changes of the overlay (run 4: 52 minutes, 3,113 s) built 50
  of the 59 green; its nine red twins were what fit-check points 5 and 2 (the changelog) fixed,
  and the same nine built green with the final overlay before the final run.
- [x] The job `blueprints` of the nightly green on GitHub: the pull request's own nightly run,
  which the workflow change starts, after the push (the orchestrator's step). Green on the
  reviewed head, see "S12.2.1 done" below.
- [x] A broken overlay makes the job fail. The job's step is `bin/build-blueprints-on-zenbpm.sh`,
  which ends with 1 where the overlay does not fit or a twin fails. Shown on a copy of the
  adapter whose `blueprints.txt` lacks `bpmn-timer`: against a fresh clone the script printed "The
  blueprint 'blueprints-bpmn/bpmn-timer' is not in blueprints.txt. ..." and ended with 1 before
  any engine started. And run 4 above, with nine red twins, ended "9 of 59 twins failed." with 1.
  `NightlyWorkflowTest#noStepOfAJobWhichHasToFailLetsAFailurePass` holds the job to no
  `continue-on-error` and no `||` (mutant killed).
- Mutants, 29 applied: 28 killed, 1 equivalent. Of `ZenBpmOverlay.java` (18): each of the eight
  ways a run ends switched off (an unlisted blueprint, a listed one gone, a variant without its
  model, a POM choosing a BPMS without `camunda8`, no adapter version, a profile with more than
  the adapter, a configuration with more, a changed model), the activation and `blueprint.bpms`
  left in the copy, the address on 18081, the model copied over its variant, exit 0 for a run
  which does not fit, the twins of a blueprint which does not run listed, the user-task rule
  skipped and applied over a task definition, the other file of Camunda 8 not copied and copied
  although it names Camunda. Of the scripts (4): `JOBS_DEFAULT_RETRIES` dropped, an image written
  into `bin/zenbpm-engine.sh`, the commit read for the image, the refusal of an existing directory
  dropped. Of the workflow and the README (4): `blueprints-overlay/**` dropped from the trigger,
  the install step dropped, `|| true` after the script; `bin/zenbpm-engine.sh` dropped from the
  README's list of scripts survived, equivalent, because the job's bullet names it as well, which
  is what the test holds. Of the page `Blueprints` (3): a row renamed, a row's answer turned to
  `runs`, a reason removed. One mutant of the first round, the refusal of an existing directory,
  cloned and built twins on the engine of the full run going on at the time; that run was thrown
  away (fit-check point 9), and the test now runs a command with a time limit.
- Builds, both green at the first run: `TZ=Etc/UTC mvn clean install`, 18:17 min, Docker ITs
  included and none skipped: engine test support 12 + 5, core 1,276 + 107 (new
  `ZenBpmModelReaderTest#aFormNamesNoUserTask`, `ZenBpmRestClientContractIT` 35 with the new
  `#aDecisionTableWithOneNamedOutputHandsAContext`), Spring Boot 14 + 97, smoke test 42 + 1,
  election integration test 8, Quarkus runtime 13, deployment 60, integration tests 26, gate 85
  (`BlueprintsOverlayTest` 19, new; `NightlyWorkflowTest` 10, `EnginePinTest` 5, `DocumentationTest`
  11). The gate measured 100 %: 0 of 38,473 (Spring Boot) and 0 of 38,470 (Quarkus) instructions
  missed; the story adds no production class. `bin/check-deploy-safety.sh`: green, the green build
  deployed exactly the 13 published files and the red gate nothing. `actionlint` and `shellcheck bin/*.sh`
  of `workflow-lint`: clean. `ZenBpmOverlay.java` compiles with `--release 21`.
- [x] Decision 64 entered; GAPS 34 entered with its `Deviations` entry; the wiki page `Blueprints`
  (new) and sentences on `Home`, `Configuration` and `Deviations`; the README's "Documentation",
  "What CI runs" and "The blueprints on ZenBPM" (new); `NOTICE` names the blueprints; the plan's
  heading, row 59, M6 and `docs/README.md`; a note under S12.4.1.

**Answers to two reviews of e12-s12.2.1, 2026-10-22** (one by Claude, approve with minor changes:
4 Minor, 2 Nit; one by Copilot, changes requested: 1 Major, 1 Minor). Every claim was checked in
the scripts, in the generator and in the engine's source at the pin, and against a fresh clone of
the blueprints at `7acfef0`. All eight are valid. No new decision and no new GAPS number; decision
64 and GAPS 34 were corrected on this branch, before their first merge.

- **A `docker run` which failed could pass as a ready engine** (Copilot 1, Major). `started` runs
  as the condition of an `if`, where Bash does not apply errexit, so a failed `docker run` went on
  to the readiness poll, and where another engine answered on port 18080 the start reported the
  pinned engine ready and ended with 0. Now `docker run ... || return 1`, and a comment says why.
  `BlueprintsOverlayTest#theEngineScriptEndsRedWhereItsContainerDoesNotStart` (new) runs the
  script against stand-ins of `docker` (its `run` failing with "port is already allocated") and
  `curl` (always ready): three attempts, exit 1.
- **Decision 64 promised a check the generator did not make** (Claude 1, Minor). Claude's probe
  holds: a `camunda7` profile activated by `<property><name>!bpms</name></property>` stayed on
  next to `zenbpm` and the generator said "applied". Maven ends `activeByDefault` profiles only.
  The generator now refuses a profile of a BPMS (`camunda7`, `camunda8`, `process-engine-api`)
  whose activation, comments aside, is anything but `activeByDefault`, and names the POM and the
  activation. `BlueprintsOverlayTest#aBpmsProfileActivatedByMoreThanItsDefaultEndsTheRun` (new);
  the decision's sentence says what is checked, and the page `Blueprints` lists the refusal.
- **Nothing held the script's exit code** (Claude 2, Minor). Three new tests run
  `bin/build-blueprints-on-zenbpm.sh` of a copy of the adapter against stand-ins of `git` (cloning
  the test's checkout), `docker`, `curl` and `mvn`:
  `#theBuildScriptEndsRedWhereATwinFailed` (every twin built, a fresh engine for each, removed
  after its build, the engine's log of the failed twin kept, exit 1),
  `#theBuildScriptEndsGreenWhereEveryTwinPassed` and
  `#theBuildScriptEndsRedWhereTheOverlayDoesNotFit` (exit 1, no twin built). The page `Blueprints`
  names them.
- **The script installed the blueprints into `~/.m2`** (Claude 3, Minor). True: 65 directories
  under `io/vanillabp/blueprint` came from the first runs, against the brief. A twin is a reactor
  of its own: in a fresh clone at `7acfef0` no POM of the 69 twin directories refers to a
  blueprint artifact or parent outside its twin. So the script builds with `-Pzenbpm verify`, and
  the page `Blueprints` says `verify` too. The stand-in Maven records its arguments, and
  `#theBuildScriptEndsRedWhereATwinFailed` asserts every call ends with `-Pzenbpm verify`.
  Fit-check point 1 no longer says nothing was installed.
- **Two runs on one machine removed each other's engine** (Claude 4, Minor). This is what spoiled
  the full run of fit-check point 9. `bin/zenbpm-engine.sh start` now ends with 1 where a container
  of its name is running, names `bin/zenbpm-engine.sh stop`, and leaves it alone; a container
  which stopped is still removed. `#theEngineScriptLeavesTheEngineOfAnotherRunAlone` (new). The
  build script's exit trap removed the engine as well, so a refused start would still have removed
  the other run's engine on the way out. The trap now removes only an engine this run started:
  `#theBuildScriptLeavesTheEngineOfAnotherRunAlone` (new). A start which gives up after its three
  attempts removes the container of its last attempt itself.
- **A decision which collects hands a list of contexts** (Copilot 2, Minor). True at the pin:
  `evaluateCollectOutput`, which `evaluateRuleOrderOutput` calls as well, builds a list of
  contexts where the one output has a name. Camunda 8 hands a list of values there. New
  `ZenBpmRestClientContractIT#aDecisionTableWhichCollectsHandsAListOfContexts` (a `COLLECT` table
  in the new `contract-collected-decision.dmn`, a third task of `contract-decided.bpmn`, its diagram
  regenerated): `=collected` is `[{"discount": 10}, {"discount": 5}]`, and `=collected.discount`
  is `[10, 5]`, the list Camunda 8 hands. GAPS 34 (row and `Deviations`) and `Configuration` say
  both shapes. The row also had no text in its last column, "Engine change which would close it";
  it has one now.
- **The artifact holds every twin's reports** (Claude 5, Nit). The step is now "Upload the logs
  and the reports of the twins", and the README says what is in it.
- **Wording and layout** (Claude 6, Nit). Decision 64 and the workflow's header comment are wrapped
  at 100 columns, the comment of the job says 54 minutes (the final run), and
  `withUserTasksNamedByTheirForm` is private.
- Mutants, 12 applied, 11 killed and 1 equivalent:
  - killed:
    - `docker run` unchecked;
    - the check for a running engine switched off;
    - the exit trap removing the engine unconditionally;
    - the trap's flag never cleared after a twin;
    - a start which gives up leaving its last container;
    - the script ending with 0 always;
    - `install` in place of `verify`;
    - the script ending at the first red twin;
    - the engine not removed after a twin;
    - the activation check dropped;
    - comments kept in the activation read;
  - equivalent: `|| true` after the overlay. A run which does not fit writes no
    `zenbpm-twins.txt`, so the script ends with 1 at reading it and builds nothing, which is what
    the test asserts.
- `shellcheck` (koalaman/shellcheck:stable) is clean on both scripts. `ZenBpmRestClientContractIT`:
  36 green against the pinned image.
- Builds: `TZ=Etc/UTC mvn clean install` green in 18:11 min, Docker ITs included and none skipped:
  - engine test support 12 + 5;
  - core 1,276 + 108 (the new contract case);
  - Spring Boot 14 + 97;
  - smoke test 42 + 1;
  - election integration test 8;
  - Quarkus runtime 13, deployment 60, integration tests 26;
  - gate 92 (`BlueprintsOverlayTest` 26, seven new).

  The gate measured 100 %: 0 of 38,473 (Spring Boot) and 0 of 38,470 (Quarkus) instructions
  missed. `bin/check-deploy-safety.sh` was green: the green build deployed exactly the 13 published
  files, and the red gate deployed nothing.

**S12.2.1 done 2026-10-22**, merged to `main` as `0aac752` (pull request #85: story `6d00f39`,
review fixes `9ac2b63`; CodeRabbit left no comment). GitHub keeps run logs for 90 days, so what the
runs showed is written here as well:

- The pull request's `Nightly`, run
  [39811072729](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/actions/runs/39811072729),
  on the head `9ac2b63`: all four jobs green. The job `blueprints` cloned
  `vanillabp-blueprints/blueprints` at `7acfef0`, the overlay listed 59 twins, and "0 of 59 twins
  failed." after 50 minutes of twins (52 minutes the job). The proof of the acceptance's third item.
- `Checks` on `0aac752`, run
  [39818677744](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/actions/runs/39818677744):
  green on both JDKs at the first attempt. `bin/check-engine-copies.sh` reported both copies as the
  engine's files of `bdd795a3...`. On both JDKs every module ran its tests, none skipped: engine
  test support 12 + 5, core 1,276 + 108 (`ZenBpmModelReaderTest` 38, `ZenBpmRestClientContractIT`
  36), Spring Boot 14 + 97, smoke test 42 + 1, election integration test 8, Quarkus runtime 13,
  deployment 60, integration tests 26, gate 92 (`BlueprintsOverlayTest` 26, `NightlyWorkflowTest`
  10, `EnginePinTest` 5, `DocumentationTest` 11). The gate measured 100 %: 0 of 38,473 (Spring
  Boot) and 0 of 38,470 (Quarkus) instructions missed on JDK 25, 0 of 38,461 and 0 of 38,458 on
  JDK 21.
- `Publish snapshots` on `0aac752`, run
  [39818677698](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/actions/runs/39818677698):
  green. The snapshot it published, `2.0.0-20261022.165922-81`, holds the core's 266 classes as
  before (the story adds no production class), the Spring Boot starter's 13, the Quarkus runtime's
  13 and the deployment's 4, and no test class, fake, fixture, BPMN file or part of the overlay.
- Both report pages (`spring-boot-report/index.html`, `quarkus-report/index.html`) answer HTTP 200
  and show 0 of 38,473 (Spring Boot) and 0 of 38,470 (Quarkus) instructions missed.
- The wiki: `wiki/` at `0aac752` was copied into `pbinitiative/zenbpm-vanillabp-adapter.wiki` and
  pushed as its commit `f043071` (decision 63), adding the page `Blueprints`. `Blueprints`,
  `Configuration` and `Deviations` answer HTTP 200 on github.com, and `Home` (served at `/wiki`)
  links `Blueprints`.

## S12.3.1 Renovate, local workspace notes and the VanillaBP wiki row

- [x] **Depends on:** S12.1.1.

`renovate.json` reviewed against the finished dependency tree (the VanillaBP platform version, the
Spring Boot and Quarkus BOMs which have to move together with the platform). Once the engine pin is
a release tag (S12.4.1), a `customManagers` regex entry follows `pbinitiative/zenbpm`'s GitHub
releases (datasource `github-releases`) for the pin, and the pull request it opens is completed by
hand with the two contract copies of S1.2.1. The local workspace notes of S1.1.2 (root `AGENTS.md`,
the two skill edits) re-read against the finished adapter; they stay local, because the workspace
superproject belongs to the VanillaBP project and no pull request to it is planned. One request to
the VanillaBP project: a row for this adapter on the platform wiki's `BPMS-adapters` page linking
`pbinitiative/zenbpm-vanillabp-adapter`.

*Fit check, 2026-10-22, by the Implementor of the pipeline.* The pipeline's policy changes two parts
of this story: the request for the wiki row is NOT made, and the local workspace notes are read but
NOT edited (the superproject is VanillaBP's; the policy forbids changing, staging or reverting its
local changes). Both are recorded below as left to the maintainer. What did not fit the merged code,
and what was decided:

1. **`renovate.json` let two artifacts move a platform version.** Renovate (44.178.1, run locally with
   `--platform=local --dry-run=lookup` on a clone of `main` at `5d207f6`) extracts a version which
   reads a property as an update of that property. The rule for the platform BOMs named the BOMs and
   the Quarkus extension plugin and processor, but not `io.quarkus:quarkus-maven-plugin` (the native
   build of `quarkus/native-image-tests`, added in S11.4.1) nor
   `org.springframework.boot:spring-boot-configuration-processor` (the Spring Boot module's
   annotation processor). Renovate would have opened `quarkus-maven-plugin` 3.40.1.1 and
   `spring-boot-configuration-processor` 4.1.2, each rewriting `quarkus.version` or
   `springboot.version` for the whole build, which is exactly what the rule's description forbids.
   Both are named in the rule now, and its description says why every reader of the property is.
   The same run after the change opens no branch for either.
2. **The JDK would have split.** Renovate's GitHub Actions manager reads the JDK of
   `actions/setup-java` (`25.0.4+101.0.LTS`) and proposed 25.0.5 for `nightly.yaml` and
   `publish-snapshots.yaml`, but not for `checks.yaml`, whose job `build` names it in its matrix
   (`${{ matrix.java }}` is no version it can read). The nightly and the publication would have
   built on another JDK than the required check, against the comments of both workflows. Decided:
   a regex custom manager reads the matrix entry, and one rule groups every full JDK version into
   one pull request (`renovate/the-jdk-of-the-workflows`, five updates in the lookup run). The
   Mandrel of the nightly's native build is named `25`, a line Renovate leaves alone; it stays
   Renovate's.
3. **The image of actionlint was nobody's.** `checks.yaml` runs `rhysd/actionlint:1.7.12` in a `run:`
   line, which no manager reads. Decided: a regex custom manager with the `docker` datasource reads it.
   `enabledManagers` gains `custom.regex`.
4. **What else the tree holds, and stays as it was:** the VanillaBP rule's two regular expressions
   match `io.vanillabp:*`, `io.vanillabp.adapter:migration-adapter` and this repository's own
   artifacts; `io.grpc{/,}**` matches `grpc-bom` and `protoc-gen-grpc-java`, `com.google.protobuf{/,}**`
   `protobuf-java`, `io.netty{/,}**` the `netty-bom` of the four Quarkus modules. Micrometer, Jackson,
   SLF4J and SnakeYAML carry no version of their own (the platform BOMs manage them). Testcontainers,
   Lombok, JaCoCo, `protobuf-maven-plugin` and Maven's plugins, and every action of the four
   workflows (`nightly.yaml`'s `graalvm/setup-graalvm` included), are Renovate's. The blueprints'
   overlay names no version: its generator writes this repository's `revision` into the blueprints
   (decision 64), and its `settings.xml` names repositories only.
5. **The test (docs are behaviour).** `RenovateConfigurationTest` in the gate holds `renovate.json` to
   the README's new section "Dependency updates" and to the root POM: the versions whose comment says
   "never on its own" (Spring Boot, Quarkus, gRPC, protobuf, Netty; the gRPC comment gained the
   phrase, and Testcontainers a comment of its own so the Netty comment no longer stands above it) are
   read only by artifacts a switched-off rule names; every rule has a description, and every name a
   switched-off rule matches is still an artifact of a POM; the custom managers find every image a
   workflow runs by its version and the JDK of the required check; every workflow names the same JDK
   and one rule groups it. It judges Renovate's three pattern forms used here (regular expression,
   `prefix{/,}**`, exact name) and fails on any other form. `renovate-config-validator` of the same
   Renovate version accepts the file; the build does not depend on it.
6. **The `customManagers` entry for engine releases is NOT added.** The pin is still the `main` commit
   `bdd795a3` with a `dev` digest, and the policy forbids the re-pin. It is left to the step which
   re-pins to a release (S12.4.1). The entry the maintainer adds then, to `customManagers` of
   `renovate.json`, together with `zenbpm.version` in the root POM:

   ```json
   {
     "description": "The engine's releases: the pin follows the GitHub releases of pbinitiative/zenbpm. The pull request moves zenbpm.version only and is completed by hand with zenbpm.commit, zenbpm.image and the two copies of the engine's contract, by the steps of the README's section 'Supported engine version'.",
     "customType": "regex",
     "managerFilePatterns": ["/^pom\\.xml$/"],
     "matchStrings": ["<zenbpm\\.version>(?<currentValue>v[^<]+)</zenbpm\\.version>"],
     "depNameTemplate": "pbinitiative/zenbpm",
     "datasourceTemplate": "github-releases"
   }
   ```

   Proven on a scratch clone only: with the entry added and `<zenbpm.version>v1.8.0</zenbpm.version>`
   written into the root POM, the validator accepts the file and the lookup run proposes `v1.9.0` on
   the branch `renovate/pbinitiative-zenbpm-1.x`. The release guard of S12.4.1 refuses an adapter
   release whose `zenbpm.commit` and `zenbpm.image` the pull request left behind (decision 66,
   entered by S12.4.1), so a half-completed pull request cannot reach a release.
7. **The VanillaBP wiki row is NOT requested** (policy). Left to the maintainer, who asks the VanillaBP
   project for it once the adapter is released (a row of a list of adapters "an application adds as
   a dependency" should name a released version). The proposed text, in the two tables of the page
   `BPMS-adapters` of `vanillabp/adapter-platform-integration`'s wiki:

   ```markdown
   | [ZenBPM](https://github.com/pbinitiative/zenbpm) | `zenbpm` | remote engine (REST and a gRPC job stream), eventually consistent; built and owned by the ZenBPM maintainers at pbinitiative; snapshots only, not released | [Wiki](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/wiki) · [Repository](https://github.com/pbinitiative/zenbpm-vanillabp-adapter) |
   ```

   and in "The dependency to add": `org.pbinitiative.zenbpm:zenbpm-vanillabp-adapter-spring-boot` and
   `org.pbinitiative.zenbpm:zenbpm-vanillabp-adapter-quarkus`, with the note that until the first
   release they are `2.0.0-SNAPSHOT` in the GitHub Packages of `pbinitiative/zenbpm-vanillabp-adapter`.
8. **The local workspace notes of S1.1.2, re-read against the finished adapter. Left for the
   maintainer to apply locally** (the pipeline changes none of them):
   - Root `AGENTS.md`, paragraph on `zenbpm-vanillabp-adapter`: "its integration tests will start the
     engine image" → they do: they start the image `zenbpm.image` of the adapter's root POM names (by
     its digest) and need Docker; without Docker they are skipped (the adapter's README, "Building").
   - Root `AGENTS.md`, "Build & verify": the adapter is listed in the chain "downstream repos resolve
     upstream from ~/.m2" → it resolves VanillaBP's snapshots from the snapshot repository of Maven
     Central its root POM names, so nothing has to be built first (the adapter's README, "Building";
     open question 16, answered 2026-10-15).
   - Root `AGENTS.md`, "Their two skills (`zenbpm-*`)" and the section "Skills" → there are three
     `zenbpm-*` skills: `zenbpm-code-review`, `zenbpm-e2e-tests` and the pipeline's
     `zenbpm-adapter-review` (ignored through `.git/info/exclude` with `/.github/agents/`).
   - Root `AGENTS.md`, the adapter paragraph could add: the user documentation is written in the
     adapter's `wiki/` and copied into its wiki after each merge (decision 63).
   - `vanillabp-bpms-characteristics`, the anchor "decision 17 of `zenbpm-vanillabp-adapter` ... engine
     `main` at `ab85f922`" → decision 66 (entered by S12.4.1) and the pin `bdd795a3`.
   - `vanillabp-bpms-characteristics`, ZenBPM "No retries (yet) ... in progress (pull request #862)" and
     the cheat sheet's "incident at once (no retries yet)" → the engine counts retries and waits a
     backoff since commit `bc3384b3` (inside the pin); the adapter fails a job without an error code
     and the engine raises the incident at the last attempt (decision 27, GAPS 6 closed); the adapter
     writes no default of retries into a model (decision 38).
   - `vanillabp-bpms-characteristics`, "the adapter defaults to `use-prefix`" and the cheat sheet's
     "prefixed identifiers (`use-prefix` default)" → wrong: `by-adapter` stays the default and is
     refused while deploying, so every application chooses `use-prefix` or `none` (decision 24).
   - `vanillabp-bpms-characteristics`, job outputs "process scope only" → the adapter writes the shared
     values into every instance from the task's own up to its workflow's root (decision 35), repeating
     an update which finds no instance yet (decision 34), before it completes the job (decision 7).
   - `vanillabp-bpms-characteristics`, "Starting a workflow the BPMS decides on": "its decision 5, not
     built yet" → built as marker service tasks after every start event and before every end event
     (decisions 26, 44 and 52); the identity is the process instance key (decision 25).
   - `vanillabp-bpms-characteristics`, "Signals": "plans to offer no `SEND_SIGNAL` and to refuse a model
     with a signal event" → done (GAPS 13, `ZenBpmUnsupportedConstructs`).
   - `vanillabp-bpms-characteristics`, "Versions": "a `main` commit plus image digest until a release
     carries what it needs" → the engine release `v1.9.0` (2026-10-17) carries the pin (`bdd795a3` is
     seven commits behind it); the pin stays on `main` until the maintainer re-pins, and an adapter
     release refuses an unreleased engine (decision 66).
   - `vanillabp-adapter-building`: "`ZenBpmBpmnModel`, planned for its deployment pipeline" → built
     (`core/.../model/ZenBpmBpmnModel.java`, a record holding the DOM `Document`); its anchor could
     name decision 66 of `zenbpm-vanillabp-adapter`; "All adapter artifacts are versioned
     2.0.0-SNAPSHOT" stays true, and this adapter's release takes its version from the tag
     (decision 65).

**Acceptance criteria**

- [x] `renovate.json` reviewed against the finished dependency tree: every artifact of every POM and
  every action and image of the workflows is either Renovate's or named by a switched-off rule with
  its reason, and `RenovateConfigurationTest` holds it (4 tests; 9 mutants applied to
  `renovate.json`, the root POM and a workflow, 9 killed: a reader of `quarkus.version` dropped from
  its rule, the "never on their own" of the gRPC comment dropped, a stale name, a rule without a
  description, either custom manager dropped, another JDK in one workflow, the JDK group dropped, a
  glob form the test cannot judge). Renovate's own lookup on a scratch clone opens no branch for
  Quarkus or Spring Boot, and one for the JDK of all five places.
- [x] The engine's `customManagers` entry is written down, ready and proven on a scratch clone, and
  NOT added (left to the re-pin, item 6).
- [x] The local workspace notes are re-read and their corrections listed (item 8); applying them is
  left to the maintainer.
- [ ] The `BPMS-adapters` wiki row: NOT requested (policy); the proposed text is in item 7, left to
  the maintainer.

*Evidence, 2026-10-22*, a clean `TZ=Etc/UTC mvn clean install` of the story's commit on JDK 25, green
in 18:22, no test skipped: engine test support 12 + 5, core 1,276 + 108, Spring Boot 14 + 97, smoke
test 42 + 1, election integration test 8, Quarkus runtime 13, deployment 60, integration tests 26,
gate 96 (`RenovateConfigurationTest` 4 new). The gate measured 100 %: 0 of 38,473 (Spring Boot) and
0 of 38,470 (Quarkus) instructions missed. `bin/check-deploy-safety.sh`: green in 36:46, the green deploy staged exactly the 13 published files and the red gate deployed nothing.

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

*Note from S12.2.1, 2026-10-22.* The blueprints get their `zenbpm-vanillabp-adapter.version` from
the overlay, which writes the `revision` of this repository's root POM (decision 64), and the
nightly installs the adapter from the checkout. So "the blueprints' version points at the release"
needs no step of its own: the job builds the blueprints against whatever version the checkout
carries, a release tag included.

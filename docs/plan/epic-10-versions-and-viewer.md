# E10 - Versions and the viewer

**Goal.** Version tags resolve, the startup check for old versions works, renamed processes are
catalogued, and the viewer/history API is served from the deployment memory and the engine.

**Done when.** `@WorkflowTask(version = "tag")` routes, the boot names old versions with running
workflows and what they miss, and a viewer shows definitions, XML and history including a call
activity, on both platforms.

Template: `Camunda8ProcessVersions`, `Camunda8WorkflowViewer`, `Camunda8StartupQuestionCostTest`.
Contract: `ProcessVersionCatalog`, `CachingProcessVersionCatalog`, `processVersionCatalogOf`,
the viewer methods of `MigratableProcessService`.

---

## F10.1 Versions

### S10.1.1 `ZenBpmProcessVersions`

- [x] **Depends on:** E4.

**Instructions**

`ZenBpmProcessVersions extends CachingProcessVersionCatalog` per adapter id: `fetchDeployedVersions
(module, plainProcessId)` -> `rest.processDefinitions(scopedId, onlyLatest=false)` mapped to
`DeployedProcessVersion(version as String, versionTag, definitionKey)` oldest first, remembering the
keys per version (decision 14: one search per process). Registered in `wireBpmn`
(`registerProcessVersions`) and warmed by the core's `resolveProcessVersions`. `versionTag` is read
from `zenbpm:versionTag` for the version this boot deployed without a request.

*Note from S4.3.2, 2026-10-17.* `deployResources` calls `registerDeployedVersion` for every
deployed process, unchanged ones included, and keeps what the engine ended up with in
`ZenBpmClientFactory#deployedProcesses()` (`ZenBpmDeployedProcesses`: key, version, version tag and
the BPMN as the engine holds it), so the version this boot deployed needs no request here. The test
S4.3.2 planned as `ZenBpmDeploymentIT#aDeployedVersionWithoutAMethodEndsTheStart` (a task whose only
method serves `version = ">1"` in a model the engine keeps as version 1 ends the start, platform
decision 126) belongs to this story: the platform runs that check only for a process with a
registered catalog (`DeployedProcessVersionsCheck#check` walks `registeredCatalogs`), so it cannot
fail before this catalog exists. Assert the core's message by the phrases
`DeployedProcessVersionsCheck.A_VERSION_OF_A_PROCESS` and `ELEMENTS_SERVED_BY_NO_METHOD_IN_THIS_VERSION`.

**Tests**: `ZenBpmProcessVersionsTest` (fake server), Spring `ZenBpmProcessVersionIT#theVersionDecidesWhichMethodRuns`
(two methods, `version = "1"` and `version = ">1"`, two deployments), `#aTagResolves`.

*Fit check, 2026-10-20, decided by the Implementor where it says so.* Checked against `main`
(`85ffe95`), `ADAPTER-AUTHORS.md` (the version calls of section 8 and checklist item 3), the
platform as the adapter resolves it (`2.0.0-20261015.045823-21`: `ProcessVersionCatalog`,
`CachingProcessVersionCatalog`, `DeployedProcessVersion`, `WorkflowTaskRegistry#resolveProcessVersions`,
`ProcessVersions#reportUnknownVersionTag`, `DeployedProcessVersionsCheck`), the Camunda 8 template
(`Camunda8ProcessVersions`, `Camunda8DeploymentService` around the registration and
`processVersionCatalogOf`, `Camunda8ProcessVersionIT`), and the engine at the pin `bdd795a3`
(source read with `git show`/`git grep` at the pin: `extractProcessVersionTag` and
`storeProcessDefinitionVersion` of `pkg/bpmn/xml_loader.go`, `processDefinitionDeployer.deployOnce`
of `internal/cluster/node.go`, `AllocateProcessDefinition` of `internal/cluster/state/definitions.go`,
`GetProcessDefinitions` of `internal/rest/server.go`), then the tests below against the pinned image.
What did not fit, and what was decided:

1. **`DeployedProcessVersion` carries no key.** The record of the platform is `(version, versionTag,
   deployedAt)`, not `(version, versionTag, definitionKey)` as the instructions write. The catalog
   keeps the definition key per version in a map of its own, filled from the same list request and
   from the deployment, as `Camunda8ProcessVersions#definitionKeysByVersion` does; the questions of
   S10.2.1 read it.
2. **Version tags at the engine** (open question 1 of the brief). The engine reads a tag from the
   `versionTag` extension element of the process in any namespace, keeps it per version, and hands
   it out in the list of definitions and with each definition, so tags route: one list request per
   process answers every version with its key and tag (decision 14), and the version this start
   deployed is recorded from the deployment's answer without a request. What the plan did not know:
   the engine gives a tag to ONE version of a process. A changed model under a tag an earlier version
   carries is refused with `400` ("version tag ... is already used by version N"), and a model
   deployed again with the tag and the content of an older version gets that older version back,
   while workflows the application starts afterwards run on the newest one. Both are GAPS 32 (new),
   pinned by `ZenBpmProcessVersionIT#aTagAnotherVersionCarriesIsRefused` and
   `#aRolledBackTaggedModelGetsItsOldVersionBack` (after the reviews such a start ends:
   `#aRolledBackTaggedModelEndsTheStart`, see the answers below), and said in the README and as
   item 18 of the
   deviations draft. Because the adapter writes markers and mappings into the model, a change of the
   methods alone needs a new tag as well; the engine's reason, which the deployment's message
   carries, names the tag.
3. **Which processes register the catalog** (open question 5). `Camunda8DeploymentService` registers
   it in `wireBpmn` for every process it wires, right after `validateTaskWiring`; this adapter wires
   only claimed processes (a called process a workflow service declares is claimed), so the
   registration sits at the same place and reaches the same processes. `processVersionCatalogOf`
   hands out the same catalog for every declared-only id, as Camunda 8 does, `null` for an adapter id
   without clients. Decided by the Implementor: it is built in this story rather than in S10.2.1,
   because the deploying test proves the recorded version through it; the questions about the
   versions of a declared id are S10.2.1's.
4. **The planned `ZenBpmDeploymentIT#aDeployedVersionWithoutAMethodEndsTheStart`** is
   `ZenBpmProcessVersionIT#aDeployedVersionWithoutAMethodEndsTheStart` (decided by the Implementor):
   it needs an application of its own, and `DeployItApplication` scans its package and every package
   below it. The note above asks to assert the phrase `A_VERSION_OF_A_PROCESS`; that phrase is the
   subject line of the findings in the startup box, and the message which ends the start writes
   "Version '1' of BPMN process 'Sealing'" instead, so the test asserts
   `ELEMENTS_SERVED_BY_NO_METHOD_IN_THIS_VERSION`, the version and process as that message writes
   them, and the method.
5. **The unknown tag is reported by the platform**, once per start, as a WARN of the startup box
   (`ProcessVersions#reportUnknownVersionTag`), beside the platform's own "neither a deployed version
   nor a version tag" line and its dead-method warning. The finding publishes no phrase, so
   `#aTagResolves` finds it by the specification and the method in the order it names them.
6. **Quarkus** (open question 6): the catalog is built in `core` and registered by the core's calls,
   so the Quarkus build-time wiring needs no change; the Quarkus suite runs unchanged in the clean
   build. Its twins are S10.4.1's.
7. **The refresh interval** of an unknown version or tag is the platform's default of 60 seconds, as
   on Camunda 8.

Decision 55 (new) records the catalog; GAPS 32 is new. `docs/architecture/01-architecture.md` no
longer says that the catalog "comes with E10".

**Acceptance criteria**

- [x] A version specification naming a tag routes; a tag nobody deployed is reported once at startup.
  `ZenBpmProcessVersionIT#aTagResolves` against the pinned image: a workflow on the version tagged
  `release-1` is served by the method of `version = "release-1"`, one on the version tagged
  `release-2` by that of `version = "release-2"`, and each start names the method of
  `version = "release-9"` once in its startup box. `#theVersionDecidesWhichMethodRuns` holds the
  numbers (`version = "1"`, `version = ">1"`, two deployments), `#aDeployedVersionWithoutAMethodEndsTheStart`
  the end of the start (platform decision 126), `#aTagAnotherVersionCarriesIsRefused` and
  `#aRolledBackTaggedModelGetsItsOldVersionBack` (now `#aRolledBackTaggedModelEndsTheStart`)
  GAPS 32;
  `ZenBpmProcessVersionsTest` the one list
  request, oldest first with the tags, and the deployed version known without a request.
  Clean build of the story's commit (`TZ=Etc/UTC mvn clean install` on OpenJDK 25.0.2, Docker ITs
  included, none skipped): engine test support 12 + 5, core 1,181 + 100, Spring Boot 14 + 88, smoke
  test 38, Quarkus runtime 13, deployment 52, integration tests 20, gate 37; coverage 100 % on both
  platforms (0 of 34,633 instructions missed on Spring Boot, 0 of 34,503 on Quarkus);
  `bin/check-deploy-safety.sh` green. Mutants: 4 written, 4 killed (the version recorded from the
  deployment, the registration in `wireBpmn`, the `null` catalog of an adapter id without clients,
  the order of the versions).

### S10.2.1 Old versions, renamed processes, open task count

- [x] **Depends on:** S10.1.1.

**Instructions**

- `tasksOfVersion(module, process, version)`: `rest.processDefinition(keyOf(version)).bpmnData` read
  through `ZenBpmModelReader` -> the same `BpmnTaskSpec`s `wireBpmn` builds (unscope task types),
  with `multiInstanceElementsWithoutAnItem` filled: the multi-instance elements of that version
  which hand no item to a round (no `inputElement`). The core warns from it (platform decision 94);
  `null` means "not read".
- From the same read of `bpmnData`: `startEventsOfVersion` (every start event the process itself
  holds, as `BpmsInitiatedStartSpec`s), `concurrentTokenElementsOfVersion` (the elements which put a
  second token into a workflow, as `reportConcurrentTokenElements` reports them for the deployed
  version) and `identifiersOfVersion` (the scoped identifiers the held model declares, unscoped, as
  `NameClashAvoidanceSupport.ModelIdentifier`s). Each answers `null` where the engine cannot hand
  the model back.
- `activeInstanceCountOf(...)`: `rest.instances(processDefinitionKey=key, state=active, size=1)
  .totalCount()`.
- `whatOlderVersionsMiss(module, process)`: names the markers and mappings of decisions 4 and 26
  which a workflow of an older version does not have (`@WorkflowEnded`, the start marker and with it
  the start row and the aggregate of a workflow the engine starts, `@TaskParam` inputs) - only where
  the current model has them.
- `ZenBpmDeploymentService.processVersionCatalogOf(module, plainProcessId)`: the same catalog for a
  declared-only id (scoped like everything else); `null` where the engine answers nothing.
- `openTaskCount` is S5.2.2; `ZenBpmStartupQuestionCostTest` counts the requests a boot makes (a fake
  server counting) and pins that they do not grow with instances.

  *Note from S5.2.2, 2026-10-17.* `openTaskCount` lists the versions under the scoped id itself
  (`GET /v1/process-definitions`) and reads per version its model (`GET
  /v1/process-definitions/{key}`) and its statistics: one request for the list and two per version.
  The platform asks it in its startup checks, before the application deployed anything (the
  `validatePersistedAdapterIdsAtStartup` pass on both platforms), so `ZenBpmDeployedProcesses` is
  still empty then. Where the catalog reads the same models, let both share them, so a boot does
  not read a model twice, and count these requests in `ZenBpmStartupQuestionCostTest`.

**Tests**: Spring `ZenBpmOldProcessVersionsIT` (deploy v1, start, redeploy v2 without the old task's
method -> the boot reports the old version, its running workflow and the missing method;
`outfaded-versions` silences it), `ZenBpmRenamedProcessTest` for the catalog of a declared id,
`ZenBpmProcessVersionCatalogTest` for the start events, concurrent-token elements, identifiers and
multi-instance elements of a held version. Assertions on the core's findings use the phrases the
core publishes (`DeployedProcessVersionsCheck.A_VERSION_OF_A_PROCESS`, `SERVED_BY_NO_METHOD`,
`STILL_RUN_ON_THIS_VERSION` and their siblings, platform decision 105), not copied text. They live
in the platform's `migration-adapter` runtime artifact, not in the SPI, so the test scope needs it.

*Fit check, 2026-10-20, decided by the Implementor where it says so.* Checked against the branch
after S10.1.1, `ADAPTER-AUTHORS.md` (the version questions and "hold that model while the version is
being asked about"), the platform as the adapter resolves it (`2.0.0-20261015.045823-21`:
`ProcessVersionCatalog`, `DeployedProcessVersionsCheck#check`, `#reportWorkflowsOnOlderVersions`,
`#reportUnservedTasks`, `#reportItemsThisVersionNeverNames`, its `InstanceCounts`), the Camunda 8
template (`Camunda8ProcessVersions`, `Camunda8DeploymentService#taskSpecsOf`,
`Camunda8OldProcessVersionsIT`), and the engine at the pin `bdd795a3` (`GetProcessInstances` of
`internal/rest/server.go` and `internal/cluster/server/server.go`, `FindProcessInstancesPage` of
`internal/sql/queries/process_instance.sql`, read with `git show` at the pin), then the tests below
against the pinned image. What did not fit, and what was decided:

1. **The models are shared by every question of a start** (open question 2 of the brief). Four
   questions read the models the engine holds: `openTaskCount` (before anything is deployed), the
   messages of every version (decision 42), the job types of a declared process (decision 41) and the
   questions of this catalog. Decided by the Implementor, beyond the brief's two: all four read
   through one cache of the adapter id, `ZenBpmHeldModels`, kept by the client factory, by definition
   key, at most 8 Mi characters, the model asked for longest ago dropped first. A start reads every
   model once; the deployment's own `GET` of the definition it created stays, because it learns the
   version and the tag from it. `ZenBpmStartupQuestionCostTest` pins the requests of a whole start:
   `4n + 5` for `n` versions of one process, every model once, whatever the number of workflows.
   Decision 56 (new).
2. **`activeInstanceCountOf`** (open question 3). Read at the pin: `GET /v1/process-instances`
   filters by `processDefinitionKey` and `state`, its `totalCount` sums an exact `COUNT(*) OVER ()`
   per partition, so a search for one item answers the number, and it finds the instances a call
   activity started as well (process type `callActivity` unless child processes are asked for), so
   the workflows of a called process count. The plan's `state=active` alone misses a workflow with an
   incident, which the engine holds as `failed` and an operator resumes; decided by the Implementor:
   active and failed, two searches of one item, as the probes count running workflows
   (`ZenBpmRunningWorkflows#RUNNING`). `null` with a WARN where the engine does not answer.
3. **`tasksOfVersion`** reads the held model through `ZenBpmModelReader`, which also reads the
   markers the adapter wrote as tasks; they are left out, because no method serves them and the
   platform would report them as unserved. The task definitions lose the deployment's prefixes
   (`plainTaskDefinition`), a user task without one keeps the engine's default type, as the wiring
   reports it, and `multiInstanceElementsWithoutAnItem` is filled with the rule
   `ZenBpmMultiInstanceItems` applies (an empty list where every element hands over an item, so the
   platform checks the version). `ZenBpmProcessVersionCatalogTest` compares the answers with what the
   wiring and the deployment report for the same model, deployed by this adapter's own pipeline.
4. **`identifiersOfVersion`** cannot use `ZenBpmScoping#apply`, which rewrites the model it reads;
   `ZenBpmScoping#declaredIn` reads a held model without changing it, takes the prefixes off, and
   leaves out the job types of the markers.
5. **`whatOlderVersionsMiss`** (open question 4). The platform appends the answer to "so those
   workflows never get it: ", asks it once per process and only where workflows run on an older
   version. Camunda 8 answers one fixed sentence. Decided by the Implementor: read from the models,
   comparing the model this start deployed with every older one the engine holds: the end markers
   (a `@WorkflowEnded` method is not told the end), the start markers (their start was not reported,
   so no aggregate for a workflow the engine started, no instance key in a start row) and the names
   mapped into a multi-instance body; only what the deployed model has, so a version is never blamed
   for what the deployed one lacks as well. A process id this start deployed nothing under, such as a
   declared one, has no model to compare with and is answered `null`. The older models are the ones
   `tasksOfVersion` reads next, so this costs no request of its own. *Corrected after the reviews:*
   that compared versions nobody runs on, and an outfaded version's model, which `tasksOfVersion`
   never reads, cost a request of its own; now only the versions workflows run on are compared, one
   model at a time (see the answers below).
6. **Renamed processes**: `processVersionCatalogOf` came with S10.1.1 (see its fit check); the
   questions about the versions of a declared id are answered by the same catalog under the scoped
   old id, `ZenBpmRenamedProcessTest`.
7. **The planned assertions** of `ZenBpmOldProcessVersionsIT` use the platform's phrases
   `A_VERSION_OF_A_PROCESS`, `STILL_RUN_ON_THIS_VERSION`, `SERVED_BY_NO_METHOD` and
   `STILL_RUN_ON_AN_OUTFADED_VERSION`. The platform's notice about the workflows on older versions
   publishes no phrase, so the test finds this adapter's own words in it.
8. **Quarkus**: nothing in the build-time wiring changes; the suite runs unchanged in the clean build.

**Acceptance criteria**

- [x] The report names version, count and what older versions miss; no page of instances is fetched.
  `ZenBpmOldProcessVersionsIT` against the pinned image: after the change the startup box names
  `version '1' of process 'Storing'`, "1 workflow(s) still run on this version", the task definition
  `'weigh'` served by no method, and, in the platform's notice on the older versions, what that
  workflow misses: "the end of such a workflow is not reported to a @WorkflowEnded method"; with
  `vanillabp.adapters.zenbpm.outfaded-versions=1` the missing method is no longer named and the box
  says "1 workflow(s) still run on version '1'" instead. `ZenBpmStartupQuestionCostTest` (2 and 5
  versions, 1 and 1,000,000 workflows on each): `4n + 5` requests, each model read once, every
  search for instances of one item. `ZenBpmProcessVersionCatalogTest` holds the answers about a held
  version against what the wiring and the deployment report for the same model,
  `ZenBpmRenamedProcessTest` the catalog of a declared id, `ZenBpmHeldModelsTest` the cache.
  Clean build of the story's commit (`TZ=Etc/UTC mvn clean install` on OpenJDK 25.0.2, Docker ITs
  included, none skipped): engine test support 12 + 5, core 1,200 + 100, Spring Boot 14 + 89, smoke
  test 38, Quarkus runtime 13, deployment 52, integration tests 20, gate 37; coverage 100 % on both
  platforms (0 of 35,654 instructions missed on Spring Boot, 0 of 35,524 on Quarkus);
  `bin/check-deploy-safety.sh` green. Mutants: 15 written, 15 killed: the markers left out of the
  tasks, the plain task definitions, the elements without an item, the failed instances counted,
  the `null` where this start deployed nothing under the id, the older versions only, only what the
  deployed model has, an element the older version lacks, the markers left out of the identifiers,
  an empty name left out, the deployed model taken without a request, the cache's bound, a model
  larger than the bound kept out (it survived first: the test let a lone model be dropped by the
  bound itself; it now shows that without the guard such a model drops every model kept), the held
  messages and the open tasks reading through the cache.

**Answers to two reviews of e10-f10.1, 2026-10-20** (one by Claude, approve after 1 Major, with
3 Minor and 4 Nits; one by Copilot, changes requested, 1 Major and 1 Nit). Every claim was checked
in the code of the branch, in the platform as the adapter resolves it and, for the engine, at the
pin `bdd795a3`. The GAPS citation was raised by both, so nine distinct findings remain; all nine
were valid and are fixed. Decisions 55 and 56, new on this branch, were extended in place before
the merge; no new decision was needed.

- **A tagged rollback left the version new workflows run on unchecked** (Claude 1, Major).
  `AllocateProcessDefinition` of `internal/cluster/state/definitions.go` compares the newest version
  first and then hands back the version carrying the tag where the content is the same (`200`,
  unchanged), while new workflows start on the newest version; the platform checks only the version
  a start deployed and the older ones. Where the engine answers a deployment as unchanged, the
  deployment now compares the version with the newest one the catalog lists (the list the platform
  reads afterwards anyway) and ends the start where an older version came back, naming the file,
  both versions, the tag and the fix (a tag no version carries yet, or none). A WARN alone was
  rejected: the application would start workflows on a model it does not bring. Tests:
  `ZenBpmDeployingTest#aModelHandedBackAsAnOlderVersionEndsTheDeployment`,
  `#aModelHeldUnchangedAsTheNewestVersionIsDeployed`, and against the pinned image
  `ZenBpmProcessVersionIT#aRolledBackTaggedModelEndsTheStart`, which replaces
  `#aRolledBackTaggedModelGetsItsOldVersionBack`. Decision 55, GAPS 32, the README, item 18 of the
  deviations draft and the architecture say it.
- **The count of a version made the engine read every instance of the partition** (Claude 2,
  Minor). `FindProcessInstancesPage` of `internal/sql/queries/process_instance.sql` searches the
  index by definition and creation time only where a lower bound is given. Both searches now send
  `createdFrom=1970-01-01T00:00:00Z`;
  `ZenBpmProcessVersionCatalogTest#theWorkflowsOnAVersionAreCountedByTheEngine` asserts it,
  decision 56 says why.
- **What older versions miss blamed workflows for versions nobody runs on, and held every older
  model at once** (Claude 3, Minor; Copilot 1, Major). The answer compared every older version, also
  one without workflows and an outfaded one, whose model `tasksOfVersion` never reads, and kept
  every parsed model in a list until the comparison; Copilot ran out of a 128 MB heap at 1,000
  versions.
  The catalog now keeps the last count of each version, which the platform asks right before, and
  compares only the versions workflows run on (or whose count the engine did not give), reading
  their models one after the other and keeping of each only what it carries
  (`ZenBpmOlderVersions.Carried`: two flags and the mapped names). Test
  `ZenBpmProcessVersionCatalogTest#onlyTheOlderVersionsWorkflowsRunOnAreCompared` (no read of the
  model of a version without workflows, no further request). No bounded-heap test: it would be slow
  and flaky in a build, and the fix keeps nothing that holds a model. Fit-check item 5 of S10.2.1 is
  corrected.
- **The javadoc cited GAPS 32** (Claude 4, Copilot 2). It now says it in its own words and cites
  decision 55.
- **The README promised `zeebe:versionTag` without a test** (Claude 5, Nit). The claim is gone from
  the README and the javadoc; decision 55 keeps the engine's fact.
- **Decision 56 departed from the contract's "drop it afterwards" without saying so** (Claude 6,
  Nit). It now names the sentence and why: the same models are asked at different moments of a
  start, and the bound caps the cost.
- **The cost test did not ask in the platform's order** (Claude 7, Nit). It now asks the list, the
  counts of every older version, what they miss, and then the questions per version, as
  `DeployedProcessVersionsCheck#check` does. The three list requests per process stay: they are
  constant per process (decision 14), and the rollback check reads the catalog's list, so it adds
  none.
- **Unwrapped lines** (Claude 8, Nit): wrapped.

Mutants of the new guards, each against a green unmutated run, 6 of 6 killed: the rollback check
removed (`#aModelHandedBackAsAnOlderVersionEndsTheDeployment`), `<=` made `<`
(`#aModelHeldUnchangedAsTheNewestVersionIsDeployed`), `createdFrom` removed, every older version
compared, the kept counts ignored (`#onlyTheOlderVersionsWorkflowsRunOnAreCompared`), an unknown
count excluded (`#twoThingsMissedAreNamedWithAnd`, `#onlyWhatTheDeployedModelHasIsNamed`).
`ZenBpmProcessVersionIT` now starts an engine per case: the first full build after the fixes
failed in `#aTagResolves` and `#aTagAnotherVersionCarriesIsRefused`, because the renamed rollback
case ran before them on the shared engine and left versions 1 and 2 of `Labelling` behind, which the
new check refuses as it should. Clean build after the fixes (`TZ=Etc/UTC mvn clean install` on
OpenJDK 25.0.2, Docker ITs included, none skipped): engine test support 12 + 5, core 1,203 + 100,
Spring Boot 14 + 89, smoke test 38, Quarkus runtime 13, deployment 52, integration tests 20, gate
37; coverage 100 % on both platforms (0 of 35,801 instructions missed on Spring Boot, 0 of 35,671
on Quarkus); `bin/check-deploy-safety.sh` green.

**S10.1.1 and S10.2.1 done 2026-10-20**, merged to `main` as `daf69c5` (pull request #69: stories
`8b48727` and `e256501`, review fixes `653180e`; CodeRabbit made no actionable comment). F10.1 is
complete; E10 goes on with S10.3.1 and S10.4.1. On `daf69c5` (GitHub keeps run logs for 90 days, so
what they showed is written here as well):

- `Checks`, run
  [39469719091](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/actions/runs/39469719091):
  green on both JDKs. `bin/check-engine-copies.sh` (in the JDK 25 job) reported both copies as the
  engine's files of `bdd795a3...`. On both JDKs `ZenBpmProcessVersionIT` ran 5 tests,
  `ZenBpmOldProcessVersionsIT` 1, `ZenBpmProcessVersionCatalogTest` 11, `ZenBpmProcessVersionsTest`
  3, `ZenBpmStartupQuestionCostTest` 4, `ZenBpmRenamedProcessTest` 1, `ZenBpmHeldModelsTest` 4 and
  `ZenBpmDeployingTest` 17, none skipped (engine test support 12 unit and 5 integration tests, core
  1,203 unit and 100 integration tests, Spring Boot 14 unit and 89 integration tests, smoke test 38,
  Quarkus runtime 13, Quarkus deployment 52, Quarkus integration tests 20, coverage gate 37). The
  gate measured 100 % on both platforms: of 35,801 instructions for Spring Boot and 35,671 for
  Quarkus on JDK 25, of 35,789 and 35,659 on JDK 21.
- `Publish snapshots`, run
  [39469719084](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/actions/runs/39469719084):
  green. The core snapshot it published to GitHub Packages, `2.0.0-20261020.175152-65`, holds the
  batch's new main classes (`ZenBpmProcessVersions`, `ZenBpmOlderVersions` with `Carried`,
  `ZenBpmHeldModels`) and the changed ones (`ZenBpmDeploymentService`, `ZenBpmClientFactory`,
  `ZenBpmHeldMessages`, `ZenBpmDeclaredProcessWorkers`, `ZenBpmScoping`, `ZenBpmTaskParameters`,
  `ZenBpmInputMappings`, `ZenBpmProcessService`), 250 classes, and no test class, fake, fixture
  or BPMN file. Both report pages (`spring-boot-report/index.html`, `quarkus-report/index.html`)
  answer HTTP 200 and show 0 of 35,801 (Spring Boot) and 0 of 35,671 (Quarkus) instructions missed.

## F10.3 The viewer

### S10.3.1 `ZenBpmWorkflowViewer`

- [x] **Depends on:** S10.1.1.

**Instructions**

- `getProcessDefinitions(module, process, persistence, aggregateId, historyContext)`: locate the
  instance by the business-key search of S5.2.1 (`historyContext` = a child instance key for a call
  activity). The aggregate id is no longer an instance key (decision 25), and the viewer methods get
  no start-row hint, so a workflow without a business key - one the engine started by timer or by
  message - is unknown to the viewer until E13.4 lets the start marker write its business key (an
  empty list, as for any unknown instance). For a located instance, answer with the definition it
  runs on (`definitionKey`, id = `String.valueOf(key)`, `usedByElements = null`) plus, for every
  call activity of that definition's model (read from `ZenBpmDeployedProcesses` or the engine's
  XML), the definition which WOULD run next (latest of the called id, or the version/tag the
  `calledElement` names) with `usedByElements` = the call activity ids. Empty list where the
  instance is unknown.
- `getBpmnXml(module, process, definitionId)`: from `ZenBpmDeployedProcesses` where this boot deployed
  it, else `rest.processDefinition(key).bpmnData`; `null` on 404.
- `getWorkflowHistory(...)`: `rest.history(key)` paged -> `WorkflowElementHistory` per flow element
  instance (element id, type, started, ended, in/out variables), a call activity entry carrying
  `secondaryWorkflowHistoryContext = child instance key` found through `rest.children(key)`;
  `processDefinitionId = String.valueOf(definitionKey)`; `null` where the instance is unknown; an
  engine which did not answer costs the element history (`elementsHistory = null`) with a WARN once.

**Tests**: `ZenBpmWorkflowViewerTest` (fake server), Spring `ZenBpmViewerApiIT` (a workflow with a call
activity: definitions list both, XML of both resolves, history of the parent names the child context and
the child's history resolves through it).

*Note from S3.1.1, 2026-10-15.* `ZenBpmProcessService` overrides the three viewer methods with the
refusal of the skeleton (the validation message of an id without clients, otherwise "implemented in a
later story"), because the SPI's defaults would call the API one the BPMS cannot serve. This story
replaces the three overrides and removes their rows from `ZenBpmProcessServiceTest#calls`.

*Fit check, 2026-10-20, decided by the Implementor where it says so.* Checked against `main`
(`06ca83f`), `ADAPTER-AUTHORS.md` (the viewer row of its table, section 3 on the viewer methods,
section 4 on the read which waits and 4.1 on the workflow id), the platform as the adapter resolves
it (`2.0.0-20261015.045823-21`: `WorkflowViewer`, `WorkflowLocator#locate`, `ProcessDefinitionIds`,
the SPI's `ProcessDefinition`, `WorkflowHistory`, `WorkflowElementHistory`, `WorkflowElementType`),
the Camunda 8 template (`Camunda8WorkflowViewer`, `Camunda8ViewerApiIT`), and the engine at the pin
`bdd795a3` (read with `git archive` of the pin: `GetHistory` of `internal/rest/server.go`,
`FindFlowElementInstances` of `internal/sql/queries/flow_element_instance.sql`,
`FindProcessInstancesPage` of `internal/sql/queries/process_instance.sql` with the default process
types of `GetProcessInstances` in `internal/cluster/server/server.go`, `createCallActivity`,
`createSubProcess` and `resolveCalledProcessDefinition` of `pkg/bpmn/sub_process.go`,
`ResolveVersion` of `pkg/bpmn/model/extensions/io_mapping.go`, the `GetType` methods of
`pkg/bpmn/model/bpmn20`, the `Incident` and `ProcessInstancesSimple` schemas of `openapi/api.yaml`),
then probed with a scratch model against the pinned image and pinned by the contract tests below.
What did not fit, and what was decided:

1. **Which instance the viewer shows** (open question 1 of the brief). The core's `WorkflowViewer`
   hands the viewer methods the aggregate id only; the workflow id it reads from the start row goes
   to the probe of the same read (`awarenessOfWorkflow` with its fourth argument), not to the viewer
   method. So the viewer searches by business key, as the probes do, under the scoped id of the
   process, every page, with `createdFrom` at the epoch so the engine searches its index (decision
   56). Several workflows of one aggregate: one which runs (active or failed), else the one created
   last, by creation time and then key (Camunda 8 takes the first item of an unordered search). A
   workflow without a business key (timer or message start, or started past VanillaBP without one)
   is answered as unknown; GAPS 1's row says so. Decided by the Implementor, as the brief
   recommends; keeping the key the core hands the probe for the next viewer call was rejected,
   because it ties the adapter to the order the core calls in.
2. **A lagging node** (open question 5). The core waits for a workflow only inside its election; a
   viewer method's answer is final. So where the search finds nothing, it is repeated every 250 ms
   until `workflow-visibility-timeout` passed, the window the probes report (decided by the
   Implementor). A search the engine does not answer throws, naming the property keys, because an
   empty answer would read as "unknown". The history reads after the workflow was found cost the
   elements (`elementsHistory = null`, as the SPI documents for a BPMS without history) with a WARN
   once until a read succeeds again, and a model which cannot be read costs the definitions of the
   call activities, as the plan has it.
3. **`historyContext`** (open question 2): the key of the instance a call activity started, as a
   decimal string. Served only where that instance is of the process type `callActivity` and its
   parents, read through the instance cache, lead to the workflow found; another workflow's context,
   a sub-process instance, an unknown key and a key of a partition the engine does not have are
   answered as unknown, the first two with a WARN.
4. **The definitions of called processes** (open question 3). The engine at the pin binds a call
   activity to the latest version, to a version (`version`), or to the version carrying a tag
   (`bindingType="versionTag"`, falling back to `v<number>`), and refuses to deploy a model with
   some combinations (corrected after the reviews: the plan said "once the call activity is
   reached"; see the answers below). `ZenBpmModelReader` now reads that binding into
   `ZenBpmBpmnModel.CallActivity#calledVersion` the way `ResolveVersion` does, and the viewer answers
   the definition the engine would start now, from one list of versions per process called (decided
   by the Implementor: Camunda 8 answers the version the application deployed, which is not what
   ZenBPM starts). The called id is the scoped one the held model names; the plain id comes from
   the core's scoping. Call activities which start the same definition share its entry.
5. **What the history holds** (open question 4). Probed at the pin: the history of an instance
   holds only its own level. An embedded sub-process keeps its elements in a child instance of type
   `subprocess`; a multi-instance element has one entry where it stands (the type of the element)
   and a body of type `multiInstance` with one entry per iteration; an instance a call activity
   started is linked to its parent instance only, not to the call activity's entry. So the viewer
   merges the workflow's sub-process instances and bodies into one history, ordered by creation time
   and key, reports the entry where a multi-instance element stands as `MULTI_INSTANCE` (Camunda 8's
   body) and the iterations with their own type, and gives each call activity entry the first
   instance of its called process with a larger key which no earlier entry took (the engine creates
   it right after the entry, in the same partition). Markers: the XML is served as the engine holds
   it (markers and their DI included), and the marker entries and their flows are left out of the
   history, because Camunda 8's listeners never show in its history (decided by the Implementor as
   the brief recommends). An entry the engine repeats at a page boundary is taken once.
6. **What the engine does not keep** (new GAPS 33): no state of an element (a terminated element has
   a `completedAt` like a completed one), no end of an instance, and no link from a call activity to
   the instance it started. `isCanceled` is always `false`; the end of an ended workflow is the latest
   end of its own entries. Errors: the engine keeps incidents with the key of the entry they
   happened at, so an element carries the message of its unresolved incident; the incidents are read
   only where a list of instances counts some (`incidentCount`, now read into `ProcessInstanceRef`),
   or for an instance read by its key which runs. New client method
   `ZenBpmRestClient#incidentsOf`.
7. **Request count** (open question 6, decision 14): pinned in `ZenBpmWorkflowViewerTest`, see
   decision 57 (new): the definitions cost one search, the model once (through the held models of
   decision 56) and one list per process called; the history costs the search, the model, and per
   instance of the workflow's own level its history pages, one read of its children where an entry
   of it starts one, and its incidents where counted.
8. **`getBpmnXml`**: `ZenBpmClientFactory#heldBpmnOf`, which serves what this start deployed without
   a request and keeps what it read (decision 56); `null` for an id which is no key or a `404`; any
   other failure throws (decided by the Implementor: Camunda 8 answers `null` with an INFO, which the
   core reports as "the definition was deleted").
9. **The skeleton's refusal**: the three overrides of `ZenBpmProcessService` now serve the viewer;
   an adapter id without clients still answers with the message of its validation;
   `notBuiltYet` is gone with its rows in `ZenBpmProcessServiceTest`, and nothing says "later story"
   any more.
10. **Quarkus** (open question 7): the viewer lives in `core` and the platform calls it through the
    process service, so the build-time wiring needs nothing; S10.4.1 proves it in prod mode.

Decision 57 (new) records the viewer; GAPS 33 is new and GAPS 1's row names the viewer. The
README says what the viewer answers.

**Acceptance criteria**

- [x] The native definition id is the `processDefinitionKey`; the core namespaces it.
  `ZenBpmViewerApiIT` against the pinned image (Spring Boot, `use-prefix`): the workflow
  `Shipping` (a task, an embedded sub-process, a call activity of `Packing` on the same aggregate, a
  multi-instance task of two iterations, an end marker) is reported with the definitions
  `zenbpm#<key of deploy-it__Shipping>` (`Shipping`, version 1) and `zenbpm#<key of
  deploy-it__Packing>` used by `CallPacking`; the BPMN of both is the model the engine holds, the
  marker `vanillabp__ended__Shipped` included; the history lists the 18 elements in the order
  entered, the sub-process's own elements and `Label` as `MULTI_INSTANCE` and twice as
  `SERVICE_TASK` included, no marker, every element drawn in the BPMN; `CallPacking` names the
  called instance, whose history (5 elements) and definitions resolve through it. A task whose
  handler throws carries the engine's message and its workflow has no end; another workflow's
  context and a context which is no key are not served; `zenbpm#1` is an unknown definition.
  `ZenBpmWorkflowViewerTest` (35) holds the choice of the workflow, the wait, the bindings, the
  merge, the matching of called instances by key, incidents, contexts, failures and the request
  counts; `ZenBpmRestClientContractIT` (3 new cases) the engine's facts the viewer relies on;
  `ZenBpmModelReaderTest` (13 new cases) the bindings of a call activity as the engine reads them.
  Clean build of the story's commit (`TZ=Etc/UTC mvn clean install` on OpenJDK 25.0.2, Docker ITs
  included, none skipped): engine test support 12 + 5, core 1,253 + 103, Spring Boot 14 + 93, smoke
  test 38, Quarkus runtime 13, deployment 52, integration tests 20, gate 37; coverage 100 % on both
  platforms (0 of 37,445 instructions missed on Spring Boot, 0 of 37,315 on Quarkus);
  `bin/check-deploy-safety.sh` green. Mutants: 22 written, 22 killed: the markers left out, a
  context of another process type, a context not below the workflow, a running workflow first,
  the instances of sub-processes and bodies passed over, the wait for a workflow, the lower bound
  of the search, `MULTI_INSTANCE`, an instance with a smaller key taken, an instance of another
  process taken, an instance taken twice, the incidents read where none are counted, the incidents
  of an ended instance read by key, the end of a running workflow, an entry met twice, the WARN
  reset after a history read, the WARN reset after a model read (it survived first: the test's
  last failure came from a fresh process service; it now fails twice on the same one), a refused
  binding read as the latest, the `v<number>` fallback, a `404` for the BPMN, a call activity
  without a process taking an instance, and a terminated workflow taken as running.

**Answers to two reviews of e10-f10.3-f10.4, 2026-10-21** (one by Claude, changes requested
with nothing blocking: 1 Major, 3 Minor, 3 Nits; one by Copilot, changes requested: 2 Major). Every
claim was checked in the code of the branch, in the platform as the adapter resolves it and, for
the engine, at the pin `bdd795a3`. The event sub-process was raised by both, so eight distinct
findings remain; all eight were valid and are fixed. Decision 57, new on this branch, was extended
in place before the merge; no new decision was needed.

- **The elements of an event sub-process were missing from the history** (Claude 1, Copilot 1,
  Major). At the pin an event sub-process runs in a child instance of the process type `subprocess`
  which "does not own that token's flow-element history" (`buildEventSubprocessInstance` of
  `pkg/bpmn/event_subprocess_subscription_handler.go`), so nothing in the history of the instance
  it stands in starts a child, and the viewer never listed the children. Both reviewers reproduced
  it on the pinned image. The model reader now records which start event opens which event
  sub-process (`ZenBpmBpmnModel#eventSubProcessStarts`); the viewer lists the children of every
  instance whose model has one, merges the child's elements, and reports the child as an
  `EVENT_SUB_PROCESS` element, from the child's creation to the latest end of its entries, as
  Camunda 8 does. The end of an ended workflow is now the latest end of all its elements, because
  an interrupting event sub-process ends the workflow after its own last element. Tests:
  `ZenBpmWorkflowViewerTest#anEventSubProcessIsReportedWithItsElements`,
  `#aRunningEventSubProcessHasNoEnd`,
  `ZenBpmModelReaderTest#theStartEventsOfEventSubProcessesNameTheirEventSubProcess`, and against
  the pinned image `ZenBpmRestClientContractIT#anEventSubProcessWritesNoEntryWhereItStands`, whose
  answers the unit fixtures copy. `ZenBpmViewerApiIT` was not extended: an event
  sub-process in its `Shipping` would have to fire within the few milliseconds the workflow runs.
  Decision 57, GAPS 33 and the README say it.
- **A history context not visible yet was refused at once** (Copilot 2, Major). The search for the
  workflow waited out `workflow-visibility-timeout`; the read of the context's instance and of its
  parents did not, and the core does not repeat a viewer read (GAPS 20: a follower answers
  `404` for an instance the node of the parent history showed). Every read which finds nothing is
  now repeated within one deadline for the whole read; a walk which meets a root other than the
  workflow is still refused at once. Test
  `ZenBpmWorkflowViewerTest#aContextNotVisibleYetIsReadAgain` (a `404` for the context and for a
  parent, each answered on the second read; the context of another workflow read once).
- **A failed list of versions escaped unwrapped** (Claude 2, Minor). It now costs the definitions
  of the call activities with the viewer's WARN, as a model which cannot be read does, and the WARN
  state is reset only after the lists were read. Test
  `ZenBpmWorkflowViewerTest#aListOfVersionsTheEngineDoesNotAnswerCostsTheCalledDefinitions`.
- **The viewer ignored `message-start-lookup: scan`** (Claude 3, Minor). The brief recommended the
  probes' lookup as far as it works without a hint, and the scan needs none. Where the search finds
  no workflow which runs and the process is scanned, the viewer takes the running root instance the
  probes' scan finds by the id variable the aggregate's persistence names
  (`ZenBpmMessageStartScan#processesToScan` gained an overload by workflow module). Test
  `ZenBpmWorkflowViewerTest#underScanAWorkflowAMessageStartedIsFound` (the scanned workflow beats an
  ended keyed one; without an id variable the ended one is shown; an instance of a sub-process the
  scan finds is not taken; a keyed workflow which runs costs no scan). GAPS 1, decision 57 and the
  README name what stays unknown (timer starts, message starts under `start-row` or ended), now
  answered after the visibility window.
- **A refused binding was said to fail "once the call activity is reached"** (Claude 4, Minor).
  `validateCallActivityVersions` of `pkg/bpmn/model/bpmn20/helper.go` runs `ResolveVersion` while
  the model is parsed, so the engine refuses the deployment (Claude probed `400`). Decision 57, the
  javadoc of `CallActivity#calledVersion`, the reader's comments, the test's display name and
  fit-check item 4 say so now.
- **`version=" 1 "` read as refused** (Claude 5, Nit). Go parses the number without the whitespace
  around it; the reader strips it now. Test: a new case of
  `ZenBpmModelReaderTest#aCallActivityStartsTheVersionTheEngineResolves`.
- **Two comments stood above the wrong method** (Claude 6, Nit): moved back in `ZenBpmModelReader`
  and `ZenBpmWorkflowLifecycleTest`.
- **`+` concatenation in `ZenBpmWorkflowViewerTest`** (Claude 7, Nit): `String#formatted` now.

Not a finding: Claude's first build failed once in
`ZenBpmMessageIT#aPublicationNothingEverWaitsForIsBlockedOnceTheWindowRanOut`, outside the batch,
while two builds and a probe engine shared the machine; it was green in the rerun, in Copilot's
build and in the builds below.

Mutants of the new guards: 13 written, 13 killed: the children listed for an event sub-process, the
`EVENT_SUB_PROCESS` element, its end only once ended, the workflow's end over every element, the
WARN reset after the lists, no scan where a keyed workflow runs, no scan without an id variable (it
survived first: the answer was the same, so the test now also counts the scan's requests), no
sub-process instance taken from the scan, the scan at all, the context and the parent read again,
the version stripped, and only event sub-processes' start events recorded. Clean build of the
review-fix commit (`TZ=Etc/UTC mvn clean install` on OpenJDK 25.0.2, Docker ITs included, none
skipped): engine test support 12 + 5, core 1,260 + 104 (`ZenBpmWorkflowViewerTest` 40,
`ZenBpmModelReaderTest` 37, `ZenBpmRestClientContractIT` 32), Spring Boot 14 + 93, smoke test 38,
Quarkus runtime 13, deployment 52, integration tests 23, gate 37; coverage 100 % on both platforms
(0 of 37,699 instructions missed on Spring Boot, 0 of 37,569 on Quarkus);
`bin/check-deploy-safety.sh` green.

**CodeRabbit on pull request #71** (one actionable comment, valid): the class javadoc of
`ZenBpmWorkflowViewer` cited "(decision 25)" instead of the greppable form the repository's
`AGENTS.md` asks for; it now reads "see decision 25 in the repository's DECISIONS.md", the only
such citation in the batch's Java files. Its pre-merge "docstring coverage 41.88 %" warning is
generic (the repository documents classes and non-private methods, not every test and private
method) and was not acted on. Clean build of the fix (`TZ=Etc/UTC mvn clean install`): the same
counts as above, none skipped, coverage 100 % on both platforms (0 of 37,699 and 0 of 37,569
instructions missed); `bin/check-deploy-safety.sh` green.

## F10.4 Quarkus twins

### S10.4.1 Versions and viewer on Quarkus

- [x] **Depends on:** S10.3.1.

`ZenBpmWorkflowLifecycleTest#theViewerServesDefinitionXmlAndHistory`, `#aVersionedMethodRoutes`.

*Fit check, 2026-10-20, decided by the Implementor where it says so.* Checked against the branch
after S10.3.1, the Quarkus integration tests as they are (`ZenBpmWorkflowLifecycleTest`, one booted
application per class against one engine), and the fit checks of S10.1.1 (item 6), S10.2.1 (item
8) and S10.3.1 (item 10), which left their Quarkus twins to this story. What did not fit, and what
was decided:

1. **One boot, but versions need a history.** A prod-mode test boots its application once per
   class, and the application deploys while it starts, so the older version a version test needs
   has to be at the engine before that boot. Decided by the Implementor: the test's static
   initializer, which already starts the engine before the application's runtime properties are
   read, deploys version 1 of the processes `Sorting` and `Storing` over the engine's REST API,
   under the prefixed ids and job types the adapter gives them, and starts a workflow on version 1
   of `Storing`, as an earlier version of the application would have. The application then deploys
   version 2 of each from its own files. No second application or class is needed.
2. **`#aVersionedMethodRoutes`** routes by version **tags**, because E10's "Done when" names
   `@WorkflowTask(version = "tag")`: version 1 carries the tag `old-belt`, the application's version
   2 the tag `new-belt`, and `E2eSortingWorkflow` serves the task `sort` with one method per tag. A
   workflow the application starts runs on version 2 and is served by the method of `new-belt`; a
   workflow the test starts on version 1 over the engine's REST API, for an aggregate the
   application kept, by the method of `old-belt`.
3. **The twin of `ZenBpmOldProcessVersionsIT`** is `#theBootNamesAnOlderVersionItsWorkflowAndWhatItMisses`:
   the application's log names version 1 of `Storing`, the workflow still running on it, its task
   `weigh` which no method serves, and that such a workflow's end is not reported to a
   `@WorkflowEnded` method, with the platform's published phrases. The part of the Spring Boot test
   which boots again with `outfaded-versions` is not repeated: it is the platform's switch, not this
   adapter's, and a second boot would need a second class with an engine of its own.
4. **`#theViewerServesDefinitionXmlAndHistory`**: the process `Dispatching`, a copy of the Spring
   Boot viewer fixture, under the endpoints `introspect/viewer/...` of `E2eViewerIntrospection`.
   Nothing in the Quarkus build-time wiring had to change for the viewer or the versions (open
   question 7 of the brief): the prod-mode tests pass against the extension as it was.

**Acceptance criteria**

- [x] The versions and the viewer work on a booted Quarkus application as on Spring Boot.
  `ZenBpmWorkflowLifecycleTest` against the pinned image, three new cases:
  `#theViewerServesDefinitionXmlAndHistory` (the definitions of `Dispatching` and of `Boxing`, used
  by `CallBoxing`, both namespaced `zenbpm#`; the BPMN of both as the engine holds it, the end marker
  included; the 18 elements of the history in the order entered, `Label` as `MULTI_INSTANCE` and twice
  as `SERVICE_TASK`; the history and the definitions of the called instance through its context; a
  context which is no key answered as not found), `#aVersionedMethodRoutes` (version 2, the
  application's, served by the method of `new-belt`, version 1 by that of `old-belt`) and
  `#theBootNamesAnOlderVersionItsWorkflowAndWhatItMisses`. Sensitivity checked: with the two tags
  of the methods swapped and no workflow started on version 1 of `Storing`, both version cases
  fail. No new guard in production code, so no mutant of one. Clean build of the story's commit
  (`TZ=Etc/UTC mvn clean install` on OpenJDK 25.0.2, Docker ITs included, none skipped): engine
  test support 12 + 5, core 1,253 + 103, Spring Boot 14 + 93, smoke test 38, Quarkus runtime 13,
  deployment 52, integration tests 23 (`ZenBpmWorkflowLifecycleTest` 22), gate 37; coverage 100 %
  on both platforms (0 of 37,445 instructions missed on Spring Boot, 0 of 37,315 on Quarkus);
  `bin/check-deploy-safety.sh` green. The first build of the story failed in the gate's
  `MethodOrderTest`: the new cases stood below the first helper of the class; they were moved.

**E10 "Done when", checked 2026-10-20 at the end of the epic.** Met on both platforms:

- *`@WorkflowTask(version = "tag")` routes:* Spring Boot `ZenBpmProcessVersionIT#aTagResolves`,
  Quarkus `ZenBpmWorkflowLifecycleTest#aVersionedMethodRoutes` (tags `old-belt` and `new-belt`).
- *The boot names old versions with running workflows and what they miss:* Spring Boot
  `ZenBpmOldProcessVersionsIT`, Quarkus
  `ZenBpmWorkflowLifecycleTest#theBootNamesAnOlderVersionItsWorkflowAndWhatItMisses`.
- *A viewer shows definitions, XML and history including a call activity:* Spring Boot
  `ZenBpmViewerApiIT`, Quarkus `ZenBpmWorkflowLifecycleTest#theViewerServesDefinitionXmlAndHistory`.

What E10 leaves to the engine (GAPS 1, 32 and 33): a business key for a workflow the engine starts
(E13.4), a tag several versions may carry, and a state on an entry of the history, an end on an
instance and the element instance which started a child; all out of the pipeline's scope. The
adapter's answers above hold until then.

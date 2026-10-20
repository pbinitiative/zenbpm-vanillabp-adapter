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
   `#aRolledBackTaggedModelGetsItsOldVersionBack`, and said in the README and as item 18 of the
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
  `#aRolledBackTaggedModelGetsItsOldVersionBack` GAPS 32; `ZenBpmProcessVersionsTest` the one list
  request, oldest first with the tags, and the deployed version known without a request.
  Clean build of the story's commit (`TZ=Etc/UTC mvn clean install` on OpenJDK 25.0.2, Docker ITs
  included, none skipped): engine test support 12 + 5, core 1,181 + 100, Spring Boot 14 + 88, smoke
  test 38, Quarkus runtime 13, deployment 52, integration tests 20, gate 37; coverage 100 % on both
  platforms (0 of 34,633 instructions missed on Spring Boot, 0 of 34,503 on Quarkus);
  `bin/check-deploy-safety.sh` green. Mutants: 4 written, 4 killed (the version recorded from the
  deployment, the registration in `wireBpmn`, the `null` catalog of an adapter id without clients,
  the order of the versions).

### S10.2.1 Old versions, renamed processes, open task count

- [ ] **Depends on:** S10.1.1.

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

**Acceptance criteria**

- [ ] The report names version, count and what older versions miss; no page of instances is fetched.

## F10.3 The viewer

### S10.3.1 `ZenBpmWorkflowViewer`

- [ ] **Depends on:** S10.1.1.

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

**Acceptance criteria**

- [ ] The native definition id is the `processDefinitionKey`; the core namespaces it.

## F10.4 Quarkus twins

### S10.4.1 Versions and viewer on Quarkus

- [ ] **Depends on:** S10.3.1.

`ZenBpmWorkflowLifecycleTest#theViewerServesDefinitionXmlAndHistory`, `#aVersionedMethodRoutes`.

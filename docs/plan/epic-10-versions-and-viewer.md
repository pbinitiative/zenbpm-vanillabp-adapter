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

- [ ] **Depends on:** E4.

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

**Acceptance criteria**

- [ ] A version specification naming a tag routes; a tag nobody deployed is reported once at startup.

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

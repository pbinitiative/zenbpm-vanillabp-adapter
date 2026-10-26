The [VanillaBP blueprints](https://github.com/vanillabp-blueprints/blueprints) show how a VanillaBP
application is built, one blueprint per topic, each as a Spring Boot and a Quarkus twin. They
belong to the VanillaBP project and choose their BPMS by a Maven profile, `camunda7` or `camunda8`;
there is no profile for ZenBPM in them. This adapter adds one from outside, with an overlay kept in
its repository. It builds the blueprints with it against the engine it is tested against, see
[Home](Home#the-engine-this-adapter-is-tested-against): every night where the blueprints, VanillaBP
or the adapter changed, and every Sunday (`NightlyWorkflowTest`, `NightlyChangeDetectionTest`).

## What the overlay adds

The overlay is a program, `blueprints-overlay/ZenBpmOverlay.java` of the adapter's repository,
which derives what a blueprint needs for ZenBPM from what it has for Camunda 8, the other remote
engine
([decision 64](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/blob/main/DECISIONS.md#64-the-blueprints-run-on-zenbpm-through-an-overlay-of-this-repository-built-every-night)):

- beside every profile `camunda8` a profile `zenbpm`, which selects
  `org.pbinitiative.zenbpm:zenbpm-vanillabp-adapter-spring-boot` or the Quarkus extension
  `zenbpm-vanillabp-adapter-quarkus` in place of the Camunda 8 adapter, and the property
  `zenbpm-vanillabp-adapter.version` beside the version of the Camunda 8 adapter
  (`BlueprintsOverlayTest#everyCamunda8ProfileGetsAZenBpmProfile`);
- beside every `application-camunda8.yaml` an `application-zenbpm.yaml` with the two keys a ZenBPM
  application writes, `rest-address: http://localhost:18080` and `name-clash-avoidance: use-prefix`
  (`BlueprintsOverlayTest#everyCamunda8ConfigurationGetsAZenBpmOne`);
- beside every directory `processes/camunda8` a directory `processes/zenbpm` with the same models,
  which the engine reads as they are, `zeebe:` extensions included; where a model has to differ, the
  overlay brings its ZenBPM variant instead
  (`BlueprintsOverlayTest#theModelsAreCopiedAndAVariantReplacesItsModel`);
- in those models, a user task which names only the external reference of its form gets that
  reference as its task definition, because the Camunda 8 adapter finds a user task by its form and
  this one by its task definition, see
  [The BPMN model for ZenBPM](Configuration#the-bpmn-model-for-zenbpm)
  (`BlueprintsOverlayTest#aUserTaskNamedByItsFormGetsItsTaskDefinition`);
- beside every other file named for Camunda 8, such as the Liquibase changelog
  `db/changelog-camunda8.xml` of a build without an embedded engine, a copy named for ZenBPM, the
  other remote engine, where the file names no Camunda
  (`BlueprintsOverlayTest#anotherFileForCamunda8IsCopied`).

Nothing else of a blueprint changes: its Java code, its tests and its other configuration run on
ZenBPM as they are. Two models have a variant today: the business rule task of
`bpmn-business-rule-task` maps the decision's result into the process
([GAPS 27](Deviations#gaps-27-a-decisions-result-needs-an-output-mapping) and
[GAPS 34](Deviations#gaps-34-a-decision-with-one-named-output-hands-a-context)), and the
multi-instance sub-process of `bpmn-multi-instance-subprocess` maps the partners its inner loop
iterates over into every iteration
([GAPS 31](Deviations#gaps-31-an-iteration-reads-only-what-its-element-maps-in)).

The engine is the one the page [Home](Home#an-engine-to-try-it-against) starts, on the same ports,
with the settings of the adapter's own integration tests, such as due timers looked for every
second, and with one addition: a task whose model names no `retries` gets three attempts, as on
Camunda 8, which the blueprints' models are written for (`JOBS_DEFAULT_RETRIES=3`, see
[The BPMN model for ZenBPM](Configuration#the-bpmn-model-for-zenbpm);
`BlueprintsOverlayTest#theEngineGivesATaskWithoutRetriesThreeAttempts`).

## Which blueprints run on ZenBPM

|                       Blueprint                        |   On ZenBPM   |                                                                                       Why not, or what differs                                                                                       |
|--------------------------------------------------------|---------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `blueprints-bpmn/bpmn-adhoc-subprocess`                | not on ZenBPM | Its point is the ad-hoc sub-process, which ZenBPM cannot run, so its model is refused before anything is deployed ([GAPS 14](Deviations#gaps-14-constructs-the-engine-refuses-or-runs-differently)). |
| `blueprints-bpmn/bpmn-aggregate-decoupling`            | runs          |                                                                                                                                                                                                      |
| `blueprints-bpmn/bpmn-async-task`                      | runs          |                                                                                                                                                                                                      |
| `blueprints-bpmn/bpmn-boundary-events`                 | runs          |                                                                                                                                                                                                      |
| `blueprints-bpmn/bpmn-bpms-initiated-start`            | not on ZenBPM | One of its two starts is a broadcast signal: the signal start event is refused before anything is deployed and `sendSignal` is not offered ([GAPS 13](Deviations#gaps-13-no-signals)).               |
| `blueprints-bpmn/bpmn-business-rule-task`              | runs          | with the ZenBPM variant of its model, which maps the decision's result into the process                                                                                                              |
| `blueprints-bpmn/bpmn-call-activity-decomposition`     | runs          |                                                                                                                                                                                                      |
| `blueprints-bpmn/bpmn-error-escalation`                | not on ZenBPM | Its point is the escalation beside the error, and escalation events are refused before anything is deployed ([GAPS 14](Deviations#gaps-14-constructs-the-engine-refuses-or-runs-differently)).       |
| `blueprints-bpmn/bpmn-gateways`                        | runs          |                                                                                                                                                                                                      |
| `blueprints-bpmn/bpmn-history-and-diagram`             | runs          |                                                                                                                                                                                                      |
| `blueprints-bpmn/bpmn-message-correlation`             | runs          |                                                                                                                                                                                                      |
| `blueprints-bpmn/bpmn-message-start`                   | runs          |                                                                                                                                                                                                      |
| `blueprints-bpmn/bpmn-multi-instance-subprocess`       | runs          | with the ZenBPM variant of its model, which maps the partners into every iteration of the sub-process                                                                                                |
| `blueprints-bpmn/bpmn-multi-instance-task`             | runs          |                                                                                                                                                                                                      |
| `blueprints-bpmn/bpmn-process-sections`                | runs          |                                                                                                                                                                                                      |
| `blueprints-bpmn/bpmn-service-task`                    | runs          |                                                                                                                                                                                                      |
| `blueprints-bpmn/bpmn-signals`                         | not on ZenBPM | Its point is signals, which ZenBPM has none of ([GAPS 13](Deviations#gaps-13-no-signals)).                                                                                                           |
| `blueprints-bpmn/bpmn-timer`                           | runs          |                                                                                                                                                                                                      |
| `blueprints-bpmn/bpmn-user-task`                       | runs          |                                                                                                                                                                                                      |
| `blueprints-bpmn/bpmn-versioning`                      | runs          |                                                                                                                                                                                                      |
| `blueprints-bpmn/bpmn-workflow-ended`                  | runs          |                                                                                                                                                                                                      |
| `blueprints-modules/module-bpms-migration`             | not on ZenBPM | It migrates from Camunda 7 to Camunda 8 and needs both adapters, which live in package registries that ask for a token; a migration to ZenBPM would be a blueprint of its own.                       |
| `blueprints-modules/module-idle-application`           | runs          |                                                                                                                                                                                                      |
| `blueprints-modules/module-interaction`                | runs          |                                                                                                                                                                                                      |
| `blueprints-modules/module-multi`                      | runs          |                                                                                                                                                                                                      |
| `blueprints-modules/module-packaging`                  | runs          |                                                                                                                                                                                                      |
| `blueprints-modules/module-single`                     | runs          |                                                                                                                                                                                                      |
| `blueprints-modules/module-standalone`                 | runs          |                                                                                                                                                                                                      |
| `blueprints-persistence/persistence-active-record`     | runs          |                                                                                                                                                                                                      |
| `blueprints-persistence/persistence-audited-aggregate` | runs          |                                                                                                                                                                                                      |
| `blueprints-persistence/persistence-custom`            | runs          |                                                                                                                                                                                                      |
| `blueprints-persistence/persistence-flyway`            | runs          |                                                                                                                                                                                                      |
| `blueprints-persistence/persistence-liquibase`         | runs          |                                                                                                                                                                                                      |
| `blueprints-persistence/persistence-mongodb`           | runs          |                                                                                                                                                                                                      |
| `blueprints-persistence/persistence-parallel-branches` | runs          |                                                                                                                                                                                                      |

`BlueprintsOverlayTest#theListAndThePageBlueprintsAgree` holds this table to the overlay's list.

## When the overlay no longer fits

The blueprints change without this adapter. Where they change in a way the overlay cannot follow,
the overlay ends the run naming what it met and what to do, and the nightly turns red, instead of
building less than this page says:

- a blueprint which is not in the overlay's list, or one of the list which is gone
  (`BlueprintsOverlayTest#aBlueprintTheListDoesNotNameEndsTheRun`,
  `BlueprintsOverlayTest#aListedBlueprintWhichIsGoneEndsTheRun`);
- a profile `camunda8` with more than the Camunda 8 adapter, a POM which chooses a BPMS without a
  profile `camunda8`, a profile of a BPMS activated by more than `activeByDefault`, which
  `-Pzenbpm` would leave on, and a twin which does not declare the Camunda 8 adapter's version
  (`BlueprintsOverlayTest#aCamunda8ProfileWithMoreThanTheAdapterEndsTheRun`,
  `BlueprintsOverlayTest#aPomChoosingABpmsWithoutACamunda8ProfileEndsTheRun`,
  `BlueprintsOverlayTest#aBpmsProfileActivatedByMoreThanItsDefaultEndsTheRun`,
  `BlueprintsOverlayTest#aTwinWithoutTheVersionOfTheCamunda8AdapterEndsTheRun`);
- an `application-camunda8.yaml` with more than the overlay translates, and another file named for
  Camunda 8 which names Camunda
  (`BlueprintsOverlayTest#aCamunda8ConfigurationWithMoreThanTheOverlayTranslatesEndsTheRun`,
  `BlueprintsOverlayTest#anotherFileForCamunda8WhichNamesCamundaEndsTheRun`);
- a model which changed since its ZenBPM variant was written from it, and a variant whose model is
  gone (`BlueprintsOverlayTest#aModelWhichChangedSinceItsVariantEndsTheRun`,
  `BlueprintsOverlayTest#aVariantWhichReplacesNoModelEndsTheRun`).

A blueprint which builds but fails a test on ZenBPM turns the nightly red as well: the script builds
every other twin all the same and ends with 1
(`BlueprintsOverlayTest#theBuildScriptEndsRedWhereATwinFailed`), as it does where the overlay does
not fit (`BlueprintsOverlayTest#theBuildScriptEndsRedWhereTheOverlayDoesNotFit`).

## Running a blueprint on ZenBPM

With a checkout of the adapter, Docker, and Java 21 or newer:

```bash
# the adapter of the checkout, which the profile 'zenbpm' reads from the local Maven repository
mvn -Dmaven.test.skip=true --projects spring-boot,quarkus/deployment --also-make install
# a fresh clone of the blueprints, the overlay applied, and the twins named built against the engine
bin/build-blueprints-on-zenbpm.sh /tmp/blueprints blueprints-bpmn/bpmn-service-task/springboot
```

Without a twin named, the script builds every twin of the table above, each against an engine of its
own, which `bin/zenbpm-engine.sh` starts and removes again. The engine has a fixed name and fixed
ports, so one run at a time works on a machine: a start ends with 1 where the engine of another run
is running and leaves it alone
(`BlueprintsOverlayTest#theEngineScriptLeavesTheEngineOfAnotherRunAlone`,
`BlueprintsOverlayTest#theBuildScriptLeavesTheEngineOfAnotherRunAlone`), and a container which does
not start ends the start as well, also where another engine answers on the port
(`BlueprintsOverlayTest#theEngineScriptEndsRedWhereItsContainerDoesNotStart`). It clones into a
directory which does not exist yet only
(`BlueprintsOverlayTest#theBuildScriptRefusesADirectoryWhichExists`). Once the overlay is applied,
the clone is a checkout of the blueprints like any other: start the engine with
`bin/zenbpm-engine.sh start` and build a twin with `mvn -Pzenbpm verify`, with
`blueprints-overlay/settings.xml` of the adapter as its Maven settings.

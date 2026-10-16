# E3 - Platform registration (Spring Boot and Quarkus)

**Goal.** An application with the adapter on the classpath boots on both platforms, gets one
`ZenBpmProcessService` and one `ZenBpmDeploymentService` per configured adapter id of type `zenbpm`,
validates its configuration at startup with the three outcomes, and reports health - while every SPI
method which needs the engine still throws `UnsupportedOperationException("<method> is implemented in a
later story")` (never a silent stub).

**Done when.** The smoke tests of both platforms boot with zero, one and two adapter ids, and the
guiding messages are asserted through `CapturedOutput`, in the block the platform's `StartupReport`
writes at the end of the start.

Templates: `camunda8-adapter/spring-boot` (announcement, `BeanRegistrar`, overlay) and
`camunda7-adapter/spring-boot` (`applicationBean` helper); `camunda8-adapter/quarkus` and
`camunda7-adapter/quarkus` (`StartupObserver` for eager validation); the platform's dummy adapters.
The rules in `ADAPTER-AUTHORS.md` section 6 are binding: element beans only on Spring, `@Singleton`
list producers with literal `Object` type arguments on Quarkus, overlay never `@Inject`ed, the id set
always from the core properties.

---

## F3.1 Spring Boot

### S3.1.1 Auto-configuration, registrar, overlay, smoke tests

- [ ] **Depends on:** E2.

**Instructions**

1. `spring-boot` module (artifact `zenbpm-vanillabp-adapter-spring-boot`), depends on `core` and
   `vanillabp-spring-boot-integration`; runs the configuration processor for IDE metadata.
2. `org.pbinitiative.zenbpmadapter.springboot.ZenBpmAdapterConfiguration extends AdapterConfigurationBase`,
   `@AutoConfiguration(before = SpringBootMigrationAdapterAutoConfiguration.class)`, returns the type.
   Nothing else in it.
3. `client.VanillaBpZenBpmProperties` = `@ConfigurationProperties("vanillabp")` overlay with
   `adapters: Map<String, ZenBpmAdapterKeys>` (all keys of the architecture table) and the module and
   workflow levels for the scoped keys (`retry-backoff`, `history-time-to-live`,
   the module level of `accept-unscoped-identifiers`; `name-clash-avoidance` is the core's) with a
   resolver `retryBackoffFor(module, process, task, id)`
   etc. (four levels, most specific wins - copy `PeaFetchVariables`' resolver shape).
4. `client.ZenBpmClientAutoConfiguration` (`after = SpringBootMigrationAdapterAutoConfiguration`):
   `@EnableConfigurationProperties`, a `ZenBpmClientRegistry` bean built by iterating
   `MigrationAdapterProperties.adapterTypes()` for `zenbpm` ids (never the overlay's keys), running
   `ZenBpmAdapterConfiguration.validate` per id with `isNowhereFirst` and the deployment-failure
   policy from the core properties. The result decides, the registry does not repeat the rule.
   Findings go to the platform's `StartupReport` bean (a constructor parameter), with topic
   `CONFIGURATION` and the scope `zenbpm adapter '<id>'`, never inside the text, so two ids with the
   same finding fold into one entry: every `notices()` line as `notice`; UNCONFIGURED as `warn`;
   INCONSISTENT throws the message where `endsTheBoot()`, because no factory can be built, else it
   is a `warn` and the factory is marked unconfigured (only an address or client id defect of a
   nowhere-first id with `warn` degrades; a number or duration which cannot work always ends the
   boot); COMPLETE builds the factory. Where no `StartupReport` exists (a test building the registry
   by hand), the findings go to the log at the same levels, as Camunda 8's
   `Camunda8DeploymentService` does. A `ZenBpmExecutor` per id
   inside the factory. Application name from
   `spring.application.name`, default `application`.
5. `processservice.ZenBpmAdapterProcessServiceConfiguration` importing `ZenBpmAdapterBeanRegistrar`
   (a `BeanRegistrar`) which, with `AdapterBeanRegistrarSupport.forEachConfiguredAdapterId(environment,
   ZenBpmAdapter.ADAPTER_TYPE, ...)`, registers per id the element beans `ZenBpm_ProcessService_<id>`
   and `ZenBpm_DeploymentService_<id>` with lazy suppliers resolving the registry, the overlay and
   `AdapterBeanRegistrarSupport.collaborators(supplierContext, adapterId)`. Both core classes exist as
   skeletons in this story: constructor with `AdapterCollaborators`, `getAdapterId/Type`,
   `getModelType`/`getProcessContextType`, `checkHealth()` -> `ZenBpmHealth`,
   `VanillaBpParts.requireAdapterFitsPlatform(ZenBpmAdapter.ADAPTER_TYPE, ...)` in the constructor
   of the deployment service only, not of the process service (the platform replaced
   `AdapterPlatformVersion` on 2026-09-18; Camunda 8 calls it in `Camunda8DeploymentService` alone);
   the deployment service takes the `StartupReport` as Camunda 8's does; every
   pipeline and operation method throws the "later story" exception; `phaseOperations()` returns a
   map with a throwing handler per REQUIRED operation (the boot refuses a missing required one).
6. `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` listing the
   three classes.
7. `smoke-test` module: `SmokeTestApplication` (JPA H2 with the platform's JDBC outbox, which is the
   default since gruelbox left the platform, platform decision 102;
   `NoPersistenceForTheSampleAggregate` pattern), a `META-INF/workflow-module`, no BPMN files. Every
   workflow aggregate of the smoke and integration-test applications marks what it does not share
   with `@NoSyncWithBPMS`, or its workflow sets
   `vanillabp.workflow-modules.<m>.workflows.<p>.allow-full-sync-with-bpms: true`: an aggregate
   which shares everything ends the start otherwise (`FullSyncCheck`, platform decision 66).
   Tests, all with `spring.autoconfigure.exclude` of the platform's `DeploymentAutoConfiguration` or an
   `ApplicationContextRunner` where the pipeline would otherwise run the stubs:
   - `ZenBpmAdapterDiscoveryTest`: id = type, both element beans present, registry knows the id;
   - `ZenBpmTwoAdapterIdsDiscoveryTest`: two ids, two of each bean, named after the ids;
   - `ZenBpmStartupValidationBootTest`: unconfigured boots with the warning naming
     `vanillabp.adapters.<id>.rest-address` in the report block at the end of the start
     (CapturedOutput, asserted by content under the heading `CONFIGURATION`), and a use of the
     adapter fails with the same message; inconsistent first-priority ends the boot naming the
     keys; an address defect of a nowhere-first id with `deployment-failure: warn` boots degraded,
     `worker-threads: 0` on the same id still ends the boot; a notice appears once in the block;
     complete boots with nothing in the block from this adapter; the deployment service's
     `StartupReport` is the platform's bean (a test asks for it, because a broken wiring would
     write the same lines to the log and nobody would notice);
   - `ZenBpmHealthBootTest`: actuator health shows the adapter UNKNOWN when unconfigured, DOWN
     against a closed port (bounded time).

   The platform's BPMS double (`io.vanillabp:bpms-double-spring-boot`, S11.3.1) is worth trying in
   place of the exclusion: if a test can let the pipeline run with the double as first adapter and
   this adapter's stubs never reached, it tests more of the real boot. Keep the exclusion where it
   cannot.

*Note from S2.3.1, 2026-10-14.* `ProtobufRuntimeCheck.defect()` in `core` says why the
application's protobuf runtime cannot run the job stream (older than the `protoc` of this build, or
without `com.google.protobuf.RuntimeVersion`). Call it while the application starts, before any
factory is built, and end the boot with its message where it answers one; a boot test puts an old
runtime on the classpath or calls the check through a class loader which hides `RuntimeVersion`, as
`ProtobufRuntimeCheckTest` does. The stream's constructor runs it again as a backstop.

*Note from S2.4.1, 2026-10-15.* For a COMPLETE result the registry gets
`new ZenBpmClientFactory(id, configuration, applicationName)`, which builds the executor, the job
stream and the REST client itself and contacts nothing. For UNCONFIGURED, and for an INCONSISTENT
result which does not end the boot, it gets `ZenBpmClientFactory.unconfigured(id, configuration,
result.message())`: every client of it throws that message, and `ZenBpmHealth.check` answers
UNKNOWN with it. The configuration is never `null` there (`unconfigured` refuses it): an id which
sets nothing but its type gets a configuration with every key unset. `new ZenBpmClientRegistry(factories)` takes them as a list in the order of the ids
and refuses two of one id. Where it refuses, the factories built for it are not closed by
anybody, and each has started its HTTP client's thread: close them before the boot ends, unless
each factory is a bean of its own which the container closes. `checkHealth()` is
`ZenBpmHealth.check(registry.getFactory(id))` in package `org.pbinitiative.zenbpmadapter.health`. The registry bean's `close()` closes every factory.

**Acceptance criteria**

- [x] No bean of type `List<...>` is registered; the platform's `ObjectProvider` streams find both
  services per id. `ZenBpmAdapterDiscoveryTest#thePlatformFindsTheElementBeans` finds the one
  process service and the one deployment service of id `zenbpm` through the platform's streams and
  no bean of type `List` with `zenbpm` in its name; `ZenBpmTwoAdapterIdsDiscoveryTest` finds two of
  each, named after `zenbpm-new` and `zenbpm-old`, and a factory with clients per id.
- [x] Every message asserted by content, including the property key, where the platform writes it:
  in the `StartupReport` block, or in the exception which ends the boot.
  `ZenBpmStartupValidationBootTest` runs the whole start, deployment included, and cuts the
  `CONFIGURATION` section out of the block at its end (`StartupBlock`): the unconfigured id stands
  there under `zenbpm adapter 'zenbpm'` with `'vanillabp.adapters.zenbpm.rest-address' is missing`,
  and the adapter's own START_WORKFLOW handler then fails with the same message; the degraded id
  `old` (nowhere first, `deployment-failure: warn`, an `ftp://` address) stands there with
  `vanillabp.adapters.old.rest-address` and `boots DEGRADED`; the `/v1` notice stands there once and
  nowhere else in the log; a complete id leaves no `CONFIGURATION` section and no line with its
  scope, derives its client id from `spring.application.name` (`smoke-test-zenbpm-...`), and its
  handler says `phase one of START_WORKFLOW is implemented in a later story`. The start ends with
  the message, read from the root cause, for a first-priority id with an `ftp://` address (naming
  `vanillabp.adapters.zenbpm.rest-address` and why it cannot degrade), for `worker-threads: 0` on
  the degradable id (naming `vanillabp.adapters.old.worker-threads`) and for a negative
  `retry-backoff` at task level (naming the full key the application wrote).
  `ZenBpmHealthBootTest` holds the health side: UNKNOWN with the message naming `rest-address`, and
  DOWN within the 5 s `assertTimeoutPreemptively` allows against a port nothing listens on, with
  `request-timeout: PT1S`, the address and the job stream's state in the details.
- [x] A typo under `vanillabp.adapters.<id>.` is ignored by Spring (documented as the difference to
  Quarkus, where it fails). `ZenBpmStartupValidationBootTest#aMisspeltKeyIsIgnored` boots with
  `rest-adress` set and finds the warning about the missing `rest-address` in the block. The
  javadoc of `VanillaBpZenBpmProperties` says so until the wiki's `Configuration` page exists (note
  under S12.1.1).

**Implemented 2026-10-15.** `spring-boot`: `ZenBpmAdapterConfiguration` (the announcement),
`ZenBpmAdapterBeanRegistrar` and `processservice.ZenBpmAdapterProcessServiceConfiguration`,
`client.ZenBpmClientAutoConfiguration`, the overlay `client.VanillaBpZenBpmProperties` with
`client.ZenBpmAdapterKeys`, the three classes in the auto-configuration imports, and the
configuration processor, whose metadata names the two maps `vanillabp.adapters` and
`vanillabp.workflow-modules` only: Spring's metadata format does not describe the keys inside a map
value, so the keys are documented in the javadoc of `ZenBpmAdapterKeys` and, under S12.1.1, on the
wiki. `core`: `client.ZenBpmStartupValidation` (what both platforms
do at startup), `deployment.ZenBpmDeploymentService` and `processservice.ZenBpmProcessService` as
skeletons, the empty type parameters `model.ZenBpmBpmnModel` and `ZenBpmProcessingContext`, and
`ZenBpmAdapterConfiguration.addScopedValue`. The Spring Boot classes are adapted from Camunda 8's
and keep their licence (decision 17); the root POM's licence comment now says that the Spring Boot
jar ships adapted classes too. `smoke-test`: `SmokeTestApplication` on JPA and H2 with the
platform's JDBC outbox, `@NoSyncWithBPMS` on its aggregate, workflow module `smoke-app` without
BPMN files. Tests: `ZenBpmStartupValidationTest` (13), `ZenBpmDeploymentServiceTest` (7),
`ZenBpmProcessServiceTest` (5), three new cases and two new rows in `ZenBpmAdapterConfigurationTest`,
`VanillaBpZenBpmPropertiesTest` (8), `ZenBpmAdapterDiscoveryTest` (5),
`ZenBpmTwoAdapterIdsDiscoveryTest` (2), `ZenBpmWithoutAdapterIdsBootTest` (1),
`ZenBpmStartupValidationBootTest` (8) and `ZenBpmHealthBootTest` (2). After the fixes of the
review below, `mvn clean install` on OpenJDK 25.0.2 builds green with every integration test run
and none skipped (core 461 unit and 34 integration tests, Spring Boot 8, smoke test 18, coverage gate
29); the gate measures 100 % of 10,112 instructions for Spring Boot and 100 % of 9,308 for Quarkus,
which so far is the core alone, and `bin/check-deploy-safety.sh` passes both runs (13 files
deployed, nothing after a red gate). The figures first recorded here, 10,054 and 9,250, came from a
build of a tree before its last edit; a clean build of the first commit measures 10,050 and 9,246.

Fixed after the review, each with a mutant seen to fail and restored afterwards:

- A `history-time-to-live` of a workflow module or a workflow counts as a started configuration,
  as the adapter's own does, so without `rest-address` it makes the id inconsistent instead of
  unconfigured. A scoped `retry-backoff` or `max-redeliveries` does not, because each falls back to
  a default (`ZenBpmAdapterConfigurationTest`: two rows of
  `aStartedConfigurationWithoutAnAddressIsInconsistent` and
  `aScopedKeyWithADefaultDoesNotStartTheConfiguration`; the mutants "no scoped key counts" and
  "every scoped key counts" each fail).
- `ZenBpmProcessService` overrides the three methods of the viewer/history API, which kept the
  SPI's defaults and so said that the BPMS cannot serve the API, with the refusal of the skeleton
  (`ZenBpmProcessServiceTest#calls`; deleting one override fails both refusal tests). Note under
  S10.3.1.
- `ZenBpmWithoutAdapterIdsBootTest` starts the application inside the test, so "says nothing" reads
  the output of its own start and not that of a cached context.
- The closed-port health test is named for what it shows; the bound of an engine which never
  answers is `ZenBpmHealthTest`'s.
- The open question of an unconfigured id with BPMN files went to S4.1.1 as a note.

The first CI run of the pull request failed in
`ZenBpmStartupValidationBootTest#aCompleteAdapterIdBootsSilently`: on a JVM running on UTC, as on
the runners, the platform reports a missing `vanillabp.outbox.housekeeping.zone` in the
`CONFIGURATION` section, which the test expects empty. Reproduced locally with `TZ=Etc/UTC`. The
smoke application now names the zone, and the smoke tests pass under `Etc/UTC` and
`Europe/Vienna`.

**Done 2026-10-15**, merged to `main` as `cbd4ca2` (pull request #24, after two reviews and
CodeRabbit's review). On that commit (GitHub keeps run logs for 90 days, so what they showed is
written here as well):

- `Checks`, run
  [38792709690](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/actions/runs/38792709690):
  green on both JDKs. `bin/check-engine-copies.sh` reported both copies as the engine's files of
  `bdd795a3...`. `ZenBpmStartupValidationTest` ran 13 tests, `ZenBpmDeploymentServiceTest` 7,
  `ZenBpmProcessServiceTest` 5, `ZenBpmAdapterConfigurationTest` 99, `VanillaBpZenBpmPropertiesTest`
  8, `ZenBpmAdapterDiscoveryTest` 5, `ZenBpmTwoAdapterIdsDiscoveryTest` 2,
  `ZenBpmWithoutAdapterIdsBootTest` 1, `ZenBpmStartupValidationBootTest` 8 and
  `ZenBpmHealthBootTest` 2, none skipped (core: 461 unit tests and 34 integration tests; Spring Boot
  8, smoke test 18, coverage gate 29). The gate measured 100 % on both platforms: of 10,112
  instructions for Spring Boot and 9,308 for Quarkus on JDK 25, of 10,108 and 9,304 on JDK 21.
- `Publish snapshots`, run
  [38792709442](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/actions/runs/38792709442):
  green, with the same test counts, none skipped. The core snapshot it published,
  `2.0.0-20261015.191756-20`, contains `client/ZenBpmStartupValidation` (with `Validated`),
  `deployment/ZenBpmDeploymentService`, `processservice/ZenBpmProcessService`,
  `model/ZenBpmBpmnModel` and `ZenBpmProcessingContext`, and holds no test class. The Spring Boot
  snapshot of the same build holds the announcement, the registrar, the two configurations, the
  overlay with its nested classes and `ZenBpmAdapterKeys`, the three configurations in its
  auto-configuration imports, the configuration metadata, and `LICENSE`, `LICENSE-APACHE-2.0` and
  `NOTICE`. Both report pages answer HTTP 200 and show 0 of 10,112 (Spring Boot) and 0 of 9,308
  (Quarkus) instructions missed.

Seen to fail, each restored afterwards (29 of 30 mutants). In the startup validation: a result which
ends the start only warned, an unconfigured id not warned, the notices dropped, the protobuf runtime
never asked and always asked, the factories not closed where a later one or the registry fails, a
failing close not kept with the failure, a missing configuration not refused with its message. In
the configuration: a negative scoped duration or number accepted, a scoped value not kept, a `null`
one kept. In the deployment service: the check of the platform integration left out (an isolating
class loader serves a descriptor built against platform 99.0.0, and also loads `VanillaBpParts`,
which judges a part once per class loader), the factory or the collaborators of another id accepted,
the validation message not used. In the process service: the same two wiring checks, the operations
a BPMS may lack served too, the map of handlers changeable, the validation message not used. In the
Spring module: the priority or the `warn` policy ignored, the startup report not handed to the
validation or to the deployment service, the application's name not read, the module level not
validated, the task level not asked, one adapter key not copied. One mutant survived and is
equivalent: reading the ids from `adapterTypes()` instead of `adapterIdsOfType("zenbpm")` changes
nothing, because the platform's validation adds a section for every id it derives, an id named only
in `prioritized-adapters` included, before the registry reads the properties.
`ZenBpmAdapterDiscoveryTest` configures exactly such an id.

Where the code differs from the instructions:

- **The smoke tests run the whole start (the user's decision).** The platform writes the block of
  the `StartupReport` only at the end of its deployment (`SpringBootDeploymentService.start()`), so
  with `DeploymentAutoConfiguration` excluded every finding of this adapter would vanish unseen. A
  workflow module without BPMN files makes the core skip the adapter for it (one `BPMN MODELS`
  warning), and none of the refusing steps is reached, so no test needs the exclusion or the BPMS
  double as first adapter.
- **The validation lives in `core`.** `ZenBpmStartupValidation.registryOf` holds everything
  instruction 4 lists, so S3.2.1 calls the same method (note there). It validates every id before
  it ends the start and names every id which ends it in one exception, builds no factory where the
  start ends, asks the protobuf runtime only where a factory with clients is built (an application
  whose ids have no clients runs no job stream, and the stream checks again when one is configured),
  and closes the factories it built where a later one or the registry fails.
- **The overlay models the sixteen keys of the core configuration (the user's decision).** The
  table's other keys arrive with the stories which read them, the module level of
  `accept-unscoped-identifiers` with S4.2.1. `max-redeliveries` is scoped like `retry-backoff`,
  because the core configuration says it is; `history-time-to-live` has the module and workflow
  levels. Every value below the adapter level goes through the startup validation
  (`addScopedValue`), so a negative one ends the start naming its key, and
  `VanillaBpZenBpmPropertiesTest` fails where the overlay misses a setter of the core configuration.
- **Two ids never fold into one entry.** Every message names the keys of its own id, so the texts
  of two ids differ; what folds is the same finding of one id reported twice. The texts stay as
  they are, because the same text is thrown at the first use of the adapter.
- **The deployment service gets the `StartupReport` through a setter**, as Camunda 8's does; the
  registry bean takes it as an `ObjectProvider` parameter and logs where there is none.
- **No ids, one id, two ids.** With the ZenBPM adapter the only adapter type on the classpath, the
  core derives the id `zenbpm` where nothing is configured, so "zero ids" needs a second type: the
  platform's BPMS double is a test dependency of `smoke-test`, and `ZenBpmWithoutAdapterIdsBootTest`
  configures only it. With two types on the classpath the platform refuses an application which
  configures nothing, so every smoke test names its ids; the derived id is covered by naming it only
  in `prioritized-adapters`.
- **A use of the adapter** is its START_WORKFLOW handler, which the platform calls, not
  `ProcessService.startWorkflow`: the smoke application has neither a transaction around the call
  nor a persistence of its aggregate.
- **The type parameters** `ZenBpmBpmnModel` and `ZenBpmProcessingContext` exist as empty classes
  named after the architecture's class map (note under S4.1.1). The refusals read
  `ZenBPM adapter '<id>': <what was asked> is implemented in a later story`, and an id without
  clients refuses with its validation message first.

## F3.2 Quarkus

### S3.2.1 Extension: runtime, deployment, extension tests

- [ ] **Depends on:** E2.

**Instructions**

1. `quarkus/runtime` (artifact `zenbpm-vanillabp-adapter-quarkus`):
   `META-INF/quarkus-extension.yaml` with `name: vanillabp-zenbpm`, `dependencies: [vanillabp]`,
   `capabilities.provides: [io.vanillabp.adapter.zenbpm]`. `VanillaBpZenBpmProperties` as
   `@StaticInitSafe @ConfigRoot(phase = RUN_TIME) @ConfigMapping(prefix = "vanillabp")` modelling
   EVERY key (adapter, module, workflow and task levels), never injected; read through
   `ConfigProvider.getConfig().unwrap(SmallRyeConfig.class).getConfigMapping(...)`. Producers:
   `ZenBpmClientProducer` (`@Singleton ZenBpmClientRegistry`, same validation as Spring, application
   name from `quarkus.application.name`, the platform's `StartupReport` as a producer parameter),
   `ZenBpmProcessServiceProducer` (`List<MigratableProcessService<Object>>`),
   `ZenBpmDeploymentServiceProducer`
   (`List<AdapterDeploymentService<Object, Object>>`, collaborators via
   `AdapterCollaboratorsSupport.collaborators(...)`, the `StartupReport` handed to each deployment
   service), `ZenBpmStartupObserver` observing
   `StartupEvent` and touching the registry so validation runs before the platform's deployment
   runner. Note on versions: the root POM imports the Spring Boot BOM before the Quarkus BOM, so a
   library both manage (Jackson, Netty, SLF4J, ...) resolves to Spring Boot's version in the Quarkus
   modules too. A Quarkus application runs the Quarkus versions. Where a Quarkus test or the native
   image depends on the exact version, pin it in the Quarkus module, as `camunda8-adapter` does with
   `netty.version.quarkus`.
2. `quarkus/deployment` (artifact `zenbpm-vanillabp-adapter-quarkus-deployment`):
   `ZenBpmIntegrationProcessor` with `FeatureBuildItem("vanillabp-zenbpm")`,
   `VanillaBpMigratableProcessServiceBuildItem` and `VanillaBpAdapterDeploymentServiceBuildItem`
   naming the two producers, `AdditionalBeanBuildItem(setUnremovable)` for the client producer and
   the observer; an empty BUILD_TIME `@ConfigRoot ZenBpmProperties` for symmetry; a
   `ZenBpmNativeImageProcessor` registering the generated gRPC message classes for reflection (the
   real native test is E11).
3. Extension tests in `quarkus/deployment/src/test` with `QuarkusExtensionTest`, a workflow-module
   marker resource and `application.yaml` variants: `ZenBpmAdapterDiscoveryTest` (list beans with one
   entry), `ZenBpmTwoAdapterIdsTest`, `ZenBpmStartupValidationTest` (unconfigured boots with the
   warning in the `StartupReport` block),
   `ZenBpmInconsistentConfigurationTest` (boot fails naming the key), `ZenBpmUnknownKeyTest` (a typo
   `vanillabp.adapters.zenbpm.rest-adress` fails the startup with `SRCFG00050` - the assertion is that
   the failure names the key), `ZenBpmOverlayResolutionTest` (`retry-backoff` through all four levels).

*Note from S2.3.1, 2026-10-14.* The protobuf runtime is checked at build time here: a build
step of `ZenBpmIntegrationProcessor` calls `ProtobufRuntimeCheck.defect()` on the application's
build class path and fails the build with its message where it answers one.

*Note from S3.1.1, 2026-10-15.* What Spring Boot does with the configuration lives in `core`, so
"same validation as Spring" means one call: `ZenBpmStartupValidation.registryOf(ids,
configurationOf, firstPriorityAnywhere, deploymentFailureWarns, applicationName, startupReport)`. It
validates every id, reports notices and warnings under `CONFIGURATION` with the scope `zenbpm
adapter '<id>'`, throws one exception naming every id whose configuration ends the start, asks the
protobuf runtime only where a factory with clients is built, and closes the factories it built where
a later one or the registry fails. The ids are
`MigrationAdapterProperties.adapterIdsOfType("zenbpm")`, the call which also decides the ids the
beans are registered for. On the validated properties `adapterTypes()` names the same ids, the ones
the core derives included, because the validation adds a section for each of them (a mutation test
of S3.1.1 showed it), so this is one rule for both places, not a fix. The overlay models exactly the
keys of `ZenBpmAdapterConfiguration` (sixteen) plus `retry-backoff` and `max-redeliveries` at
module, workflow and task level and `history-time-to-live` at module and workflow level; every value
below the adapter level is handed to the validation with `addScopedValue(fullPropertyKey, value)`,
so a negative one ends the start naming the key where it was written. The other keys of the
architecture table arrive with the stories which read them, the module level of
`accept-unscoped-identifiers` with S4.2.1 (user's decision 2026-10-15). The Spring smoke tests run
the whole start, deployment included, because the block is written only by the deployment's end of
the start; on Quarkus the block comes after `startWorkflowProcessing`, and a workflow module without
BPMN files reaches no step of the adapter there either.

**Acceptance criteria**

- [x] `io.vanillabp.adapter.zenbpm` is declared; the platform's capability check passes. The
  descriptor of `quarkus/runtime` provides it, and the platform finds the adapter types of an
  application through these capabilities: with the capability renamed, `ZenBpmAdapterDiscoveryTest`
  fails with the platform's "No VanillaBP BPMS adapter found in classpath!", and with it every boot
  test of `quarkus/deployment` passes the platform's check. The discovery test also finds the
  feature `vanillabp-zenbpm` among the installed ones in the log of its start.
- [x] No class injects the `@ConfigMapping` (a test greps the runtime sources for `@Inject` next to
  the mapping type, or a comment in `AGENTS.md` plus review; choose the grep test).
  `QuarkusOverlayTest#theOverlayIsNeverInjected` in the coverage gate reads the main sources of every
  Quarkus module, comments left out, and fails on a field marked `@Inject` (imported or written out)
  whose declaration names `VanillaBpZenBpmProperties`, and on a parameter of that type, plain or
  inside `Instance<>` or `Provider<>`, of a constructor or of a method marked `@Produces`,
  `@Disposes`, `@Observes`, `@ObservesAsync` or `@Inject`, because CDI fills those; a helper the
  adapter calls itself may take the overlay. `#theRuleTellsAnInjectionFromAHelper` holds the rule
  against sample declarations, and `#theSourcesAreFound` fails where no source reads the mapping at
  all.
- [x] The Quarkus overlay knows every key the Spring overlay knows (a test lists both and compares).
  `QuarkusOverlayTest#theQuarkusOverlayKnowsEveryKeyOfTheSpringBootOverlay` walks both overlays by
  reflection (a getter with a setter on Spring Boot, a method without parameters and body on
  Quarkus) and compares every key path, `*` standing for an id, a module, a workflow or a task:
  the 16 adapter keys, `retry-backoff` and `max-redeliveries` at three levels below the adapter,
  `history-time-to-live` at two.

**Implemented 2026-10-16.** `quarkus/runtime`: the overlay `VanillaBpZenBpmProperties` (the sixteen
keys of the core configuration and the scoped levels, with the resolvers of the Spring overlay),
`ZenBpmClientProducer` (the one call `ZenBpmStartupValidation.registryOf`, application name from
`quarkus.application.name`, the platform's `StartupReport` as a parameter, a disposer which closes
the registry), `ZenBpmProcessServiceProducer` and `ZenBpmDeploymentServiceProducer` (one
`@Singleton` list each, ids from `adapterIdsOfType("zenbpm")`, collaborators from
`AdapterCollaboratorsSupport`, the report handed to each deployment service) and
`ZenBpmStartupObserver`. `quarkus/deployment`: `ZenBpmIntegrationProcessor` (feature, the two
announcements, the client producer and the observer as unremovable beans, and the protobuf check as
a build step) and `ZenBpmNativeImageProcessor`. All are adapted from Camunda 8's Quarkus modules and
keep their licence (decision 17); the root POM's licence comment names the Quarkus jars as well.
Tests: `VanillaBpZenBpmPropertiesTest` (8) in `quarkus/runtime`, binding the mapping with SmallRye
alone; in `quarkus/deployment` the boot tests `ZenBpmAdapterDiscoveryTest` (4),
`ZenBpmTwoAdapterIdsTest` (2), `ZenBpmStartupValidationTest` (2), `ZenBpmWithoutAdapterIdsTest`
(1), `ZenBpmUnknownKeyTest` (1), `ZenBpmInconsistentConfigurationTest` (1),
`ZenBpmDegradedBootTest` (1), `ZenBpmUnworkableNumberTest` (1), `ZenBpmNegativeScopedValueTest`
(1), `ZenBpmNoticeOnceTest` (1), `ZenBpmCompleteAdapterIdTest` (2) and
`ZenBpmOverlayResolutionTest` (4), and the build-step tests `ZenBpmIntegrationProcessorTest` (3)
and `ZenBpmNativeImageProcessorTest` (2); `QuarkusOverlayTest` (3) in the coverage gate. After the
fixes of the reviews below, `ZenBpmAdapterDiscoveryTest` has 5 tests,
`ZenBpmBesideAnotherAdapterTypeTest` (2) and `ZenBpmHealthDownTest` (1) are new,
`QuarkusOverlayTest` has 4, and the smoke test of Spring Boot gains
`ZenBpmBesideAnotherAdapterTypeBootTest` (1). `TZ=Etc/UTC mvn clean install` on OpenJDK 25.0.2
builds green with every integration test run and none skipped (core 461 unit and 34 integration
tests, Spring Boot 8, smoke test 19, Quarkus runtime 8, Quarkus deployment 30, coverage gate 33);
the gate measures 100 % of 10,112 instructions for Spring Boot and 100 % of
9,998 for Quarkus, which now counts the extension and not only the core (9,308 before), and
`bin/check-deploy-safety.sh` passes both runs (13 files deployed, nothing after a red gate). The run
before it failed in `ZenBpmJobStreamIT` alone, because the pinned engine's container did not start
("Failed to start Zen node: failed to bootstrap cluster: boot timeout", the node failing to join
its own cluster); nothing of this story runs there, and the next run passed.

Seen to fail, each restored afterwards (22 of 24 mutants). The capability renamed (above). In the
client producer: the `warn` policy
ignored, every id taken as first priority (both fail `ZenBpmDegradedBootTest`), the application's
name not read, the startup report not handed to the validation (three boot tests lose their
finding from the block), the registry not closed when the application stops. In the other
producers: the startup report not handed to the deployment service, the collaborators of another
id. In the build steps: the client producer and the observer removable (every boot fails), a
protobuf defect not reported, a defect reported for the build's own runtime, every class of the
core opened to reflection, an empty build-time configuration root added (the misspelt key boots).
In the overlay: one adapter key not copied, the module level not validated, the task level not
asked. In the gate: an injected field (imported and written out), an injected parameter, plain and
in `Instance<>` or `Provider<>`, and a key the Spring overlay has and the Quarkus overlay lacks. Two
mutants survive and are equivalent. Reading the ids from `adapterTypes()` changes nothing, as on
Spring Boot. And the observer which builds the registry changes nothing on this platform version,
because the platform's deployment runner asks for the deployment services, and with them for the
registry, in the same start; the observer stays, because nothing in the platform's contract says
when the runner asks.

**Answers to two reviews, 2026-10-16** (one by Claude, one by Copilot; both approve). Every
finding was checked against the code, and every one held. Each fix below has a mutant seen to fail
and restored afterwards, unless it is wording only:

- **A misspelt key is ignored beside the Camunda 8 adapter.** Reproduced, not only derived: in a
  scratch copy of this commit, with Camunda 8's Quarkus extension (built from the local
  `camunda8-adapter` at `fd67a1d`) as a second adapter type, an application with
  `vanillabp.adapters.zenbpm.rest-adress` and one with `vanillabp.bogus-top-level-key` both
  started. Quarkus only logged "Unrecognized configuration key ... it will be ignored", and the
  block said that `vanillabp.adapters.zenbpm.rest-address` is missing; the same application with
  the right key started too. Camunda 8's empty build-time root under `vanillabp` is the cause, as in
  this story's own mutant. The javadoc of both overlays and of `ZenBpmUnknownKeyTest`, and the E12
  note, now say so. It is no test of this repository: the Camunda 8 adapter is not published where
  this build can resolve it, and nothing in this adapter can turn the check back on.
- **A mixed boot** (Copilot): `ZenBpmBesideAnotherAdapterTypeTest` on Quarkus and
  `ZenBpmBesideAnotherAdapterTypeBootTest` on Spring Boot boot a ZenBPM id beside the BPMS double,
  each with a key of its own under `vanillabp.adapters.`. The platform keeps one list per type, the
  ZenBPM registry and services hold only the ZenBPM id, and the block has no configuration finding.
  Mutants: the registry fed every configured id, on each platform (the registry holds `dummy`, and
  the block warns about it); the Quarkus deployment and process services fed every id (the start
  fails on the core's "No ZenBPM adapter with id 'dummy' is configured").
- **Two Spring cases were not mirrored**: `ZenBpmHealthDownTest` boots a complete id on a free port
  nothing listens on and asks the deployment service for its health, and
  `ZenBpmAdapterDiscoveryTest` gains `theSmokeApplicationPersistsNothing`.
- **The injection guard blamed a helper.** It flagged every parameter of the overlay's type; now
  only one CDI fills (see the acceptance block). Each part of the rule switched off fails
  `theRuleTellsAnInjectionFromAHelper`: any parameter counted, constructors not counted, comments
  kept, an annotation's array kept, `new` taken for a constructor, `Instance<? extends>` not read.
- **"Nothing in the block" also passed without a block.** `StartupBlock.isWritten` finds the head
  line of the block, and the tests which expect an empty `CONFIGURATION` (both complete-id tests,
  both mixed boots, Spring's boot without ZenBPM ids) assert it; with the head line changed,
  `ZenBpmCompleteAdapterIdTest` fails.
- **The reason the overlay is never injected was wrong in its detail.** The mutant (the overlay as
  a parameter of the client producer) fails every boot, but with the platform's keys,
  `vanillabp.prioritized-adapters` and `vanillabp.outbox.housekeeping.zone`, named as unknown
  (`SRCFG00050`, while Quarkus initializes its static configuration), not the adapter's keys as the
  javadoc said. The javadoc of the overlay and the gate's message now say what happens.
- Wording: `everyLevelReachesTheConfiguration` is `theAdapterLevelReachesTheConfiguration`, named
  for what it asserts; the processor's sentence about the missing root and the one above are
  rewritten; one javadoc line is wrapped and the root POM's licence comment reflowed.

**Done 2026-10-16**, merged to `main` as `6998046` (pull request #26, after two reviews and
CodeRabbit's review, whose one finding, two cross-references pointing below instead of above, was
fixed in `89b2115`). On that commit (GitHub keeps run logs for 90 days, so what they showed is
written here as well):

- `Checks`, run
  [38887026946](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/actions/runs/38887026946):
  green on both JDKs. `bin/check-engine-copies.sh` reported both copies as the engine's files of
  `bdd795a3...`. `VanillaBpZenBpmPropertiesTest` ran 8 tests; in `quarkus/deployment`,
  `ZenBpmAdapterDiscoveryTest` 5, `ZenBpmTwoAdapterIdsTest` 2, `ZenBpmStartupValidationTest` 2,
  `ZenBpmWithoutAdapterIdsTest` 1, `ZenBpmUnknownKeyTest` 1, `ZenBpmInconsistentConfigurationTest`
  1, `ZenBpmDegradedBootTest` 1, `ZenBpmUnworkableNumberTest` 1, `ZenBpmNegativeScopedValueTest` 1,
  `ZenBpmNoticeOnceTest` 1, `ZenBpmCompleteAdapterIdTest` 2, `ZenBpmOverlayResolutionTest` 4,
  `ZenBpmBesideAnotherAdapterTypeTest` 2, `ZenBpmHealthDownTest` 1,
  `ZenBpmIntegrationProcessorTest` 3 and `ZenBpmNativeImageProcessorTest` 2;
  `ZenBpmBesideAnotherAdapterTypeBootTest` 1 in the smoke test and `QuarkusOverlayTest` 4 in the
  gate, none skipped (core: 461 unit tests and 34 integration tests; Spring Boot 8, smoke test 19,
  Quarkus runtime 8, Quarkus deployment 30, coverage gate 33). The gate measured 100 % on both
  platforms: of 10,112 instructions for Spring Boot and 9,998 for Quarkus on JDK 25, of 10,108 and
  9,994 on JDK 21.
- `Publish snapshots`, run
  [38887026965](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/actions/runs/38887026965):
  green, with the same test counts, none skipped. Its snapshots, `2.0.0-20261016.102441-22`, are
  the first Quarkus jars with the extension in them: the ones before (`2.0.0-20261015.191756-20`
  of S3.1.1) held the extension's descriptor and no class. The runtime jar
  `zenbpm-vanillabp-adapter-quarkus` holds the overlay with its six nested types, the three
  producers and the startup observer, the config root list naming the overlay, and a
  `quarkus-extension.properties` which provides `io.vanillabp.adapter.zenbpm` and names
  `zenbpm-vanillabp-adapter-quarkus-deployment` as its deployment artifact. The deployment jar holds
  the two processors with their accessors and the build-step list naming both. Both carry
  `LICENSE`, `LICENSE-APACHE-2.0` and `NOTICE`, and no jar of the build holds a test class. Both
  report pages answer HTTP 200 and show 0 of 10,112 (Spring Boot) and 0 of 9,998 (Quarkus)
  instructions missed.

Where the code differs from the instructions:

- **No build-time configuration root.** Instruction 2 asks for an empty BUILD_TIME
  `ZenBpmProperties` "for symmetry", as Camunda 8 has one. With it, an application with
  `vanillabp.adapters.zenbpm.rest-adress` set still started, and so did one with any other unknown
  key under `vanillabp.`. Without it, the start fails with `SRCFG00050` naming the key
  (`ZenBpmUnknownKeyTest`; adding the empty root back is one of the mutants above). The root of
  another extension does the same: beside Camunda 8's adapter, Quarkus ignores the key (see the
  reviews above). The deployment module has no public class then, so
  its javadoc check documents the package-private build steps (`<show>package</show>`).
- **No adapter id of type `zenbpm` cannot boot on Quarkus.** The platform refuses an application
  whose adapter extension on the classpath has no configured id ("VanillaBP Quarkus adapter
  extensions found but not configured", naming `vanillabp.adapters.*.type=zenbpm`), where Spring Boot
  boots it and the adapter has nothing. `ZenBpmWithoutAdapterIdsTest` asserts that refusal with the
  platform's BPMS double as the only configured adapter. The epic's "zero adapter ids" boots on
  Quarkus as an application which configures nothing at all: the platform derives the id `zenbpm`,
  and `ZenBpmStartupValidationTest` boots exactly that. The platform also wants every configured id
  in `prioritized-adapters`, so the double is a test dependency of `quarkus/deployment` that every
  other test leaves out of its application (`QuarkusExtensionTest.setExcludedDependencies`), and
  each test names only its ZenBPM ids, as on Spring Boot.
- **The protobuf check is a build step which produces a validation error.** A step which produces
  nothing is never run by Quarkus, so the step hands a defect to the CDI validation, which ends the
  build naming it. `ZenBpmIntegrationProcessorTest` runs the real step in a class loader without
  `RuntimeVersion`.
- **The tests mirror every case of the Spring smoke tests**, one boot each (the user's decision),
  beyond the six the instructions name. The first commit left out two (an address nothing listens
  on is DOWN, and the smoke application persists nothing); both came with the reviews above. The
  block at the end of the start is read from the log records the extension test keeps, which end
  where the application begins to stop, so what the factory says while it closes is heard on its
  own logger. The platform's phase-two outbox needs a
  datasource, so the tests run the JDBC outbox on H2, as the Spring smoke application does.
- **Health** is the produced deployment service's `checkHealth()` (UNKNOWN with the missing key in
  `ZenBpmStartupValidationTest`, DOWN naming the address, the request timeout and the job stream in
  `ZenBpmHealthDownTest`), the user's decision: the readiness endpoint is the platform's and
  exists only where the application has SmallRye Health.
- **The two acceptance tests live in the coverage gate** (the user's decision), the one module which
  sees both platforms. Its test classpath has the core and the two overlay jars without anything
  they bring.
- **The binding is tested twice.** `VanillaBpZenBpmPropertiesTest` of `quarkus/runtime` holds every
  case of the Spring overlay's test on a SmallRye configuration built without Quarkus (it needs ASM
  on the test classpath, which Quarkus otherwise brings at build time);
  `ZenBpmOverlayResolutionTest` reads the four levels from a booted application.
- **`ZenBpmAdapterConfiguration.validate` validates on Quarkus at startup**, not at build time as
  its javadoc said: the adapter's keys are runtime configuration. The javadoc says so now.
- **The native-image processor registers the generated classes only**; what else a native image
  needs is S11.4.1's (note there).
- **The accessor classes the Quarkus extension processor generates are not counted** for
  coverage, like the gRPC stubs: the processor writes one next to every class of build steps, and
  nothing calls its private constructor, which were the only 8 instructions of the Quarkus report
  missed before the root POM excluded `**/*$$accessor.class`.

## F3.3 Distinct instances

### S3.3.1 `validateDistinctAdapterInstances`

- [ ] **Depends on:** S3.1.1, S3.2.1.

**Instructions**

`ZenBpmDeploymentService.validateDistinctAdapterInstances(ids)`: group by
`configuration.instanceIdentity()`; a group with two ids ends the boot with a message naming both ids,
the shared address and the key which tells them apart (`vanillabp.adapters.<id>.rest-address`), and
saying that two ids deliberately sharing one engine are still allowed as long as they differ in
`name-clash-avoidance` or in the modules they serve - which is what a prefix migration looks like -
by setting `vanillabp.adapters.<id>.shared-engine: true` on the second (new key, adapter level,
default false; validated to be set on at most all but one id of a group).

`getAddressedSystem()` keeps its default (empty). It exists so that two adapter ids of DIFFERENT
types on one system are refused (platform decision 129); no other adapter type works on a ZenBPM
engine, so there is nothing to compare, and this per-type check stays the one which matters.

**Tests**: `ZenBpmInstanceIdentityTest` (core), and one boot test per platform with two ids on the
same address (fails) and with `shared-engine: true` (boots).

*Note from S3.2.1, 2026-10-16.* `shared-engine` is a new adapter key, so both overlays get it:
`QuarkusOverlayTest` of the coverage gate fails while only one platform knows a key, and the
binding tests of both platforms (`VanillaBpZenBpmPropertiesTest`) compare the overlay with the
setters of `ZenBpmAdapterConfiguration`. On Quarkus the boot test of each case is a test class of its
own, in `quarkus/deployment` next to `ZenBpmTwoAdapterIdsTest`.

**Acceptance criteria**

- [x] The failure names both ids and the key; the shared-engine setup boots and is the input to S11.3.1.
  Two ids of type `zenbpm` on one engine with the same mode end the start with one message which
  names both ids, the engine (`http://localhost:8080/v1`), the mode, and the keys `rest-address` and
  `name-clash-avoidance` of each id: `ZenBpmInstanceIdentityTest` in core, and the real start on
  both platforms (`ZenBpmDistinctInstancesBootTest#oneEngineWithOneModeEndsTheStart`,
  `ZenBpmOneEngineOneModeTest`). The shared-engine setup is one engine with two modes, `use-prefix`
  for the new id and `none` for the old one. It boots on both platforms with nothing in the
  `CONFIGURATION` section, and the registry knows that the two ids share the engine
  (`#oneEngineWithTwoModesBoots`, `ZenBpmOneEngineTwoModesTest`). It is the setup S11.3.1 runs
  against a real engine.

**Implemented 2026-10-16.** Core: `client.ZenBpmInstanceIdentity` (adapted from Camunda 8's
`Camunda8InstanceIdentity`, decision 17) groups the given ids by engine, the normalised address of
`instanceIdentity()`, and by the mode the platform's scoping resolves for the adapter id as a whole
(`modeFor(null, null, id)`). It names every group of two or more ids in one message, so one restart
shows them all, and it leaves out an id without clients. `ZenBpmDeploymentService` overrides
`validateDistinctAdapterInstances` with that check. To reach the configuration of the other ids,
it now takes the `ZenBpmClientRegistry` instead of the factory of its own id. Both platforms hand
it over, and the check that the collaborators belong to the id stays. `getAddressedSystem()` keeps
its default. Tests: `ZenBpmInstanceIdentityTest` (7), two new cases in
`ZenBpmDeploymentServiceTest` (now 9), `ZenBpmDistinctInstancesBootTest` (2) in the smoke test, and
`ZenBpmOneEngineOneModeTest` (1) and `ZenBpmOneEngineTwoModesTest` (1) in `quarkus/deployment`.
`TZ=Etc/UTC mvn clean install` on OpenJDK 25.0.2 builds green with every integration test run and
none skipped (core 470 unit and 34 integration tests, Spring Boot 8, smoke test 21, Quarkus runtime
8, Quarkus deployment 32, coverage gate 33). The gate measures 100 % of 10,310 instructions for
Spring Boot and 100 % of 10,193 for Quarkus, and `bin/check-deploy-safety.sh` passes both runs (13
files deployed, nothing after a red gate).

Seen to fail, each restored afterwards (11 of 11 mutants). In the identity, each mutant fails
`ZenBpmInstanceIdentityTest`, and four of them `ZenBpmDeploymentServiceTest` as well:
- the mode ignored;
- the raw address compared instead of the normalised one;
- an id without clients compared;
- only the first group named;
- every id asked for the mode of the first one;
- the mode asked for a workflow module instead of the adapter id;
- a group of one id refused.

In the deployment service:
- the check not asked: fails `ZenBpmDeploymentServiceTest`, `ZenBpmDistinctInstancesBootTest` and
  `ZenBpmOneEngineOneModeTest`;
- collaborators of another id accepted.

In the wiring, the service handed a registry with only its own factory:
- on Spring Boot, `ZenBpmDistinctInstancesBootTest` fails;
- on Quarkus, both `ZenBpmOneEngine*` tests fail, because the registry has no factory of the other
  id.

Where the code differs from the instructions:

- **No `shared-engine` key** (the user's decision at the fit check). The instructions ask for a new
  key which lets two ids on one address boot. Camunda 8, the template, has none. Its identity
  includes the mode, so its migration setup needs no key, and two ids with the same mode are
  refused. On ZenBPM the mode is the only thing which separates two ids on one engine (there are
  no tenants and no credentials), and the key as worded would have let two ids with the same mode
  boot, though they reach the engine with the same identifiers. So the identity is the address and
  the mode, the key is gone from the architecture table and from S11.3.1, and the note from S3.2.1
  above about putting it on both overlays no longer applies. The "modules they serve" of the
  instructions are not compared: the platform asks once per type with the ids only, and Camunda 8
  does not compare them either.
- **The mode is read at the adapter level.** A workflow module can set a mode of its own. The check
  is about the adapter id as a whole, as on Camunda 8, and the platform's scoping resolves a mode
  without a module for exactly this comparison.
- **Until S4.2.1 the default mode is `by-adapter` for every id.** So two ids on one address are
  refused unless one of them sets a mode, and S4.2.1 then refuses `by-adapter` itself.
- **An id without an address is not compared.** It has no engine. The validation already reported
  the key it misses, and it refuses all work.

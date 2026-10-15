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
UNKNOWN with it. `new ZenBpmClientRegistry(factories)` takes them as a list in the order of the ids
and refuses two of one id. `checkHealth()` is `ZenBpmHealth.check(registry.getFactory(id))` in
package `org.pbinitiative.zenbpmadapter.health`. The registry bean's `close()` closes every factory.

**Acceptance criteria**

- [ ] No bean of type `List<...>` is registered; the platform's `ObjectProvider` streams find both
  services per id.
- [ ] Every message asserted by content, including the property key, where the platform writes it:
  in the `StartupReport` block, or in the exception which ends the boot.
- [ ] A typo under `vanillabp.adapters.<id>.` is ignored by Spring (documented as the difference to
  Quarkus, where it fails).

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

**Acceptance criteria**

- [ ] `io.vanillabp.adapter.zenbpm` is declared; the platform's capability check passes.
- [ ] No class injects the `@ConfigMapping` (a test greps the runtime sources for `@Inject` next to
  the mapping type, or a comment in `AGENTS.md` plus review; choose the grep test).
- [ ] The Quarkus overlay knows every key the Spring overlay knows (a test lists both and compares).

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

**Acceptance criteria**

- [ ] The failure names both ids and the key; the shared-engine setup boots and is the input to S11.3.1.

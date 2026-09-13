# E3 - Platform registration (Spring Boot and Quarkus)

**Goal.** An application with the adapter on the classpath boots on both platforms, gets one
`ZenBpmProcessService` and one `ZenBpmDeploymentService` per configured adapter id of type `zenbpm`,
validates its configuration at startup with the three outcomes, and reports health - while every SPI
method which needs the engine still throws `UnsupportedOperationException("<method> is implemented in a
later story")` (never a silent stub).

**Done when.** The smoke tests of both platforms boot with zero, one and two adapter ids, and the
guiding messages are asserted through `CapturedOutput`.

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

1. `spring-boot` module (artifact `zenbpm-adapter-spring-boot`), depends on `core` and
   `vanillabp-spring-boot-integration`; runs the configuration processor for IDE metadata.
2. `org.pbinitiative.zenbpmadapter.springboot.ZenBpmAdapterConfiguration extends AdapterConfigurationBase`,
   `@AutoConfiguration(before = SpringBootMigrationAdapterAutoConfiguration.class)`, returns the type.
   Nothing else in it.
3. `client.VanillaBpZenBpmProperties` = `@ConfigurationProperties("vanillabp")` overlay with
   `adapters: Map<String, ZenBpmAdapterKeys>` (all keys of the architecture table) and the module and
   workflow levels for the scoped keys (`retry-backoff`, `max-redeliveries`, `history-time-to-live`,
   `name-clash-avoidance` is the core's) with a resolver `retryBackoffFor(module, process, task, id)`
   etc. (four levels, most specific wins - copy `PeaFetchVariables`' resolver shape).
4. `client.ZenBpmClientAutoConfiguration` (`after = SpringBootMigrationAdapterAutoConfiguration`):
   `@EnableConfigurationProperties`, a `ZenBpmClientRegistry` bean built by iterating
   `MigrationAdapterProperties.adapterTypes()` for `zenbpm` ids (never the overlay's keys), running
   `ZenBpmAdapterConfiguration.validate` per id with `isNowhereFirst` and the deployment-failure
   policy from the core properties, logging the UNCONFIGURED warning, throwing on INCONSISTENT unless
   nowhere-first-with-warn (then WARN and mark the factory unconfigured), and building the factory on
   COMPLETE; a `ZenBpmExecutor` per id inside the factory. Application name from
   `spring.application.name`, default `application`.
5. `processservice.ZenBpmAdapterProcessServiceConfiguration` importing `ZenBpmAdapterBeanRegistrar`
   (a `BeanRegistrar`) which, with `AdapterBeanRegistrarSupport.forEachConfiguredAdapterId(environment,
   ZenBpmAdapter.ADAPTER_TYPE, ...)`, registers per id the element beans `ZenBpm_ProcessService_<id>`
   and `ZenBpm_DeploymentService_<id>` with lazy suppliers resolving the registry, the overlay and
   `AdapterBeanRegistrarSupport.collaborators(supplierContext, adapterId)`. Both core classes exist as
   skeletons in this story: constructor with `AdapterCollaborators`, `getAdapterId/Type`,
   `getModelType`/`getProcessContextType`, `checkHealth()` -> `ZenBpmHealth`,
   `AdapterPlatformVersion.requireCompatiblePlatform` in the constructor; every pipeline and
   operation method throws the "later story" exception; `phaseOperations()` returns a map with a
   throwing handler per REQUIRED operation (the boot refuses a missing required one).
6. `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` listing the
   three classes.
7. `smoke-test` module: `SmokeTestApplication` (JPA H2 + gruelbox outbox as in Camunda 8's smoke
   test, `NoPersistenceForTheSampleAggregate` pattern), a `META-INF/workflow-module`, no BPMN files.
   Tests, all with `spring.autoconfigure.exclude` of the platform's `DeploymentAutoConfiguration` or an
   `ApplicationContextRunner` where the pipeline would otherwise run the stubs:
   - `ZenBpmAdapterDiscoveryTest`: id = type, both element beans present, registry knows the id;
   - `ZenBpmTwoAdapterIdsDiscoveryTest`: two ids, two of each bean, named after the ids;
   - `ZenBpmStartupValidationBootTest`: unconfigured boots with the WARN naming
     `vanillabp.adapters.<id>.rest-address` (CapturedOutput); inconsistent first-priority ends the
     boot naming the keys; inconsistent nowhere-first with `deployment-failure: warn` boots degraded;
     complete boots without a warning and logs the INFO line;
   - `ZenBpmHealthBootTest`: actuator health shows the adapter UNKNOWN when unconfigured, DOWN
     against a closed port (bounded time).

**Acceptance criteria**

- [ ] No bean of type `List<...>` is registered; the platform's `ObjectProvider` streams find both
  services per id.
- [ ] Every message asserted by content, including the property key.
- [ ] A typo under `vanillabp.adapters.<id>.` is ignored by Spring (documented as the difference to
  Quarkus, where it fails).

## F3.2 Quarkus

### S3.2.1 Extension: runtime, deployment, extension tests

- [ ] **Depends on:** E2.

**Instructions**

1. `quarkus/runtime` (artifact `zenbpm-adapter-quarkus`): `META-INF/quarkus-extension.yaml` with
   `name: vanillabp-zenbpm`, `dependencies: [vanillabp]`, `capabilities.provides:
   [io.vanillabp.adapter.zenbpm]`. `VanillaBpZenBpmProperties` as `@StaticInitSafe @ConfigRoot(phase =
   RUN_TIME) @ConfigMapping(prefix = "vanillabp")` modelling EVERY key (adapter, module, workflow and
   task levels), never injected; read through
   `ConfigProvider.getConfig().unwrap(SmallRyeConfig.class).getConfigMapping(...)`. Producers:
   `ZenBpmClientProducer` (`@Singleton ZenBpmClientRegistry`, same validation as Spring, application
   name from `quarkus.application.name`), `ZenBpmProcessServiceProducer`
   (`List<MigratableProcessService<Object>>`), `ZenBpmDeploymentServiceProducer`
   (`List<AdapterDeploymentService<Object, Object>>`, collaborators via
   `AdapterCollaboratorsSupport.collaborators(...)`), `ZenBpmStartupObserver` observing
   `StartupEvent` and touching the registry so validation runs before the platform's deployment
   runner.
2. `quarkus/deployment` (artifact `zenbpm-adapter-quarkus-deployment`): `ZenBpmIntegrationProcessor`
   with `FeatureBuildItem("vanillabp-zenbpm")`, `VanillaBpMigratableProcessServiceBuildItem` and
   `VanillaBpAdapterDeploymentServiceBuildItem` naming the two producers,
   `AdditionalBeanBuildItem(setUnremovable)` for the client producer and the observer; an empty
   BUILD_TIME `@ConfigRoot ZenBpmProperties` for symmetry; a `ZenBpmNativeImageProcessor` registering
   the generated gRPC message classes for reflection (the real native test is E11).
3. Extension tests in `quarkus/deployment/src/test` with `QuarkusExtensionTest`, a workflow-module
   marker resource and `application.yaml` variants: `ZenBpmAdapterDiscoveryTest` (list beans with one
   entry), `ZenBpmTwoAdapterIdsTest`, `ZenBpmStartupValidationTest` (unconfigured boots with WARN),
   `ZenBpmInconsistentConfigurationTest` (boot fails naming the key), `ZenBpmUnknownKeyTest` (a typo
   `vanillabp.adapters.zenbpm.rest-adress` fails the startup with `SRCFG00050` - the assertion is that
   the failure names the key), `ZenBpmOverlayResolutionTest` (`retry-backoff` through all four levels).

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

**Tests**: `ZenBpmInstanceIdentityTest` (core), and one boot test per platform with two ids on the
same address (fails) and with `shared-engine: true` (boots).

**Acceptance criteria**

- [ ] The failure names both ids and the key; the shared-engine setup boots and is the input to S11.3.1.

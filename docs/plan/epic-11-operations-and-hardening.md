# E11 - Operations and hardening

**Goal.** What an operator gets to see, the election proven with two ids and next to another BPMS, a
native image, and coverage at the rule on both platforms.

**Done when.** Meters and health are documented and tested, the election ITs are green, the native
image runs the lifecycle flow, and both coverage reports read above 90.

---

## S11.1.1 Metrics

- [x] **Depends on:** E6.

`ZenBpmMetrics` (interface with a `NONE`) and `MicrometerZenBpmMetrics` (registered where Micrometer
is present: Spring `@ConditionalOnClass(MeterRegistry)`, Quarkus the Micrometer-processor detection of
`Camunda8IntegrationProcessor`): counters `vanillabp.zenbpm.jobs.received|completed|failed|queued|redelivered`,
`vanillabp.zenbpm.lock.renewed|lost`, `vanillabp.zenbpm.stream.reconnects`, `vanillabp.zenbpm.usertasks.polls`; gauges
`vanillabp.zenbpm.slots.busy|free` (`CachedGaugeValue`, decision 18 of the platform), all tagged
`adapter=<id>`. Tests: `MicrometerZenBpmMetricsTest`, and a boot test per platform that the meters
exist only with Micrometer.

*Note from S2.3.1, 2026-10-14.* `ZenBpmJobStream.reconnects()` counts every attempt to open the
call again since the stream was built; `vanillabp.zenbpm.stream.reconnects` is a function counter on
it rather than a callback the stream would need to know about.

*Note from S2.4.2, 2026-10-15.* The two slot gauges read `ZenBpmExecutor.getBusySlots()` and
`getFreeSlots()`: the slots of handlers which run or were handed over, and the rest.

*Note from the review of S2.4.1, 2026-10-15.* The health check is UP where the engine answers ready
over REST, whatever the job stream does: a wrong `grpc-address`, a TLS mismatch or a blocked gRPC
port leaves REST working and delivers no job. The health names the stream's state as the detail
`jobStream` (`ZenBpmJobStream.state()`: `not opened yet`, `connecting`, `connected`, `down since
<instant>, <n> attempt(s)`, `closed`), so an operator sees it. Whether a stream which has been down
longer than some bound turns the health DOWN was left to this epic: it is a stronger contract, and it
needs a bound which is either a new key or a fixed value. Decide it with the meters and document it
in the README's health section.

*Fit check, 2026-10-21, decided by the Implementor where it says so.* Checked against `main` at
`0c5e443`, Camunda 8's `Camunda8Metrics`, `MicrometerCamunda8Metrics`, its registrar, its client
auto-configuration, `Camunda8MetricsProducer` and `Camunda8IntegrationProcessor`, the platform's
`VanillaBpMetrics`, `MicrometerVanillaBpMetrics`, `CachedGaugeValue` and its Quarkus
`ObservabilityTest`, and the engine at the pin `bdd795a3`. What did not fit, and what was decided:

1. **Where the meters are made** (open question 1 of the brief). Decided by the Implementor as
   Camunda 8 does it: one binder per application, `MicrometerZenBpmMetrics`, registered by the
   platform module where Micrometer is there and handed to every deployment service through
   `setMetrics`, which registers its adapter id; the interface and the implementation live in the
   core, with Micrometer optional in the core and both platform modules. Unlike Camunda 8, the
   counts are kept in the binder (a `LongAdder` per meter and adapter id) and handed to a registry
   as function counters, so a count before the registry is bound is not lost and two registries
   read the same number, and every counter of an adapter id exists at zero from its registration
   on. Decision 59.
2. **`CachedGaugeValue` for the slot gauges** (open question 2). The platform wraps only what asks
   a store (`MicrometerVanillaBpMetrics` holds its outbox queries in one), and `CachedGaugeValue`'s
   own javadoc says a value already in memory, such as the free permits of a semaphore, must not be
   wrapped. Decided by the Implementor: the gauges read `ZenBpmExecutor#getBusySlots()` and
   `#getFreeSlots()` at every collection, and the javadoc of `MicrometerZenBpmMetrics` and decision
   59 say why. The plan's wording is not followed here.
3. **The health of a stream down for long** (open question 3, and the note from the review of
   S2.4.1). Camunda 8's health asks for the topology only and never looks at its job workers.
   Decided by the Implementor: the health stays as it is, `UP` where REST answers ready, the
   stream's state in the detail `jobStream`, no new key. Decision 58; the README's new section
   "Health" says it, and `ZenBpmHealthTest#aReadyEngineWithAStreamDownSaysSo` now waits until the
   stream failed at least three times and still reads `UP`.
4. **`queued`** (open question 4). The queue of the dispatcher is a `ConcurrentLinkedDeque`, whose
   `size()` walks it, so no gauge. Decided by the Implementor: a counter, once per delivery which is
   still waiting once its own arrival looked for a slot. Counting each refusal of `tryExecute`
   would have missed a job which waited behind another and was handed over at its first try, and
   counted a job refused twice twice.
5. **`failed` and `completed`** (open question 5). Camunda 8 counts no outcome itself (its client's
   hook counts activated and handled jobs). Decided by the Implementor: a completion and a BPMN error
   the engine took count as completed, a failure it took, with or without retries, as failed; a task
   left open and an answer which found the job gone count as neither. The start and end markers are
   jobs of the engine and part of `received`, so they count alike, which `ZenBpmMetricsIT` shows
   with the end marker of `Crating`.
6. **`redelivered`** ("a delivery id seen before" in the architecture). The delivery id of a job is
   its key, and the engine tells a redelivery itself: at the pin, `RecordJobDeliveries`
   (`internal/cluster/partition/partition_persistence.go`) raises a job's `delivery_token` with every
   delivery it records, from 0 to 1 at the first. Decided by the Implementor: a delivery token above
   1 counts as redelivered, whatever the reason (a lapsed lock, a retry, a parked task coming back),
   with no memory of the adapter and the same across a restart; `ZenBpmMetricsIT` shows the retry of
   a failed job arriving with token 2.
7. **`lock.lost`**. The renewal ends for a lost lock (`409`) and for an engine it could not ask
   before the lock lapsed; both say that a second delivery may run beside the handler. Decided by
   the Implementor: both count as lost; a job the engine no longer holds, and an answer after the
   handler ended, do not. The extension which parks a job is no renewal.
8. **`usertasks.polls`** (open question 6): one per look of the poller of an adapter id, the looks
   the engine did not answer included, as the javadoc of `ZenBpmMetrics#USER_TASK_POLLS` says.
9. **Quarkus** (open question 7): the producer `ZenBpmMetricsProducer` is registered by
   `ZenBpmIntegrationProcessor` where `io.quarkus.micrometer.deployment.MicrometerProcessor` is on the
   deployment classpath, as Camunda 8 and the platform detect it; the deployment services ask for
   the binder through an `Instance`. No module of the repository had Micrometer before, so the
   Quarkus extension tests in `quarkus/deployment` are the application without it
   (`ZenBpmWithoutMicrometerTest`, two adapter ids), and `quarkus/integration-tests` now uses the
   Micrometer extension (with a `SimpleMeterRegistry` as its backend, as the platform's
   `TestMeterRegistryProducer` does), where
   `ZenBpmWorkflowLifecycleTest#theMetersOfTheAdapterIdMoveWithTheWork` reads the meters through
   `introspect/meters/{adapterId}`. On Spring Boot `ZenBpmMetricsBootTest` boots the same two adapter
   ids with Micrometer and with it hidden by a `FilteredClassLoader`, as the platform's
   `WorkflowAdapterCacheConfigurationTest` does.
10. **An adapter id without clients**: it has no stream and no executor, and none of its counters
    could ever move. Decided by the Implementor: it registers no meter (Camunda 8 publishes its
    configured slots there; this adapter has no such number without an executor).
11. **Names**: the plan's names are taken, and the architecture's section 7, which wrote
    `vanillabp.zenbpm.jobs.lock.renewed`, is brought in line. GAPS: none cited, none added.

**Acceptance criteria**

- [x] An application without Micrometer boots unchanged. Spring Boot: `ZenBpmMetricsBootTest` boots two adapter ids with
  Micrometer (one binder bean, both deployment services measure into it, 11 meters per id, the two
  sets differing in the tag `adapter` only) and with Micrometer hidden by a `FilteredClassLoader`
  (the same deployment services, process services and factories, the startup block, no binder bean,
  every deployment service on `ZenBpmMetrics.NONE`). Quarkus: `ZenBpmWithoutMicrometerTest` boots two
  adapter ids in `quarkus/deployment`, where Micrometer is not on the classpath at all, and
  `ZenBpmWorkflowLifecycleTest#theMetersOfTheAdapterIdMoveWithTheWork` reads the 11 meters of the
  prod-mode application, which uses the Micrometer extension, and sees received, completed and polls
  move. Against the pinned image, `ZenBpmMetricsIT` (Spring Boot, one handler slot, the process
  `Crating`) reads exactly 4 jobs received (two weighings, the sealing, the end marker), 1
  redelivered (the retried weighing, delivery token 2), at least 1 queued, 3 completed, 1 failed,
  at least 1 lock renewed, 0 lost, 0 reconnects, and the slots gauged busy 1 / free 0 while a handler
  runs. Every counter moves in a test at its place: `ZenBpmJobDispatcherTest` (received, redelivered,
  queued), `ZenBpmLockRenewalTest` (renewed, lost), `ZenBpmJobHandlerTest`, `ZenBpmJobFailureTest`,
  `ZenBpmBpmsInitiatedStartHandlerTest`, `ZenBpmWorkflowEndedHandlerTest` (completed, failed),
  `ZenBpmUserTaskPollerTest` (polls, failed looks included), `ZenBpmWorkflowProcessingTest`
  (the deployment service's registration, reconnects after a broken call, nothing for an adapter id
  without clients); `MicrometerZenBpmMetricsTest` pins the README's table "Metrics" against the
  meters and the constants. Mutants: 32 in the core (each counter call removed or moved, the
  redelivery guard at `>= 1` and always, the queued guard negated and its two `settled` writes
  removed, a lost lock counted for a gone job and after the handler ended, the registration's four
  guards, the binder's four), 2 in the Spring Boot glue (the `@ConditionalOnClass` removed, the
  registrar handing no metrics), 3 in the Quarkus glue (the detection forced on and off, the
  producer handing `NONE`): all 37 killed by a failing test, each mutant compiled.
  Clean build (`TZ=Etc/UTC mvn clean install` on OpenJDK 25.0.2, Docker ITs included, none skipped):
  engine test support 12 + 5, core 1,268 + 104, Spring Boot 14 + 94, smoke test 40, Quarkus runtime
  13, deployment 53, integration tests 24 (`ZenBpmWorkflowLifecycleTest` 23), gate 37; coverage
  100 % on both platforms (0 of 38,268 instructions missed on Spring Boot, 0 of 38,162 on Quarkus);
  `bin/check-deploy-safety.sh` green (13 files deployed by the green build, nothing by the red gate).

## S11.2.1 Startup-message review

- [ ] **Depends on:** E10.

Walk every message the adapter writes (grep for `log.warn`, `log.error`, `IllegalStateException`,
`UnsupportedOperationException`, and every finding handed to the platform's `StartupReport`)
against the config-validation skill: names module, process, aggregate, attempted operation, exact
property key, the way out; never a value which could be a secret. A finding of the start - a notice,
a warning, a configuration the adapter refuses - goes through the `StartupReport` bean with its
`StartupTopic` (`CONFIGURATION` for the adapter's own keys) and the adapter id as the scope, never
inside the text (platform decisions 96 and 97); only what happens after the start is logged
directly. Each message gets an assertion in an existing test or a new `ZenBpmMessagesTest`: a
startup finding is asserted in the report block the core prints at the end of the start, a runtime
message through `CapturedOutput`. Write the messages into the README section "Boot behavior" like
Camunda 8 does.

**Acceptance criteria**: every guiding message is asserted by content in a test; no startup finding
of the adapter bypasses the `StartupReport`.

## S11.3.1 Election ITs

- [ ] **Depends on:** S3.3.1, E7.

New module `election-integration-test` (Spring, Docker): `ZenBpmSharedEngineElectionIT` - two ids of
type `zenbpm` on ONE engine, differing in `name-clash-avoidance` (`use-prefix`
first, `none` second); a workflow started under the old id is found and completed by the old id, a
new one starts under the new id, a task probe of the wrong id answers UNKNOWN. `ZenBpmNextToTheDoubleIT`
- `zenbpm` first, the platform's BPMS double second (`bpms-double-spring-boot`): a workflow the double
holds is not claimed by zenbpm; `canLocateWorkflows` true so no `guessing-adapters` message.

**Acceptance criteria**: both ITs green; the `ElectionScopeContractTest` reasoning holds against a
real engine.

## S11.4.1 Native image

- [ ] **Depends on:** E9.

Module `quarkus/native-image-tests` (Camunda 8's shape, Netty version note applies): reflection
registration for the generated protobuf classes and the Jackson records
(`@RegisterForReflection` or a `ZenBpmNativeImageProcessor` producing `ReflectiveClassBuildItem`s),
`grpc-netty-shaded` native configuration as Quarkus documents it. The part descriptor
`META-INF/vanillabp/adapter-zenbpm.properties` needs no registration of its own: the platform's
Quarkus extension registers every `META-INF/vanillabp/*.properties` it finds in the application as a
native-image resource. `ZenBpmNativeImageIT` runs deploy,
start, task, message against the container from the native executable. Runs in CI on a schedule
(nightly) like Camunda 8's line matrix, not per pull request.

*Note from S3.2.1, 2026-10-16.* `ZenBpmNativeImageProcessor` exists: it indexes the core
(`IndexDependencyBuildItem`) and opens every class of the generated package
`org.pbinitiative.zenbpmadapter.client.grpc` to reflection with constructors, methods and fields
(`ZenBpmNativeImageProcessorTest`, against an index of the core, no native build). Nothing else is
registered yet: the gRPC providers the channel looks up through the `ServiceLoader`, the run-time
initialization Netty and protobuf need (Camunda 8's `Camunda8NativeImageProcessor` lists both), and
the Jackson records. `ProtobufRuntimeCheck` reads `META-INF/vanillabp/adapter-zenbpm.properties` with
`ResourceBundle.getBundle`, which a native image may want registered as a bundle and not only as a
resource; the job stream runs the check when it is built, so the native flow shows it.

**Acceptance criteria**: the native build succeeds and the flow runs; the wiki page "Native images"
says what is needed.

## S11.6.1 Nightly workflow: the pinned engine, the engine's latest image, the native image

- [ ] **Depends on:** S11.4.1.

`.github/workflows/nightly.yaml`, `on: schedule` (once a night) and `workflow_dispatch`, three jobs:

1. `pinned-engine`: the full build with Docker ITs against `zenbpm.image`, the same as `checks.yaml`
   (so a flaky test shows up on a quiet night and not in somebody's pull request);
2. `latest-engine`: the same ITs with `-Dzenbpm.image=ghcr.io/pbinitiative/zenbpm:dev` (the newest
   `main` build; the property the container helper reads is overridable for exactly this),
   `continue-on-error: true`, and a summary line naming the engine commit the container reported; a
   red job here is the earliest warning that an engine change broke the adapter, and because the
   same organisation owns both, the finding goes straight into the engine's issue tracker;
3. `native-image`: the native build and `ZenBpmNativeImageIT` of S11.4.1 (GraalVM via
   `graalvm/setup-graalvm`), never on pull requests because it takes long.

The README's "What CI runs" section describes the three jobs; the nightly gets its own badge.

**Acceptance criteria**: the nightly runs green against the pinned engine; the `latest` job reports
the engine version it met; the native job runs the lifecycle flow.

## S11.5.1 Coverage at the rule

- [ ] **Depends on:** S11.4.1.

Compare `jacoco.csv` of both reports class by class; every core class high on Spring and low on
Quarkus names a feature the Quarkus twin never runs - add the twin, never the execution data. Fill the
remaining edges with unit tests. Both reports above `coverage.rule` (90); the gate stays at 85 and
is never edited to pass.

**Acceptance criteria**: both badges read above 90; the gate's exception list names only
`engine-test-support` and the native module with a reason.

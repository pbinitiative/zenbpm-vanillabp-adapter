# E11 - Operations and hardening

**Goal.** What an operator gets to see, the election proven with two ids and next to another BPMS, a
native image, and coverage at the rule on both platforms.

**Done when.** Meters and health are documented and tested, the election ITs are green, the native
image runs the lifecycle flow, and both coverage reports read above 90.

---

## S11.1.1 Metrics

- [ ] **Depends on:** E6.

`ZenBpmMetrics` (interface with a `NONE`) and `MicrometerZenBpmMetrics` (registered where Micrometer
is present: Spring `@ConditionalOnClass(MeterRegistry)`, Quarkus the Micrometer-processor detection of
`Camunda8IntegrationProcessor`): counters `vanillabp.zenbpm.jobs.received|completed|failed|queued|redelivered`,
`vanillabp.zenbpm.lock.renewed|lost`, `vanillabp.zenbpm.stream.reconnects`, `vanillabp.zenbpm.usertasks.polls`; gauges
`vanillabp.zenbpm.slots.busy|free` (`CachedGaugeValue`, decision 18 of the platform), all tagged
`adapter=<id>`. Tests: `MicrometerZenBpmMetricsTest`, and a boot test per platform that the meters
exist only with Micrometer.

**Acceptance criteria**: an application without Micrometer boots unchanged.

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
type `zenbpm` on ONE engine (`shared-engine: true`), differing in `name-clash-avoidance` (`use-prefix`
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

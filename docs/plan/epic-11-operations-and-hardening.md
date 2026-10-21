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

**Answers to two reviews of e11-s11.1.1, 2026-10-21** (one by Claude, ready after the Minors are
weighed: 3 Minor, 2 Nits; one by Copilot, changes requested: 1 Minor). Every claim was checked in
the code of the branch, by the reviewers' probes and by a test of my own. Claude's fifth finding
and Copilot's only one are the same race, so five distinct findings remain; all five were valid
and are fixed. Decision 59, new on this branch, was extended in place before the merge; no new
decision was needed.

- **`jobs.queued` missed a job another handover held out of the queue** (Copilot 1, Claude 5,
  Minor). `accept` read the job's `settled` flag once, after its own handover, while a handover on
  a handler thread (`onSlotFreed`) could have taken the job out and not yet heard the slot refuse
  it. That handover put the job back without counting it, and nothing counted it later. Copilot
  showed it with latches on the unmodified dispatcher: three jobs waiting, two counted. Claude also
  named the reverse window, between `pollFirst` and the flag. Taking a job out of the queue and
  marking it taken is now one step under the queue's lock, and so is putting it back. The arrival
  counts a job which is still in the queue. A job which another handover holds while its arrival
  looks is decided by that handover: it is counted once where the slot refuses it, and not at all
  where the slot takes it. Tests with a forced interleaving:
  `ZenBpmJobDispatcherTest#aJobTakenByAnotherArrivalAndRefusedIsCountedThere` (3 counted; it
  fails on `46d70a1`) and `#aJobTakenByAnotherArrivalAndStartedIsNotCounted` (2 counted).
- **The Spring Boot boot without Micrometer could not catch an adapter class which loads a
  Micrometer type** (Claude 1, Minor). A `FilteredClassLoader` hides Micrometer from the
  conditions only, so the application class loader still loaded Micrometer for the adapter's own
  classes. Claude's mutant, a registrar which asks for `MicrometerZenBpmMetrics`, survived, and
  that class fails to load without Micrometer. `smoke-test` now runs
  `ZenBpmWithoutMicrometerBootTest` in a Surefire execution of its own (`without-micrometer`),
  with `micrometer-core` and Spring Boot's metrics left off the classpath. The test asserts that
  the classpath really lacks Micrometer. `ZenBpmMetricsBootTest`'s comparison of the two boots
  keeps its filtered class loader, which now hides `io.micrometer.`.
- **No Spring Boot test showed that Spring Boot applies the binder to its own registry** (Claude 2,
  Minor). Both Spring Boot tests bound the binder to a registry of their own, as Camunda 8's
  `Camunda8WorkerThreadsIT` does. `smoke-test` now has Spring Boot's
  `spring-boot-micrometer-metrics` as a test dependency, and
  `ZenBpmMetricsBootTest#withMicrometerEachAdapterIdPublishesItsMeters` reads the context's own
  `MeterRegistry`.
- **A task completed later through the process service was not counted, and nothing said so**
  (Claude 3, Minor). The behaviour stays as it is: `jobs.completed` counts what the handlers
  answered. Camunda 8 counts the jobs its handlers took. Phase two runs on whichever node the
  application calls, outside the handler slots. A user task never comes through the job stream, so
  counting it would make `completed` exceed `received`. `ZenBpmMetrics#JOBS_COMPLETED`, the README's
  "Metrics" and decision 59 now say so, and the new workflow `Labelling` of `ZenBpmMetricsIT` pins
  it against the pinned image: `#aTaskCompletedThroughTheProcessServiceIsNotCountedAsCompleted`.
  Its start marker counts as completed and its task left open does not, neither while it is open
  nor once the application completed it. After the completion only the jobs which came after it
  count, the end marker's. `#theMetersFollowTheJobs` now compares against a snapshot taken at its
  start, because the binder counts from the start of the application on.
- **A second registration of an adapter id left gauges reading `NaN`** (Claude 4, Nit). Micrometer
  ignores a meter registered twice and holds a gauge's state weakly, while the binder kept only the
  second suppliers. No production path registers twice. `MicrometerZenBpmMetrics#registerAdapter`
  now keeps the first registration, and the javadoc of `ZenBpmMetrics#registerAdapter` says so.
  Test: `MicrometerZenBpmMetricsTest#aSecondRegistrationOfAnAdapterIdChangesNothing`.

Mutants of the fixes, each killed by a failing test: in the dispatcher, the put-back never
counting, `taken` ignored, `counted` ignored, `looked` ignored, `taken` never set, `taken` never
cleared (6); the first registration's guard removed (1); phase two counting a completion, through
a static hook (1, `ZenBpmMetricsIT`); the binder without `implements MeterBinder` (1, the old test
binding by hand passed it); Claude's registrar mutant (1, killed by the new execution only).
Build after the fixes (`TZ=Etc/UTC mvn clean install` on OpenJDK 25.0.2, Docker ITs included,
none skipped): engine test support 12 + 5, core 1,271 + 104, Spring Boot 14 + 95, smoke test 40 + 1
(`without-micrometer`), Quarkus runtime 13, deployment 53, integration tests 24, gate 37; coverage
100 % on both platforms (0 of 38,341 instructions missed on Spring Boot, 0 of 38,235 on Quarkus);
`bin/check-deploy-safety.sh` green (13 files deployed by the green build, nothing by the red gate).

**Answer to CodeRabbit on pull request #73, 2026-10-21.** One comment, valid. The Spring Boot
binder was `@ConditionalOnMissingBean` on its own class, so an application's own `ZenBpmMetrics`
bean stood next to it, and the registrar's `getIfAvailable` threw `NoUniqueBeanDefinitionException`
at the boot. On Quarkus the same two beans made the `Instance` of the deployment-service producer
unresolvable, so the deployment services silently measured into `NONE`. The binder is now
conditional on the interface on Spring Boot. On Quarkus the deployment-service producer prefers a
bean of any other producer to the binder. A `@DefaultBean` was tried first and broke
`ZenBpmWorkflowLifecycleTest#theMetersOfTheAdapterIdMoveWithTheWork`: Arc leaves a default bean
out of every lookup of a type another bean has, and the Micrometer extension's own binders are
`MeterBinder`s, so the registry was never handed the adapter's binder. Tests:
`ZenBpmMetricsBootTest#anApplicationsOwnMetricsReplaceTheBinder` (with the old condition it fails
with exactly that exception) and the new prod-mode `ZenBpmOwnMetricsTest`. Mutants: the old
condition, the preference answering `NONE`, the preference picking the binder: all killed. The
README's "Metrics" section and decision 59 say it. Build (`TZ=Etc/UTC mvn clean install`, Docker ITs, none skipped): smoke test 41 + 1, Quarkus
integration tests 25, the rest as above; coverage 100 % on both platforms (0 of 38,341 missed on
Spring Boot, 0 of 38,257 on Quarkus); `bin/check-deploy-safety.sh` green.

**S11.1.1 done 2026-10-21**, merged to `main` as `eac63e8` (pull request #73: story `46d70a1`,
review fixes `639abf8`, CodeRabbit fix `4ba2da9`). On `eac63e8` (GitHub keeps run logs for 90
days, so what they showed is written here as well):

- `Checks`, run
  [39564947296](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/actions/runs/39564947296):
  green on both JDKs. `bin/check-engine-copies.sh` (in the JDK 25 job) reported both copies as the
  engine's files of `bdd795a3...`. On both JDKs `MicrometerZenBpmMetricsTest` ran 6 tests,
  `ZenBpmJobDispatcherTest` 15, `ZenBpmHealthTest` 12, `ZenBpmMetricsIT` 2,
  `ZenBpmMetricsBootTest` 3, `ZenBpmWithoutMicrometerBootTest` 1 (in the Surefire execution
  `without-micrometer`), the Quarkus `ZenBpmWithoutMicrometerTest` 1, `ZenBpmOwnMetricsTest` 1 and
  `ZenBpmWorkflowLifecycleTest` 23, none skipped (engine test support 12 unit and 5 integration
  tests, core 1,271 unit and 104 integration tests, Spring Boot 14 unit and 95 integration tests,
  smoke test 41 + 1, Quarkus runtime 13, Quarkus deployment 53, Quarkus integration tests 25,
  coverage gate 37). The gate measured 100 % on both platforms: of 38,341 instructions for Spring
  Boot and 38,257 for Quarkus on JDK 25, of 38,329 and 38,245 on JDK 21.
- `Publish snapshots`, run
  [39564947339](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/actions/runs/39564947339):
  green. The snapshot it published to GitHub Packages, `2.0.0-20261021.075138-69`, holds the
  batch's new main classes: in the core `ZenBpmMetrics` (with `NONE`) and `MicrometerZenBpmMetrics`
  with `Counted`, `Counter` and `Read` (265 classes), in the Spring Boot module
  `ZenBpmClientAutoConfiguration.ZenBpmMetricsConfiguration`, in the Quarkus runtime
  `ZenBpmMetricsProducer`, and no test class, fake, fixture or BPMN file. Both report pages
  (`spring-boot-report/index.html`, `quarkus-report/index.html`) answer HTTP 200 and show 0 of
  38,341 (Spring Boot) and 0 of 38,257 (Quarkus) instructions missed.

## S11.2.1 Startup-message review

- [x] **Depends on:** E10.

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

*Fit check, 2026-10-21, decided by the Implementor where it says so.* Checked against `main` at
`f058a0a`, section 3.9 of the platform's `ADAPTER-AUTHORS.md`, the platform's `StartupReport`,
`StartupFindings` and `DeploymentService` (where it ends the start and where it starts the
processing), the Camunda 8 adapter's `Camunda8DeploymentService` (`warnAboutThisAdapter`,
`warnAboutUnscopedIdentifiers`) and the skill `vanillabp-config-validation`. No claim about the
engine was needed. What did not fit, and what was decided:

1. **The platform's contract contradicts itself** (open question 1). The example of section 3.9
   writes the adapter id into the text; the rules below it say never to. The code decides:
   `StartupFindings` folds findings of one topic by their TEXT and writes the scopes on a line of
   their own, so a text which names its scope never folds. Decided by the Implementor: the scope
   carries the adapter id (and the workflow module and process, where the finding is about one),
   the text says "this adapter", "this workflow module", "this BPMN process", and a property key
   is still written out in full, as the skill and the platform's own findings write keys. Decision
   60.
2. **The inventory.** 207 sites in the main sources of `core`, `spring-boot`, `quarkus/runtime` and
   `quarkus/deployment` (`log.info|warn|error`, every exception constructed, every report call);
   the platform modules hand the report to the core and hold no message of their own but the
   Quarkus build step which refuses an old protobuf runtime while the application is built. Startup findings
   which bypassed the report: the warning about `name-clash-avoidance: none`, the warning about
   `message-start-lookup: scan`, the engine which does not say its commit, the versions the held
   messages could not read, the three questions of the version catalog the engine did not answer,
   the four findings about a process id declared without a model, and the validation's refusal,
   which was thrown. All now go through the new `ZenBpmFindings`, which logs `<scope>: <message>`
   at the report's level where no report was handed in. The deployment service also handed a report
   which may be `null` to the environment check, which called it unguarded; that is gone with it.
3. **A configuration which ends the start** (decided by the Implementor): it is refused through the
   report, and the adapter id gets a factory without clients; the platform ends the start with
   every collected reason before it deploys anything. So is a protobuf runtime too old for the job
   stream, under `PARTS AND VERSIONS` with the dependency as its scope, every adapter id then
   without clients. Only without a report is it thrown at once. Decision 60.
4. **What a deployment step refuses stays thrown** (decided by the Implementor): `prepareBpmn`,
   `wireBpmn` and `deployResources` throw, because the platform's `deployment-failure` policy
   decides whether the start ends or the failure stands in the block of an application which boots;
   reported instead, the deployment of the workflow module would go on. Decision 60.
5. **A finding after the block** (open question 2). The platform starts the workflow processing of
   a Spring Boot application after the block, so the only adapter findings found there, about a
   declared process id, are late there; the report writes them into the log on its own, with their
   scope, and a refusal would be written, not thrown. The adapter reports through the report in both
   cases. Proved on both platforms: `ZenBpmDeclaredProcessFindingTest` (Quarkus, new) reads the
   finding in the block under `INFRASTRUCTURE`; `ZenBpmRenamedProcessIT` (Spring Boot, against the
   pinned image; its application now also declares `Parcels`, of which the engine holds no model)
   reads it written by the platform's `StartupFindings` with its scope.
6. **Reports about the adapter doing its work** (open question 3) stay INFO lines: the engine
   reached, the tasks wired, the files deployed, the job types subscribed and unsubscribed, the
   wait for the engine, the user-task looks, the drain. Where a declared process id is partly
   served, the INFO says what serves it and a finding says what does not; before, one INFO said
   both.
7. **Changed wording** (open question 4): the tests which asserted the old words were changed with
   them, the README quoted none of them, and the wiki quotes none.
8. **Runtime messages against the skill.** Two changed: the parse failure of a job's variables
   quoted the parser's message and carried the parser's exception as its cause, both of which quote
   a token of the workflow's data, and now does neither; the executor's "a handler threw" now says
   it is a defect to report. The others name the adapter id, the job, the task and the aggregate or
   module where they are known, what happens next and the key where one helps; kept.
9. **`ZenBpmMessagesTest`** (decided by the Implementor): not built. Every message's assertion went
   into the test which already drives its path; the sites without a literal of their own pass on a
   message which is asserted where it is built.
10. **GAPS**: none cited, none added. The README has a new section "Boot behavior".
11. **Coverage of a class this story did not touch**: the first clean build missed one branch of
    `ZenBpmDeploymentServiceProducer#measuredInto` on Quarkus, because `findFirst` stopped before
    the filter saw the binder wherever CDI listed the application's own bean first, and that order
    is not fixed. The filter now sees every handle and the first other bean is taken; the choice
    is the same.

**Acceptance evidence, 2026-10-21.**

- [x] Every guiding message is asserted by content in a test. The 207 sites were checked by a
  script against the test sources (a run of at least four words of each literal); what it found
  without an assertion got one: the executor's two refusals, the iteration and the instance a
  lagging node does not list, a probe whose job names an instance the node does not hold yet (new
  `ZenBpmTaskProbesTest#aJobOfAnInstanceTheNodeDoesNotHoldIsUnavailable`), the two "pushed" lines,
  the two lines of the check before the commit, "has no job type to subscribe", the address derived
  from an unvalidated `rest-address`. The remaining sites pass on a message which is asserted where
  it is built. Every finding of the start is asserted as the report receives it, with its level,
  topic, scope and text, and in the block the platform's own `StartupFindings` draws from it, under
  the heading of its topic (core, `CollectedStartupReport`, which hands every finding on to the
  platform's class; see the answers to the reviews below). On the platforms, the block is asserted
  where a test boots with the finding; the README's "Boot behavior" names the test of each
  sentence.
- [x] No startup finding of the adapter bypasses the `StartupReport`: every one goes through
  `ZenBpmFindings` or the validation's refusal. The refusal reaches the platform's exception on both
  platforms (`ZenBpmStartupValidationBootTest#anInconsistentFirstPriorityAdapterIdEndsTheStart`,
  `ZenBpmInconsistentConfigurationTest`: the scope line under `CONFIGURATION (1)`), a finding of the
  start of the processing stands in the block on Quarkus (`ZenBpmDeclaredProcessFindingTest`) and is
  written by the platform's `StartupFindings` on Spring Boot (`ZenBpmRenamedProcessIT`, against the
  pinned image).
- Mutants, each compiled and killed by a failing test: the validation's refusal removed, its finding
  text replaced by the runtime message, the throw kept beside a report, the factory with clients
  kept beside a refused protobuf runtime; `ZenBpmFindings` logging a warning and a notice beside a
  report; the deployment service not handing the report on; the finding about what a declared id
  does not serve and the INFO about what serves it suppressed; the variables' parser exception kept
  as the cause, the two parse messages swapped (11 in the core); the declared process's finding
  logged past the report (1, killed by the Quarkus `ZenBpmDeclaredProcessFindingTest`). 12 of 12.
- Clean build (`TZ=Etc/UTC mvn clean install` on OpenJDK 25.0.2, Docker ITs included, none skipped):
  engine test support 12 + 5, core 1,275 + 104, Spring Boot 14 + 95, smoke test 41 + 1, Quarkus
  runtime 13, deployment 54, integration tests 25, gate 37; coverage 100 % on both platforms (0 of
  38,473 instructions missed on Spring Boot, 0 of 38,395 on Quarkus); `bin/check-deploy-safety.sh`
  green (13 files deployed by the green build, nothing by the red gate).


**Answers to two reviews of e11-s11.2.1, 2026-10-21** (one by Claude, approve with minor changes:
2 Minor, 6 Nits; one by Copilot, changes requested: 1 Major). Every claim was checked in the code of
the branch and the platform's `StartupFindings`. Claude's first finding and Copilot's only one are
the same gap seen from two sides; all findings were valid and are fixed. Decision 60, new on this
branch, was corrected in place before the merge; no new decision was needed.

- **The topic and the block of the startup findings were not asserted** (Claude 1, Minor; Copilot
  1, Major). The tests of the declared process ids, of the held messages and of `scan` built the
  deployment service without a report. So the findings went to the log fallback, which drops the
  topic. Other tests read the findings only from `CollectedStartupReport`'s list, which renders
  nothing. Claude's mutant (six topics set to `CODE`) survived the core and the two Spring ITs.
  The version catalog's test checked only one of its four questions. `CollectedStartupReport` now
  hands every finding on to the platform's own `StartupFindings` (the core has
  `io.vanillabp.adapter:migration-adapter` as a test dependency). `block(topic)` reads what the
  block or the platform's refusal draws under a topic's heading. `showsInTheBlock(finding)` checks
  the scope line with the indented text below it. The tests which drive each startup finding now
  hand in the report and assert the finding there:
  - `ZenBpmDeclaredProcessWorkersTest`: the four findings about a declared id;
  - `ZenBpmDeployingTest#versionsWhichCannotBeReadAreNamed` and
    `#theProcessesAMessageStartsAreNamedOnce`;
  - `ZenBpmProcessVersionCatalogTest`: all four questions, the count and the older versions;
  - `ZenBpmEnvironmentInfoTest`: both warnings;
  - `ZenBpmStartupValidationTest`: every notice, warning and refusal of eight tests.

  `ZenBpmMessageStartScanIT` reads its finding under `CONFIGURATION`. 17 topic mutants, one per
  finding site of the core, all killed by the core tests. The rendering is one class of the
  platform on both platforms. What differs per platform is how the report reaches the adapter,
  which the platform tests already boot with (`ZenBpmStartupValidationBootTest`,
  `ZenBpmDistinctInstancesBootTest`, `ZenBpmMessageStartScanIT`, `ZenBpmRenamedProcessIT`, the
  Quarkus `ZenBpmInconsistentConfigurationTest`, `ZenBpmOneEngineTwoModesTest`,
  `ZenBpmDeclaredProcessFindingTest`).
- **Two README promises were not held by the tests they named** (Claude 2, Minor). No test showed
  two refusals in one start on a platform. The new
  `ZenBpmStartupValidationBootTest#twoAdapterIdsWhichEndTheStartAreNamedInOneStart` boots with two
  inconsistent adapter ids and reads both in the platform's one exception (`CONFIGURATION (2)`).
  A mutant which hands only the first refusal to the report is killed by it. The README and decision
  60 now promise what it shows: the keys of two adapter ids. The protobuf sentence now says what
  its core test shows: a refusal handed to the platform's report, and no adapter id gets clients.
- **"The engine holds it as" where it holds no model** (Claude 3, Nit). The finding now reads "...
  reaches its workflows, which run at the engine under the id '<id>'".
- **"With the same words"** (Claude 4, Nit). The README now says "with the same message, which
  names the adapter id where the block's scope does".
- **The javadoc of `registryOf`** (Claude 5, Nit) now says how both paths end in one exception.
- **Decision 60's layout** (Claude 6, Nit): the list of findings is now a bulleted list, and every
  line fits in 100 columns.
- **"Report it" without a place** (Claude 7, Nit): both defect messages of the executor now name
  the issue tracker (`https://github.com/pbinitiative/zenbpm-vanillabp-adapter/issues`).
- **Nested parentheses** (Claude 8, Nit): "did not say which version it runs: <reason>. So it is
  unknown whether ...".
- Build after the fixes (`TZ=Etc/UTC mvn clean install`, OpenJDK 25.0.2, Docker ITs included, none
  skipped): engine test support 12 + 5, core 1,275 + 104, Spring Boot 14 + 95, smoke test 42 + 1,
  Quarkus runtime 13, deployment 54, integration tests 25, gate 37; coverage 100 % on both
  platforms (0 of 38,473 instructions missed on Spring Boot, 0 of 38,395 on Quarkus);
  `bin/check-deploy-safety.sh` green (13 files deployed by the green build, nothing by the red
  gate). Mutants: 17 topics (one per finding site of the core) and the second refusal, 18 of 18
  killed.

**S11.2.1 done 2026-10-21**, merged to `main` as `d8eefef` (pull request #75: story `304da90`,
review fixes `2310dd0`; CodeRabbit had no comment). On `d8eefef` (GitHub keeps run logs for 90
days, so what they showed is written here as well):

- `Checks`, run
  [39604986109](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/actions/runs/39604986109):
  green on both JDKs. Its first attempt failed on JDK 21 only: Maven Central answered `502 Bad
  Gateway` for `maven-builder-support` while the Quarkus runtime resolved its plugin. The JDK 21
  job was rerun once and passed. `bin/check-engine-copies.sh` (in the JDK 25 job) reported both
  copies as the engine's files of `bdd795a3...`. On both JDKs `ZenBpmDeclaredProcessWorkersTest`
  ran 25 tests, `ZenBpmDeployingTest` 17, `ZenBpmProcessVersionCatalogTest` 11,
  `ZenBpmEnvironmentInfoTest` 11, the core `ZenBpmStartupValidationTest` 17, `ZenBpmExecutorTest`
  50, `ZenBpmStartupValidationBootTest` 18, `ZenBpmDistinctInstancesBootTest` 2,
  `ZenBpmMessageStartScanIT` 1, `ZenBpmRenamedProcessIT` 1, the Quarkus
  `ZenBpmDeclaredProcessFindingTest` 1, `ZenBpmInconsistentConfigurationTest` 1,
  `ZenBpmOneEngineTwoModesTest` 1 and `ZenBpmStartupValidationTest` 2, none skipped (engine test
  support 12 unit and 5 integration tests, core 1,275 unit and 104 integration tests, Spring Boot
  14 unit and 95 integration tests, smoke test 42 + 1, Quarkus runtime 13, Quarkus deployment 54,
  Quarkus integration tests 25, coverage gate 37). The gate measured 100 % on both platforms: of
  38,473 instructions for Spring Boot and 38,395 for Quarkus on JDK 25, of 38,461 and 38,383 on
  JDK 21.
- `Publish snapshots`, run
  [39604986164](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/actions/runs/39604986164):
  green. The snapshot it published to GitHub Packages, `2.0.0-20261021.130555-71`, holds the
  batch's new main class `ZenBpmFindings` in the core (266 classes), the review fixes' wording
  (the declared id's finding "run at the engine under the id", the executor's issue tracker), and
  no test class, fake, fixture or BPMN file. Both report pages (`spring-boot-report/index.html`,
  `quarkus-report/index.html`) answer HTTP 200 and show 0 of 38,473 (Spring Boot) and 0 of 38,395
  (Quarkus) instructions missed.

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

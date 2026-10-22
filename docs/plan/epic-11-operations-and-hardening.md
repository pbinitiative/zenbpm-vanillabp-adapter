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

- [x] **Depends on:** S3.3.1, E7.

New module `election-integration-test` (Spring, Docker): `ZenBpmSharedEngineElectionIT` - two ids of
type `zenbpm` on ONE engine, differing in `name-clash-avoidance` (`use-prefix`
first, `none` second); a workflow started under the old id is found and completed by the old id, a
new one starts under the new id, a task probe of the wrong id answers UNKNOWN. `ZenBpmNextToTheDoubleIT`
- `zenbpm` first, the platform's BPMS double second (`bpms-double-spring-boot`): a workflow the double
holds is not claimed by zenbpm; `canLocateWorkflows` true so no `guessing-adapters` message.

**Acceptance criteria**: both ITs green; the `ElectionScopeContractTest` reasoning holds against a
real engine.

*Fit check, 2026-10-21, decided by the Implementor where it says so.* Checked against `main` at
`03294bf`, the platform's `ADAPTER-AUTHORS.md` section 4, `ElectionScopeContractTest`,
`MigrationProcessService` (the start on the first adapter,
`validateElectionCapabilityAfterDeployment`), `WorkflowLocator` (a start row or a cached hint is
probed first) and `ExtensionWorkflowElection`, all read from the published snapshot
`2.0.0-20261020.102546-22` (the sources jar on Maven Central's snapshot repository; the
superproject's checkout of the platform is older), and the BPMS double of the same snapshot
(`bpms-double`, `bpms-double-spring-boot`, sources jar). No claim about the engine at the pin was
needed beyond what the two ITs show against the pinned image. What did not fit, and what was
decided:

1. **Quarkus** (open question of the brief). Decided by the Implementor: Spring Boot only, as the
   story names it. The story adds no adapter behaviour, it runs the platform's election end to end;
   the election is the platform's code, and the adapter's probes are the core's, the same on both
   platforms. The Quarkus side of the shared-engine setup boots in `ZenBpmOneEngineTwoModesTest`. A
   Quarkus twin would need two prod-mode applications on one database for the migration, which
   `quarkus/integration-tests` has no pattern for; it is not cheap, so it is not built.
2. **The module and the coverage report** (open question). Decided by the Implementor: everything of
   `election-integration-test` lives in `src/test`, like the Spring ITs of `spring-boot`. The jar
   holds only the licence files every jar of the repository carries, so it is never empty, and
   install and deploy are skipped (`bin/check-deploy-safety.sh` green). The module is a dependency
   of `test-coverage-report/spring-boot`, so its execution data feeds the Spring Boot report and the
   gate's list of deliberate exceptions still names only `engine-test-support`. Without production
   sources the module needs no rule in `ProductionModules`. The ITs run in `checks.yaml` with `mvn
   install` (failsafe, `@Testcontainers(disabledWithoutDocker = true)`, as every engine IT).
3. **Where the double comes from.** `io.vanillabp:bpms-double-spring-boot` is now managed in the
   root POM's `dependencyManagement` with the platform's version property, like every other
   `io.vanillabp` artifact; `smoke-test` drops its own version. The build resolved build `-22` from
   Maven Central's snapshot repository.
4. **What the double answers** (open question). Read from its code: without a hook it knows no task
   and no workflow (`UNKNOWN_TO_BPMS`), and `canLocateWorkflows` is `true` unless a
   `DummyTaskAwarenessSource` says otherwise. The test steers it with `TheDouble`, a hook which
   holds the workflows a test names and writes down every push of a changed aggregate. So the
   absence of the `guessing-adapters` message depends on both adapters; decided by the Implementor
   to add what zenbpm contributes: a third test makes the double guess, and the platform's refusal
   names `[double]` alone and counts two adapters (`[zenbpm, double]`). The check runs in this
   setup, and the adapter is not among the guessing ones.
5. **A start marker gives every workflow a hint.** The adapter writes a start marker into every
   model it deploys (decision 26), and its delivery writes the row which names the adapter id. So a
   workflow started at the engine on a deployed model reaches the old id, gets that row, and the
   election probes the old id first and never asks the new one. That is right, and it is no gap. To
   run the walk without a hint, which `ElectionScopeContractTest` describes, the test starts a
   workflow on a plain copy of the model it deploys itself under the process id of the old id, as
   another client of the engine would; the platform's `WorkflowElection` then asks the new id first,
   which answers `UNKNOWN_TO_BPMS`, and finds the workflow at the old id.
6. **"Found and completed by the old id".** A task operation names its task, and phase one routes it
   by the record of the delivery (platform decision 30), so the completion of the old user task is
   not where the probes decide. The IT shows the end to end with the delivery log: every delivery of
   the old workflow, the service task which became due only after the migration booted included,
   names `zenbpm-old`, and the instance ends under `Shipping`. The probes are asked directly for the
   rest: the task probe (user tasks, both ids, both directions) and the workflow probe, with the
   aggregate id and with the key of the other id's instance, which the shared engine answers for
   both (decision 25).
7. **Contradictions**: none found between `ADAPTER-AUTHORS.md`, the double's README and the code. No
   GAPS row is cited or added, and no decision (61 stays free); the README names the module and what
   the ITs show.

**Acceptance evidence, 2026-10-21.**

- [x] Both ITs green against the pinned image, in `election-integration-test`:
  `ZenBpmSharedEngineElectionIT` (5 tests) and `ZenBpmNextToTheDoubleIT` (3 tests).
  - The shared engine: an application with `zenbpm-old` alone (`none`) starts two shipments and
    closes; the application of the migration (`zenbpm-new` under `use-prefix` first, `zenbpm-old`
    second) boots on the same database. The old workflow is completed through the old id (its
    user task by the application, its service task by the old id, every delivery naming
    `zenbpm-old`, the instance ending under `Shipping`). A new one starts under the new id
    (`election-it__Shipping`, its start row and every delivery naming `zenbpm-new`). The task probe
    of the wrong id answers `UNKNOWN_TO_BPMS` for the user task of the other id, both ways, and
    the right id `ACTIVE`; so does the workflow probe, by the aggregate and by the other id's key.
    The platform's `WorkflowElection` finds a workflow of the old id it holds no row of past the
    new id.
  - Next to the double: a workflow the double holds is not claimed (the adapter's probe answers
    `UNKNOWN_TO_BPMS`, the election answers `double`, the push of the changed aggregate reaches
    the double), a workflow the application starts runs at the engine; the application boots
    without a `guessing-adapters` message, `canLocateWorkflows` is `true`, and where the double
    guesses the refusal names `[double]` alone for both processes, served by `[zenbpm, double]`.
- [x] The reasoning of `ElectionScopeContractTest` holds against a real engine: on one engine every
  key answers for both ids, and each id answers for its own scope only, so the walk reaches the
  holder (the election test above), and an id which claimed more would win it (the mutants below).
  E11's clause "the election ITs are green" is met by this story; E11 is not done yet.
- Mutants, each installed into the core and run against both ITs, all killed: the scope check of
  the task probe off (killed by the task-probe test); the search of the workflow probe without the
  process id (the workflow-probe and the election tests); the key of a workflow taken without its
  process (the workflow-probe test); `canLocateWorkflows` answering `false` (both double tests which
  boot, and the shared-engine class, whose start is refused); the search answering `ACTIVE` where
  it found nothing (the double test, the workflow-probe and the election tests). 5 of 5. The
  completion of the old workflow survives the first one on its own, as fit-check point 6 says: the
  engine completes a job by its key for either id.
- Clean build (`TZ=Etc/UTC mvn -U clean install` on OpenJDK 25.0.2, Docker ITs included, none
  skipped): engine test support 12 + 5, core 1,275 + 104, Spring Boot 14 + 95, smoke test 42 + 1,
  election integration test 0 + 8, Quarkus runtime 13, deployment 54, integration tests 25, gate 37;
  coverage 100 % on both platforms (0 of 38,473 instructions missed on Spring Boot, 0 of 38,395 on
  Quarkus); `bin/check-deploy-safety.sh` green (13 files deployed by the green build, none of the
  new module, and nothing by the red gate).

**Answers to two reviews of e11-s11.3.1, 2026-10-21** (one by Claude, approve: 2 Nits; one by
Copilot, approve: no finding). Both claims were checked in the code of the branch; both are valid
and fixed. No decision and no gap were needed.

- **The IT's Javadoc named a test class of the platform's repository** (Claude 1, Nit). A rename
  there would leave the name wrong and nothing here would notice, which the repository's rule on
  what code may point at forbids. The name is gone; the sentence keeps the reasoning in its own
  words. The plan keeps the name.
- **"Every key of the engine answers for both ids" was backed by no assertion** (Claude 2, Nit).
  The workflow probe hands whatever a key does not lead to (a `404`, a key of another partition, a
  failure) on to the search by business key, so the wrong id's `UNKNOWN_TO_BPMS` by the other id's
  key would also come from a key which leads nowhere, and the right id's `ACTIVE` may come from the
  search. `ZenBpmSharedEngineElectionIT#aWorkflowProbeOfTheWrongIdAnswersUnknown` now first reads
  both keys through the test's own client (`ZenBpmRestClient#instance`): the old key is the
  running instance of `Shipping`, the new key the running instance of `election-it__Shipping`.
  Mutant: the old key moved off its instance (`+ 1,000,000`) fails the test at that read (the
  engine answers `502 partition not found`). Read from the code, the probes alone would not have
  noticed it: both ids fall through to the search, which answers as before. The unmutated class is
  green (5 tests). The task probe needs no such read: it answers by the job's key alone, without a
  search, so the right id's `ACTIVE` already shows that the key resolves.

Build after the fixes (`TZ=Etc/UTC mvn clean install` on OpenJDK 25.0.2, Docker ITs included,
none skipped, 17:32 min): engine test support 12 + 5, core 1,275 + 104, Spring Boot 14 + 95, smoke
test 42 + 1, election integration test 0 + 8, Quarkus runtime 13, deployment 54, integration tests
25, gate 37; coverage 100 % on both platforms (0 of 38,473 instructions missed on Spring Boot, 0 of
38,395 on Quarkus); `bin/check-deploy-safety.sh` green (13 files deployed by the green build,
none of the new module, and nothing by the red gate).

**S11.3.1 done 2026-10-21**, merged to `main` as `3cf1e32` (pull request #77: story `77b8a72`,
review fixes `207ef98`; CodeRabbit had no comment). On `3cf1e32` (GitHub keeps run logs for 90
days, so what they showed is written here as well):

- `Checks`, run
  [39646351093](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/actions/runs/39646351093):
  green on both JDKs at the first attempt. `bin/check-engine-copies.sh` (in the JDK 25 job)
  reported both copies as the engine's files of `bdd795a3...`. On both JDKs the new module
  `election-integration-test` ran `ZenBpmNextToTheDoubleIT` 3 and `ZenBpmSharedEngineElectionIT` 5
  tests against the pinned image, none skipped (engine test support 12 unit and 5 integration
  tests, core 1,275 unit and 104 integration tests, Spring Boot 14 unit and 95 integration tests,
  smoke test 42 + 1, election integration test 8, Quarkus runtime 13, Quarkus deployment 54,
  Quarkus integration tests 25, coverage gate 37). The gate measured 100 % on both platforms: of
  38,473 instructions for Spring Boot and 38,395 for Quarkus on JDK 25, of 38,461 and 38,383 on
  JDK 21.
- `Publish snapshots`, run
  [39646350771](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/actions/runs/39646350771):
  green. It skipped the deployment of `election-integration-test` ("Skipping artifact
  deployment"), and GitHub Packages knows no such artifact (its `maven-metadata.xml` answers 404).
  The snapshot it published, `2.0.0-20261021.174349-73`, holds the core's 266 classes as before
  (the story adds no main class) and no test class, fake, fixture or BPMN file. Both report pages
  (`spring-boot-report/index.html`, `quarkus-report/index.html`) answer HTTP 200 and show 0 of
  38,473 (Spring Boot) and 0 of 38,395 (Quarkus) instructions missed.

## S11.4.1 Native image

- [x] **Depends on:** E9.

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

*Fit check, 2026-10-21, decided by the Implementor where it says so.* Checked against `main` at
`de79b1d`, Camunda 8's `quarkus/native-image-tests`, its `Camunda8NativeImageProcessor` and the
`native-build` job of its `deploy-to-github-packages.yaml`, the platform's `ADAPTER-AUTHORS.md`
section 2.1 (the part descriptor), its `PartVersionsBuildStepProcessor` and `VanillaBpParts` (the
published snapshot in `~/.m2`), and Quarkus 3.40.0 (`quarkus-core-deployment`, `quarkus-netty`,
`quarkus-grpc-common`). The flow runs the same REST and gRPC calls as the JVM tests of E4 to E9, so
no claim about the engine at the pin was needed beyond what the native run shows against the pinned
image. Every native build below ran in a container of
`quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25` (Mandrel for JDK 25, the builder
Quarkus 3.40 picks), as no GraalVM is installed here. What did not fit, and what was decided:

1. **The wiki page "Native images"** (open question 1). Decided by the Implementor, as the brief
   recommends: no batch writes the wiki before S12.1.1, so the README gets a section "Native image"
   with what an application needs, what the extension registers and how the native build is run,
   each claim naming its test; S12.1.1 got a note that the wiki page takes its content from there.
2. **`grpc-netty-shaded` in a native image "as Quarkus documents it".** Quarkus documents no way:
   its xDS gRPC guide says that integration "doesn't support building native executables due to the
   issues with shaded grpc-netty library". The native build showed why. With the shaded transport,
   after the providers were registered, the image build first failed on
   `io.grpc.netty.shaded.io.netty.handler.ssl.JdkNpnApplicationProtocolNegotiator` (its
   initialization loads Jetty's NPN, which is not on the classpath; the shaded Netty's own
   `native-image.properties` initializes the whole shaded package at build time). Initialized at run
   time instead, it failed on the next one: the TLS paths of the shaded Netty name Conscrypt, and
   Quarkus answers that for the unshaded Netty with substitutions, which do not reach the shaded
   copy. The options: write those substitutions again for the shaded package names (a copy of a
   large part of Quarkus' Netty extension, following every Netty release by hand); let the image
   build accept an incomplete classpath (`NativeImageAllowIncompleteClasspathBuildItem`, deprecated
   in Quarkus 3.40, and it would apply to the whole application); or run the job stream on
   `grpc-netty` over Quarkus' Netty extension on Quarkus. The core builds its channel with
   `Grpc.newChannelBuilder`, which takes whatever transport the classpath offers, so the third
   needs no code change. Decided by the Implementor: the third, recorded as decision 61 in
   `DECISIONS.md`. `quarkus/runtime` excludes `grpc-netty-shaded` from the core and depends on
   `io.grpc:grpc-netty` and `io.quarkus:quarkus-netty`, and `quarkus/deployment` on
   `quarkus-netty-deployment`. Spring Boot keeps the shaded transport. The Quarkiverse Google Cloud
   extensions make the same exchange, and Camunda 8's extension depends on `quarkus-netty` for the
   same reason.
3. **The Netty version** (the note of S3.2.1 under step 1 of epic 3). The Spring Boot BOM, imported
   first, lifts Netty to 4.2.17.Final in every module; Quarkus 3.40.0 manages 4.1.138.Final, and its
   substitutions are written for that. The four modules under `quarkus/` import the Netty BOM in
   `netty.version.quarkus` (4.1.138.Final, root POM), as Camunda 8 does in its native module; in all
   four rather than in the native module only, because the extension now brings Netty into every
   Quarkus application, and its tests should run the Netty such an application runs. Renovate
   leaves `io.netty` alone. `QuarkusNettyTest` (in `quarkus/deployment`) fails when the classpath's
   Netty is not the pinned one, or the pin is not the Quarkus BOM's; `QuarkusNettyPinTest` (in the
   coverage gate) fails when a POM under `quarkus/` lacks the import, which a test on one module's
   classpath cannot see for the others.
4. **What is registered** (open question 4). Only what a native run proved necessary, each with
   the failure that named it, and each pinned by `ZenBpmNativeImageProcessorTest`:
   - the gRPC providers (`ManagedChannelProvider`, `NameResolverProvider`, `LoadBalancerProvider`,
     all providers of the classpath): without them the boot ended with "No functional channel
     service provider found", because Quarkus switches off GraalVM's own registration
     (`-H:-UseServiceLoaderFeature`);
   - run-time initialization of `io.grpc.netty.Utils$ByteBufAllocatorPreferDirectHolder` and
     `...PreferHeapHolder`: without it the image build failed with "An object of type
     'io.netty.buffer.PooledByteBufAllocator' was found in the image heap", reached through those
     two (checked again with the unshaded transport). The other five classes Camunda 8 initializes
     at run time (`io.grpc.netty.Utils`, `NettyChannelBuilder`, `RetriableStream`,
     `JavaFeaturesProto`, `UnsafeUtil`) were not needed: the image builds and runs without them;
   - the nested records of `ZenBpmWire`, the only types the REST client hands to Jackson, taken from
     the index: without them the binary waited for the engine until it gave up, because the answer
     of `GET /system/health/ready` "cannot be read";
   - the platform's version descriptor `META-INF/vanillabp/platform-version.properties`: without it
     the binary warned that the adapter was built against 2.0.0-SNAPSHOT "and unknown is on the
     classpath", so the check whether adapter and platform belong together could not judge.
     `ZenBpmNativeImageIT` asserts the warning is absent (the mutant without the registration
     failed it);
   - the classes generated from the proto, the registration S3.2.1 made: the flow runs without it
     (a native run with the step switched off was green). Decided by the Implementor to keep it,
     because protobuf's `toString()`, `equals` and `hashCode` of a message go through its descriptor
     and read the accessors by reflection; a log line or a collection holding a message would fail in
     the binary where the JVM never does. Its javadoc says so.
5. **The part descriptor needs no registration of its own** (the note of S3.2.1). Here
   `ADAPTER-AUTHORS.md` (section 2.1: "On Quarkus, register the file as a resource of your
   extension") and the plan disagree; the platform's code decides. `PartVersionsBuildStepProcessor`
   registers every `META-INF/vanillabp/*.properties` of the application's archives, and the core
   is one, because the extension indexes it (`theCoreIsIndexed`). The binary read the adapter's
   descriptor (it named the adapter's version and the platform version it was built against). The
   platform's own descriptor is in `vanillabp-integration-spi`, which is no application archive,
   so that one the extension registers (point 4).
6. **`ProtobufRuntimeCheck` and the `ResourceBundle`** (the note of S3.2.1). No bundle
   registration is needed: the binary built the channel, which runs the check, and logged "this
   adapter was tested with engine commit bdd795a3...", which `ZenBpmEnvironmentInfo` reads with
   `ResourceBundle.getBundle` from the same file. The platform's registration of the file as a
   resource is enough.
7. **Micrometer** (open question 3). Decided as the brief recommends: the native application has no
   Micrometer; the README says the meters are tested in the JVM only. No adapter code changed for
   native, so no decision.
8. **The module** follows Camunda 8: `src/main/java` holds the application (`NativeImageApplication`
   as `@QuarkusMain`, `NativeImageWorkflow`, a JPA aggregate on H2, so the platform's JDBC phase-two
   outbox serves it), `jacoco.skip`, `skipITs` true unless `-Dnative` turns the profile
   `native-image` on, a container build by default, the binary's log in
   `target/native-image-application.log`. Install and deploy are skipped like Camunda 8's: the second
   Maven call (`-pl quarkus/native-image-tests -Dnative verify`) builds the module itself and reads
   the rest from `~/.m2`. The engine comes from `EngineUnderTest` as a `@Container` field, so
   `EngineTestsWithoutDockerTest` holds the IT to `@Testcontainers(disabledWithoutDocker = true)`.
   Its aggregate is `@NoSyncWithBPMS`: the model reads none of its attributes, and the platform
   refuses an aggregate which shares every attribute.
9. **The coverage gate.** The module has production sources under `quarkus/`, so the gate's layout
   check asked for it in the Quarkus report; a native binary writes no execution data. It is a
   deliberate exception, and the list of exceptions became a map from artifact id to reason
   (`CoverageGateTest`), in this commit because the build needs it; S11.5.1 pins the list.
10. **Contradictions, GAPS.** One, settled from the code (point 5). No engine gap was met (GAPS 34
    stays free); decision 61 is new. The nightly job and any CI job for the native build are
    S11.6.1's; this story adds none.

**Acceptance evidence, 2026-10-21.**

- [x] The native build succeeds and the flow runs. The two Maven calls the README names, run on
  this machine after the clean build below (Docker 29.1, 14 cores, no GraalVM):
  `mvn -Dmaven.test.skip=true -pl quarkus/deployment,quarkus/native-image-tests -am install` (14 s)
  and `mvn -pl quarkus/native-image-tests -Dnative verify` (152 s). The image was built in a
  container of `quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25` in 2 min 9 s, peak RSS
  5.93 GB (between 5.75 and 5.99 GB over six builds, so `quarkus.native.native-image-xmx` was not
  needed); the binary is 108 MB. `ZenBpmNativeImageIT` passed (1 test) against the pinned image:
  the binary started in 0.077 s, deployed `Native.bpmn` as `zenbpm-native__Native`, subscribed both
  job types, started a workflow through the phase-two outbox, served `serve`, correlated `goAhead`,
  served `finish`, printed "VanillaBP ran the workflow of aggregate '...' on ZenBPM to its end
  (status 'finished')", exited with 0 and logged no warning. (This run predates the review fixes:
  since then the binary serves the end marker too, and the test asks the engine whether the
  workflow completed; see the answers to the two reviews under S11.5.1.) The first call with `-DskipTests`
  instead still ran the core's 104 integration tests (failsafe 3.6.0 on Maven 3.9.12), so the README
  names `-Dmaven.test.skip=true`. A build with a local GraalVM
  (`-Dquarkus.native.container-build=false`) was not run here, as none is installed; the nightly
  of S11.6.1 runs it.
- [x] What is needed is written down: the README's section "Native image" (the wiki page follows
  in S12.1.1, see the note there and fit-check point 1).
- Mutants, all killed: in `ZenBpmNativeImageProcessorTest` and `QuarkusNettyTest`, one gRPC
  provider fewer, one allocator holder fewer, the filter of the REST records on the wrong
  separator, the REST records without fields, the wrong descriptor, and the Netty BOM import
  removed from `quarkus/deployment` (6 of 6); in a native run, the platform descriptor's build step
  switched off (killed by `ZenBpmNativeImageIT`). The other registrations were each shown
  necessary by the native run which failed without them (fit-check point 4).
- Clean build (`TZ=Etc/UTC mvn clean install` on OpenJDK 25.0.2, Docker ITs included, none
  skipped, 17:37 min): engine test support 12 + 5, core 1,275 + 104, Spring Boot 14 + 95, smoke
  test 42 + 1, election integration test 0 + 8, Quarkus runtime 13, deployment 60 (54 before: four
  processor tests and two Netty tests are new), integration tests 25 (now on `grpc-netty`),
  native-image tests 0 (the IT runs only with `-Dnative`), gate 37; coverage 100 % on both
  platforms (0 of 38,473 instructions missed on Spring Boot, 0 of 38,470 on Quarkus, 75 more than
  before for the new build steps); `bin/check-deploy-safety.sh` green (13 files deployed by the
  green build, none of the new module, nothing by the red gate).

**S11.4.1 done 2026-10-22**, merged to `main` as `d686c32` together with S11.5.1 (pull request
#79); what the runs on `main` showed is written at the end of S11.5.1.

## S11.6.1 Nightly workflow: the pinned engine, the engine's latest image, the native image

- [x] **Depends on:** S11.4.1.

`.github/workflows/nightly.yaml`, `on: schedule` (once a night) and `workflow_dispatch`, three jobs:

1. `pinned-engine`: the full build with Docker ITs against `zenbpm.image`, the same as `checks.yaml`
   (so a flaky test shows up on a quiet night and not in somebody's pull request);
2. `latest-engine`: the same ITs with `-Dzenbpm.image=ghcr.io/pbinitiative/zenbpm:dev` (the newest
   `main` build; the property the container helper reads is overridable for exactly this),
   `continue-on-error: true`, and a summary line naming the engine commit the container reported; a
   red job here is the earliest warning that an engine change broke the adapter, and because the
   same organisation owns both, a person takes the finding to the engine's maintainers (the
   workflow itself opens no issue, see fit-check point 2);
3. `native-image`: the native build and `ZenBpmNativeImageIT` of S11.4.1 (GraalVM via
   `graalvm/setup-graalvm`), never on pull requests because it takes long.

The README's "What CI runs" section describes the three jobs; the nightly gets its own badge.

**Acceptance criteria**: the nightly runs green against the pinned engine; the `latest` job reports
the engine version it met; the native job runs the lifecycle flow.

*Fit check, 2026-10-22, decided by the Implementor where it says so.* Checked against `main` at
`02b0254`: `checks.yaml`, `publish-snapshots.yaml`, `EngineUnderTest` with its IT and
`MalformedPinsTest`, the coverage gate's `EnginePinTest` and `PublicationSafetyTest`, the module
`quarkus/native-image-tests` and the README's section "Native image"; the scheduled workflows of
`pbinitiative/zenbpm` on its `main` (read only); `graalvm/setup-graalvm` v1.6.7 (`src/mandrel.ts`)
and the releases of `graalvm/mandrel`; and the engine image `ghcr.io/pbinitiative/zenbpm:dev` as it
stood on 2026-10-22 (digest `a847afe3...`, revision `1ee1fd663e340017725b9867c9db41c476f8d94a` of
2026-10-19, eight commits after the pin; see the acceptance below for what it showed). The story
touches CI and test fixtures only, so neither `ADAPTER-AUTHORS.md` nor the platform's code was
concerned, and no claim about the engine's behaviour was needed beyond what the runs against both
images show. What did not fit, and what was decided:

1. **Pointing the build at the newest engine** (open question 1). `-Dzenbpm.image=...:dev`, as the
   story writes it, is refused by `EngineUnderTest` ("is not pinned by its digest"). Decided as the
   brief recommends: `bin/resolve-latest-engine.sh` pulls the tag, takes the image's digest of
   `ghcr.io/pbinitiative/zenbpm` and the full commit of its `org.opencontainers.image.revision`
   label, starts the image once and reads `git.commitId` of `/system/status`, and fails unless that
   is the start of the label's commit; it writes `image` and `commit` to the step's outputs and one
   line to the job's summary naming the tag, the digest, the commit, the commit the engine reported
   and whether it is the pin (and, since the review fixes, the version the engine reported). The
   build then runs with `-Dzenbpm.image` and `-Dzenbpm.commit` set to them. A script in `bin/`
   rather than lines in the workflow, so it runs by hand the same way. actionlint does not read a
   script a workflow calls; shellcheck passed it when run by hand, and since the review fixes the
   job `workflow-lint` of `checks.yaml` runs shellcheck over every script of `bin/`. Every test
   which reads the engine follows the two properties: `EngineUnderTest` reads its filtered
   resource, failsafe hands `zenbpm.image` to `EngineUnderTestIT`, and the core filters
   `zenbpm.commit` into the adapter's descriptor, which `ZenBpmEnvironmentInfo` and the tests of
   `pinnedCommit()` read. Two things did not follow:
   - **The coverage gate's `EnginePinTest`** compares the README's table and the headers of the two
     copied engine files with the pin, which in this run is on purpose another engine. The job
     leaves out the whole gate (`--projects '!test-coverage-report/coverage-gate'`): its other
     checks read the repository's files, not the engine, and coverage is the pinned build's
     business, as the brief recommends.
   - **Eight test files wrote the pinned commit `bdd795a399b6` into a stand-in engine's
     `/system/status`.** The first local run against `:dev` failed four tests of
     `ZenBpmDeployingTest`: the adapter's warning "The engine reports commit bdd795a399b6 (version
     1.2.0), but this adapter was tested with engine commit 1ee1fd66..." entered the findings these
     tests count. Moving the pin would fail them the same way. Decided by the Implementor, a
     minimal change of test code only: a stand-in reports the build's pin, through the new
     `FakeEngine#pinnedCommitAsReported` (`zenbpm.commit` shortened to twelve characters, as the
     engine reports it) in five core tests, and through the adapter's own descriptor in the
     `StandInEngine`s of `smoke-test` and `quarkus/deployment`, which do not depend on
     `engine-test-support`; `ZenBpmRestClientTest`'s two tests which only parse a status take the
     made-up commit `0123456789ab`, as `ZenBpmHealthTest` does. No production code changed. Since
     this shapes every later stand-in, it is decision 62 in `DECISIONS.md`, together with the
     override itself. The pin was not moved.
2. **A red `latest-engine`** (open question 2). The story's sentence that "the finding goes
   straight into the engine's issue tracker" contradicts the pipeline's policy, so it is reworded
   above: the job writes its line into the run's summary and nothing else, a person takes the
   finding to the engine's maintainers. Recorded in decision 62 and the README; the workflow's
   permissions are all `read` (`NightlyWorkflowTest#theNightlyWritesNothing`).
3. **Proving the nightly before the merge** (open question 3). Taken as the brief decided: the
   workflow also runs on a `pull_request` whose changes include `.github/workflows/nightly.yaml`
   or, since the review fixes, `bin/resolve-latest-engine.sh` (a `paths` filter), so the feature
   pull request runs all three jobs, and so does any later change of the nightly; a pull request
   which does not touch them never starts it. The README's section
   "What CI runs" says so, `NightlyWorkflowTest#theNightlyRunsWhenTheReadmeSays` holds the triggers
   to exactly `schedule`, `workflow_dispatch` and that `pull_request`, and actionlint 1.7.12 accepts
   them. A pull request from a fork needs nothing but the read-only `GITHUB_TOKEN`, with which the
   jobs log in to ghcr.io as `checks.yaml` does (the test refuses any other secret). The
   `concurrency` group is `nightly-<ref>`, cancelled on a pull request only, as in `checks.yaml`.
   The nightly's jobs are not added to the required checks (no settings change).
4. **The schedule** (open question 4). The engine's scheduled workflows run on Mondays at 04:00
   (`codeql-analysis.yml`), 05:00 (`go-static-analysis.yml`) and 06:00 UTC (`gosec.yml`), and every
   day at 06:00 UTC (`trivy-scan.yml`); its image `:dev` is built on every push to its `main`.
   Taken: `37 2 * * *`, every night at 02:37 UTC, before those and off the full hour. The test
   holds the schedule to one fixed daily time off the full hour and to the time the README names.
5. **The badge and what holds it** (open question 5). The badge `Nightly` of `nightly.yaml` on
   `main` stands in the line after `Checks`. No test read a workflow before:
   `PublicationSafetyTest` reads the README's coverage badges and the POMs only. So
   `NightlyWorkflowTest` in the coverage gate (8 tests, reading the workflow with SnakeYAML, which
   the Spring Boot BOM manages, in test scope) pins the triggers and the time, the jobs (the three
   of the story kept, and the same set as the README's list), no `needs` between them,
   `continue-on-error` on `latest-engine` alone, permissions which only read and no secret but
   `GITHUB_TOKEN`, a summary line written with `if: always()` by every job, the build of
   `latest-engine` with the resolved image and commit and without the gate, Mandrel for Java 25 with
   `-Dquarkus.native.container-build=false`, and the badge.
6. **The GraalVM of the native job** (the note of S11.4.1). Decided by the Implementor: the job
   installs Mandrel for Java 25 with `graalvm/setup-graalvm` (`distribution: mandrel`,
   `java-version: "25"`) and builds with `-Dquarkus.native.container-build=false`. Mandrel is the
   GraalVM distribution Quarkus builds with, and the newest Mandrel for Java 25 (25.0.4.1-Final
   today) is the same one the builder image `ubi9-quarkus-mandrel-builder-image:jdk-25` carries
   (its `native-image --version` reads 25.0.4.1), so the job builds what a laptop builds, by the
   path the README names but no run had taken. No release is pinned: the job follows the line the
   builder image's moving tag follows, and its summary line names the `native-image` version it
   used. The container build stays the README's default for a laptop. Mandrel's JDK runs the whole
   job, both Maven calls included.
7. **A fourth job** (the policy for S12.2.1). The jobs do not depend on one another, the README
   lists them by name without counting them, and `NightlyWorkflowTest` asks every job for a README
   description and a summary line; S12.2.1 got a note saying what its job has to bring.
8. **`pinned-engine` runs on one JDK**, 25.0.4 of the required check `build`; the second run on
   Java 21 stays the business of `checks.yaml`, and the engine copies are checked there too. The
   reports of a failed build are attached as `pinned-engine-reports`, `latest-engine-reports` and
   `native-image-reports` (the last with `native-image-application.log`).
9. **The documents.** The README's section "Contributing", which held only what `checks.yaml`
   runs, became "What CI runs" with that paragraph unchanged and the snapshot publication and the
   nightly added; the section "Native image" names the nightly's build, and step 1 of moving the pin
   names the script. `docs/README.md` (point 4 of its summary) and the module tree of
   `docs/architecture/01-architecture.md` said "the engine's `latest` image", which does not exist
   for `main`: both name `:dev` now. `epic-13-engine-enablement.md` still holds: the job is
   `latest-engine`.
10. **GAPS.** None (point 1 of the acceptance below says what the run against `:dev` showed); GAPS
    34 stays free, decision 63 is the next free number.

**Acceptance evidence, 2026-10-22.** Everything here ran on this machine (OpenJDK 25.0.2, Maven
3.9.12, Docker 29.1, 14 cores); a scheduled or dispatched run needs the file on `main`. What the
three jobs show on GitHub is seen on the feature pull request, which the workflow's `paths` filter
makes run them all (fit-check point 3); what they showed is written at the end of this story.

- [x] The nightly runs green against the pinned engine. Locally: the job's build is the clean build
  below, green. On GitHub: green on the pull request, see the end of this story.
- [x] The `latest` job reports the engine version it met. Locally:
  `bin/resolve-latest-engine.sh ghcr.io/pbinitiative/zenbpm:dev` resolved the tag in 8 s to
  `ghcr.io/pbinitiative/zenbpm@sha256:a847afe3f96b9faf4807a1907154c7ecb00c75f1469c8cc7ad890b7052190392`,
  built from `1ee1fd663e340017725b9867c9db41c476f8d94a`, which the started engine reported as
  `1ee1fd663e34`, and wrote "...; not the pinned commit bdd795a399b6484d062d52ac90c0d0725cb0ad91".
  The job's build with both values and without the gate (`TZ=Etc/UTC mvn install
  -Dzenbpm.image=... -Dzenbpm.commit=... --projects '!test-coverage-report/coverage-gate'`) was red
  at the first attempt, in four tests of `ZenBpmDeployingTest` (fit-check point 1), and green after
  the fix of the stand-ins, in 17:48 min: engine test support 12 + 5 (so `EngineUnderTestIT` saw
  the container run the `:dev` digest and the engine report its commit), core 1,275 + 104, Spring
  Boot 14 + 95, smoke test 42 + 1, election integration test 8, Quarkus runtime 13, deployment 60,
  integration tests 25. So the adapter at the pin runs against the engine's `main` of 2026-10-19,
  eight commits later; its newest commit makes the engine refuse a multi-instance
  `completionCondition`, which the adapter refuses already (`ZenBpmUnsupportedConstructs`), and its
  `api.yaml` differs from the copy by one line. No engine gap was met (open question 6), so no GAPS
  row. On GitHub: green on the pull request, see the end of this story.
- [x] The native job runs the lifecycle flow. Locally, as the job runs it: Mandrel
  25.0.4.1-Final for Java 25 (the release `graalvm/setup-graalvm` takes for `java-version: "25"`,
  downloaded from `graalvm/mandrel`) as `JAVA_HOME` of both calls, `mvn -Dmaven.test.skip=true
  --projects quarkus/deployment,quarkus/native-image-tests --also-make install` (14 s), then
  `mvn --projects quarkus/native-image-tests -Dnative -Dquarkus.native.container-build=false verify`
  (2:22 min): "Running Quarkus native-image plugin on MANDREL 25.0.4.1 JDK 25.0.4.1+1-LTS", the
  image built on the machine with gcc 13.3 in 2 min 1 s, peak RSS 6.19 GB, and `ZenBpmNativeImageIT`
  passed (1 test) against the pinned engine: the binary served both tasks and the end marker,
  stopped after draining, and the engine showed the instance completed. This was the first build
  with a local GraalVM (the note of S11.4.1). On GitHub: green on the pull request, see the end of
  this story.
- [x] Every job writes a summary line, and the README describes the jobs in "What CI runs" with
  the nightly's badge next to `Checks`: `NightlyWorkflowTest` (8 tests, 9 since the review fixes)
  holds both. actionlint 1.7.12 passes the workflows; shellcheck, which its image carries, run by
  hand, passes the script (actionlint itself does not read it; see the answers to the reviews).
- Mutants, all killed: 17 changes of the workflow or the README, each failing `NightlyWorkflowTest`:
  `continue-on-error` removed from `latest-engine`, and added to `pinned-engine`; a `needs` between
  jobs; `packages: write`; the `paths` filter removed; the schedule on the full hour, and at another
  time than the README's; the summary step of `native-image` on `failure()` only; a job renamed; the
  gate kept in `latest-engine`'s build; the container build back; GraalVM CE instead of Mandrel; the
  badge removed; a secret other than `GITHUB_TOKEN`; the image taken from the wrong output; the
  workflow's own `permissions` removed; a job's README bullet dropped. The stand-ins' commit has no
  guard to mutate: the red run against `:dev` before the fix and the green one after are its proof.
- Clean build (`TZ=Etc/UTC mvn clean install`, Docker ITs included, none skipped, 17:43 min): engine
  test support 12 + 5, core 1,275 + 104, Spring Boot 14 + 95, smoke test 42 + 1, election
  integration test 8, Quarkus runtime 13, deployment 60, integration tests 25, native-image tests 0
  (the IT runs only with `-Dnative`), gate 49 (41 before: `NightlyWorkflowTest` is new); coverage
  100 % on both platforms (0 of 38,473 instructions missed on Spring Boot, 0 of 38,470 on Quarkus,
  unchanged: the story adds no production code).
- `bin/check-deploy-safety.sh` (the gate module changed): the first run was red in its green build,
  in an integration test of the core; the script removes its reports on exit, so which one is not
  known (the same happened once in the batch before; the core's 104 ITs had passed in both builds
  above and the batch changes none of them). The second run, with the failsafe reports copied out
  while it ran, was green: 13 files deployed by the green build, nothing by the red gate.

**E11 "Done when"**, checked at the end of the epic:

- *Meters and health are documented and tested*: met. The README's sections "Metrics" and "Health"
  name their tests; S11.1.1's acceptance lists them (`ZenBpmMetricsIT`, `ZenBpmMetricsBootTest`,
  `ZenBpmWithoutMicrometerTest`,
  `ZenBpmWorkflowLifecycleTest#theMetersOfTheAdapterIdMoveWithTheWork`, the health tests of E3),
  all green in the clean build above.
- *The election ITs are green*: met. `election-integration-test`, 8 ITs of S11.3.1, green in the
  clean build above and against `:dev`.
- *The native image runs the lifecycle flow*: met locally. S11.4.1's `ZenBpmNativeImageIT` with the
  builder container, and in this story with a local Mandrel as the nightly's `native-image` job runs
  it, and on GitHub in the job's first run, on the pull request (see the end of this story).
- *Both coverage reports read above 90*: met. 100 % on both platforms in the clean build above, and
  both badges read 100 % since S11.5.1.

**Answers to two reviews of e11-s11.6.1, 2026-10-22** (one by Claude, approve with minor fixes:
3 Minor, 5 Nit; one by Copilot, approve: 1 Minor). Every claim was checked in the workflow, the
script, the test and the engine's code at the pin, by the reviewers' probes and by probes of my
own. All nine findings were valid, one of them in its effect though not in its mechanism; all are
fixed. No decision and no GAPS row was needed.

- **A step could let a job that has to fail the run pass** (Claude 1, Minor). The test read
  `continue-on-error` at job level only, and only `true`, so a step's `continue-on-error`, an
  `|| true` after the build, or `continue-on-error: ${{ true }}` on the job turned a red
  `pinned-engine` or `native-image` green unnoticed. Now
  `NightlyWorkflowTest#noStepOfAJobWhichHasToFailLetsAFailurePass` refuses, in every job but
  `latest-engine`, a step with `continue-on-error` and a step without an `if` whose command
  contains `||`. A `continue-on-error` which is anything but `false` counts, at job level too.
- **The native job's run of the binary was not held** (Copilot 1, Minor). `verify` changed to
  `package` kept every test green while `ZenBpmNativeImageIT` would never run.
  `#theNativeImageIsBuiltOnTheRunner` now requires the one step with `-Dnative` to be a `mvn` call
  of `--projects quarkus/native-image-tests` ending in `verify`, with no switch which skips tests.
- **The resolve script was neither run nor linted by a pull request** (Claude 2, Minor). The
  `paths` filter named the workflow only, and actionlint does not read a script a workflow calls,
  which fit-check point 1 claimed it did. The script is in the filter now, and the job
  `workflow-lint` of `checks.yaml` runs the shellcheck of the actionlint image over every script
  of `bin/` (all four pass). Fit-check points 1 and 3, the acceptance line and the README say so.
- **A summary step could lose its line** (Claude 3, Minor). The review said GitHub runs a step with
  `pipefail`; it does so only with `shell: bash`, and these steps name no shell, so they run as
  `bash -e`. Probed locally without `native-image` and without `pom.xml`: the native step wrote its
  line with an empty builder, and the pinned step stopped at its `sed` (exit 2) before its line.
  Both now fall back (`... || true`, "no native-image on the runner", commit "unread") and write
  their line in either shell.
- **Nits** (Claude 4 to 8). The README said each job writes one line; `latest-engine` writes its
  script's line too, and the README says so now. The script's line names the version the engine
  reports as well (`.build.version` of `/system/status`; `v1.9.0` for `:dev` today), which the
  acceptance asks for; the commit still tells two builds apart. The roadmap's M6 row and row 57
  and step 3 of S1.3.1 name `:dev` and "What CI runs"; `docs/open-questions.md` keeps "Contributing"
  as the record of a question answered before. Five lines of this section and one of
  `docs/README.md` are wrapped; the image digest of the acceptance stays one line. The README's
  sentence that no workflow needs a secret and only the publication writes is held by the new
  `WorkflowPermissionsTest` (every workflow: no secret but `GITHUB_TOKEN`, permissions named at the
  top, a permission which writes in `publish-snapshots.yaml` only).
- Mutants, all killed (10 of 10): the script dropped from `paths`; a step's `continue-on-error` on
  the pinned build and on the native build; `|| true` after the pinned build;
  `continue-on-error: ${{ true }}` on `pinned-engine`; `verify` to `package` and `-DskipITs` in the
  native build; another secret, `packages: write` and no top-level permissions in `checks.yaml`.
  Unmutated, `NightlyWorkflowTest` 9 and `WorkflowPermissionsTest` 2 pass.

Builds after the fixes (OpenJDK 25.0.2, Docker ITs, none skipped):

- `TZ=Etc/UTC mvn clean install`, 17:42 min, green: engine test support 12 + 5, core 1,275 + 104,
  Spring Boot 14 + 95, smoke test 42 + 1, election integration test 8, Quarkus runtime 13,
  deployment 60, integration tests 25, native-image tests 0 (the IT runs only with `-Dnative`),
  gate 52 (49 before: one test of `NightlyWorkflowTest` and the two of `WorkflowPermissionsTest`
  are new); coverage 100 % on both platforms (0 of 38,473 instructions missed on Spring Boot, 0 of
  38,470 on Quarkus).
- `bin/check-deploy-safety.sh`, green at the first run: 13 files deployed by the green build,
  nothing by the red gate.
- actionlint 1.7.12 and the new shellcheck step, run as `checks.yaml` runs them: both exit 0.

**S11.6.1 done 2026-10-22**, merged to `main` as `96c2a7a` (pull request #81: story `4bbc51f`,
review fixes `28b1563`; CodeRabbit had no comment). With it E11 is complete. GitHub keeps run logs
for 90 days, so what the runs showed is written here as well:

- `Nightly` on the pull request, run
  [39717515078](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/actions/runs/39717515078)
  (event `pull_request`, started by the `paths` filter, head `28b1563`): all three jobs green at the
  first attempt, the first run of each on a GitHub runner.
  - `pinned-engine` (21:31 min of Maven): engine test support 12 + 5, core 1,275 + 104, Spring
    Boot 14 + 95, smoke test 42 + 1, election integration test 8, Quarkus runtime 13, deployment
    60, integration tests 25, gate 52; 100 % on both platforms (0 of 38,473 and 0 of 38,470
    instructions missed).
  - `latest-engine` (20:03 min of Maven): `bin/resolve-latest-engine.sh` resolved `:dev` to
    `sha256:a847afe3...`, built from `1ee1fd663e340017725b9867c9db41c476f8d94a`, which the engine
    reported as `1ee1fd663e34` with version `v1.9.0`, "not the pinned commit"; the build with both
    values ran the same counts as `pinned-engine` without the gate, green.
  - `native-image` (4 min 38 s in all): `graalvm/setup-graalvm` installed Mandrel
    25.0.4.1-Final for Java 25; "Running Quarkus native-image plugin on MANDREL 25.0.4.1 JDK
    25.0.4.1+1-LTS" on the runner, without a container; the image was generated in 2 min 24 s at a
    peak RSS of 5.10 GB on the 4-core runner, linked without an extra package, and
    `ZenBpmNativeImageIT` passed (1 test).
- `Checks` on `96c2a7a`, run
  [39719732462](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/actions/runs/39719732462):
  green on both JDKs at the first attempt. `bin/check-engine-copies.sh` reported both copies as the
  engine's files of `bdd795a3...`. On both JDKs every module ran its tests, none skipped, with the
  counts of the clean build above; the gate ran 52 tests, among them `NightlyWorkflowTest` 9 and
  `WorkflowPermissionsTest` 2; `quarkus/native-image-tests` skipped its IT by design. The gate
  measured 100 %: 0 of 38,473 (Spring Boot) and 0 of 38,470 (Quarkus) instructions missed on JDK 25,
  0 of 38,461 and 0 of 38,458 on JDK 21. `workflow-lint` ran actionlint and the new step "Lint the
  scripts" (shellcheck over `bin/*.sh`), both green; its log shows the steps run as
  `/usr/bin/bash -e {0}`, the shell the answer to Claude's third finding names.
- `Publish snapshots` on `96c2a7a`, run
  [39719732512](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/actions/runs/39719732512):
  green. The snapshot it published, `2.0.0-20261022.035822-77`, holds the core's 266 classes as
  before (the story adds no production class) and no test class, fake, fixture or BPMN file.
- Both report pages (`spring-boot-report/index.html`, `quarkus-report/index.html`) answer HTTP 200
  and show 0 of 38,473 (Spring Boot) and 0 of 38,470 (Quarkus) instructions missed.

## S11.5.1 Coverage at the rule

- [x] **Depends on:** S11.4.1.

Compare `jacoco.csv` of both reports class by class; every core class high on Spring and low on
Quarkus names a feature the Quarkus twin never runs - add the twin, never the execution data. Fill the
remaining edges with unit tests. Both reports above `coverage.rule` (90); the gate stays at 85 and
is never edited to pass.

**Acceptance criteria**: both badges read above 90; the gate's exception list names only
`engine-test-support` and the native module with a reason.

*Fit check, 2026-10-21, decided by the Implementor where it says so.* Checked against the branch
after S11.4.1 (`2192e43`): the two reports of its clean build, `CoverageGateTest`,
`ProductionModules` and `ProductionModulesTest`. What did not fit, and what was decided:

1. **Nothing to fill on the gate's metric.** The gate and the badges read instructions, and both
   reports stood at 100 % before this story: Spring Boot 0 of 38,473 instructions missed in 214
   classes (core 197, `spring-boot` 13, `smoke-test` 4), Quarkus 0 of 38,470 in 206 classes (core
   197, `quarkus` 7, `quarkus-deployment` 2). The class-by-class comparison of the two
   `jacoco.csv` covers the 197 core classes both reports read: the same classes, and in every one
   the same instructions, lines and methods, all covered. So no core class is high on Spring Boot
   and low on Quarkus, and no Quarkus twin is missing.
2. **One branch differed.** By branches, the only metric where the core differed, 196 classes
   are equal and one is not: `ZenBpmUnsupportedConstructs.Findings`, 3 of 160 branches missed on
   Spring Boot and 4 on Quarkus. The branch is in `Findings#checkSubProcess`: a non-interrupting
   start event of an event sub-process which is no error start; only a model of the Spring Boot ITs
   held one. It is
   platform-neutral model checking, so decided by the Implementor: a core unit test rather than a
   Quarkus twin. `ZenBpmUnsupportedConstructsTest#whatZenBpmRunsPasses` now holds a
   non-interrupting message start event of an event sub-process; the core's tests count in both
   reports. The mutant which refuses every non-interrupting start event (`&&` to `||`) was killed.
3. **The remaining missed branches** (30 of the core's 2,609 on both platforms, in 16 classes, among
   them `ZenBpmMarkerTasks` 6, `ZenBpmModelReader.ProcessReading` 3, `ZenBpmJobDispatcher` 3,
   `ZenBpmExecutor` 3) are left as they are, decided by the Implementor: branches are not the
   metric of the rule or the gate, every instruction behind them is covered, and they are the same
   on both platforms, so they name no missing twin.
4. **The exception list.** S11.4.1 turned it into a map from artifact id to reason in
   `CoverageGateTest`, and it names `engine-test-support` and `quarkus/native-image-tests`. This
   story pins it: `CoverageGateTest#everyExceptionNamesAModuleWithProductionSources` fails on an
   exception which names no module of the repository with production sources (one left behind by a
   renamed module would excuse the next one taking its name) or has a blank reason, through the new
   `ProductionModules#artifactIdsWithProductionSources`. `ProductionModulesTest` gets the native
   module's case (a module under `quarkus/` no report reads is named "not read by the Quarkus
   report" until it is an exception) and a case for the new method. `ProductionModules` itself holds
   no list. The README's section "Test coverage" names both exceptions with their reasons.
5. **The gate stays at 85 and `coverage.rule` at 90**; neither property is touched. The badges are
   read after the merge, in the evidence step.

**Acceptance evidence, 2026-10-21.**

- [x] Both reports above the rule (the badges are read after the merge): Spring Boot 100 % (0 of
  38,473 instructions missed), Quarkus 100 % (0 of 38,470). The core's branches are now the same on
  both platforms, 30 of 2,609 missed each (`Findings` 3 on both).
- [x] The gate's exception list names only `engine-test-support` and `quarkus/native-image-tests`,
  each with its reason, and `CoverageGateTest#everyExceptionNamesAModuleWithProductionSources`
  holds it to modules with production sources and a reason.
- Mutants, all killed: the check of a non-interrupting start event with `||` for `&&`
  (`whatZenBpmRunsPasses`); a stale exception and a blank reason
  (`everyExceptionNamesAModuleWithProductionSources`); the native module dropped from the exceptions
  (`everyProductionModuleIsReadByTheReportOfItsPlatform`). 4 of 4.
- Clean build (`TZ=Etc/UTC mvn clean install` on OpenJDK 25.0.2, Docker ITs included, none
  skipped, 17:45 min): engine test support 12 + 5, core 1,275 + 104, Spring Boot 14 + 95, smoke
  test 42 + 1, election integration test 0 + 8, Quarkus runtime 13, deployment 60, integration
  tests 25, native-image tests 0 (the IT runs only with `-Dnative`), gate 40 (three new tests);
  coverage as above; `bin/check-deploy-safety.sh` green (13 files deployed by the green build, nothing by the red
  gate).

**Answers to two reviews of e11-s11.4.1-s11.5.1, 2026-10-22** (one by Claude, approve with minor
fixes: 3 Minor, 1 Nit; one by Copilot, approve: 1 Minor). Every claim was checked in the code of the
branch, by the reviewers' probes and by a test of my own. Claude's second finding and Copilot's
only one are the same gap, so four distinct findings remain; all four were valid and are fixed. No
decision and no GAPS row was needed.

- **The native run proved the handlers, not the end of the workflow** (Claude 2, Copilot 1,
  Minor). The binary exited with 0 once its aggregate read `finished`, a status the handler of the
  last task writes before the adapter completes that task's job, and the test checked only the exit
  code and the printed line. Copilot showed the false success with a proxy which refused the
  completion of `Finish`: exit 0, the line printed, and the instance still `active` at the engine.
  The workflow also had no `@WorkflowEnded` method, so the end of a workflow (decision 26) never ran
  in the binary. Now `NativeImageWorkflow#ended` writes the status `ended` through the end marker,
  the main waits for it, and `ZenBpmNativeImageIT` reads the aggregate id from the line the binary
  prints and asks the engine for the instance of that business key: the run counts only when it
  reads `completed`. Native mutant: the end handler commits `ended` in a transaction of its own and
  then throws, so the binary exits with 0 and prints its line while the job is never completed;
  killed (the engine showed the instance `active` with one incident). The README sentence says what
  the test holds.
- **The Netty pin was held in one module only** (Claude 3, Minor). `QuarkusNettyTest` reads the
  Netty of the classpath of `quarkus/deployment`, so the import of the Netty BOM could go from the
  other three modules under `quarkus/` unnoticed, and in the native module a wrong Netty shows only
  in a native build. `QuarkusNettyPinTest` in the coverage gate reads every POM under `quarkus/` and
  fails on one whose dependency management does not import `io.netty:netty-bom` in
  `${netty.version.quarkus}`. Mutant: the import removed from `quarkus/native-image-tests`; killed,
  the message names the POM. README and fit-check point 3 name the test.
- **The gate's new message had two runs of 17 spaces in a sentence** (Claude 1, Minor). The text
  block of `CoverageGateTest#everyExceptionNamesAModuleWithProductionSources` was one source line;
  it is split into lines continued with `\`, as the other messages of the gate are.
- **The S11.5.1 fit check named line 539** (Claude 4, Nit); the condition is on lines 537-538. The
  note names the method `Findings#checkSubProcess` now, which does not drift.
- **Not findings of this batch:** each reviewer's first full build was red once in a module the
  batch does not touch, while both reviewer builds shared the machine: Claude's in
  `ZenBpmInboundIdempotencyIT#longHandlersKeepTheirLock` (a lock of `PT2S` lapsed under the load),
  Copilot's in `ZenBpmJobStreamTest#aConnectionNobodyAnswersIsNoReturn` ("Address already in use",
  a known flake). Both passed on their reruns and in every build below.

Builds after the fixes (OpenJDK 25.0.2, Docker ITs, none skipped):

- `TZ=Etc/UTC mvn clean install`, 17:39 min, green: engine test support 12 + 5, core 1,275 + 104,
  Spring Boot 14 + 95, smoke test 42 + 1, election integration test 8, Quarkus runtime 13,
  deployment 60, integration tests 25, native-image tests 0 (the IT runs only with `-Dnative`),
  gate 41 (40 before: `QuarkusNettyPinTest` is new); coverage 100 % on both platforms (0 of 38,473
  instructions missed on Spring Boot, 0 of 38,470 on Quarkus).
- `mvn -pl quarkus/native-image-tests -Dnative verify`, green: the image was built in the
  container of `quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25` in 1 min 47 s, peak RSS
  5.92 GB; `ZenBpmNativeImageIT` passed (1 test): the binary served both tasks and the end marker,
  printed "... to its end (status 'ended')", exited with 0, and the engine showed the instance
  `completed`.
- `bin/check-deploy-safety.sh`: the first run was red in its green build, in an integration test
  of `spring-boot` (the script removes its logs, and the reports with them, so which one is not
  known; the module is untouched by the batch, and its 95 ITs had just passed in the build above);
  the second run was green: 13 files deployed by the green build, none of the new module, nothing
  by the red gate.

**S11.4.1 and S11.5.1 done 2026-10-22**, merged to `main` as `d686c32` (pull request #79: stories
`2192e43` and `6d4a0d1`, review fixes `2d373f5`; CodeRabbit's one comment was answered as not
valid and withdrawn). On `d686c32` (GitHub keeps run logs for 90 days, so what they showed is
written here as well):

- `Checks`, run
  [39695172319](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/actions/runs/39695172319):
  green on both JDKs at the first attempt. `bin/check-engine-copies.sh` (in the JDK 25 job)
  reported both copies as the engine's files of `bdd795a3...`. On both JDKs every module ran its
  tests, none skipped: engine test support 12 unit and 5 integration tests, core 1,275 unit and
  104 integration tests (`ZenBpmUnsupportedConstructsTest` 53), Spring Boot 14 + 95, smoke test
  42 + 1, election integration test 8, Quarkus runtime 13, Quarkus deployment 60
  (`ZenBpmNativeImageProcessorTest` 6, `QuarkusNettyTest` 2), Quarkus integration tests 25, now on
  `grpc-netty`, and coverage gate 41 (`CoverageGateTest` 5, `ProductionModulesTest` 9,
  `QuarkusNettyPinTest` 1). `quarkus/native-image-tests` compiled, built its application in JVM
  mode and skipped `ZenBpmNativeImageIT` by design ("Tests are skipped" without `-Dnative`); the
  native build runs in the nightly of S11.6.1. The gate measured 100 % on both platforms: of
  38,473 instructions for Spring Boot and 38,470 for Quarkus on JDK 25, of 38,461 and 38,458 on
  JDK 21.
- `Publish snapshots`, run
  [39695172436](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/actions/runs/39695172436):
  green. It skipped the deployment of `quarkus/native-image-tests` ("Skipping artifact
  deployment"), and GitHub Packages knows no such artifact (its `maven-metadata.xml` answers 404).
  The snapshot it published, `2.0.0-20261021.234300-75`, holds the core's 266 classes, the Quarkus
  runtime's 13 and the deployment's 4, among them `ZenBpmNativeImageProcessor` with the new
  registrations (the gRPC providers, the two allocator holders of `io.grpc.netty.Utils`, the
  records of `ZenBpmWire`, the platform descriptor), and no test class, fake, fixture or BPMN
  file. The published POM of the Quarkus runtime excludes `grpc-netty-shaded` and depends on
  `grpc-netty` 1.81.0 and `quarkus-netty` 3.40.0 (decision 61).
- Both report pages (`spring-boot-report/index.html`, `quarkus-report/index.html`) answer HTTP 200
  and show 0 of 38,473 (Spring Boot) and 0 of 38,470 (Quarkus) instructions missed; both badges of
  the README read 100 %, above the rule of 90.

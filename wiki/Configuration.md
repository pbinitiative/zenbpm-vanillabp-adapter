Two things have to fit for this adapter: its properties and your BPMN model. An application needs
two keys to run, `rest-address` and `name-clash-avoidance` (see [Home](Home#minimal-configuration));
everything else has a default which works, so read on when you have a reason.

1. [Connecting to the engine](#connecting-to-the-engine)<br>
   The addresses of the REST API and the job stream, and what the adapter sends over them.
2. [Every key](#every-key)<br>
   The keys of this adapter with the levels they may be set at and their defaults, and the keys of
   VanillaBP which change what the adapter does.
3. [A key the adapter does not know](#a-key-the-adapter-does-not-know)<br>
   What a misspelt key does on Spring Boot, and what it does on Quarkus.
4. [Several adapter ids](#several-adapter-ids)<br>
   Two ids of this type in one application, and when two of them may name one engine.
5. [The BPMN model for ZenBPM](#the-bpmn-model-for-zenbpm)<br>
   What your model has to carry so the engine runs it, and what the adapter writes into it.
6. [What reaches the engine](#what-reaches-the-engine)<br>
   The shared values as process variables, the business key, and what the engine makes of a type.
7. [Keeping workflow modules apart](#keeping-workflow-modules-apart)<br>
   What `use-prefix` and `none` deploy, and why `by-adapter` is refused.
8. [Timeouts and worker settings](#timeouts-and-worker-settings)<br>
   The handler slots, the lock of a job, retries and backoff, and the shutdown.
9. [Starting a workflow by a message](#starting-a-workflow-by-a-message)<br>
   How a workflow a message started is found again, and the three values of `message-start-lookup`.
10. [Boot behavior](#boot-behavior)<br>
    What the start checks, what ends it, and what an unconfigured adapter id does.
11. [What an operator gets to see](#what-an-operator-gets-to-see)<br>
    The health check, the meters, and the lines of the log.
12. [The versions the engine still holds](#the-versions-the-engine-still-holds)<br>
    What the start asks about older versions of a process, and how to retire one.

## Connecting to the engine

Each adapter id reads its connection from its own section, `vanillabp.adapters.<id>.*`. The engine
offers two ports, and the adapter uses both:

- **REST**, `rest-address`, e.g. `http://zenbpm:8080`. The adapter appends the API version `/v1`
  itself, so an address which ends with `/v1` gets a notice at startup
  (`ZenBpmAdapterConfigurationTest#theApiVersionIsAppendedOnce`,
  `ZenBpmStartupValidationBootTest#aNoticeStandsOnceInTheBlock`). Every deployment, start,
  correlation, completion and question goes over REST, see [Engine APIs](Engine-APIs).
- **gRPC**, `grpc-address`, `host:port` of the job stream, over which the engine hands out the
  jobs of service tasks. Without the key it is the host of `rest-address` with the engine's
  default port 9090 (`ZenBpmAdapterConfigurationTest#theGrpcAddressIsDerivedFromTheRestHost`). The
  stream goes without TLS unless `grpc-plaintext: false` says otherwise; an `https` REST address
  beside a stream without TLS gets a notice
  (`ZenBpmAdapterConfigurationTest#aTlsRestAddressBesideAPlaintextJobStreamIsNoticed`).

The engine has no authentication and no TLS of its own, so the adapter has no keys for credentials
and sends none ([GAPS 12](Deviations#gaps-12-no-authentication-and-no-tls)). Put a proxy in front
of the engine where it needs either, and use an `https` address and `grpc-plaintext: false` to
reach it. An address which carries a user is refused, because it would put a password into the log
(`ZenBpmAdapterConfigurationTest#valuesWhichMayBeSecretAreNeverRepeated`).

Every node of your application opens a job stream of its own under a client id, which the engine
uses to tell the nodes apart. Without `client-id` the adapter makes one up per start, from the
application's name, the adapter id and eight random hexadecimal digits
(`ZenBpmAdapterConfigurationTest#theClientIdIsDerived`). Set it only where you need a stable one,
and then make it unique per node.

## Every key

All keys below stand under `vanillabp.adapters.<id>.`. The levels are those of VanillaBP's
[configuration model](https://github.com/vanillabp/adapter-platform-integration/wiki/Workflow-modules#the-levels-a-setting-is-written-at):
a key set for a workflow module stands at
`vanillabp.workflow-modules.<module>.adapters.<id>.<key>`, for a workflow at
`vanillabp.workflow-modules.<module>.workflows.<process>.adapters.<id>.<key>`, and for a task at
`vanillabp.workflow-modules.<module>.workflows.<process>.tasks.<task>.adapters.<id>.<key>`. The most
specific level wins (`ZenBpmOverlayResolutionTest`). A duration is written as in Java,
`PT5M` for five minutes.

<!-- every-key: the adapter's keys; DocumentationTest compares this table with the keys the code reads -->
| Key | Levels | Default | What it does |
|---|---|---|---|
| `rest-address` | adapter | none, required | the engine's REST API, see [Connecting to the engine](#connecting-to-the-engine) |
| `grpc-address` | adapter | the host of `rest-address`, port `9090` | `host:port` of the job stream |
| `grpc-plaintext` | adapter | `true` | `false` opens the job stream with TLS, for a proxy which offers it |
| `client-id` | adapter | `<application name>-<id>-<8 random hex digits>` | the job stream's client id, unique per node |
| `request-timeout` | adapter | `PT10S` | the deadline of every REST call; below one second is refused |
| `startup-wait` | adapter | `PT10M` | how long the first deployment waits for the engine to report ready |
| `worker-threads` | adapter | `4` | how many handlers of the adapter id run at once on platform threads, or `virtual` |
| `worker-threads-bound` | adapter | `4`, only with `virtual` | how many handlers run at once on virtual threads |
| `job-timeout` | adapter, workflow module, workflow, task | `PT5M` | how long the engine locks a job to the node which got it; renewed while the handler runs |
| `async-task-lock-renewal` | adapter | `PT1H` | how long the job of a task left open for its asynchronous completion stays locked once its handler returned |
| `async-task-max-age-action` | adapter | `report` | `incident` fails the job of a task open longer than `vanillabp.delivery.max-task-age`, so the engine raises an incident |
| `retry-backoff` | adapter, workflow module, workflow, task | none | how long the engine waits before it hands out the job of a handler which threw again |
| `shutdown-grace` | adapter | `PT20S` | how long a shutdown waits for the handlers still running |
| `workflow-visibility-timeout` | adapter | `PT2S` | how long a workflow just started may stay invisible to a question, and how long a message waits before it is published again |
| `user-task-poll-interval` | adapter | `PT5S` | how often the adapter looks for user tasks; `PT0S` switches the looks off |
| `history-time-to-live` | adapter, workflow module, workflow | none, the engine's own | how long the engine keeps an ended workflow, sent with every start in whole seconds |
| `instance-cache-max-entries` | adapter | `10000` | how many process instances the adapter remembers what it read of; `0` remembers none |
| `message-start-lookup` | adapter | `start-row` | how a workflow a message started is found: `start-row`, `scan` or `refuse` |
| `message-start-lookup-max-instances` | adapter | `1000` | how many instances one lookup of `scan` reads at most |
| `accept-unscoped-identifiers` | adapter, workflow module | `false` | `true` says that the identifiers of your workflow modules are unique, so `none` is not warned about |
<!-- /every-key -->

Every value is checked while the application starts, and one which cannot work ends the start
naming its key in full and the way out (`ZenBpmAdapterConfigurationTest#eachDefectIsNamedWithItsKeyAndItsFix`,
`ZenBpmStartupValidationBootTest#aNegativeScopedValueEndsTheStart`). The values below the adapter
level are checked as well (`ZenBpmAdapterConfigurationTest#aNegativeScopedValueIsAValueDefect`).

VanillaBP's own keys of the same section, which every adapter has:

|          Key           |                                                                      What it does here                                                                      |
|------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `type`                 | `zenbpm`; needed only where the id is not `zenbpm`                                                                                                          |
| `name-clash-avoidance` | `use-prefix` or `none`, see [Keeping workflow modules apart](#keeping-workflow-modules-apart); the default `by-adapter` is refused while deploying          |
| `deployment-failure`   | `fail` (the default) or `warn`; with `warn` an adapter id which is nowhere first priority boots without its deployment, see [Boot behavior](#boot-behavior) |
| `outfaded-versions`    | the versions of a process you retired, see [The versions the engine still holds](#the-versions-the-engine-still-holds)                                      |

And keys of VanillaBP outside the section which decide what this adapter does:

|                                              Key                                               |                                                                                                                                                                                                 Why it matters here                                                                                                                                                                                                  |
|------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `vanillabp.delivery.retention`                                                                 | `async-task-lock-renewal` has to stay below it, or the start ends (`ZenBpmAdapterConfigurationTest#theAsyncTaskLockRenewalStaysBelowTheDeliveryRetention`)                                                                                                                                                                                                                                                           |
| `vanillabp.delivery.max-task-age`                                                              | how old a task left open may get before `async-task-max-age-action` applies                                                                                                                                                                                                                                                                                                                                          |
| `vanillabp.delivery.workflow-start-retention`                                                  | how long the start row lives which finds a workflow the engine started, see [GAPS 1](Deviations#gaps-1-a-workflow-the-engine-started-carries-no-business-key)                                                                                                                                                                                                                                                        |
| `vanillabp.delivery.release-on-workflow-end`                                                   | asks for the end of every workflow, which the adapter learns from an end marker, see [GAPS 8](Deviations#gaps-8-no-listeners-and-no-event-stream)                                                                                                                                                                                                                                                                    |
| `vanillabp.delivery.check-open-tasks-on-delivery`, `vanillabp.delivery.max-open-tasks-checked` | after each delivery VanillaBP asks the adapter about the other tasks it believes open in the workflow, at most ten by default; each question costs one read of a job at the engine (`ZenBpmOpenTaskProbeTest#theStateOfTheJobDecides`), see [Completing and canceling asynchronous tasks](https://github.com/vanillabp/adapter-platform-integration/wiki/Workflow-tasks#completing-and-canceling-asynchronous-tasks) |
| `vanillabp.outbox.wait-for-visibility-at-most`                                                 | how long a message nothing waits for is published again before its entry is blocked, see [GAPS 5](Deviations#gaps-5-a-message-nothing-waits-for-is-dropped)                                                                                                                                                                                                                                                          |

### A key the adapter does not know

The platforms differ for a key under `vanillabp.adapters.<id>.` which nobody knows, a misspelt one
for example:

- **Spring Boot** ignores it. A misspelt `rest-adress` leaves the adapter id without an address, and
  the start warns about the `rest-address` it misses
  (`ZenBpmStartupValidationBootTest#aMisspeltKeyIsIgnored`).
- **Quarkus** ends the start, naming the key (`ZenBpmUnknownKeyTest#aMisspeltKeyFailsTheStart`), the
  removed `max-redeliveries` included (`ZenBpmRemovedKeyTest#maxRedeliveriesFailsTheStart`). Unless
  another extension of the application declares a configuration root of its own under `vanillabp`,
  as the Camunda 8 adapter does: then Quarkus too ignores the key, warning only that it does, and
  the start warns about the key the adapter misses. An application which moves from Camunda 8 to
  ZenBPM has both extensions while it migrates. This was seen in a boot beside the Camunda 8
  extension; no test of this repository holds it, because it needs that extension.

### Several adapter ids

An application may configure several adapter ids of the type `zenbpm`, for example one per engine,
or two on one engine while it moves its workflows from one name-clash-avoidance mode to the other.
Each id needs its `type` and a place in `vanillabp.prioritized-adapters`, and the first one is where
new workflows start (`ZenBpmDistinctInstancesBootTest#oneEngineWithTwoModesBoots`).

**Two ids may name one `rest-address` only where their `name-clash-avoidance` at the adapter level
differs.** That is the setup of a migration from one mode to the other: the new id first in
`prioritized-adapters`. With the same mode both ids reach the engine with the same identifiers, so
the start ends, naming both ids, the engine and the keys `rest-address` and `name-clash-avoidance` of
each (`ZenBpmInstanceIdentityTest#oneEngineWithOneModeIsOneInstance`,
`ZenBpmDistinctInstancesBootTest#oneEngineWithOneModeEndsTheStart`,
`ZenBpmOneEngineOneModeTest#oneEngineWithOneModeEndsTheStart`). There is no key which declares an
engine shared; Camunda 8 compares the same way. What the check does not compare:

- an id without a valid `rest-address`; an id which boots degraded because of another key is
  compared (`ZenBpmInstanceIdentityTest#anAdapterIdWithoutAnAddressIsNotCompared`,
  `ZenBpmInstanceIdentityTest#aDegradedAdapterIdIsComparedByItsAddress`);
- a mode set for a workflow module only;
- two addresses of one engine, such as an alias, a proxy or two nodes of one cluster, which the check
  cannot know to be one. The address is compared as written, apart from the case of its host, a
  trailing slash and the API version `/v1`
  (`ZenBpmInstanceIdentityTest#theAddressIsComparedNormalised`).

Each id answers VanillaBP's questions only for the workflows and tasks of its own mode, so a
workflow the old id started is completed through it, while new workflows start under the new id
(`ZenBpmSharedEngineElectionIT#aWorkflowStartedUnderTheOldIdIsCompletedByTheOldId`,
`ZenBpmSharedEngineElectionIT#aNewWorkflowStartsUnderTheNewId`).

**Without an adapter id of this type** the platforms differ. On Quarkus an application whose
classpath holds the adapter but which configures adapter ids of another type only does not start:
VanillaBP wants an id of every adapter extension on the classpath and names
`vanillabp.adapters.*.type=zenbpm` (`ZenBpmWithoutAdapterIdsTest#noAdapterIdOfThisTypeIsRefused`).
Spring Boot starts it, and the adapter has nothing to do. An application which configures no
adapter id at all gets the id `zenbpm`, which VanillaBP derives from the classpath; without an
address it boots with a warning (`ZenBpmStartupValidationTest#theHealthIsUnknown` on Quarkus). One
which configures the keys of the id `zenbpm` only, as [Home](Home#minimal-configuration) does, gets
that id as its prioritized adapter on both platforms, without `prioritized-adapters`
(`ZenBpmHomePageIT`, `ZenBpmHomePageTest`); VanillaBP asks for the list only where several adapter
ids are configured.

## The BPMN model for ZenBPM

The engine reads its extension elements, such as `zenbpm:taskDefinition`, from the
`extensionElements` of an element. Where the adapter adds one, it writes it in the namespace the
file uses for them already, else in the engine's own, `http://zenbpm.pbinitiative.org/1.0`
(`ZenBpmCorrelationKeysTest#theEnginesNamespaceGetsAFreePrefix`).

**A task names its job type** in `zenbpm:taskDefinition type`, and the engine hands its job out
under that type to whoever subscribed it: the adapter subscribes the types of the tasks your
`@WorkflowTask` methods serve (`ZenBpmTaskProcessingIT#happyPathRunsEveryTaskKind`). An element which creates a job and names no
task definition would get a job of the type `''` which nothing takes, so the start ends naming it,
unless you mark the task `implemented-externally`
([GAPS 25](Deviations#gaps-25-a-job-without-a-type-is-accepted)). How often a task is tried before the
engine raises an incident is `zenbpm:taskDefinition retries`; a model without it gets the engine's
`JOBS_DEFAULT_RETRIES`, which is one attempt by default, so the first exception of the handler
raises an incident (`ZenBpmTaskProcessingIT#aModelWithoutRetriesEndsInAnIncidentAtTheFirstThrow`).
Set `retries` in the model, or `JOBS_DEFAULT_RETRIES=3` at the engine for the default of Camunda 8
([decision 38](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/blob/main/DECISIONS.md#38-the-adapter-writes-no-default-of-retries-into-a-model-and-sends-a-backoff-only-where-one-is-configured)).

**A user task is found by its type** as well: the engine hands it out as a job of its
`zenbpm:taskDefinition type`, else of the engine-wide default `user-task-type`, and the adapter
looks for the user tasks a method serves by that type or by the element's id
(`ZenBpmUserTaskPollerTest#aUserTaskServedByItsElementIdIsNotified`,
`ZenBpmDeclaredProcessWorkersTest#theDefaultTypeOfAHeldUserTaskIsPolledUnscoped`). A user task marked
`implemented-externally` is never looked for (`ZenBpmUserTaskIT#aMarkedUserTaskIsNotPolled`).

**No correlation key has to be modelled.** Every message a claimed workflow waits for, and which
carries no `zenbpm:subscription` of yours, gets the aggregate's id as its key, so `correlateMessage`
works as on Camunda 8 (`ZenBpmCorrelationKeysTest#aMessageWithoutAKeyGetsTheAggregateId`). A key you
modelled stays; then the application passes a correlation id, which is the key the message is
published with (`ZenBpmProcessServiceTest#aCorrelationIdIsTheCorrelationKey`). A message name may not
both start a workflow of the module and be awaited by a claimed one
([GAPS 4](Deviations#gaps-4-a-message-without-a-match-starts-a-workflow)).

**What else the adapter writes into the model it deploys**, and where you see it in the engine:

- a start marker, the service task `vanillabp__started`, behind every start event of a process which
  starts a workflow of its own ([GAPS 8](Deviations#gaps-8-no-listeners-and-no-event-stream),
  `ZenBpmMarkerTasksTest#aMarkerFollowsEveryStartEventOfTheProcessItself`);
- an end marker, `vanillabp__ended`, in front of every none, message and terminate end event, where
  the application listens to the end of the workflow
  (`ZenBpmMarkerTasksTest#anEndMarkerStandsInFrontOfEveryEndEventOfTheProcessItself`);
- input mappings of the aggregate id and of the names your methods read into a multi-instance body,
  which starts with its item only
  (`ZenBpmTaskParametersTest#theNamesOfAJobInsideABodyAreMappedIntoEveryBodyAroundIt`);
- under `use-prefix`, the workflow module's id in front of every identifier, see
  [Keeping workflow modules apart](#keeping-workflow-modules-apart).

A file which needs none of it reaches the engine as the bytes you saved, so an unchanged model is
no new version (`ZenBpmModelWriterTest#anUnchangedFileIsDeployedAsItsBytes`). Because the adapter
writes into the model, a changed `@WorkflowEnded` method can change the model the engine gets,
which matters for a version tag
([GAPS 32](Deviations#gaps-32-a-version-tag-names-one-version)).

**What the engine cannot run is refused before anything is deployed**: signals, conditional,
escalation, compensation and cancel events, script, manual and plain tasks, complex gateways,
ad-hoc sub-processes, transactions, standard loops and the other constructs
[GAPS 13 and 14](Deviations#gaps-14-constructs-the-engine-refuses-or-runs-differently) list, and a
file with more than one process ([GAPS 18](Deviations#gaps-18-one-process-per-file)). The message
names every element and whether the engine would refuse it or run something else
(`ZenBpmUnsupportedConstructsTest#severalOffencesAreNamedInOneMessage`).

**A business rule task hands its result to the process only through an output mapping**, for
example `<zenbpm:output source="=rating" target="rating" />`
([GAPS 27](Deviations#gaps-27-a-decisions-result-needs-an-output-mapping)).

**A version tag** is written as `<zenbpm:versionTag value="..." />` on the process, and the engine
gives a tag to one version only ([GAPS 32](Deviations#gaps-32-a-version-tag-names-one-version)).

## What reaches the engine

A workflow is started with the aggregate's id as its business key, and with the values the
aggregate shares as process variables, the id among them as a text named after the id attribute
(`ZenBpmDeploymentAndStartIT#instanceAppearsOnlyAfterCommit`,
`ZenBpmSharedValuesTest#theIdFollowsTheSharedValuesAsText`). What a handler computed is written into
the job's instance and every instance above it up to the workflow before the job is completed, so a
gateway behind the task, also behind an embedded sub-process, decides on the new values
(`ZenBpmTaskProcessingIT#gatewayAfterTaskSeesTheNewValues`,
`ZenBpmTaskProcessingIT#aTaskInASubProcessNamesTheRootAndTheGatewayBehindItSeesTheValues`). The
engine passes the variables a job is completed with to the process only through output mappings
([GAPS 7](Deviations#gaps-7-job-outputs-reach-the-process-only-through-mappings)), which is why the
adapter writes them first.

The engine stores every number as a floating-point number of 64 bits, so a few types come back
changed, which the adapter tells VanillaBP: a whole number above 2^53, a `BigDecimal`, a `char`,
and a text of another type than `String` (`ZenBpmValueTypesIT#aValueComesBackAsItsTypeIsAnswered`,
`ZenBpmRestClientContractIT#aWholeNumberBeyond2To53ComesBackRounded`).

`history-time-to-live` is sent with every start in whole seconds; a value with a fraction of a
second is refused while the application starts
(`ZenBpmTimeToLiveTest#aTimeToLiveIsSentAsWholeSeconds`). Without it the engine keeps an ended
workflow as long as its own configuration says, which is for good by default. The user tasks the
adapter looks for are read from what the engine keeps, so set it, but long enough, see
[GAPS 10](Deviations#gaps-10-no-user-task-events).

## Keeping workflow modules apart

ZenBPM has no tenant and no namespace, so the engine sees the identifiers of every workflow module
of every application side by side ([GAPS 11](Deviations#gaps-11-no-tenant-or-namespace)). Of
VanillaBP's three modes:

- **`use-prefix`** puts the workflow module's id in front of every identifier the engine resolves
  across files, so the process `Ordering` of the module `deploy-it` reaches the engine as
  `deploy-it__Ordering` (`ZenBpmDeploymentAndStartIT#instanceAppearsOnlyAfterCommit`). That is the
  process ids and the processes a call activity calls, message names, error and escalation codes,
  decision ids, and the job types of tasks and user tasks, which get the process id as well
  (`ZenBpmScopingTest#usePrefixScopesWhatTheEngineResolvesAcrossFiles`). Your code and your
  configuration keep the plain names (`ZenBpmScopingTest#theDeclaredIdentifiersAreHandedBackPlain`). A decision id which the engine would then
  split at a `.` ends the start (`ZenBpmScopingTest#aDecisionIdTheEngineWouldSplitIsRefusedUnderUsePrefix`).
- **`none`** deploys the identifiers as they are. The start warns, naming `use-prefix`, an engine of
  its own and the keys which accept unique identifiers
  (`ZenBpmStartupValidationBootTest#noneIsWarnedAboutWhileTheModuleIsPrepared`), until
  `accept-unscoped-identifiers: true` says that they are unique
  (`ZenBpmDeploymentServiceTest#acceptedUnscopedIdentifiersAreNotWarnedAbout`).
- **`by-adapter`**, VanillaBP's default, ends the start of a workflow module with BPMN files, naming
  both ways out, also where it is set for a single workflow
  (`ZenBpmStartupValidationBootTest#byAdapterConfiguredEndsTheStartNamingBothWaysOut`,
  `ZenBpmStartupValidationBootTest#byAdapterOfOneWorkflowEndsTheStartNamingItsKey`).

The mode may be set for the adapter id, a workflow module or a workflow. Changing it later is a
BPMS migration, because the engine knows the running workflows under the old identifiers, see
[Several adapter ids](#several-adapter-ids). Two processes of the application which would reach the
engine under one id end the start
(`ZenBpmDeploymentServiceTest#twoFilesDeclaringOneProcessAreRefused`). What another application
deployed on the same engine is not known to the adapter, so a clash with it is not found
([GAPS 24](Deviations#gaps-24-nothing-tells-who-deployed-a-definition)).

## Timeouts and worker settings

**Handler slots.** `worker-threads` handlers of one adapter id run at once, four platform threads by
default; `virtual` runs each on a virtual thread, `worker-threads-bound` of them at once
(`ZenBpmAdapterConfigurationTest#virtualWorkerThreadsAreAccepted`,
`ZenBpmAdapterConfigurationTest#platformThreadsAreTheirOwnBound`). Each job type is subscribed with
the number of slots as the number of its jobs the engine may hand to this node at once
(`ZenBpmJobStreamTest#everySubscriptionCarriesItsSettings`). What a handler may assume about its
thread is on [Home](Home#what-a-handler-may-assume-about-its-thread).

**The lock of a job.** `job-timeout` is how long the engine locks a job to the node which got it.
The adapter subscribes each job type with it and renews the lock while the handler runs, once half
of it is left (`ZenBpmLockRenewalTest#aFullLockIsExtendedOnceHalfOfItIsLeft`). The value of a task
comes from its most specific level (`ZenBpmTaskProcessingIT#theJobTimeoutOfATaskComesFromItsMostSpecificLevel`);
two tasks of one job type with different values end the start, because the engine locks by type
(`ZenBpmJobTimeoutBootTest#twoJobTimeoutsOfOneJobTypeEndTheStart`). A longer `job-timeout` keeps a
job longer from another node after this one went down without closing its stream; a shutdown closes
the stream, which releases every lock at once (`ZenBpmShutdownDrainIT#anotherNodeGetsTheOpenJobsAtOnce`).

**A task left open** for its asynchronous completion keeps its job locked for
`async-task-lock-renewal` after its handler returned, after which the engine hands the job out
again and the adapter locks it once more without running the method again
(`ZenBpmLockRenewalTest#aParkedJobIsExtendedOnceByTheWindow`). One not shorter than
`vanillabp.delivery.retention` ends the start
(`ZenBpmAsyncTaskKeysTest#theKeysOfTasksLeftOpenAreValidatedAgainstThePlatform`). The engine counts a
locked job against the slots of its type, so the adapter raises the number by every task it keeps
open, and the next task of the type still arrives (`ZenBpmParkedTasksIT#aParkedTaskLeavesRoomForTheNextOfItsType`;
[GAPS 28](Deviations#gaps-28-a-locked-job-holds-a-slot)). With `async-task-max-age-action: incident` a
task open longer than `vanillabp.delivery.max-task-age` is failed without retries, so an incident
names it (`ZenBpmJobHandlerTest#anOverdueTaskWithTheIncidentActionIsFailedWithoutRetries`).

**Failures and retries.** A method which throws is failed at the engine, which spends one attempt
of the task, hands the job out again after the backoff, and after the last attempt raises an
incident naming the attempts, the task, the aggregate and the exception
(`ZenBpmTaskProcessingIT#aThrowingHandlerEndsInAnIncidentAfterItsRetries`). The number of attempts
is the model's, see [The BPMN model for ZenBPM](#the-bpmn-model-for-zenbpm). The backoff is
`retry-backoff`, in whole seconds, where a level sets it; where none does, the failure carries none
and the backoff the model or the engine sets applies (`ZenBpmRestClientTest#aFailureSendsItsBackoffInWholeSeconds`,
`ZenBpmJobFailureTest#aBackoffNoLevelSetsIsLeftOut`). A
BPMN error is not a failure: it is sent with its error code, so an error boundary event or an error
event sub-process takes over, and where none catches the code the engine raises an incident
(`ZenBpmTaskProcessingIT#aBpmnErrorRoutesTheBoundaryEvent`,
`ZenBpmTaskProcessingIT#anUnknownErrorCodeIsAnIncident`). A job of a workflow whose aggregate this
application does not hold is failed without retries, so an incident shows it at once
(`ZenBpmTaskProcessingIT#aJobOfAWorkflowThisApplicationDoesNotOwnEndsInAnIncident`).

**The shutdown.** A shutdown stops the subscriptions of each workflow module and then waits for the
handlers still running, those of every module of the adapter id together, at most `shutdown-grace`,
and names each handler it gave up on (`ZenBpmShutdownDrainIT#aHandlerWithinTheGraceFinishes`,
`ZenBpmShutdownDrainIT#aHandlerAfterTheGraceIsNamed`). A handler the shutdown cut off is not failed
at the engine, so its job keeps its retries (`ZenBpmShutdownDrainIT#aCutOffHandlerCostsNoFailure`).
Above thirty seconds the start warns, because the time your runtime grants a stop, such as
Kubernetes' `terminationGracePeriodSeconds`, has to grow with it
(`ZenBpmStartupValidationBootTest#aLongShutdownGraceIsWarnedAboutInTheBlock`).

**Questions to the engine.** `request-timeout` is the deadline of each REST call. A read answered
by a node of the engine which lags behind may not find a workflow started a moment ago
([GAPS 20](Deviations#gaps-20-a-read-may-lag-behind)), so the adapter tells VanillaBP to ask again
within `workflow-visibility-timeout`. The same window is how long a message nothing waits for yet
waits before it is published again ([GAPS 5](Deviations#gaps-5-a-message-nothing-waits-for-is-dropped)).
`startup-wait` is how long the first deployment waits for the engine to report ready; the wait says
where it stands while it waits (`ZenBpmEngineWaitTest#theWaitSaysWhereItStandsEveryLogInterval`,
`ZenBpmEngineWaitTest#aWaitWhichRunsOutSaysEverything`).

**User tasks** are looked for every `user-task-poll-interval`, see
[GAPS 10](Deviations#gaps-10-no-user-task-events). `PT0S` switches the looks off, so the application
is told of no user task; the start warns about it and about an interval below a second
(`ZenBpmAdapterConfigurationTest#aUserTaskPollIntervalWhichWorksAgainstTheApplicationIsWarnedAbout`).

**`instance-cache-max-entries`** bounds what the adapter remembers of the process instances it read:
their process, version, parent and business key, which never change while an instance runs. A full
cache forgets the instance used longest ago (`ZenBpmInstanceCacheTest#aFullCacheForgetsTheInstanceUsedLongestAgo`),
and `0` keeps none, which costs one more read for most deliveries
(`ZenBpmInstanceCacheTest#aCacheOfNoEntriesKeepsNothing`).

## Starting a workflow by a message

The engine gives a workflow it starts by a message no business key and names no instance in its
answer ([GAPS 1](Deviations#gaps-1-a-workflow-the-engine-started-carries-no-business-key)), so a
search by the aggregate's id never finds it. `message-start-lookup` says how it is found:

- **`start-row`**, the default: the start marker behind the message start event reports the
  instance's key, which VanillaBP writes into the start row of the aggregate, and every later
  operation on the running workflow finds it through that key
  (`ZenBpmMessageIT#aMessageStartedWorkflowIsFoundThroughItsStartRow`). The row lives for
  `vanillabp.delivery.workflow-start-retention` (`P30D` by default, `0` keeps rows for good) or as
  long as `vanillabp.delivery.keep-workflow-start-while-aggregate-exists` says. What the row does
  not cover is under GAPS 1.
- **`scan`**, an opt-in: in addition the adapter reads the running instances of the processes a
  version of which has a message start event, where nothing else finds the workflow, and finds one
  by its id variable, also before a start is sent again
  (`ZenBpmMessageStartScanIT#scanFindsTheWorkflowOfARedispatchedStart`). It reads at most
  `message-start-lookup-max-instances` instances (`1000` by default), beyond which it answers that
  the engine is unavailable. The start warns about it, because a workflow which ended stays unknown
  to it as well (`ZenBpmDeployingTest#theProcessesAMessageStartsAreNamedOnce`).
- **`refuse`**: every start by a message is refused where the application calls, naming the key
  (`ZenBpmMessageStartRefusedIT#startWorkflowByMessageIsRefusedWithRefuse`).

Each start of the application names the processes a message starts and the value in force. Start
messages of one name which reach the engine at the same moment lose starts
([GAPS 30](Deviations#gaps-30-start-messages-of-one-name-at-the-same-moment-lose-starts)), so each
node publishes them one after the other
(`ZenBpmProcessServiceIT#startsByOneMessageAtOnceEachStartTheirWorkflow`); several nodes of an
application may still lose such a start unseen.

## Boot behavior

Every finding of the start goes into the block VanillaBP writes once, at its end, under the topic
where its fix lies, and names the property keys to set, written out in full so they can be copied
([decision 60](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/blob/main/DECISIONS.md#60-a-finding-of-the-start-goes-to-the-platforms-report-under-its-scope-what-a-deployment-step-refuses-is-thrown)):

- **an adapter id without `rest-address`** boots with a warning. Every use of it fails with the same
  message, and its health is `UNKNOWN`
  (`ZenBpmStartupValidationBootTest#anUnconfiguredAdapterIdBootsWithAWarning`,
  `ZenBpmStartupValidationTest#aUseOfTheAdapterFailsWithTheSameWords`,
  `ZenBpmHealthBootTest#anUnconfiguredAdapterIdIsUnknown`). A workflow module with BPMN
  files which this id would have to deploy ends the start
  (`ZenBpmStartupValidationBootTest#anUnconfiguredAdapterIdWithBpmnFilesEndsTheStart`);
- **an address or a client id which cannot work**, on an adapter id which is nowhere first priority
  and whose `deployment-failure` is `warn`, is a warning, and the application boots degraded
  (`ZenBpmStartupValidationBootTest#anAddressDefectWhichMayDegradeBootsDegraded`,
  `ZenBpmDegradedBootTest#anAddressDefectWhichMayDegradeBootsDegraded`);
- **every other value which cannot work ends the start** before anything is deployed, together with
  every other reason found, so the keys of two adapter ids got wrong are named in one start
  (`ZenBpmStartupValidationBootTest#twoAdapterIdsWhichEndTheStartAreNamedInOneStart`);
- **an engine which runs another commit** than the one this adapter was tested with, or does not say
  which one it runs, is a warning (`ZenBpmEnvironmentInfoTest#anotherCommitIsOneWarning`).

What ends the deployment of a workflow module: a model ZenBPM cannot run, `by-adapter`, a process
declared in two files, a message which starts one workflow and which another waits for, two
`job-timeout`s of one job type, a file the engine refuses, and an engine which does not report
ready within `startup-wait`. VanillaBP decides with `deployment-failure` what follows: the start
ends, or, where the adapter id is nowhere first priority and the value is `warn`, the application
boots and the failure stands in the block (`ZenBpmUnsupportedModelBootTest`,
`ZenBpmRefusedFileBootTest`, `ZenBpmEngineWaitTest#aWaitWhichRunsOutSaysEverything`).

The deployment sends one file per request once the engine reports ready, and a start with
unchanged files keeps the versions the engine holds
(`ZenBpmDeploymentIT#aWorkflowModuleWithBpmnAndDmnDeploys`). A file the engine refuses ends the
start with the engine's reason (`ZenBpmRefusedFileBootTest#aRefusedFileEndsTheStart`,
`ZenBpmDeploymentServiceIT#aFileTheEngineRefusesEndsTheDeployment`). The engine takes one file per
request, so the files sent before it stay deployed.

No message carries a password or the data of a workflow: the variables of a job are never quoted,
not even where they cannot be read (`ReceivedJobTest#aJobWithoutAJsonObjectHasNoneOrIsRefused`).

## What an operator gets to see

**Health.** Each adapter id adds an entry named after it to VanillaBP's health check, the readiness
check on Quarkus. It asks the engine's readiness, `GET /system/health/ready`, once per check: an id
without clients is `UNKNOWN`, an engine which answers ready is `UP` with the address, the version and
commit the engine reported at the start, and the state of the job stream, and an engine which is not
ready, cannot be reached or does not answer within `request-timeout` is `DOWN`
(`ZenBpmHealthTest#aReadyEngineIsUp`, `ZenBpmHealthTest#anEngineWhichIsNotReadyIsDown`,
`ZenBpmHealthTest#anEngineOutOfReachIsDown`). A job stream which is down never turns the entry
`DOWN`: a readiness probe which went down for it would take every replica out of service and fix
nothing, while starts and questions still go over REST (`ZenBpmHealthTest#aReadyEngineWithAStreamDownSaysSo`,
[decision 58](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/blob/main/DECISIONS.md#58-the-health-check-stays-up-while-the-job-stream-is-down-however-long)).

**Meters.** Where the application brings Micrometer, each adapter id with clients publishes these
meters, each tagged `adapter` with the adapter id, beside those VanillaBP publishes for every BPMS
(`MicrometerZenBpmMetricsTest`, `ZenBpmMetricsIT`):

|                Meter                 |  Kind   |                                  What it says                                   |
|--------------------------------------|---------|---------------------------------------------------------------------------------|
| `vanillabp.zenbpm.jobs.received`     | counter | jobs the job stream handed over, every delivery                                 |
| `vanillabp.zenbpm.jobs.completed`    | counter | jobs a handler answered with a completion or a BPMN error, the markers included |
| `vanillabp.zenbpm.jobs.failed`       | counter | jobs failed at the engine: a handler threw, or the adapter raised an incident   |
| `vanillabp.zenbpm.jobs.queued`       | counter | jobs which found no free handler slot and waited for one                        |
| `vanillabp.zenbpm.jobs.redelivered`  | counter | deliveries of a job the engine had handed out before                            |
| `vanillabp.zenbpm.lock.renewed`      | counter | extensions of a running handler's lock the engine granted                       |
| `vanillabp.zenbpm.lock.lost`         | counter | locks which ended while their handler ran                                       |
| `vanillabp.zenbpm.stream.reconnects` | counter | attempts of the job stream to open its call again after it broke                |
| `vanillabp.zenbpm.usertasks.polls`   | counter | looks at the user tasks                                                         |
| `vanillabp.zenbpm.slots.busy`        | gauge   | handler slots taken right now                                                   |
| `vanillabp.zenbpm.slots.free`        | gauge   | handler slots free right now                                                    |

What to alert on is the pair `vanillabp.zenbpm.stream.reconnects` rising while
`vanillabp.zenbpm.jobs.received` stays flat: the job stream is down while REST works, for example
because of a wrong `grpc-address` or a blocked port. An application without Micrometer boots
unchanged and publishes none of them (`ZenBpmWithoutMicrometerTest`).

**The log.** The job stream writes one WARN when it goes down, however long the outage lasts, and
one INFO when it is back (`ZenBpmJobStreamTest#anOutageIsWarnedAboutOnce`). The start writes one line
naming the engine it reached, its version and commit
(`ZenBpmEnvironmentInfoTest#oneLineNamesTheEnvironment`).

## The versions the engine still holds

At startup the versions of a process which are older than the one this start deployed are checked:
VanillaBP names such a version with the number of workflows which still run on it, a task of it no
method serves any more, and what those workflows miss of what the adapter writes into the model it
deploys, the markers and the mappings into a multi-instance body (`ZenBpmOldProcessVersionsIT`). A
version you retired with `vanillabp.adapters.<id>.outfaded-versions` is not checked for its tasks;
the workflows still running on it are named instead. The numbers are counted by the engine, so the
start reads no page of instances, and each model is read once per start
([decision 56](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/blob/main/DECISIONS.md#56-a-start-reads-each-model-the-engine-holds-once-and-the-questions-about-a-held-version-read-it-from-there)).

Where this adapter does not deliver what VanillaBP's contract for every BPMS promises, because the
engine cannot do something, and what the adapter does instead. Everything not listed works as the
[VanillaBP wiki](https://github.com/vanillabp/adapter-platform-integration/wiki) describes it; the
two-phase start and the at-least-once delivery of a task are what a remote BPMS looks like, not a
deviation.

Each entry carries the number of the gap in the adapter's
[`GAPS.md`](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/blob/main/GAPS.md), which names
the evidence in the engine's source, and links the section of the adapter's README which describes
the behaviour. `GAPS.md` also names, for every gap, what in the engine would close it, and an entry
here repeats it where it helps; nothing here promises when.

<!-- gaps: one heading per row of GAPS.md; DocumentationTest compares the two -->

### GAPS 1: A workflow the engine started carries no business key

A workflow a timer started, one a message started, and one somebody started past VanillaBP without a
business key are found again only through the instance key their start marker recorded in the
application's start row, for as long as that row lives (`vanillabp.delivery.workflow-start-retention`,
`P30D` by default, `0` keeps it for good, or
`vanillabp.delivery.keep-workflow-start-while-aggregate-exists`)
(`ZenBpmLifecycleIT#aWorkflowStartedPastVanillaBpGetsAnAggregate`,
`ZenBpmMessageIT#aMessageStartedWorkflowIsFoundThroughItsStartRow`). The row does not cover an
application without a delivery log, the moments between publishing a start message and the commit of
its marker's report, in which only the node which published knows the workflow, and a start sent
again after its answer was lost before the marker wrote the row, which publishes once more and may
start a second workflow. Nor does it cover a workflow which ended: VanillaBP is then told that the
engine does not know it, which the contract does not allow
(`ZenBpmWorkflowProbesTest#whateverElseTheKeyLeadsToFallsThroughToTheSearch`), and the viewer
does not know such a workflow either (`ZenBpmWorkflowViewerTest#anUnknownWorkflowIsUnknown`).
`message-start-lookup: scan` finds a running workflow a message started by its id variable as well,
for VanillaBP's question and for the viewer
(`ZenBpmWorkflowProbesTest#underScanAWorkflowAMessageStartedIsFoundByItsIdVariable`,
`ZenBpmWorkflowViewerTest#underScanAWorkflowAMessageStartedIsFound`), see
[Starting a workflow by a message](Configuration#starting-a-workflow-by-a-message). A business key the adapter could set after the start would close the gap.
[README: The probes](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/blob/main/README.md#the-probes)

### GAPS 2: A new leader hands every job out again

The engine keeps the lock of a job in the memory of the node which leads its partition, so after a
leader change every waiting job is handed out again at once, the job of a handler which still runs
and of a task left open included. A delivery after one which committed is answered from VanillaBP's
record and does not run your method again
(`ZenBpmRestartDeliveryIT#aTaskDeliveredBeforeARestartAndCompletedAfterItRunsOnce`); one while the
first still runs runs a second time, and the adapter names the lock it could not keep
(`ZenBpmInboundIdempotencyIT#aLostLockIsNamed`). Locks kept in the replicated state of the partition
would close the gap.
[README: Task processing](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/blob/main/README.md#task-processing)

### GAPS 3: At most ten active jobs per client (closed)

Closed in the engine before the adapter's pin: each job type is subscribed with a cap of its own,
which the adapter sets to its handler slots (`ZenBpmJobStreamTest#everySubscriptionCarriesItsSettings`).
[README: Task processing](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/blob/main/README.md#task-processing)

### GAPS 4: A message without a match starts a workflow

The engine hands a message whose correlation key no waiting workflow matches to a start event of the
same name, so a message name may not both start a workflow of a workflow module and be awaited by a
workflow of it: the start ends naming the name
(`ZenBpmCorrelationKeysTest#aMessageWhichStartsAndIsAwaitedIsRefused`), and `correlateMessage` with a
message the newest version of a process starts with is refused where the application calls, naming
`startWorkflowByMessage` (`ZenBpmMessageDeclarationTest#aMessageTheNewestVersionStartsWithIsRefusedAlthoughAModelWaitsForIt`).
A strict mode on publishing would close the gap.
[README: Messages](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/blob/main/README.md#messages)

### GAPS 5: A message nothing waits for is dropped

The engine refuses a message nothing waits for yet and keeps nothing, so VanillaBP publishes it again
every `workflow-visibility-timeout` without counting an attempt, until a workflow waits for it, or
until `vanillabp.outbox.wait-for-visibility-at-most` (about four hours by default) passed and the
outbox entry is blocked (`ZenBpmMessageIT#aMessagePublishedBeforeTheSubscriptionIsRepeatedUntilItCorrelates`).
A workflow which passed the waiting element, or waits under another key, is answered the same way.
Buffering a message for a time to live would close the gap.
[README: Messages](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/blob/main/README.md#messages)

### GAPS 6: No retries (closed)

Closed in the engine before the adapter's pin: the engine counts the attempts of a task and waits a
backoff, so a handler which threw is failed at the engine, and its last attempt raises an incident
(`ZenBpmTaskProcessingIT#aThrowingHandlerEndsInAnIncidentAfterItsRetries`). A task whose model sets no
`retries` has one attempt, see [The BPMN model for ZenBPM](Configuration#the-bpmn-model-for-zenbpm).
[README: Task processing](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/blob/main/README.md#task-processing)

### GAPS 7: Job outputs reach the process only through mappings

The engine passes the variables a job is completed with to the process only through the task's
output mappings (`ZenBpmRestClientContractIT#aCompletionWithoutAnOutputMappingWritesNothingIntoTheInstance`),
so the adapter writes the shared values into the workflow first and completes the job afterwards,
which costs one update per instance from the task's up to its workflow's
(`ZenBpmTaskProcessingIT#gatewayAfterTaskSeesTheNewValues`).
Nothing is asked of you.
[README: Task processing](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/blob/main/README.md#task-processing)

### GAPS 8: No listeners and no event stream

Nothing in the engine tells a client that a workflow started or ended, so the adapter writes markers
into the model it deploys and serves their jobs: a start marker behind every start event of the
top-level process which starts a workflow of its own, and, where your application listens to the
end of a workflow, an end marker in front of every none, message and terminate end event of the
top-level process (`ZenBpmLifecycleIT#theEndOfAWorkflowIsReportedWithItsEndEvent`,
[decision 52](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/blob/main/DECISIONS.md#52-the-end-marker-reports-an-end-only-where-nothing-else-of-the-workflow-runs-and-no-error-end-event-gets-one)). A
workflow of a version deployed before the markers existed reports neither, a workflow which ends in an
interrupting event sub-process or in an error event sub-process reports no end, an error end event
which nothing catches raises an incident instead of ending the workflow, as on Camunda 8
(`ZenBpmMarkerTasksIT#anUncaughtErrorEndEventRaisesAnIncident`), a message end event's own task runs
after `@WorkflowEnded`, because its marker stands in front of it
(`ZenBpmMarkerTasksTest#aMessageEndEventGetsItsMarkerInFront`), and two branches which end at the
same moment may each report the end (`ZenBpmWorkflowEndedHandlerTest#branchesWhichEndAtOnceReportTheEnd`).
The start marker of a workflow the engine started builds its aggregate through your
`@WorkflowStartedByBpms` method and then writes the aggregate's id into the instance, so a second
delivery of the marker finds it and builds nothing (`ZenBpmBpmsInitiatedStartHandlerTest#aRedeliveryFindsTheNameWrittenBefore`).
Where the application stops between the commit of the aggregate and that write, the next delivery
builds a second aggregate, unless your method gives the aggregate an id which follows from the
workflow; Camunda 8 has the same window.
Listener jobs, or an end event which creates a job, would close the gap.
[README: The end of a workflow](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/blob/main/README.md#the-end-of-a-workflow)

### GAPS 9: A cancelled workflow tells nobody

A workflow cancelled at the engine runs no marker, so `@WorkflowEnded` never reports a cancelled
workflow (`ZenBpmLifecycleIT#aCancelledWorkflowReportsNothing`), and its service tasks hear of it only
where one of its user tasks is told as cancelled, which wakes the workflow up
(`ZenBpmUserTaskIT#theCancellationOfAUserTaskTellsTheOpenServiceTaskOfItsWorkflow`). A task taken away
while its workflow goes on, by an interrupting boundary event, is told as `CANCELED` at the next
delivery of the same workflow (`ZenBpmTaskProcessingIT#aTaskTakenAwayByABoundaryEventIsReportedCanceled`).
A cancellation the engine announced would close the gap.
[README: The end of a workflow](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/blob/main/README.md#the-end-of-a-workflow)

### GAPS 10: No user-task events

The engine pushes nothing about user tasks, so the adapter looks for them every
`user-task-poll-interval` (`PT5S` by default), and your application is told of a new or a cancelled
user task up to one interval and the time of a look late (`ZenBpmUserTaskIT`). Each look reads more
the more terminated user tasks the engine keeps, so set `history-time-to-live`, but long enough: a
cancellation is told only while the engine still lists the terminated task, and one whose history
the engine removed before a look reached it, while a node was down for example, is never told. A
user task which came and went between two looks is told as cancelled only. User-task events on the
job stream would close the gap.
[README: User tasks](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/blob/main/README.md#user-tasks)

### GAPS 11: No tenant or namespace

Only `use-prefix` and `none` keep workflow modules apart. `by-adapter` stays VanillaBP's default and
is refused while deploying, so every application writes one of the two, one line per adapter id
(`ZenBpmStartupValidationBootTest#nothingConfiguredEndsTheStartNamingBothWaysOut`), see
[Keeping workflow modules apart](Configuration#keeping-workflow-modules-apart). Nothing is planned in
the engine.
[README: Deployment](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/blob/main/README.md#deployment)

### GAPS 12: No authentication and no TLS

The adapter sends no credentials, because the engine checks none and has no TLS on its ports. Put the
engine behind a proxy which does both, and reach it with an `https` `rest-address` and
`grpc-plaintext: false` (`ZenBpmAdapterConfigurationTest#aTlsRestAddressBesideAPlaintextJobStreamIsNoticed`);
[Engine APIs](Engine-APIs) lists what the proxy has to let through. Authentication and TLS on the
engine's ports would close the gap.
[README: Boot behavior](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/blob/main/README.md#boot-behavior)

### GAPS 13: No signals

`sendSignal` is not offered, so VanillaBP answers it as not supported
(`ZenBpmProcessServiceTest#everyRequiredOperationIsServed`), and a model with a signal event is
refused before anything is deployed, a signal start or end event as well, which the engine would run
as a plain one (`ZenBpmUnsupportedConstructsTest#everyConstructIsRefused`). Nothing is planned in
the engine.
[README: Deployment](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/blob/main/README.md#deployment)

### GAPS 14: Constructs the engine refuses or runs differently

A model which uses what the engine refuses or would run differently from the model is refused before
anything is deployed, naming every element: conditional, escalation, compensation and cancel events;
script, manual and plain tasks, complex gateways, ad-hoc sub-processes and transactions; standard
loops, completion conditions and loop cardinalities; conditions outside exclusive and inclusive
gateways or without `=`; values Camunda 8 evaluates as FEEL where the engine takes them as they are
written (timers, message names, called processes, job types, decision ids, candidate groups); and a
called decision bound by `deployment` or named with more than one `.`
(`ZenBpmUnsupportedConstructsTest#everyConstructIsRefused`).
[README: Deployment](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/blob/main/README.md#deployment)

### GAPS 15: A job names neither its process nor its business key

A job on the job stream carries its key, the key of its instance, its type and its element, but not
its process, version, business key or element instance. So the adapter reads each instance once and
keeps what does not change while it runs, at most `instance-cache-max-entries` of them
(`ZenBpmInstanceCacheTest#theFactsAreReadOnce`). A delivery of a job whose instance is not known yet waits for the
next delivery (`ZenBpmJobDispatcherTest#aJobOfAnInstanceNotKnownYetIsLeftToItsLock`). A richer job
on the stream would close the gap.
[README: The workflow id](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/blob/main/README.md#the-workflow-id)

### GAPS 16: An iteration is told neither its index nor its total

The engine hands a job of a multi-instance iteration its item only, so the adapter reads the index
and the total from what the engine keeps of the element, only where a method asks for one, which
costs reads (`ZenBpmJobHandlerTest#theIterationsAreReadOnceWhereTheMethodAsksForThem`). Where two
activations of the same element wait in one instance at once over collections of different sizes,
the total is `-1` (`ZenBpmMultiInstanceTest#twoActivationsOfDifferentSizesHaveNoTotal`). The
variables `loopCounter` and `nrOfInstances` would close the gap.
[README: Multi-instance](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/blob/main/README.md#multi-instance)

### GAPS 17: No idempotent start

The engine starts a second workflow for a second start under the same business key
(`ZenBpmRestClientContractIT#twoStartsUnderOneBusinessKeyCreateTwoInstances`). So before VanillaBP
sends a start again, the adapter counts only the workflows created from the moment the start was
planned (`ZenBpmProbesIT#theRedispatchProbeCountsFromThePlannedMoment`); a workflow may stay
invisible to that question for `workflow-visibility-timeout`, and a start sent again after its
answer was lost may still start a second workflow. A start by a message sent again is found only
where its marker already ran or under `message-start-lookup: scan` (GAPS 1). A business key the
engine keeps unique would close the gap.
[README: The probes](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/blob/main/README.md#the-probes)

### GAPS 18: One process per file

A file holding an executable process and any other process is refused, naming every process, because
the engine would read them as one (`ZenBpmModelReaderTest#aSecondProcessIsRefused`). Nothing
is planned in the engine.
[README: Deployment](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/blob/main/README.md#deployment)

### GAPS 19: An instantiating receive task starts a workflow nobody built an aggregate for

A receive task which starts an instance is refused in a process a workflow service claims
(`ZenBpmUnsupportedConstructsTest#severalOffencesAreNamedInOneMessage`); a process nobody claims belongs to
somebody else and is deployed as it was modelled.
[README: Deployment](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/blob/main/README.md#deployment)

### GAPS 20: A read may lag behind

A node of the engine answers a read from its own database, which may lag behind, so a workflow
started a moment ago may not be found yet. VanillaBP asks again within `workflow-visibility-timeout`
(`PT2S`) for a workflow it has a reason to believe exists. A task has no such window: a job asked
about a moment after it was created may be reported gone
(`ZenBpmTaskProbesTest#anUnknownJobIsUnknown`). Reads at a consistency the caller
asks for would close the gap.
[README: The probes](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/blob/main/README.md#the-probes)

### GAPS 21: No promise that the API stays stable

The engine's API changes between versions, so the adapter is tested against one engine build and
supports that one ([Home](Home#the-engine-this-adapter-is-tested-against), `EngineUnderTestIT`).
[README: Supported engine version](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/blob/main/README.md#supported-engine-version)

### GAPS 22: A job names no root instance

A job names the instance it waits in, such as an embedded sub-process or an iteration, not the root
of its workflow. So the adapter walks up to the root, one read for every instance on the way it does
not remember yet (`ZenBpmInstanceCacheTest#theWorkflowIdIsTheRootKey`). The key of the root on the
job would close the gap.
[README: The workflow id](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/blob/main/README.md#the-workflow-id)

### GAPS 23: One subscription per message name and correlation key

The engine holds one waiting subscription per message name and correlation key, so where two waits of
one workflow for the same message overlap, in parallel branches or a parallel multi-instance body,
the second cannot subscribe under the aggregate's id. The start does not find this; model a
correlation key of your own there. Start messages of one name share one subscription as well, see
GAPS 30.
[README: Messages](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/blob/main/README.md#messages)

### GAPS 24: Nothing tells who deployed a definition

The engine does not record which application deployed a definition, so the start cannot warn about an
identifier another application on the same engine uses. The processes the files of your application
declare are handed to VanillaBP's check for colliding identifiers, so two workflow modules of one
application sharing one are found (`ZenBpmDeploymentServiceTest#deployingChecksForCollidingProcessIdsFirst`).
Under `use-prefix` the module's id keeps them apart anyway.
[README: Deployment](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/blob/main/README.md#deployment)

### GAPS 25: A job without a type is accepted

An element which creates a job and names no task definition would get a job of the type `''`, which
nothing takes, so it is refused in a claimed process unless the task is marked
`implemented-externally` (`ZenBpmDeploymentWiringTest#anElementCreatingAJobOfNoTypeIsRefused`).
[README: Deployment](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/blob/main/README.md#deployment)

### GAPS 26: Variables larger than 4 MiB cannot be sent

A workflow whose shared values are larger than 4 MiB cannot be started, and a task whose values are
cannot be completed: the engine answers with a technical failure. A start, and a task your application
completes later, go through the outbox, which repeats them until it blocks the entry
(`ZenBpmErrorsIT#aStartLargerThanTheEnginePassesOnIsRepeatable`). The answer of a `@WorkflowTask`
method does not: the job is left to its lock, and the engine hands it out again
(`ZenBpmJobHandlerTest#anUpdateOrAFailureRefusedForGoodLeavesTheJobToItsLock`). Nothing measures the
size beforehand, because a limit in the adapter would refuse what a fixed engine takes.
[README: Which phase-two failures are repeated](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/blob/main/README.md#which-phase-two-failures-are-repeated)

### GAPS 27: A decision's result needs an output mapping

A business rule task hands the result of its decision to the process only through an output mapping,
where Camunda 8 sets its `resultVariable`. Map it, for example
`<zenbpm:output source="=rating" target="rating" />`, and where the decision is a table whose one
output has a name, map that output out of the result, see
[GAPS 34](#gaps-34-a-decision-with-one-named-output-hands-a-context). The adapter does not find a
task without a mapping while the application starts.
[README: Deployment](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/blob/main/README.md#deployment)

### GAPS 28: A locked job holds a slot

The engine counts every locked job against the cap of its type, a task left open for its asynchronous
completion included, so the adapter raises the cap by every task it keeps open
(`ZenBpmParkedTasksIT#aParkedTaskLeavesRoomForTheNextOfItsType`). Past the engine's ceiling,
`JOB_MANAGER_MAX_ACTIVE_JOBS_CAP` (1000 by default), a new job of the type waits until a lock lapses,
which a WARN names once (`ZenBpmJobStreamTest#aCapAboveTheEnginesCeilingIsWarnedAboutOnce`).
[README: Tasks left open](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/blob/main/README.md#tasks-left-open)

### GAPS 29: A message which was received may be answered as if nothing waited

Where a workflow takes a message and then fails right behind the waiting element, on a called
process which is not deployed for example, the engine answers the publication as if nothing waited,
so VanillaBP publishes it again as for GAPS 5, and it is received a second time where the workflow
waits for it again within the window
(`ZenBpmRestClientContractIT#aMessageWhichWasReceivedIsA404WhereTheWorkflowFailsBehindIt`).
[README: Messages](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/blob/main/README.md#messages)

### GAPS 30: Start messages of one name at the same moment lose starts

The engine answers some of several start messages of one name which reach it at the same moment as if
they started a workflow, and starts none for them. Each node of your application publishes the start
messages of one name one after the other, so twenty at once each start their workflow
(`ZenBpmProcessServiceIT#startsByOneMessageAtOnceEachStartTheirWorkflow`); several nodes still publish
at the same moment, and such a lost start is lost unseen.
[README: Starting a workflow by a message](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/blob/main/README.md#starting-a-workflow-by-a-message)

### GAPS 31: An iteration reads only what its element maps in

A gateway inside a multi-instance body decides on a value of the workflow only where the
multi-instance element maps it in (`zenbpm:input`). A push of the workflow writes every iteration
which runs (`ZenBpmAggregateChangedIT#aGlobalPushReachesTheGatewayOfEveryIteration`); an iteration
which starts later reads the pushed value only through such a mapping.
[README: Pushing a changed aggregate](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/blob/main/README.md#pushing-a-changed-aggregate)

### GAPS 32: A version tag names one version

The engine refuses a changed model under a tag an earlier version carries, so give every changed model
a new tag, also where only your methods changed, because the adapter writes markers and mappings into
the model it deploys (`ZenBpmProcessVersionIT#aTagAnotherVersionCarriesIsRefused`). A model deployed
again with the tag and the content of an older version gets that older version back, while new
workflows would start on the newest one, so that start ends naming both versions and the fix
(`ZenBpmProcessVersionIT#aRolledBackTaggedModelEndsTheStart`).
[README: Versions of a process](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/blob/main/README.md#versions-of-a-process)

### GAPS 33: The history keeps no state of an element

The viewer reports no element as cancelled, which Camunda 8 does, because the engine keeps none, and
the end of a workflow is the latest end of its elements
(`ZenBpmWorkflowViewerTest#aMultiInstanceTaskAloneAndACancelledWorkflow`; the engine's side:
`ZenBpmRestClientContractIT#aTerminatedElementEndsAsACompletedOneDoes`).
[README: Viewing workflows](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/blob/main/README.md#viewing-workflows)

### GAPS 34: A decision with one named output hands a context

A decision table whose one output has a name, such as `<output name="approval" />`, hands its
business rule task a context holding that output, `{"approval": "APPROVED"}`, where Camunda 8 hands
the value `"APPROVED"`; a table whose one output has no name hands the value itself
(`ZenBpmRestClientContractIT#aDecisionTableWithOneNamedOutputHandsAContext`). So the output mapping
a business rule task needs ([GAPS 27](#gaps-27-a-decisions-result-needs-an-output-mapping)) reads
the output out of the result: `<zenbpm:output source="=approval.approval" target="approval" />`.
The adapter does not detect a mapping which takes the whole context.
[README: Deployment](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/blob/main/README.md#deployment)

<!-- /gaps -->

### Not a gap of the engine: what an expression reads

An expression in the model which reads an attribute the aggregate does not share is not found while
the application starts; it fails while the workflow runs. As on Camunda 8, the adapter does not tell
VanillaBP which attributes its expressions read.

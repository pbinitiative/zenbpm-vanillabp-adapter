# VanillaBP adapter for ZenBPM

[![Checks](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/actions/workflows/checks.yaml/badge.svg?branch=main)](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/actions/workflows/checks.yaml)
[![MIT License](https://img.shields.io/badge/License-MIT-blue.svg)](./LICENSE)
[![Coverage Spring Boot](https://img.shields.io/badge/dynamic/regex?url=https%3A%2F%2Fpbinitiative.github.io%2Fzenbpm-vanillabp-adapter%2Fspring-boot-report%2Findex.html&search=Total%3C%2Ftd%3E%3Ctd%20class%3D%22bar%22%3E%5B%5E%3C%5D%2A%3C%2Ftd%3E%3Ctd%20class%3D%22ctr2%22%3E%28%5B%5E%3C%5D%2B%29%3C&replace=%241&label=Coverage%20Spring%20Boot&color=green&cacheSeconds=60)](https://pbinitiative.github.io/zenbpm-vanillabp-adapter/spring-boot-report/index.html)
[![Coverage Quarkus](https://img.shields.io/badge/dynamic/regex?url=https%3A%2F%2Fpbinitiative.github.io%2Fzenbpm-vanillabp-adapter%2Fquarkus-report%2Findex.html&search=Total%3C%2Ftd%3E%3Ctd%20class%3D%22bar%22%3E%5B%5E%3C%5D%2A%3C%2Ftd%3E%3Ctd%20class%3D%22ctr2%22%3E%28%5B%5E%3C%5D%2B%29%3C&replace=%241&label=Coverage%20Quarkus&color=green&cacheSeconds=60)](https://pbinitiative.github.io/zenbpm-vanillabp-adapter/quarkus-report/index.html)

This is the [VanillaBP](https://www.vanillabp.io) adapter for the
[ZenBPM](https://github.com/pbinitiative/zenbpm) engine (VanillaBP Version 2). It lets a VanillaBP
business application run its workflows on a ZenBPM engine without the business code depending on
the ZenBPM API. It is owned by the ZenBPM maintainers at pbinitiative and follows the conventions
of the VanillaBP adapters.

This `README.md` is aimed at contributors. The user documentation is not written yet: it goes into
the [wiki](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/wiki) as the features land, and
until then the wiki holds a placeholder page only. The VanillaBP concepts the adapter builds on are
documented in the [VanillaBP wiki](https://github.com/vanillabp/adapter-platform-integration/wiki).

## Status

**Under construction.** On Spring Boot and on Quarkus the adapter registers one process service and
one deployment service per adapter id of type `zenbpm`, validates the configuration of each id
while the application starts and answers the platform's health check. The smoke tests in
`smoke-test` boot it on Spring Boot with no, one and two adapter ids, the extension tests in
`quarkus/deployment` on Quarkus with one and two, and with none configured at all, where the
platform derives the id `zenbpm`. An application on Quarkus which configures adapter ids of another
type only is refused by the platform, which asks for an id of every adapter type on the classpath.
Two adapter ids on one `rest-address` boot only where their `name-clash-avoidance` at the adapter
level differs, the setup of a migration from one mode to the other; with the same mode they end the
start, naming the keys which tell them apart. The address is compared as configured, so two
addresses of one engine (an alias, a proxy, two nodes of one cluster) are not recognised as one.
The adapter reads the BPMN files of a workflow module, also for an adapter id without an engine, and
refuses a file holding an executable process and any other process, because the engine would read
them as one, and a file which uses what ZenBPM cannot run or would run differently from the model,
as [`GAPS.md`](./GAPS.md) lists. ZenBPM has no isolation of its own, so the name-clash-avoidance
mode `by-adapter`, the default where nothing is configured, ends the start of a workflow module with
BPMN files, naming `use-prefix` and `none` (decision 24 in [`DECISIONS.md`](./DECISIONS.md)); under
`use-prefix` the identifiers of the BPMN and DMN files are prefixed with the workflow module id, and
a business rule task whose decision ZenBPM would then not find ends the start, and `none` warns
unless `accept-unscoped-identifiers` says that the identifiers are unique. Two
processes which would reach the engine under one id end the start. Every message a claimed workflow
waits for and which carries no correlation key of the modeller's gets the aggregate id as its key,
carried into the multi-instance bodies around the elements which wait for it, and a message which
both starts a workflow of the module and is awaited by a claimed one ends the start, because ZenBPM
would start a workflow with a correlation which finds none waiting. A job inside a multi-instance
body carries only what input mappings give it, so the adapter maps the names the methods serving it
read (theirs and the extensions'), and the aggregate id, into the body; a job anywhere else gets the
variables of its scope from the engine. The processes a workflow service claims are wired with
the platform: a task nothing serves ends the start, and so does an element which creates a job
and names no task definition, which ZenBPM would give a job of the type `''` that nothing takes,
unless the application marks the task `implemented-externally`; the start events, the start
messages, the expressions the modeller wrote and the elements which give a workflow a second token
are reported, a method which wants the item of an iteration whose element names none ends the
start, and a process called on the same aggregate from inside a multi-instance body gets the
aggregate id carried into that body. Then the files are deployed, one request per file and the
DMN files first, once the engine answers ready; a file the pipeline left as it was written reaches
the engine as its bytes, and a file the engine refuses ends the start with the engine's reason. The
files sent before it stay deployed, because ZenBPM takes one file per request, but no version is
reported then; once the engine took every file, the version of every process is reported to the
platform, also where the engine kept an unchanged file. An adapter id without clients ends the
start there with the message of its validation, which names the key to set. Starting a workflow
module subscribes the job types of its claimed processes on the job stream, each with the
`job-timeout` of its tasks as the lock (set per adapter id, workflow module, workflow or task,
`PT5M` by default) and the number of handler slots as the cap of active jobs, and stopping it
unsubscribes them before the clients close; a type two modules share stays subscribed until both
stopped, and two tasks of one type with different `job-timeout`s end the start, also where two
modules share the type. A workflow is started: phase one asks the engine nothing, and phase two
starts the latest version of the process under the id the engine knows it by, with the aggregate's
id as the business key and as a text variable after the values the aggregate shares, sends
`history-time-to-live` in whole seconds where a level sets it, and reports the key and the version
of the new instance. The instance is created only once the caller's transaction committed,
through the platform's phase-two outbox, and a start whose transaction rolls back creates none. A
`history-time-to-live` with a fraction of a second is refused while the application starts, and a
start of a process the engine does not hold is not repeated (decisions 30 and 31 in
[`DECISIONS.md`](./DECISIONS.md)). The adapter tells the platform what the engine does with the type
of a value which travels: a whole number above 2^53, a `BigDecimal`, a character and a text of
another type than `String` come back changed. The probe which asks for the workflow of an aggregate
first asks the engine for the instance whose key the platform holds, which may only make an `ACTIVE`
answer shorter (decision 25), and otherwise searches the aggregate's business key in each process
of the scope; a search which fails answers that the engine is unavailable, never that it holds
nothing. The probe asked before a start is sent again counts only the workflows created from the
millisecond the start was planned in, and a workflow may stay invisible to the search for
`workflow-visibility-timeout`. The workflow id of an instance is the key of its root instance, or
of the instance a call activity started (decision 28). The probes which ask for a task and for a
user task read the job by its key and the process of its instance, and answer for the scope they
are asked about only; a task id of a partition the engine does not have is unknown to it (decision
32), and so is the job of a user task to the probe of a task, and the job of any other element to
the probe of a user task (decision 51). The open tasks of a process are counted from the engine's statistics
of every version it holds. A job of a subscribed type runs its `@WorkflowTask` method on a handler
slot, under a lock which is renewed while the method runs; then the values the aggregate shares and
its id are written into the job's instance and every instance above it up to the workflow's root,
and the job is completed (decision 35). A job whose instance names no aggregate, and one of a
workflow whose aggregate this application does not hold, are failed without retries, so an incident
shows them (decision 33). A BPMN error writes the same values and then fails the job with the
error code as the deployed model carries it, so an error boundary event or an error event
sub-process of the model takes over, and where none catches the code the engine raises an incident
on the task, which a WARN names. A task left open for its asynchronous completion keeps its job
locked for `async-task-lock-renewal` (`PT1H` by default; one not shorter than
`vanillabp.delivery.retention` ends the start), after which the engine hands the job out again and
it is locked once more without running the method again. The engine counts a locked job against
its type's cap of active jobs, so the adapter raises the cap by every task it keeps open, and the
next task of the type still arrives (decision 37). With `async-task-max-age-action: incident` a
task open longer than `vanillabp.delivery.max-task-age` is failed without retries instead, so an
incident names it. The application completes such a task later, or cancels it with a BPMN error,
through its process service: right before its transaction commits, the adapter asks the engine
whether the task still waits and belongs to the workflow module; a task which ended, or which the
engine does not know within `workflow-visibility-timeout`, throws the platform's
`TaskNotFoundException`, and a task of another module or one which carries an incident rolls the
transaction back with an `IllegalStateException` naming the reason. After the commit the shared
values are written as for a completed task, and the job is completed, or failed with the error code
as the deployed model carries it, so an error boundary event takes over; a task which ended
meanwhile costs a WARN and nothing else, and one which meanwhile carries an incident is repeated by
the outbox until the incident is resolved. The engine takes both from any node of the application,
without the delivery which left the task open (decision 40). A process id a workflow service
declares without bringing a model for it, such as the old id of a renamed process, keeps its
workflows served: the methods' task definitions are scoped by that id, and each one a model the
engine holds under the id gives to a task is subscribed and routed to the module; one a held model
gives to a user task, in any version, is not, because the engine hands a user task out as a job of
the same kind, but is looked for like the user tasks of a deployed process, and the start's line
names it, as it names a method wired by element id, whose workflows stand still (decision 41). A method which throws is failed at the engine without an error code, so the
engine spends one attempt of the task, hands the job out again after the backoff, and after the
last attempt raises an incident which names the attempts, the task, the aggregate and the
exception (decision 27); a failure sent again for the same delivery counts once. How many attempts
a task has is its model's `zenbpm:taskDefinition retries`. A model without it gets the engine's
`JOBS_DEFAULT_RETRIES`, one attempt by default, so the first exception raises an incident: set
`retries` in the model, or `JOBS_DEFAULT_RETRIES=3` at the engine for the default of Camunda 8
(decision 38). The backoff is `retry-backoff` (set per adapter id, workflow module, workflow or
task, in whole seconds) where a level sets it; where none does, the failure carries none and the
engine decides. Once the outcome of a delivery reached the engine, the
platform asks the adapter about the other tasks it believes open in the same workflow: a task the
engine took away, as an interrupting boundary event does, is reported to the application as
`CANCELED`, once; only a task served by a job of the job stream is asked about, and an engine which
cannot be asked or does not know the job, or a job of another workflow, reports nothing.
A user task is a job as well, which nothing pushes, so the job stream never subscribes its type: the
adapter looks for the user tasks a method serves, by its task definition or its element id, every
`user-task-poll-interval` (`PT5S` by default; `PT0S` switches the looks off, and the start warns
about it and about an interval below a second), and tells the application once per node that a user
task was created, when its job is listed active, and that it was cancelled, when its job is listed
terminated, as an interrupting boundary event or a cancelled instance leaves it; a user task which
came and went between two looks is told as cancelled only, and a user task marked
`implemented-externally` is never looked for (decision 12). The start names the job types and the
interval. A notification carries the job key as the task id and the kind `USER_TASK`, and after a
restart the platform answers from its record what it was told before. Each look reads every page of
the active and the terminated jobs of each type, because the engine filters jobs by neither time nor
key, so it reads more the more terminated user tasks the engine keeps: set `history-time-to-live`,
but long enough, because a cancellation is told only while the engine still lists the terminated
job, and one whose job the engine deleted with its instance's history before a look reached it,
while a node was down for example, is never told. A look starts one interval after the last one
ended, so a notification is told one interval and the time of a look late, and later where every
handler slot is taken or the engine does not answer. One which threw is told again by the next look,
and after each further failure by a look which waits twice as many looks, at most 64, so it cannot
take the handler slots from the others; its failure is logged at WARN, and a failure right after
another at DEBUG. A user task whose workflow module starts after the first look is told once it
started. A user task told as cancelled is not told as created afterwards by the same node, even
where a lagging node of the engine lists it active again. A cancelled user task wakes its workflow
up, so a service task left open in a cancelled instance is told as cancelled as well. The
application completes a user task, or cancels it with a BPMN error, through its process service as
it does a task left open: right before its transaction commits, the adapter asks the engine whether
the user task still waits and belongs to the workflow module, and after the commit the shared values
are written up to the workflow's root and the job is completed, so the gateway behind the user task
decides on them, or failed with the error code as the deployed model carries it, so an error
boundary event of the user task takes over (`ZenBpmUserTaskIT#cancelUserTaskRoutesTheBoundaryEvent`
on Spring Boot, `ZenBpmWorkflowLifecycleTest#userTaskCancellationByError` on Quarkus). The Camunda
8 adapter cannot do the second: no cluster up to 8.9 offers a command to cancel a Camunda-managed
user task by BPMN error. The application is
then told once that the user task was cancelled, as the Camunda 7 adapter tells it (decision 50); a
completed user task is not told as cancelled. The commands of a task find no user task, and those
of a user task no task: the call throws the platform's `TaskNotFoundException`, which names the
method meant for the id where the platform recorded the delivery of the task (decision 51). A shutdown
unsubscribes the job types of each workflow module and then waits for the handlers which still run,
those of every module of an adapter id together, at most `shutdown-grace` (`PT20S` by default;
above thirty seconds the start warns that the runtime's shutdown budget has to grow with it), and
names each handler it gave up on with its job, task and aggregate. While a module stops, an outcome
a handler reached is still reported, but a handler which threw is not failed at the engine, so its
job keeps its retries; the job stream closes last, which releases every lock the application held,
so another node gets those jobs at once instead of after their `job-timeout` (decision 39). The
engine can hand a job out again: when its lock was lost, when its partition got a new leader, which
knows no locks (see [`GAPS.md`](./GAPS.md)), or after the application restarted. Where an earlier
delivery committed, the method does not run again: the platform answers the delivery from its
record of deliveries, and the adapter sends the recorded outcome to the engine. Two deliveries which
run at the same time both run; the adapter names the lock it could not keep, and the platform names
the second run.

A claimed process which starts a workflow of its own gets a start marker behind every start event of
its own: a service task whose job tells the platform that a workflow started (decisions 26 and 44 in
[`DECISIONS.md`](./DECISIONS.md)). A workflow a timer started, or one somebody started past
VanillaBP without a business key, gets the aggregate the application's `@WorkflowStartedByBpms`
method builds and names. Its id is written into the instance before the workflow goes on, and the
workflow is found later through the instance key of its start
(`ZenBpmLifecycleIT#aWorkflowStartedPastVanillaBpGetsAnAggregate`, on Quarkus
`ZenBpmWorkflowLifecycleTest#theEngineStartsAWorkflowOnItsOwn`). A second delivery of the marker reads
that id from the instance, because the engine hands a job out with the variables it was created
with, and builds nothing; it builds a second aggregate only where a node which has not seen the
update yet answers that read (decision 46). A workflow the application started itself passes its
marker at the cost of one job and one transaction, without an update of its variables. A start the
platform refuses, one under a business key no aggregate carries for example, fails the marker's job
like a handler which threw. A call activity which starts a process of the same workflow module on an
aggregate of its own starts it without the caller's business key and id, so the called process's own
method builds its aggregate (decision 45). A call activity is left as the modeller wrote it where
they wrote a `zenbpm:in` of their own or a mapping of the called process's id variable, and where
the called process belongs to another workflow module or application, which the boot cannot judge:
there the model hands on whatever name the called workflow is to start with.

A claimed process whose end the application listens to, through a `@WorkflowEnded` method or
`vanillabp.delivery.release-on-workflow-end`, gets an end marker in front of every none, message and
terminate end event of its own (decision 52). Its job reports the end with the end event's id
(`ZenBpmLifecycleIT#theEndOfAWorkflowIsReportedWithItsEndEvent`, on Quarkus
`ZenBpmWorkflowLifecycleTest#theWorkflowEndIsReported`), after the platform told every task
it believed open in the workflow as cancelled, a task inside an embedded sub-process included, and
the instance ends only once the report committed (`#theEndCancelsTheTasksStillOpen`). An end event
which ends one branch while another branch of the workflow still runs reports nothing, and the end
the last branch reaches is reported (`#aBranchWhichEndsWhileAnotherRunsReportsNothing`); an end
reached while a non-interrupting event sub-process still runs is reported once that sub-process
ended, up to one `job-timeout` of `vanillabp__ended` later, because the marker's job is left to its
lock until then (`#theEndWaitsForAnEventSubProcessStillRunning`). A workflow cancelled at the engine
reports nothing (`#aCancelledWorkflowReportsNothing`), and neither does one which ends inside an
interrupting event sub-process. An error end event gets no marker: where nothing catches the error,
the engine raises an incident there and the workflow does not end
(`ZenBpmMarkerTasksIT#anUncaughtErrorEndEventRaisesAnIncident`). A `@WorkflowEnded` method which
throws fails the marker's job like a handler which threw, so the engine hands it out again after the
`retry-backoff` where the job has retries left (`#aReportWhichThrewIsRepeated`), and a workflow
whose aggregate the application deleted ends without a report
(`#aWorkflowWhoseAggregateIsGoneEnds`). A user task the end took away is told as cancelled once, by
that report, and the looks for user tasks tell it under the same id, so the platform answers them
from its record, also after a restart (decision 53,
`ZenBpmUserTaskRestartIT#aUserTaskTheEndTookAwayIsToldOnceAcrossARestart`); where the end releases
the platform's records, the next look tells it a second time
(`#aUserTaskTheEndTookAwayIsToldTwiceWhereTheEndReleasesTheRecords`). Whether an end event ends the
workflow is read from the model of the instance's own version
(`ZenBpmWorkflowEndedHandlerTest#anEndEventIsReadByTheModelOfTheInstancesVersion`), and the markers
of a version deployed while somebody listened to the end are served after nobody does, without a
report (`#anEndNobodyListensToAnyMoreIsCompletedWithoutAReport`). A report costs one job, a
transaction for the end and one for each task still open, a read of the instance's tokens and of its
child instances, and a read of the model once per version; a message end event's own task runs after
the report, and two branches whose end markers wait at the same time may report the end twice.

A task inside a multi-instance element is told the iteration it runs in, through
`@MultiInstanceElement`, `@MultiInstanceIndex` and `@MultiInstanceTotal` named after the id of the
multi-instance element (decision 54). ZenBPM hands a job neither an index nor a total (see
[`GAPS.md`](./GAPS.md)), so the adapter reads them from the instances the engine keeps of the
element, only where a method asks for one and once for all its parameters
(`ZenBpmJobHandlerTest#theIterationsAreReadOnceWhereTheMethodAsksForThem`). The element is the item
of the iteration, the index counts from 0 in the order the engine handed out the items, and the
total is the size of the collection the engine iterates over, as it evaluated it when the element
was entered, also where the aggregate's list changed since
(`ZenBpmMultiInstanceIT#aCollectionChangedWhileTheIterationsRunKeepsItsTotal`): for an iteration of
a parallel multi-instance sub-process
(`ZenBpmLifecycleIT#theIterationIsReported`) and of a sequential multi-instance task
(`#theIterationsOfASequentialTaskAreReportedOneAfterTheOther`), on Quarkus both in
`ZenBpmWorkflowLifecycleTest#multiInstanceBindsElementIndexAndTotal`. A collection written as an
expression is counted as well (`ZenBpmMultiInstanceIT#aCollectionWrittenAsAnExpressionIsCounted`).
Where two activations of the same element wait in one instance at once over collections of
different sizes, the total is `-1`, as Camunda 8 reports a total it cannot tell
(`ZenBpmMultiInstanceTest#twoActivationsOfDifferentSizesHaveNoTotal`). Nested iterations are told
the outermost first (`#nestedIterationsAreToldOutermostFirst`). A task of a process which an
iteration of a multi-instance call activity started is told the caller's iteration as well
(`ZenBpmLifecycleIT#aCalledProcessIsToldTheIterationOfItsCaller`); where the caller's model calls
the process from two call activities, from the one the caller waits at
(`ZenBpmMultiInstanceTest#aCallerWithTwoCallsOfTheProcessIsToldTheOneItsBodyWaitsAt`), and where it
waits at neither, the caller's iterations are not told, which a WARN says once
(`#aCallerWithTwoCallsOfTheProcessWhichWaitsAtNoneIsNotTold`). A user task is told its
iteration as a job is (`ZenBpmUserTaskPollerTest#aUserTaskInAnIterationIsToldItsIteration`). The
reads cost the model once per version, the instance the element stands in and its history once per
body (again at the next ask where the total could not be read,
`ZenBpmMultiInstanceTest#aTotalWhichCouldNotBeReadIsReadAgain`), and the list of its iterations once per body and once more for every iteration of a sequential
element; a read which fails fails
the delivery like a method which threw
(`ZenBpmJobHandlerTest#aReadOfTheIterationsWhichFailsFailsTheJob`).

A message is correlated in two phases. Phase one never asks the engine whether
something waits. It refuses where the application calls a message which no version the engine holds
of the workflow module's processes waits for, and one which the newest version of a process starts
it with, also where an older version waits for it, naming `startWorkflowByMessage`, because ZenBPM
hands a message no workflow waits for under its key to a start event of the same name. Before it
refuses, it reads those versions from the engine again, because another node may have deployed a
newer one since; where a version could not be read, or the module serves a process it brought no
model for, it refuses nothing (decision 42). Phase two publishes the message under its scoped name,
with the values the aggregate shares and its id, and with the correlation id as its correlation key
where the application passed one, else the aggregate id. So a correlation id is the correlation key,
and the subscription a modeller writes for a message has to read it, as on Camunda 8: the key the
deployment writes reads the aggregate id. ZenBPM keeps no message nothing waits for and answers
`404` (see [`GAPS.md`](./GAPS.md)): the outbox repeats such a publication every
`workflow-visibility-timeout` without counting an attempt, until the workflow waits for it, or until
`vanillabp.outbox.wait-for-visibility-at-most` passed and the entry is blocked; a workflow which
passed the waiting element, or waits under another key, is answered the same way. ZenBPM answers the
same `404` where the message was received and the workflow failed right behind the waiting element
on something it does not find, such as a called process which is not deployed, so that publication
is repeated as well, and is received a second time where the workflow waits for it again within the
window (decision 43). ZenBPM deduplicates no message: a second correlation with the same correlation
id is not planned while the first still waits in the outbox, a correlation without one is never
deduplicated, and a publication repeated after its answer was lost may correlate twice.

A workflow is started by a message in two phases as well, and how it is found afterwards is
`message-start-lookup`. ZenBPM gives a workflow it starts by a message no business key and names no
instance in its answer (see [`GAPS.md`](./GAPS.md)). Unless `refuse` is set, phase one refuses
where the application calls a message the newest version of no process of the workflow module
starts with, read again from the engine before it refuses, naming `correlateMessage` where a model
waits for the name; phase two publishes the message under its scoped name without a correlation key,
with the values the aggregate shares and its id, and reports nothing. Where the modeller maps the
outputs of a message start event, the deployment adds an output of the id variable, because the
engine hands the instance the mapped variables only (decision 48). Under `start-row`, the default, the start
marker behind the message start event reports the instance's key, which the platform writes into the
start row of the aggregate, and every later operation on the running workflow finds it through that
key. The start row does not cover an application without a delivery log, the moments between the
publication and the commit of the marker's report, in which only the node which published knows the
workflow, or a start sent again after its answer was lost before the marker wrote the row, which
publishes once more and may start a second workflow. Nor does it cover a workflow which ended: the
key finds a running instance only, so where the platform asks which adapter holds a workflow a
message started and which ended, it is answered as unknown to the engine, in every mode, which the
platform's contract for adapters does not allow. How long the row lives is
`vanillabp.delivery.workflow-start-retention` (`P30D` by default, `0` keeps rows for good) or
`vanillabp.delivery.keep-workflow-start-while-aggregate-exists`. Under `scan`, an opt-in, the probes
in addition read the instances of the processes a version of which has a message start event where
nothing else finds the workflow, and find a running one by its id variable, also before a start is
sent again, at most `message-start-lookup-max-instances` instances (`1000` by default), beyond which
they answer that the engine is unavailable. A workflow which ended stays unknown to them as well, and
the start of the application warns so. The engine hands out the running instances page by page, so a
lookup over several pages may miss a workflow while other instances of its process end, and then
answers as `start-row` does. Under
`refuse` every start by a message is refused where the application calls, naming the key. Each start
of the application names the processes a message starts and the value in force. ZenBPM loses starts
when two start messages of one name reach it at the same moment, answering one of them as if it
started a workflow, so each node publishes the start messages of one name one after the other;
several nodes of an application may still lose such a start unseen. A start message no start event
takes is answered `404`, also where another node took the start event at that moment, so it is
repeated with the outbox's backoff and blocked after `vanillabp.outbox.block-after-attempts`
(decision 47).

A changed aggregate is pushed in phase two only. A push of the workflow writes the values the
aggregate shares, and its id, into the workflow's root and then into every instance which runs below
it, at any depth, because ZenBPM gives an embedded sub-process, a multi-instance body and each of
its iterations, and a called process a copy of the variables of their own, and a gateway decides on
the copy of the instance it runs in (decision 49). It does not enter a called instance of a process
which works on another aggregate, as the workflow services say, although that instance carries a
copy of the caller's id. The root is the instance the workflow started as, where the start row names
it, else the one the aggregate's business key finds, a workflow a call activity started included,
else, under `message-start-lookup: scan`, the one whose id variable names the aggregate; where none
runs any more, the push is a warned no-op, and where the engine does not answer for the start row's
key and nothing else finds the workflow, the push is repeated later. A push into the scope of a task
writes the instance the task waits in, that of its sub-process or its iteration, and leaves the root
and every other branch as they were; the task's completion writes the values up to the root later.
Where the instance the task waits in ended, nothing is written; a task the engine does not know yet
is pushed again later. A handler which runs already, or a task handed out again, gets the variables
its job was created with all the same. An iteration of a multi-instance element which starts after a
push reads the pushed value only where the element maps it in (see [`GAPS.md`](./GAPS.md)).

A `@WorkflowTask(version = ...)` method runs for the jobs of the versions it names. A version made of numbers is compared with the version the job carries. A
version tag, which a model carries as `<zenbpm:versionTag value="..." />` on its process (or
`zeebe:versionTag` from the Camunda Modeler), is placed by the list of the definitions the engine
holds under the process, one request per process and start, and the version this start deployed is
known from the deployment's answer (decision 55). A tag no version carries is reported once at
startup, and a task of the version this start deployed which no method serves for that version ends
the start. ZenBPM gives a tag to one version of a process only: it refuses a changed model under a
tag an earlier version carries, so a changed model needs a new tag, also where only the methods
changed, because the adapter writes into the model it deploys; and a model deployed again with the
tag and the content of an older version gets that older version back, while new workflows start on
the newest one (see [`GAPS.md`](./GAPS.md)).

What is built next, and in which order, is the implementation plan in [`docs/`](docs/README.md).

## Modules

|                Module                |                   Artifact                    |                                    Purpose                                    |
|--------------------------------------|-----------------------------------------------|-------------------------------------------------------------------------------|
| `engine-test-support`                | (not published)                               | the pinned ZenBPM engine as a Testcontainers container, for integration tests |
| `core`                               | `zenbpm-vanillabp-adapter`                    | the platform-neutral adapter: deployment, task delivery, REST and gRPC client |
| `spring-boot`                        | `zenbpm-vanillabp-adapter-spring-boot`        | Spring Boot auto-configuration                                                |
| `smoke-test`                         | (not published)                               | discovery, health and startup validation on Spring Boot without an engine     |
| `quarkus/runtime`                    | `zenbpm-vanillabp-adapter-quarkus`            | Quarkus extension, runtime part                                               |
| `quarkus/deployment`                 | `zenbpm-vanillabp-adapter-quarkus-deployment` | Quarkus extension, build-time part                                            |
| `quarkus/integration-tests`          | (not published)                               | end-to-end tests on a booted Quarkus application                              |
| `test-coverage-report/spring-boot`   | (not published)                               | aggregated coverage of the core and the Spring Boot modules                   |
| `test-coverage-report/quarkus`       | (not published)                               | aggregated coverage of the core and the Quarkus modules                       |
| `test-coverage-report/coverage-gate` | (not published)                               | breaks the build below the coverage threshold, last module of the reactor     |

All artifacts have the groupId `org.pbinitiative.zenbpm`, the Java packages start with
`org.pbinitiative.zenbpmadapter`.

## Supported engine version

The adapter is tested against exactly one ZenBPM engine build, and supports that one: tested, not
newer. Today it is a build of the engine's `main`, because no engine release carries the job lock the
adapter's task delivery is built on (decision 15 in [`DECISIONS.md`](./DECISIONS.md)). The pin
becomes a release tag once the engine tags a release containing that commit.

| Pinned |                                                 Value                                                 |
|--------|-------------------------------------------------------------------------------------------------------|
| commit | `bdd795a399b6484d062d52ac90c0d0725cb0ad91` (2026-10-13)                                               |
| image  | `ghcr.io/pbinitiative/zenbpm@sha256:d7808e30821a7349e0f42476d8aaa21ff92fc752c0ccc432771764d307b87ed0` |

The build reads both from `zenbpm.commit` and `zenbpm.image` in the root `pom.xml`. The integration
tests start that image, and `EngineUnderTestIT` fails when the running container is not that image
or the engine reports another commit.

The REST contract the adapter is written against is the copy `core/src/main/zenbpm/api.yaml`. The
job stream's contract is the copy `core/src/main/proto/zenbpm.proto`. Both come from the pinned
commit and name it, with the image, in their first lines. The table above repeats the pin as well.
`EnginePinTest` fails when one of these three names another pin than the POM.
`bin/check-engine-copies.sh` compares the two copies with the engine's files of the pinned commit,
which it reads from GitHub. Only the header and the proto's `java_package` line may differ. The
pull-request check runs it, and it fails on any other difference (decision 18 in
[`DECISIONS.md`](./DECISIONS.md)).

Moving the pin is one commit with these steps:

1. Pull `ghcr.io/pbinitiative/zenbpm:dev` and read its digest and its
   `org.opencontainers.image.revision` label.
2. Check that the revision is the head of the engine's `main`, or the newest commit which got an
   image.
3. Write the commit and the digest into `zenbpm.commit` and `zenbpm.image` of the root `pom.xml`.
4. Copy `openapi/api.yaml` and `pkg/zenclient/proto/zenbpm.proto` of exactly that commit over the
   two copies (`git show <commit>:<path>`). Keep the header of each, with the new commit, date and
   image, and keep the `java_package` line of the proto.
5. Update the table above.
6. Run `bin/check-engine-copies.sh` and `mvn install`.

## Which phase-two failures are repeated

A failed call of the engine is classified in one place, `ZenBpmErrors` in the core, so that the
phase-two outbox and the commands a job handler sends back cannot disagree about what a failure
means. It answers from the status, the method and the path of the call and never from the
engine's message, which any version of the engine may reword (decision 30 in
[`DECISIONS.md`](./DECISIONS.md)). The first exception of the REST client in the chain of causes
answers alone, so a failure wrapped by a caller is read like a plain one.

|                                Failure                                 |   Read as    |                                      Why                                      |
|------------------------------------------------------------------------|--------------|-------------------------------------------------------------------------------|
| status `400`, `413` or `415`                                           | permanent    | the request is malformed, too large or of the wrong content type              |
| status `404` from `POST /v1/process-instances`                         | permanent    | the engine holds no process of that id: nothing deployed it to this engine    |
| `NumberFormatException`                                                | permanent    | a key which is not a number never becomes one                                 |
| status `404` from a call of a job                                      | gone job     | the engine holds no such job                                                  |
| status `409` from a completion or failure of a job                     | gone job     | the job no longer takes the command: it ended, or a newer delivery decides    |
| status `404` from `POST /v1/messages`                                  | nobody waits | the engine drops a message nothing waits for                                  |
| status `409` from `POST /v1/jobs/{key}/extend-lock`                    | lock lost    | the lock lapsed or was never taken, the job ended, or another client holds it |
| status `502`, `503` or `504`                                           | unavailable  | the engine or a proxy in front of it could not pass the request               |
| `ZenBpmUnavailableException`                                           | unavailable  | no answer, a broken connection, or an answer which cannot be read             |
| `SocketTimeoutException`, `HttpTimeoutException` or `ConnectException` | unavailable  | the engine could not be reached, and no REST client wrapped it                |
| any other status, `500` included                                       | repeatable   | a technical fault of the engine may pass                                      |

`ZenBpmErrorsTest` holds every row, plain and wrapped, and fails when the table names a row it does
not test, tests a row the table does not name, names a row twice or orders the rows differently.
`ZenBpmErrorsIT` makes the pinned engine answer each status of the job, message, lock and start
rows, `400`, `413` and `502`, and checks how each is read.

Only a permanent failure is not repeatable: `ZenBpmErrors.isRepeatable` is false for the first three
rows and true for every other. The three narrower answers, a gone job, nobody waits and a lost lock,
mean something only to the caller which sent that call, so it asks for them before it asks whether
to repeat.

Where the status says less than it seems:

- The engine answers `502` also for a key of a partition it does not have, such as a key another
  engine handed out. The status alone cannot tell this apart from a node which could not be
  reached, so such a key reads as unavailable. Where it matters, the probe for a task, the key's
  partition (bits 12 to 21) is compared with the partitions `GET /system/status` reports
  (decision 32 in [`DECISIONS.md`](./DECISIONS.md)).
- A `ZenBpmUnavailableException` may name a `2xx` status: the engine did what was asked, and only
  the body of its answer was lost or could not be read. It is repeatable all the same, because a
  permanent failure would block an operation the engine carried out. Not every repetition is
  harmless, though. A read is answered again, and a completion of a completed job is answered as the
  first one was. A start is repeated only after the core asked whether its workflow is there already,
  which a wrong "unknown" turns into a second workflow. A message may be correlated twice, because
  the engine does not deduplicate messages, and a failure without a delivery token counts twice. The
  caller of such a call decides whether it accepts the duplicate.
- A `404` from `POST /v1/messages` reads as nobody waits, which a correlation hands back to the
  outbox without counting an attempt. For a start message it means that no start event took the
  message, so the start's handler throws it as an ordinary failure, which is repeated with the
  outbox's backoff and counted (decision 47 in [`DECISIONS.md`](./DECISIONS.md)).
- A `404` or `409` is read from its status, method and path alone. A proxy in front of the engine
  which answers `404` to a call of a job, for example because its routes leave out a path, is read
  as a gone job like the engine's own answer.

## Building

The build depends on two repositories of the VanillaBP project,
[`spi-for-java`](https://github.com/vanillabp/spi-for-java) and
[`adapter-platform-integration`](https://github.com/vanillabp/adapter-platform-integration). Both
are `2.0.0-SNAPSHOT`, and VanillaBP publishes their snapshots to the snapshot repository of Maven
Central, which the root POM names and which needs no login. So nothing has to be built first:

```bash
mvn install
```

`install` and not `install verify`: install runs every phase verify has, so naming both walks the
lifecycle twice. Spotless checks the formatting in every build; `mvn spotless:apply` fixes it.

The integration tests start the engine in Docker, with the image of the section above. Without a
Docker environment they are skipped, and the build stays green. The job stream's gRPC stubs are
generated from `core/src/main/proto/zenbpm.proto` in every build, into
`core/target/generated-sources/protobuf`; `protoc` and its gRPC plugin are downloaded from Maven
Central, so nothing has to be installed for it. A new module with integration tests copies
`engine-test-support/src/test/resources/logback-test.xml`, which says why it looks the way it does.

Working rules for this repository, for people and coding agents alike, are in
[`AGENTS.md`](./AGENTS.md). The decisions the code relies on are in
[`DECISIONS.md`](./DECISIONS.md), what the engine cannot do and how the adapter answers it in
[`GAPS.md`](./GAPS.md).

## Using the snapshots

Every push to `main` publishes the artifacts as `2.0.0-SNAPSHOT` to this repository's GitHub
Packages (`.github/workflows/publish-snapshots.yaml`), for example:

```xml
<dependency>
  <groupId>org.pbinitiative.zenbpm</groupId>
  <artifactId>zenbpm-vanillabp-adapter-spring-boot</artifactId>
  <version>2.0.0-SNAPSHOT</version>
</dependency>
```

The Quarkus extension is `zenbpm-vanillabp-adapter-quarkus`. GitHub Packages asks for credentials
even for a public package, and its Maven registry accepts only a classic personal access token: any
GitHub account's classic token with `read:packages` works. A consumer adds the registry and the
token to its Maven `settings.xml`:

```xml
<settings>
  <servers>
    <server>
      <id>zenbpm-vanillabp-adapter</id>
      <username>YOUR_GITHUB_USER</username>
      <password>YOUR_CLASSIC_TOKEN</password>
    </server>
  </servers>
  <profiles>
    <profile>
      <id>zenbpm-vanillabp-adapter</id>
      <repositories>
        <repository>
          <id>zenbpm-vanillabp-adapter</id>
          <url>https://maven.pkg.github.com/pbinitiative/zenbpm-vanillabp-adapter</url>
          <snapshots>
            <enabled>true</enabled>
          </snapshots>
          <releases>
            <enabled>false</enabled>
          </releases>
        </repository>
      </repositories>
    </profile>
  </profiles>
  <activeProfiles>
    <activeProfile>zenbpm-vanillabp-adapter</activeProfile>
  </activeProfiles>
</settings>
```

Releases stay switched off for this repository on purpose. A repository of the settings is asked
before Maven Central, so without it every release dependency is first looked up in GitHub Packages
and refused: measured on 2026-10-06, resolving this adapter from an empty local repository took six
minutes with 1,317 such refusals, and one minute without them.

The adapter builds on VanillaBP's own snapshots, which live in the snapshot repository of Maven
Central, `https://central.sonatype.com/repository/maven-snapshots/`. It asks for no token, and the
adapter's published POMs name it. A consumer whose build does not read the repositories of its
dependencies, one behind a mirror for example, adds it itself, with snapshots switched on and
releases switched off, the way the root `pom.xml` of this repository does.

## Contributing

Every pull request and every push to `main` runs `.github/workflows/checks.yaml`: one `mvn install`,
which is Spotless, compile, javadoc, the tests, both coverage reports and the gate. Its job `build`
is the check branch protection on `main` requires, so nothing reaches `main` without it. The same
build runs a second time on Java 21 (`build on Java 21`), the Java the published classes are
compiled for, because a dependency built for a newer Java would only fail at run time. When a red
run got as far as writing test or coverage reports, they are attached to the run as the artifact
`test-reports` (`test-reports-java-21` for the run on Java 21). A build which stops earlier, at
Spotless or the compiler, has none, and its log is the place to read.

The build reads the VanillaBP snapshots from the snapshot repository of Maven Central, which needs
no login. So a pull request from a fork builds exactly like one from this repository, and the
workflows need no secret of their own: `publish-snapshots.yaml` deploys with the workflow's
`GITHUB_TOKEN`.

## Test coverage

`mvn install` builds one aggregated JaCoCo report per platform:

1. **Spring Boot** (core + Spring Boot integration) - into `test-coverage-report/spring-boot/report`
2. **Quarkus** (core + Quarkus extension) - into `test-coverage-report/quarkus/report`

Every push to `main` publishes both to GitHub Pages, as
[`spring-boot-report`](https://pbinitiative.github.io/zenbpm-vanillabp-adapter/spring-boot-report/index.html)
and [`quarkus-report`](https://pbinitiative.github.io/zenbpm-vanillabp-adapter/quarkus-report/index.html).
The two coverage badges at the top read the instruction coverage from those pages, the number the
gate below compares. While the core compiles no code yet they show `n/a`, as the reports do.

Coverage is measured separately per platform, because a platform's tests never cover the other
platform's code.

The build breaks below the line: `test-coverage-report/coverage-gate` is the last module of the
reactor, reads both reports and fails whenever a platform is below its threshold in the root POM
(`coverage.threshold.spring-boot`, `coverage.threshold.quarkus`, in percent of covered
instructions). Both properties hold 85, the number every VanillaBP adapter gates on, and that is not
the target: the rule is 90 per platform, so a report between 85 and 90 passes the build and still
names a gap. Two more checks keep a report from being incomplete without anybody noticing. Every
module producing a `jacoco.exec` has to be read by a report. And every module with production
sources has to be read by the report of its platform, decided by where it lives (the core by both,
`spring-boot` and `smoke-test` by Spring Boot, `quarkus/` by Quarkus), because a module without
tests writes no `jacoco.exec`, and left out of its report it would empty that report instead of
failing it. All of these are `CoverageGateTest`; the failure paths of the last one are held by
`ProductionModulesTest`. The conventions every test class of this repository follows are checked by
`TestClassConventionsTest`, what the published POMs hand an application by `PublishedPomsTest`, and
that every published jar and sources jar carries `LICENSE`, `LICENSE-APACHE-2.0` and `NOTICE` by
`PublishedJarsTest`. `PublicationSafetyTest` keeps the snapshot publication honest: the deploy is
deferred until the gate, the last module, has passed, and each coverage badge's expression reads the
report this build wrote.

`bin/check-deploy-safety.sh` proves the deferred deploy itself, without GitHub: it deploys a copy of
the working tree into a local directory (exactly the jar, sources jar and POM of the core,
`spring-boot`, `quarkus` and `quarkus-deployment` and the parent's POM have to arrive, no file
missing and none more), then adds an untested method to the copy, raises both coverage thresholds
to 100 % so that this method alone turns the gate red, and checks that the red gate leaves the
directory empty. It installs nothing into the local Maven repository. Run it
after changing anything about deploying, the module order or the gate.

The gate reports what it measured on every run, green ones included. The angle brackets stand for
the numbers of the run:

```
coverage gate | Spring Boot: <percent> % instructions (<missed> of <total> missed) | at the rule of 90 %
coverage gate | Quarkus: <percent> % instructions (<missed> of <total> missed) | <gap> points below the rule of 90 %, build breaks below 85 %
```

Two kinds of run cannot be measured, and in both the gate prints a line per platform saying why and
reports its two threshold tests as skipped rather than passed. A build which stops before `verify`
never writes the reports. A report without a single instruction (what the skeleton produces today,
and what an aggregate which lost all its modules would produce) prints
`the report holds no instruction, so there is no coverage to check`.

## Licence

This repository is licensed under the [MIT License](./LICENSE).

Parts of it are copied and adapted from the VanillaBP adapters
[`camunda8-adapter`](https://github.com/vanillabp/camunda8-adapter) and
[`process-engine-api-adapter`](https://github.com/vanillabp/process-engine-api-adapter), which are
licensed under the Apache License, Version 2.0. Those files keep that licence: each carries a header
naming its origin and `SPDX-License-Identifier: Apache-2.0`, the licence text is in
[`LICENSE-APACHE-2.0`](./LICENSE-APACHE-2.0), and [`NOTICE`](./NOTICE) names both origins. Every
other file is MIT, with one exception that is still open. Decision 17 in
[`DECISIONS.md`](./DECISIONS.md) says why.

The exception: `core/src/main/zenbpm/api.yaml` and `core/src/main/proto/zenbpm.proto` are copies of
files of the ZenBPM engine, which is licensed under the AGPL-3.0-or-later and an enterprise licence.
The core jar ships classes generated from the proto, and its sources jar their Java sources. Under
which licence these copies and the generated code travel is not settled yet; [`NOTICE`](./NOTICE)
says so, and the ZenBPM maintainers decide it.

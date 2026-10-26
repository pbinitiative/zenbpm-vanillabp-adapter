# VanillaBP adapter for ZenBPM

[![Checks](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/actions/workflows/checks.yaml/badge.svg?branch=main)](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/actions/workflows/checks.yaml)
[![Nightly](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/actions/workflows/nightly.yaml/badge.svg?branch=main)](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/actions/workflows/nightly.yaml)
[![MIT License](https://img.shields.io/badge/License-MIT-blue.svg)](./LICENSE)
[![Coverage Spring Boot](https://img.shields.io/badge/dynamic/regex?url=https%3A%2F%2Fpbinitiative.github.io%2Fzenbpm-vanillabp-adapter%2Fspring-boot-report%2Findex.html&search=Total%3C%2Ftd%3E%3Ctd%20class%3D%22bar%22%3E%5B%5E%3C%5D%2A%3C%2Ftd%3E%3Ctd%20class%3D%22ctr2%22%3E%28%5B%5E%3C%5D%2B%29%3C&replace=%241&label=Coverage%20Spring%20Boot&color=green&cacheSeconds=60)](https://pbinitiative.github.io/zenbpm-vanillabp-adapter/spring-boot-report/index.html)
[![Coverage Quarkus](https://img.shields.io/badge/dynamic/regex?url=https%3A%2F%2Fpbinitiative.github.io%2Fzenbpm-vanillabp-adapter%2Fquarkus-report%2Findex.html&search=Total%3C%2Ftd%3E%3Ctd%20class%3D%22bar%22%3E%5B%5E%3C%5D%2A%3C%2Ftd%3E%3Ctd%20class%3D%22ctr2%22%3E%28%5B%5E%3C%5D%2B%29%3C&replace=%241&label=Coverage%20Quarkus&color=green&cacheSeconds=60)](https://pbinitiative.github.io/zenbpm-vanillabp-adapter/quarkus-report/index.html)

This is the [VanillaBP](https://www.vanillabp.io) adapter for the
[ZenBPM](https://github.com/pbinitiative/zenbpm) engine (VanillaBP Version 2). It lets a VanillaBP
business application run its workflows on a ZenBPM engine without the business code depending on
the ZenBPM API. It is owned by the ZenBPM maintainers at pbinitiative and follows the conventions
of the VanillaBP adapters.

This `README.md` is aimed at contributors. The user documentation is the
[wiki](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/wiki), whose pages are written in
this repository, see [The wiki](#the-wiki). The VanillaBP concepts the adapter builds on are
documented in the [VanillaBP wiki](https://github.com/vanillabp/adapter-platform-integration/wiki).

## Status

**Feature-complete against the VanillaBP 2 adapter SPI, and not released yet.** On Spring Boot and
on Quarkus the adapter deploys each workflow module's BPMN and DMN files to a ZenBPM engine, starts
workflows through VanillaBP's two-phase start, also by a message, runs `@WorkflowTask` methods for
the jobs the engine hands out over its job stream, completes and cancels the tasks and the user
tasks the application left open, tells the application about user tasks, correlates messages, pushes
a changed aggregate, reports the start and the end of a workflow, answers every probe of the
platform and the viewer/history API, and runs in a native image. Each of these is described under
[Behavior](#behavior) and [Native image](#native-image), with the tests which hold it. The snapshots
of `main` are published, see [Using the snapshots](#using-the-snapshots); no version is released.

What the engine cannot do, and how the adapter answers it, is under
[Known deviations](#known-deviations). The adapter is tested against exactly one build of the
engine, see [Supported engine version](#supported-engine-version).

## Documentation

The wiki is for users, this README for contributors:

1. [Home](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/wiki/Home): the dependencies, an
   engine to try the adapter against, the smallest configuration, what a handler may assume about
   its thread, and the engine version tested.
2. [Configuration](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/wiki/Configuration):
   every key with its levels and default, the BPMN model for ZenBPM, keeping workflow modules
   apart, the boot behavior and what an operator gets to see.
3. [Deviations](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/wiki/Deviations): one entry
   per row of [`GAPS.md`](./GAPS.md).
4. [Engine APIs](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/wiki/Engine-APIs): every
   call the adapter makes against the engine, and those it never makes.
5. [Native images](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/wiki/Native-images).
6. [Blueprints](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/wiki/Blueprints): which
   VanillaBP blueprints run on ZenBPM through the overlay in [`blueprints-overlay/`](blueprints-overlay),
   and why the others do not.
7. [Examples](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/wiki/Examples): the example
   applications of [`examples/`](examples), each a business story on Spring Boot and on Quarkus.

A change of behaviour changes the page which describes it in the same pull request. The analysis,
the architecture and the implementation plan the adapter was built from are in
[`docs/`](docs/README.md).

## Supported engine version

The adapter is tested against exactly one ZenBPM engine build, and supports that one: tested, not
newer. Today it is a build of the engine's `main`: it was pinned while no engine release carried the
job lock the adapter's task delivery is built on (decision 15 in [`DECISIONS.md`](./DECISIONS.md)).
The engine's release `v1.9.0` of 2026-10-17 contains the pinned commit. Moving the pin to an engine
release is the maintainers' step before the adapter's first release, because a release refuses an
engine pin which is no engine release, see [Releasing](#releasing).

| Pinned |                                                 Value                                                 |
|--------|-------------------------------------------------------------------------------------------------------|
| commit | `bdd795a399b6484d062d52ac90c0d0725cb0ad91` (2026-10-13)                                               |
| image  | `ghcr.io/pbinitiative/zenbpm@sha256:d7808e30821a7349e0f42476d8aaa21ff92fc752c0ccc432771764d307b87ed0` |

The build reads both from `zenbpm.commit` and `zenbpm.image` in the root `pom.xml`. The integration
tests start that image, and `EngineUnderTestIT` fails when the running container is not that image
or the engine reports another commit.

The REST contract the adapter is written against is the copy `core/src/main/zenbpm/api.yaml`. The
job stream's contract is the copy `core/src/main/proto/zenbpm.proto`. Both come from the pinned
commit and name it, with the image, in their first lines. The table above repeats the pin as well,
and so does the wiki's page `Home`. `EnginePinTest` fails when one of them names another pin than
the POM. `bin/check-engine-copies.sh` compares the two copies with the engine's files of the pinned
commit, which it reads from GitHub. Only the header and the proto's `java_package` line may differ.
The pull-request check runs it, and it fails on any other difference (decision 18 in
[`DECISIONS.md`](./DECISIONS.md)).

Moving the pin is one commit with these steps:

1. Pull `ghcr.io/pbinitiative/zenbpm:dev` and read its digest and its
   `org.opencontainers.image.revision` label; `bin/resolve-latest-engine.sh` does both and prints
   them.
2. Check that the revision is the head of the engine's `main`, or the newest commit which got an
   image.
3. Write the commit and the digest into `zenbpm.commit` and `zenbpm.image` of the root `pom.xml`.
4. Copy `openapi/api.yaml` and `pkg/zenclient/proto/zenbpm.proto` of exactly that commit over the
   two copies (`git show <commit>:<path>`). Keep the header of each, with the new commit, date and
   image, and keep the `java_package` line of the proto.
5. Update the table above, and the commit and the image on the wiki's page `wiki/Home.md`.
6. Run `bin/check-engine-copies.sh` and `mvn install`.

A pin which is an engine release takes the release's tag instead of `ghcr.io/pbinitiative/zenbpm:dev`
in step 1, and names it in a third property, `zenbpm.version` (for example `v1.9.0`), next to the
commit the tag points at and the digest of `ghcr.io/pbinitiative/zenbpm:<tag>`. The image stays
pinned by its digest, which the tests ask for. `bin/release.sh engine` checks that the three belong
together, against GitHub and the engine's registry (decision 66 in [`DECISIONS.md`](./DECISIONS.md)).

## Dependencies

All artifacts have the groupId `org.pbinitiative.zenbpm`. Until the first release they are
`2.0.0-SNAPSHOT`, published as the section [Using the snapshots](#using-the-snapshots) says.

On Spring Boot an application adds `zenbpm-vanillabp-adapter-spring-boot`, which brings the
platform-neutral core and VanillaBP's Spring Boot integration with it
(`DocumentationTest#theSpringBootDependencyBringsThePlatform`). On Quarkus it adds the extension
`zenbpm-vanillabp-adapter-quarkus` beside VanillaBP's `io.vanillabp:vanillabp-quarkus-integration`.
The wiki's page `Home` shows both.

## Modules

|                Module                |                   Artifact                    |                                    Purpose                                    |
|--------------------------------------|-----------------------------------------------|-------------------------------------------------------------------------------|
| `engine-test-support`                | (not published)                               | the pinned ZenBPM engine as a Testcontainers container, for integration tests |
| `core`                               | `zenbpm-vanillabp-adapter`                    | the platform-neutral adapter: deployment, task delivery, REST and gRPC client |
| `spring-boot`                        | `zenbpm-vanillabp-adapter-spring-boot`        | Spring Boot auto-configuration                                                |
| `smoke-test`                         | (not published)                               | discovery, health and startup validation on Spring Boot without an engine     |
| `election-integration-test`          | (not published)                               | the BPMS election on Spring Boot: two adapter ids on one engine, the double   |
| `quarkus/runtime`                    | `zenbpm-vanillabp-adapter-quarkus`            | Quarkus extension, runtime part                                               |
| `quarkus/deployment`                 | `zenbpm-vanillabp-adapter-quarkus-deployment` | Quarkus extension, build-time part                                            |
| `quarkus/integration-tests`          | (not published)                               | end-to-end tests on a booted Quarkus application                              |
| `quarkus/native-image-tests`         | (not published)                               | an application built as a native image, run against the pinned engine         |
| `test-coverage-report/spring-boot`   | (not published)                               | aggregated coverage of the core and the Spring Boot modules                   |
| `test-coverage-report/quarkus`       | (not published)                               | aggregated coverage of the core and the Quarkus modules                       |
| `test-coverage-report/coverage-gate` | (not published)                               | breaks the build below the coverage threshold, last module of the reactor     |

All artifacts have the groupId `org.pbinitiative.zenbpm`, the Java packages start with
`org.pbinitiative.zenbpmadapter`. The example applications of [`examples/`](examples) are no
modules of this build, see [The examples](#the-examples).

## Configuration

The adapter provides the adapter type `zenbpm`, and every configured adapter id of that type gets
an instance of its own, configured under `vanillabp.adapters.<id>.*`. Where nothing is configured,
the platform derives the id `zenbpm`. An application needs two keys, `rest-address` and
`name-clash-avoidance`, see the wiki's page `Home`. Every key, with the levels it may be set at and
its default, is on the wiki's page
[Configuration](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/wiki/Configuration), and
`DocumentationTest` fails when its table and the keys the code reads differ.

In the code, the core's `ZenBpmAdapterConfiguration` documents what each key means, its default
and how it is validated; it does not depend on a platform. Each platform binds the keys into an
overlay of its own, `VanillaBpZenBpmProperties` with `ZenBpmAdapterKeys` on Spring Boot and the
mapping `VanillaBpZenBpmProperties` on Quarkus, and copies them into the core's configuration.
`QuarkusOverlayTest` holds the two overlays to the same keys at the same places, and
`VanillaBpZenBpmPropertiesTest` fails where the Spring Boot overlay misses a setter of the core
configuration.

On Spring Boot and on Quarkus the adapter registers one process service and one deployment service
per adapter id of type `zenbpm`, validates the configuration of each id while the application starts
and answers the platform's health check. The smoke tests in `smoke-test` boot it on Spring Boot with
no, one and two adapter ids, the extension tests in `quarkus/deployment` on Quarkus with one and
two, and with none configured at all, where the platform derives the id `zenbpm`. An application on
Quarkus which configures adapter ids of another type only is refused by the platform, which asks for
an id of every adapter type on the classpath. Two adapter ids on one `rest-address` boot only where
their `name-clash-avoidance` at the adapter level differs, the setup of a migration from one mode to
the other; with the same mode they end the start, naming the keys which tell them apart. The address
is compared as configured, so two addresses of one engine (an alias, a proxy, two nodes of one
cluster) are not recognised as one. `election-integration-test` runs that migration against the
pinned engine on Spring Boot: every key of the engine answers for both ids, and each id still
answers the platform's probes only for the workflows and tasks of its own mode (decision 11 in
[`DECISIONS.md`](./DECISIONS.md)), so a workflow the old id started is completed through it, a new
workflow starts under the new id, and the platform's election walks past the new id to a workflow
of the old one it holds no row of. Next to the platform's BPMS double as a second BPMS, the adapter
does not claim the double's workflows, and it says that it asks the engine, so the platform's
refusal of an adapter which guesses next to another one does not apply to it.

## Behavior

### Deployment

The adapter reads the BPMN files of a workflow module, also for an adapter id without an engine, and
refuses a file holding an executable process and any other process, because the engine would read
them as one, and a file which uses what ZenBPM cannot run or would run differently from the model,
as [`GAPS.md`](./GAPS.md) lists. ZenBPM has no isolation of its own, so the name-clash-avoidance
mode `by-adapter`, the default where nothing is configured, ends the start of a workflow module with
BPMN files, naming `use-prefix` and `none` (decision 24 in [`DECISIONS.md`](./DECISIONS.md)); under
`use-prefix` the identifiers of the BPMN and DMN files are prefixed with the workflow module id, and
a business rule task whose decision ZenBPM would then not find ends the start, and `none` warns
unless `accept-unscoped-identifiers` says that the identifiers are unique. Two
processes which would reach the engine under one id end the start.

What VanillaBP needs beyond what the engine's protocol carries is written into the model before it
is deployed, once per file and never over what the modeller wrote (decision 4). Every message a
claimed workflow waits for and which carries no correlation key of the modeller's gets the aggregate
id as its key, carried into the multi-instance bodies around the elements which wait for it, and a
message which both starts a workflow of the module and is awaited by a claimed one ends the start,
because ZenBPM would start a workflow with a correlation which finds none waiting. A job inside a
multi-instance body carries only what input mappings give it, so the adapter maps the names the
methods serving it read (theirs and the extensions'), and the aggregate id, into the body; a job
anywhere else gets the variables of its scope from the engine. The processes a workflow service
claims are wired with the platform: a task nothing serves ends the start, and so does an element
which creates a job and names no task definition, which ZenBPM would give a job of the type `''`
that nothing takes, unless the application marks the task `implemented-externally`; the start
events, the start messages, the expressions the modeller wrote and the elements which give a
workflow a second token are reported, a method which wants the item of an iteration whose element
names none ends the start, and a process called on the same aggregate from inside a multi-instance
body gets the aggregate id carried into that body.

Then the files are deployed, one request per file and the DMN files first, once the engine answers
ready; a file the pipeline left as it was written reaches the engine as its bytes, and a file the
engine refuses ends the start with the engine's reason. The files sent before it stay deployed,
because ZenBPM takes one file per request, but no version is reported then; once the engine took
every file, the version of every process is reported to the platform, also where the engine kept an
unchanged file. An adapter id without clients ends the start there with the message of its
validation, which names the key to set.

### Two-phase start

A workflow is started: phase one asks the engine nothing, and phase two starts the latest version of
the process under the id the engine knows it by, with the aggregate's id as the business key and as
a text variable after the values the aggregate shares, sends `history-time-to-live` in whole seconds
where a level sets it, and reports the key and the version of the new instance. The instance is
created only once the caller's transaction committed, through the platform's phase-two outbox, and
a start whose transaction rolls back creates none. A `history-time-to-live` with a fraction of a
second is refused while the application starts, and a start of a process the engine does not hold
is not repeated (decisions 30 and 31 in [`DECISIONS.md`](./DECISIONS.md)). The adapter tells the
platform what the engine does with the type of a value which travels: a whole number above 2^53, a
`BigDecimal`, a character and a text of another type than `String` come back changed.

### The probes

The probe which asks for the workflow of an aggregate first asks the engine for the instance whose
key the platform holds, which may only make an `ACTIVE` answer shorter (decision 25), and otherwise
searches the aggregate's business key in each process of the scope; a search which fails answers
that the engine is unavailable, never that it holds nothing. The probe asked before a start is sent
again counts only the workflows created from the millisecond the start was planned in, because the
engine knows no idempotent start (GAPS 17), and a workflow may stay invisible to the search for
`workflow-visibility-timeout`. The probes which ask for a task and for a user task read the job by
its key and the process of its instance, and answer for the scope they are asked about only
(decision 11); a task id of a partition the engine does not have is unknown to it (decision 32),
and so is the job of a user task to the probe of a task, and the job of any other element to the
probe of a user task (decision 51). The open tasks of a process are counted from the engine's
statistics of every version it holds.

### The workflow id

The workflow id of an instance is the key of its root instance, or of the instance a call activity
started (decision 28). A job names the instance it waits in, which is often an embedded sub-process
or an iteration (GAPS 22), so the adapter walks up to the root, reading each instance on the way
once and keeping what does not change while it runs in the instance cache of the adapter id
(GAPS 15).

### The start marker

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

### Task processing

Starting a workflow module subscribes the job types of its claimed processes on the job stream, each
with the `job-timeout` of its tasks as the lock (set per adapter id, workflow module, workflow or
task, `PT5M` by default) and the number of handler slots as the cap of active jobs, and stopping it
unsubscribes them before the clients close; a type two modules share stays subscribed until both
stopped, and two tasks of one type with different `job-timeout`s end the start, also where two
modules share the type. A job of a subscribed type runs its `@WorkflowTask` method on a handler
slot, under a lock which is renewed while the method runs (decision 9); then the values the
aggregate shares and its id are written into the job's instance and every instance above it up to
the workflow's root, and the job is completed (decisions 7 and 35): the engine hands the variables
of a completion to the process only through output mappings (GAPS 7), so the values are written
first. A job whose instance names no aggregate, and one of a workflow whose aggregate this
application does not hold, are failed without retries, so an incident shows them (decision 33). A
BPMN error writes the same values and then fails the job with the error code as the deployed model
carries it, so an error boundary event or an error event sub-process of the model takes over, and
where none catches the code the engine raises an incident on the task, which a WARN names.

A method which throws is failed at the engine without an error code, so the engine spends one
attempt of the task, hands the job out again after the backoff, and after the last attempt raises
an incident which names the attempts, the task, the aggregate and the exception (decision 27); a
failure sent again for the same delivery counts once. How many attempts a task has is its model's
`zenbpm:taskDefinition retries`. A model without it gets the engine's `JOBS_DEFAULT_RETRIES`, one
attempt by default, so the first exception raises an incident: set `retries` in the model, or
`JOBS_DEFAULT_RETRIES=3` at the engine for the default of Camunda 8 (decision 38). The backoff is
`retry-backoff` (set per adapter id, workflow module, workflow or task, in whole seconds) where a
level sets it; where none does, the failure carries none and the engine decides. Once the outcome of
a delivery reached the engine, the platform asks the adapter about the other tasks it believes open
in the same workflow: a task the engine took away, as an interrupting boundary event does, is
reported to the application as `CANCELED`, once; only a task served by a job of the job stream is
asked about, and an engine which cannot be asked or does not know the job, or a job of another
workflow, reports nothing.

The engine can hand a job out again: when its lock was lost, when its partition got a new leader,
which knows no locks (GAPS 2), or after the application restarted. Where an earlier delivery
committed, the method does not run again: the platform answers the delivery from its record of
deliveries, and the adapter sends the recorded outcome to the engine. Two deliveries which run at the
same time both run; the adapter names the lock it could not keep, and the platform names the second
run.

### Tasks left open

A task left open for its asynchronous completion keeps its job locked for `async-task-lock-renewal`
(`PT1H` by default; one not shorter than `vanillabp.delivery.retention` ends the start), after which
the engine hands the job out again and it is locked once more without running the method again. The
engine counts a locked job against its type's cap of active jobs, so the adapter raises the cap by
every task it keeps open, and the next task of the type still arrives (decision 37, GAPS 28). With
`async-task-max-age-action: incident` a task open longer than `vanillabp.delivery.max-task-age` is
failed without retries instead, so an incident names it. The application completes such a task
later, or cancels it with a BPMN error, through its process service: right before its transaction
commits, the adapter asks the engine whether the task still waits and belongs to the workflow
module; a task which ended, or which the engine does not know within `workflow-visibility-timeout`,
throws the platform's `TaskNotFoundException`, and a task of another module or one which carries an
incident rolls the transaction back with an `IllegalStateException` naming the reason. After the
commit the shared values are written as for a completed task, and the job is completed, or failed
with the error code as the deployed model carries it, so an error boundary event takes over; a task
which ended meanwhile costs a WARN and nothing else, and one which meanwhile carries an incident is
repeated by the outbox until the incident is resolved. The engine takes both from any node of the
application, without the delivery which left the task open (decision 40).

### A process declared without a model

A process id a workflow service declares without bringing a model for it, such as the old id of a
renamed process, keeps its workflows served: the methods' task definitions are scoped by that id,
and each one a model the engine holds under the id gives to a task is subscribed and routed to the
module; one a held model gives to a user task, in any version, is not, because the engine hands a
user task out as a job of the same kind, but is looked for like the user tasks of a deployed
process, and the start's line names it, as it names a method wired by element id, whose workflows
stand still (decision 41).

### The shutdown

A shutdown unsubscribes the job types of each workflow module and then waits for the handlers which
still run, those of every module of an adapter id together, at most `shutdown-grace` (`PT20S` by
default; above thirty seconds the start warns that the runtime's shutdown budget has to grow with
it), and names each handler it gave up on with its job, task and aggregate. While a module stops, an
outcome a handler reached is still reported, but a handler which threw is not failed at the engine,
so its job keeps its retries; the job stream closes last, which releases every lock the application
held, so another node gets those jobs at once instead of after their `job-timeout` (decision 39).

### User tasks

A user task is a job as well, which nothing pushes, so the job stream never subscribes its type
(GAPS 10): the adapter looks for the user tasks a method serves, by its task definition or its
element id, every `user-task-poll-interval` (`PT5S` by default; `PT0S` switches the looks off, and
the start warns about it and about an interval below a second), and tells the application once per
node that a user task was created, when its job is listed active, and that it was cancelled, when its
job is listed terminated, as an interrupting boundary event or a cancelled instance leaves it; a user
task which came and went between two looks is told as cancelled only, and a user task marked
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
up, so a service task left open in a cancelled instance is told as cancelled as well.

The application completes a user task, or cancels it with a BPMN error, through its process service
as it does a task left open: right before its transaction commits, the adapter asks the engine
whether the user task still waits and belongs to the workflow module, and after the commit the
shared values are written up to the workflow's root and the job is completed, so the gateway behind
the user task decides on them, or failed with the error code as the deployed model carries it, so an
error boundary event of the user task takes over
(`ZenBpmUserTaskIT#cancelUserTaskRoutesTheBoundaryEvent` on Spring Boot,
`ZenBpmWorkflowLifecycleTest#userTaskCancellationByError` on Quarkus). The Camunda 8 adapter cannot
do the second: no cluster up to 8.9 offers a command to cancel a Camunda-managed user task by BPMN
error. The application is then told once that the user task was cancelled, as the Camunda 7 adapter
tells it (decision 50); a completed user task is not told as cancelled. The commands of a task find
no user task, and those of a user task no task: the call throws the platform's
`TaskNotFoundException`, which names the method meant for the id where the platform recorded the
delivery of the task (decision 51).

### The end of a workflow

A claimed process whose end the application listens to, through a `@WorkflowEnded` method or
`vanillabp.delivery.release-on-workflow-end`, gets an end marker in front of every none, message and
terminate end event of its own (decision 52). Its job reports the end with the end event's id
(`ZenBpmLifecycleIT#theEndOfAWorkflowIsReportedWithItsEndEvent`, on Quarkus
`ZenBpmWorkflowLifecycleTest#theWorkflowEndIsReported`), after the platform told every task
it believed open in the workflow as cancelled, a task inside an embedded sub-process included, and
the instance ends only once the report committed
(`ZenBpmLifecycleIT#theEndCancelsTheTasksStillOpen`). An end event which ends one branch while
another branch of the workflow still runs reports nothing, and the end the last branch reaches is
reported (`ZenBpmLifecycleIT#aBranchWhichEndsWhileAnotherRunsReportsNothing`); an end reached while a
non-interrupting event sub-process still runs is reported once that sub-process ended, up to one
`job-timeout` of `vanillabp__ended` later, because the marker's job is left to its lock until then
(`ZenBpmLifecycleIT#theEndWaitsForAnEventSubProcessStillRunning`). A workflow cancelled at the engine
reports nothing (`ZenBpmLifecycleIT#aCancelledWorkflowReportsNothing`, GAPS 9), and neither does one
which ends inside an interrupting event sub-process. An error end event gets no marker: where nothing
catches the error, the engine raises an incident there and the workflow does not end
(`ZenBpmMarkerTasksIT#anUncaughtErrorEndEventRaisesAnIncident`). A `@WorkflowEnded` method which
throws fails the marker's job like a handler which threw, so the engine hands it out again after the
`retry-backoff` where the job has retries left (`ZenBpmLifecycleIT#aReportWhichThrewIsRepeated`),
and a workflow whose aggregate the application deleted ends without a report
(`ZenBpmLifecycleIT#aWorkflowWhoseAggregateIsGoneEnds`). A user task the end took away is told as
cancelled once, by that report, and the looks for user tasks tell it under the same id, so the
platform answers them from its record, also after a restart (decision 53,
`ZenBpmUserTaskRestartIT#aUserTaskTheEndTookAwayIsToldOnceAcrossARestart`); where the end releases
the platform's records, the next look tells it a second time
(`#aUserTaskTheEndTookAwayIsToldTwiceWhereTheEndReleasesTheRecords`). Whether an end event ends the
workflow is read from the model of the instance's own version
(`ZenBpmWorkflowEndedHandlerTest#anEndEventIsReadByTheModelOfTheInstancesVersion`), and the markers
of a version deployed while somebody listened to the end are served after nobody does, without a
report (`#anEndNobodyListensToAnyMoreIsCompletedWithoutAReport`). A report costs one job, a
transaction for the end and one for each task still open, a read of the instance's tokens and of its
child instances, and a read of the model once per version; a message end event's own task runs after
the report, and two branches whose end markers wait at the same time may report the end twice
(GAPS 8).

### Multi-instance

A task inside a multi-instance element is told the iteration it runs in, through
`@MultiInstanceElement`, `@MultiInstanceIndex` and `@MultiInstanceTotal` named after the id of the
multi-instance element (decision 54). ZenBPM hands a job neither an index nor a total (GAPS 16), so
the adapter reads them from the instances the engine keeps of the element, only where a method asks
for one and once for all its parameters
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
`ZenBpmMultiInstanceTest#aTotalWhichCouldNotBeReadIsReadAgain`), and the list of its iterations once
per body and once more for every iteration of a sequential element; a read which fails fails the
delivery like a method which threw (`ZenBpmJobHandlerTest#aReadOfTheIterationsWhichFailsFailsTheJob`).

### Messages

A message is correlated in two phases. Phase one never asks the engine whether
something waits. It refuses where the application calls a message which no version the engine holds
of the workflow module's processes waits for, and one which the newest version of a process starts
it with, also where an older version waits for it, naming `startWorkflowByMessage`, because ZenBPM
hands a message no workflow waits for under its key to a start event of the same name (GAPS 4).
Before it refuses, it reads those versions from the engine again, because another node may have
deployed a newer one since; where a version could not be read, or the module serves a process it
brought no model for, it refuses nothing (decision 42). Phase two publishes the message under its
scoped name, with the values the aggregate shares and its id, and with the correlation id as its
correlation key where the application passed one, else the aggregate id. So a correlation id is the
correlation key, and the subscription a modeller writes for a message has to read it, as on Camunda
8: the key the deployment writes reads the aggregate id. ZenBPM keeps no message nothing waits for
and answers `404` (GAPS 5): the outbox repeats such a publication every
`workflow-visibility-timeout` without counting an attempt, until the workflow waits for it, or until
`vanillabp.outbox.wait-for-visibility-at-most` passed and the entry is blocked; a workflow which
passed the waiting element, or waits under another key, is answered the same way. ZenBPM answers the
same `404` where the message was received and the workflow failed right behind the waiting element
on something it does not find, such as a called process which is not deployed, so that publication
is repeated as well, and is received a second time where the workflow waits for it again within the
window (decision 43, GAPS 29). ZenBPM deduplicates no message: a second correlation with the same
correlation id is not planned while the first still waits in the outbox, a correlation without one
is never deduplicated, and a publication repeated after its answer was lost may correlate twice.
One subscription per message name and correlation key may wait at a time, engine-wide (GAPS 23).

### Starting a workflow by a message

A workflow is started by a message in two phases as well, and how it is found afterwards is
`message-start-lookup`. ZenBPM gives a workflow it starts by a message no business key and names no
instance in its answer (GAPS 1). Unless `refuse` is set, phase one refuses where the application
calls a message the newest version of no process of the workflow module starts with, read again from
the engine before it refuses, naming `correlateMessage` where a model waits for the name; phase two
publishes the message under its scoped name without a correlation key, with the values the aggregate
shares and its id, and reports nothing. Where the modeller maps the outputs of a message start
event, the deployment adds an output of the id variable, because the engine hands the instance the
mapped variables only (decision 48). Under `start-row`, the default, the start marker behind the
message start event reports the instance's key, which the platform writes into the start row of the
aggregate, and every later operation on the running workflow finds it through that key. The start
row does not cover an application without a delivery log, the moments between the publication and
the commit of the marker's report, in which only the node which published knows the workflow, or a
start sent again after its answer was lost before the marker wrote the row, which publishes once more
and may start a second workflow. Nor does it cover a workflow which ended: the key finds a running
instance only, so where the platform asks which adapter holds a workflow a message started and which
ended, it is answered as unknown to the engine, in every mode, which the platform's contract for
adapters does not allow. How long the row lives is `vanillabp.delivery.workflow-start-retention`
(`P30D` by default, `0` keeps rows for good) or
`vanillabp.delivery.keep-workflow-start-while-aggregate-exists`. Under `scan`, an opt-in, the probes
in addition read the instances of the processes a version of which has a message start event where
nothing else finds the workflow, and find a running one by its id variable, also before a start is
sent again, at most `message-start-lookup-max-instances` instances (`1000` by default), beyond which
they answer that the engine is unavailable. A workflow which ended stays unknown to them as well, and
the start of the application warns so. The engine hands out the running instances page by page, so a
lookup over several pages may miss a workflow while other instances of its process end, and then
answers as `start-row` does. Under `refuse` every start by a message is refused where the
application calls, naming the key. Each start of the application names the processes a message
starts and the value in force. ZenBPM loses starts when two start messages of one name reach it at
the same moment, answering one of them as if it started a workflow (GAPS 30), so each node publishes
the start messages of one name one after the other; several nodes of an application may still lose
such a start unseen. A start message which no start event takes is answered `404`, also where
another node took the start event at that moment, so it is repeated with the outbox's backoff and
blocked after `vanillabp.outbox.block-after-attempts` (decision 47).

### Pushing a changed aggregate

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
push reads the pushed value only where the element maps it in (GAPS 31).

### Versions of a process

A `@WorkflowTask(version = ...)` method runs for the jobs of the versions it names. A version made
of numbers is compared with the version the job carries. A version tag, which a model carries as
`<zenbpm:versionTag value="..." />` on its process, is placed by the list of the definitions the
engine holds under the process, one request per process and start, and the version this start
deployed is known from the deployment's answer (decision 55). A tag no version carries is reported
once at startup, and a task of the version this start deployed which no method serves for that
version ends the start. ZenBPM gives a tag to one version of a process only: it refuses a changed
model under a tag an earlier version carries, so a changed model needs a new tag, also where only
the methods changed, because the adapter writes into the model it deploys; and a model deployed
again with the tag and the content of an older version gets that older version back, while new
workflows would start on the newest one, so such a start ends, naming both versions and the fix: a
tag no version carries yet (GAPS 32).

At startup the versions the engine holds of a process which are older than the one this start
deployed are checked: the platform names such a version with the number of workflows which still
run on it, a task of it which no method serves any more, and what those workflows miss, which the
adapter reads from the models of the older versions workflows still run on, because it writes
VanillaBP's behaviour into the model it deploys: the end markers, the start markers and the names
mapped into a multi-instance body. A version faded out with
`vanillabp.adapters.<id>.outfaded-versions` is not checked for its tasks; the workflows still running
on it are named instead. The numbers are counted by the engine, so no page of instances is read, and
every question of a start which reads a model the engine holds reads it through one cache of the
adapter id, so a start reads each model once (decision 56).

### Viewing workflows

The viewer/history API is read from the engine (decision 57). A process definition's id is its key
at the engine, and its BPMN is the model as the engine holds it, with the markers the adapter wrote.
The workflow of an aggregate is found by its business key: one which runs, else the one created
last; under `message-start-lookup: scan` a running workflow a message started is found by its id
variable, as the probes find it. A read which finds nothing is repeated within
`workflow-visibility-timeout`, and a workflow the engine started by a timer, or by a message outside
the scan, which has no business key, is unknown to the viewer. Its definitions are the one it runs
on and, for each call activity, the one the engine would start now: the latest version, the version
the call activity names, or the one which carries the tag it names. Its history merges the elements
of its embedded sub-processes, event sub-processes and multi-instance elements, which the engine
keeps in instances of their own, reports an event sub-process as `EVENT_SUB_PROCESS` and the entry
of a multi-instance element as `MULTI_INSTANCE`, leaves the adapter's markers out, carries the
message of an incident nobody resolved, and names, for a call activity, the instance it started,
whose history and definitions are read through that context; a context which belongs to another
workflow is not served. The engine keeps no state of an element, so none is reported as cancelled
(GAPS 33).

### Which phase-two failures are repeated

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

### Testing

The adapter is tested at the boundary of VanillaBP's adapter SPI, as every VanillaBP adapter is, and
against the engine itself wherever the engine decides what happens:

- the integration tests (`*IT` on Spring Boot and in the core, the prod-mode tests of
  `quarkus/integration-tests`) start the pinned engine image with Testcontainers
  (`engine-test-support`, `EngineUnderTest`), one engine per test class, and boot real applications
  against it;
- `ZenBpmRestClientContractIT` runs every method of the REST client once against the engine, and
  the rows of [`GAPS.md`](./GAPS.md) name the tests which measured each gap against it;
- the smoke tests in `smoke-test` and the extension tests in `quarkus/deployment` boot an application
  without an engine, or against a stand-in which answers what the test needs, and the platform's
  BPMS double stands in for a second BPMS;
- unit tests in the core cover the edge cases, with stand-ins of the engine shaped like what the
  engine answers;
- the wiki's configuration is booted against a fresh engine by `ZenBpmHomePageIT` and
  `ZenBpmHomePageTest`, which read it from `wiki/Home.md`.

Every test class uses VanillaBP's `test-utils` (`SuppressOutputExtension`), which
`TestClassConventionsTest` checks. Without Docker the tests which start the engine are skipped
(`EngineTestsWithoutDockerTest`); CI always has Docker.

## Boot behavior

**What the start finds (validated at startup).** Every finding of the start goes into the block
VanillaBP writes once, at its end, under the topic where its fix lies. A finding stands under its
scope, the thing it is about: `zenbpm adapter '<id>'`, `workflow module '<module>', zenbpm adapter
'<id>'` or `process '<process>' of workflow module '<module>', zenbpm adapter '<id>'`. Its text
does not repeat that scope and names the property keys to set, written out in full so they can be
copied (decision 60 in [`DECISIONS.md`](./DECISIONS.md)):

- an adapter id without `rest-address` is a warning under `CONFIGURATION`. The application boots,
  and every use of the adapter id fails with the same message, which names the adapter id where the
  block's scope does
  (`ZenBpmStartupValidationBootTest#anUnconfiguredAdapterIdBootsWithAWarning`, the Quarkus
  `ZenBpmStartupValidationTest`);
- an address or a client id which cannot work, on an adapter id which is nowhere first priority and
  whose `deployment-failure` is `warn`, is a warning as well, and the application boots DEGRADED
  (`ZenBpmStartupValidationBootTest#anAddressDefectWhichMayDegradeBootsDegraded`,
  `ZenBpmDegradedBootTest`);
- every other key which cannot work is refused. The platform collects the refusal and ends the
  start with every reason it collected, before it deploys anything, so a developer who got the keys
  of two adapter ids wrong reads both in one start
  (`ZenBpmStartupValidationBootTest#anInconsistentFirstPriorityAdapterIdEndsTheStart`,
  `ZenBpmStartupValidationBootTest#twoAdapterIdsWhichEndTheStartAreNamedInOneStart`,
  `ZenBpmInconsistentConfigurationTest`, `ZenBpmStartupValidationTest#everyAdapterIdWhichEndsTheStartIsRefused`);
- a `rest-address` which ends with `/v1`, and an `https` address beside a job stream without TLS,
  are notices (`ZenBpmStartupValidationBootTest#aNoticeStandsOnceInTheBlock`,
  `ZenBpmNoticeOnceTest`, `ZenBpmAdapterConfigurationTest#aTlsRestAddressBesideAPlaintextJobStreamIsNoticed`);
- a `shutdown-grace` above thirty seconds, and a `user-task-poll-interval` of zero or below a
  second, are warnings (`ZenBpmStartupValidationBootTest#aLongShutdownGraceIsWarnedAboutInTheBlock`,
  `ZenBpmLongShutdownGraceTest`,
  `ZenBpmAdapterConfigurationTest#aUserTaskPollIntervalWhichWorksAgainstTheApplicationIsWarnedAbout`);
- a protobuf runtime too old for the job stream is handed to the platform's report as a refusal
  under `PARTS AND VERSIONS` on Spring Boot, and no adapter id gets clients
  (`ZenBpmStartupValidationTest#anUnfitProtobufRuntimeIsRefused`), and it ends the build on
  Quarkus (`ZenBpmIntegrationProcessorTest#aRuntimeWithoutRuntimeVersionFailsTheBuild`);
- a workflow module deployed with `name-clash-avoidance: none` is a warning under `CONFIGURATION`
  which names `use-prefix`, an engine of its own and the keys which accept unique identifiers
  (`ZenBpmStartupValidationBootTest#noneIsWarnedAboutWhileTheModuleIsPrepared`,
  `ZenBpmDeploymentServiceTest#noneWarnsNamingTheAlternatives`), and so is
  `message-start-lookup: scan` (`ZenBpmMessageStartScanIT`,
  `ZenBpmDeployingTest#theProcessesAMessageStartsAreNamedOnce`);
- an engine which runs another commit than the one this adapter was tested with, or which does not
  say which one it runs, is a warning under `INFRASTRUCTURE` (`ZenBpmEnvironmentInfoTest`), and so
  is a model the start could not read or a count the engine did not give
  (`ZenBpmDeployingTest#versionsWhichCannotBeReadAreNamed`, `ZenBpmProcessVersionCatalogTest`);
- a process id a workflow module declares without bringing its model, as the old id of a renamed
  process, is a warning under `DEPLOYED VERSIONS` where a task definition of its methods reaches
  none of its workflows, and under `INFRASTRUCTURE` where the models the engine holds under that id
  could not be read (`ZenBpmDeclaredProcessWorkersTest`).

That last finding comes from the start of the workflow processing. On Quarkus that is part of the
start, so the finding stands in the block (`ZenBpmDeclaredProcessFindingTest`). On Spring Boot the
platform starts the processing once the application is ready, after the block, and its report
writes such a finding into the log on its own, with its scope (`ZenBpmRenamedProcessIT`).

**What ends the deployment.** A model ZenBPM cannot run, `name-clash-avoidance: by-adapter`, a
process declared in two files, a message which starts one workflow and which another waits for, two
`job-timeout`s of one job type, a file the engine refuses, and an engine which does not report ready
within `startup-wait` end the deployment of the workflow module with an exception which names what
it is about and the way out. They are thrown and not refused through the report, because the
platform decides what follows from its `deployment-failure` policy: the start ends, or, where the
adapter id is nowhere first priority and the policy is `warn`, the application boots and the failure
stands in the block (`ZenBpmUnsupportedModelBootTest`,
`ZenBpmStartupValidationBootTest#byAdapterOfOneWorkflowEndsTheStartNamingItsKey`,
`ZenBpmDeploymentServiceTest#twoFilesDeclaringOneProcessAreRefused`,
`ZenBpmDeploymentServiceTest#aMessageWhichStartsAndIsAwaitedEndsPreparing`, `ZenBpmJobTimeoutBootTest`,
`ZenBpmRefusedFileBootTest`, `ZenBpmEngineWaitTest#aWaitWhichRunsOutSaysEverything`).

**What stays a line of the log.** What the adapter reports about doing its work is no finding: the
engine it reached, the tasks it wired, the files it deployed, the job types it subscribed, its wait
for the engine (`ZenBpmEnvironmentInfoTest#oneLineNamesTheEnvironment`,
`ZenBpmWorkflowProcessingTest`, `ZenBpmEngineWaitTest#theWaitReturnsOnceTheEngineIsReady`). And what
happens once the application runs is logged where it happens: the job stream writes one WARN when it
goes down, however long the outage lasts, and one INFO when it is back
(`ZenBpmJobStreamTest#anOutageIsWarnedAboutOnce`, `ZenBpmJobStreamTest#aStreamIsReportedBackOnce`).

No message carries a password or the data of a workflow: an address which carries a user is refused
(`ZenBpmAdapterConfigurationTest`), and the variables of a job are never quoted, not even where they
cannot be read (`ReceivedJobTest#aJobWithoutAJsonObjectHasNoneOrIsRefused`).

## Health

Each adapter id adds an entry named after it to the platform's health check (the readiness check
on Quarkus). The entry asks the engine's own readiness probe, `GET /system/health/ready`, once per
check:

- an adapter id without clients is `UNKNOWN`, with the message of the startup validation, which
  names the key to set;
- an engine which answers ready is `UP`, with the details `address`, `versionAtStart` and
  `commitAtStart` (what the engine reported when the application started) and `jobStream`;
- an engine which answers not ready is `DOWN`, with its reasons, and one which cannot be reached or
  does not answer within `request-timeout` is `DOWN`, with what the REST client reported and the
  detail `requestTimeout`.

`UP` says that the engine answers over REST, not that jobs arrive: the job stream is a connection
of its own, over gRPC, which a wrong `grpc-address`, a TLS mismatch or a blocked port keeps down
while REST works. So every entry of an adapter id with clients names the stream's state as the
detail `jobStream`: `not opened yet`, `connecting`, `connected`, `down since <instant>, <n>
attempt(s)` or `closed`. A stream which is down never turns the entry `DOWN`, however long it
stays down (decision 58 in [`DECISIONS.md`](./DECISIONS.md)): a readiness probe which went down
for it would take every replica out of service and fix nothing, while starts, correlations and
probes still go over REST. What an operator alerts on is the pair of meters below:
`vanillabp.zenbpm.stream.reconnects` rising while `vanillabp.zenbpm.jobs.received` stays flat.
`ZenBpmHealthTest` checks each of these answers.

## Metrics

Where the application brings Micrometer, each adapter id with clients publishes the meters below,
on top of those the platform publishes for every BPMS. On Spring Boot the adapter registers its
`MeterBinder` where `io.micrometer.core.instrument.MeterRegistry` is on the classpath; on Quarkus
where the application uses the Micrometer extension. The adapter does not bring Micrometer, and
an application without it boots unchanged and publishes none of them. Every meter carries the tag
`adapter` with the adapter id, so two adapter ids publish two sets which differ in that tag only.
An adapter id without clients publishes none: nothing could ever move them. An application which
brings its own bean of the core's `ZenBpmMetrics` gets that one measured into instead of the
adapter's binder, on both platforms.

|                Meter                 |  Kind   |   Tags    |                                              What it says                                               |
|--------------------------------------|---------|-----------|---------------------------------------------------------------------------------------------------------|
| `vanillabp.zenbpm.jobs.received`     | counter | `adapter` | jobs the job stream handed over, every delivery, those of a type nobody subscribes included             |
| `vanillabp.zenbpm.jobs.completed`    | counter | `adapter` | jobs a handler answered with a completion or a BPMN error, start and end markers included               |
| `vanillabp.zenbpm.jobs.failed`       | counter | `adapter` | jobs failed at the engine: a handler threw, or the adapter failed the job without retries (an incident) |
| `vanillabp.zenbpm.jobs.queued`       | counter | `adapter` | jobs which found no free handler slot and waited for one, each delivery once                            |
| `vanillabp.zenbpm.jobs.redelivered`  | counter | `adapter` | deliveries of a job the engine had handed out before, as its delivery token says                        |
| `vanillabp.zenbpm.lock.renewed`      | counter | `adapter` | extensions of a running handler's lock the engine granted                                               |
| `vanillabp.zenbpm.lock.lost`         | counter | `adapter` | locks which ended while their handler ran: lost at the engine, or not renewed before they lapsed        |
| `vanillabp.zenbpm.stream.reconnects` | counter | `adapter` | attempts of the job stream to open its call again after it broke                                        |
| `vanillabp.zenbpm.usertasks.polls`   | counter | `adapter` | looks at the user tasks, one per round, those the engine did not answer included                        |
| `vanillabp.zenbpm.slots.busy`        | gauge   | `adapter` | handler slots taken right now                                                                           |
| `vanillabp.zenbpm.slots.free`        | gauge   | `adapter` | handler slots free right now                                                                            |

A task left open for its asynchronous completion counts as neither completed nor failed, neither
when its handler returns nor when the application completes or cancels it later through the process
service, and a user task does not count either: the job meters count what the handlers answered, so
that they add up against `jobs.received`. The extension which parks a task is no renewal. The two gauges read the semaphore of the handler slots and
the reconnects counter reads the job stream, values already in memory, so reading a meter never
asks the engine anything. `MicrometerZenBpmMetricsTest` fails when this table and the meters the
adapter registers differ in a name, a kind or a tag.

## Native image

An application on Quarkus can be built as a native image with the adapter; it needs nothing of its
own for that. `quarkus/native-image-tests` proves it: a small application with one workflow is built
as a native executable and started against the pinned engine. The binary deploys its workflow
module, starts a workflow through the platform's two-phase start, serves the job of a first task,
correlates the message the workflow then waits for, serves the job of a second task and reports the
end of the workflow to the application's `@WorkflowEnded` method. The test accepts the run only
when the binary exits with 0 and the engine shows the workflow completed (`ZenBpmNativeImageIT`).
It has no Micrometer: the meters of the section above are tested in the JVM only.

On Quarkus the job stream runs on `grpc-netty` over Quarkus' Netty extension, which the extension
brings, and not on the `grpc-netty-shaded` of the core: the shaded Netty does not build into a
native image (decision 61 in [`DECISIONS.md`](./DECISIONS.md)). The Quarkus modules of this
repository pin Netty to the version the Quarkus platform manages, the one an application runs
(`QuarkusNettyTest` holds the version, `QuarkusNettyPinTest` the pin in each of them). What the extension registers for the native image, each item named by a
native build which failed without it (`ZenBpmNativeImageProcessorTest` holds every one):

- the gRPC providers the job stream's channel finds through the service loader: the transport, the
  name resolver and the load balancer;
- the run-time initialization of the two classes of `grpc-netty` which hold a Netty buffer
  allocator;
- the records the REST client reads the engine's JSON into, and every class generated from the
  engine's proto, for reflection;
- the platform's version descriptor, so that the check whether the adapter fits the platform
  integration can read the platform's version.

The adapter's own version descriptor and the workflow modules' BPMN files are registered by the
platform's extension. The native build runs in its own profile, because it takes minutes; every
build of the repository compiles the module and builds its application in JVM mode. It takes two
Maven calls, the first installing what the application needs, the second building the image and
running the binary against the engine:

```bash
mvn -Dmaven.test.skip=true -pl quarkus/deployment,quarkus/native-image-tests -am install
mvn -pl quarkus/native-image-tests -Dnative verify
```

Without an installed GraalVM the image is built in a container of
`quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25`, the builder Quarkus picks; with one,
`-Dquarkus.native.container-build=false` builds it locally, which is how the nightly's job
`native-image` builds it (see "What CI runs"). The integration test needs Docker for the engine
either way. On a laptop with 14 cores the image took about two minutes and 6 GB of
memory at its peak. What the binary printed is in
`quarkus/native-image-tests/target/native-image-application.log`, the place to read when it fails.

## Decision log

Decisions which several places in this repository rely on live in [`DECISIONS.md`](./DECISIONS.md).
It is the one thing the code is allowed to cite. A citation reads `see decision 3 in the
repository's DECISIONS.md`, numbers are never reused, and an overturned entry stays and names its
successor, so a citation written today still resolves in a year. The plan handed out the numbers 1
to 17 in advance; the four of them which the code works differently from (2, 5, 6 and 8) were
superseded by 24 to 27 before they were entered, and their drafts stay in
[`docs/architecture/02-design-decisions.md`](docs/architecture/02-design-decisions.md).

## Known deviations

What the engine cannot do that VanillaBP's contract for adapters asks for is listed in
[`GAPS.md`](./GAPS.md), with the evidence in the engine's source at the pinned commit and how the
adapter answers it; the wiki's page
[Deviations](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/wiki/Deviations) says the same
for users, one entry per row. The two-phase start and the at-least-once delivery of a task are not
among them: that is what a remote BPMS looks like in VanillaBP. The ones a user meets first:

- GAPS 1: a workflow the engine starts itself, by a timer or a message, carries no business key, so
  it is found only through the instance key its start marker recorded, while the start row lives
  ([The probes](#the-probes), [Starting a workflow by a message](#starting-a-workflow-by-a-message));
- GAPS 2: a change of a partition's leader hands every waiting job out again
  ([Task processing](#task-processing));
- GAPS 5: a message nothing waits for is dropped by the engine and published again by the outbox
  ([Messages](#messages));
- GAPS 8 and 9: no listeners, so the start and the end are reported by markers, and a cancelled
  workflow tells nobody ([The end of a workflow](#the-end-of-a-workflow));
- GAPS 10: user tasks are polled ([User tasks](#user-tasks));
- GAPS 11: no tenant, so `by-adapter` is refused and every application writes `use-prefix` or
  `none` ([Deployment](#deployment));
- GAPS 12: no authentication and no TLS, so the adapter sends no credentials;
- GAPS 14: what the engine refuses or runs differently is refused before anything is deployed
  ([Deployment](#deployment));
- GAPS 15 and 22: a job names neither its process nor its root instance, so the adapter reads and
  keeps both ([The workflow id](#the-workflow-id));
- GAPS 16: an iteration is told neither its index nor its total, so the adapter reads them
  ([Multi-instance](#multi-instance)).

One deviation is no gap of the engine: an expression in the model which reads an attribute the
aggregate does not share is not found while the application starts, and fails while the workflow
runs; like Camunda 8, the adapter does not tell VanillaBP which attributes its expressions read.

The gaps 3 and 6 were closed by engine changes the pin contains, and keep their rows.

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

## Dependency updates

[Renovate](https://docs.renovatebot.com) opens the pull requests which move the dependencies, the
Maven plugins, the actions of the workflows, the JDK they build on and the image of actionlint
(`renovate.json`, on `config:recommended`). What moves with something else is switched off, each
rule saying why: VanillaBP and this adapter, which follow their own releases; the Spring Boot and
Quarkus versions, which move with the VanillaBP platform; gRPC and protobuf, which are raised by hand
when a platform BOM moves; and Netty, which moves with Quarkus. Renovate moves a property of the POM
with whichever of its artifacts has an update, so every artifact which reads such a version is named
in its rule, the Quarkus Maven plugin and Spring Boot's configuration processor included. The JDK
moves in every workflow in one pull request, the matrix of `checks.yaml` included.

`RenovateConfigurationTest` in `test-coverage-report/coverage-gate` holds `renovate.json` to this:
every version the root POM says never moves on its own is read only by artifacts a rule switches off,
every rule says why and still matches an artifact of the build, the image a workflow runs by its
version and the JDK of the required check are found by a custom manager, and every workflow builds on
the same JDK.

The engine pin is not Renovate's. While it is a build of the engine's `main`, it moves by hand
together with the copies of the engine's contract, as the section
[Supported engine version](#supported-engine-version) says. Once the pin is an engine release, an
entry which follows the engine's GitHub releases is added to `renovate.json`, and the pull request it
opens is completed by hand the same way. The plan holds that entry ready (story S12.3.1 in
[`docs/plan/epic-12-documentation-and-release.md`](./docs/plan/epic-12-documentation-and-release.md)),
and it finds the release a re-pinned root POM names
(`RenovateConfigurationTest#theEntryForTheEnginesReleasesFindsTheRelease`).

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

## What CI runs

No workflow of this repository needs a secret of its own: each works with the workflow's
`GITHUB_TOKEN`, and only the two publications may write with it: the jobs of
`publish-snapshots.yaml`, and the job `publish` of `release.yaml`, which runs for a pushed tag only
(see [Releasing](#releasing)). `WorkflowPermissionsTest` in `test-coverage-report/coverage-gate`
holds every workflow to this and names the permissions each of those jobs may ask for.

**Every pull request and every push to `main`** run `.github/workflows/checks.yaml`: one
`mvn install`, which is Spotless, compile, javadoc, the tests, both coverage reports and the gate.
Its job `build` is the check branch protection on `main` requires, so nothing reaches `main`
without it. The same build runs a second time on Java 21 (`build on Java 21`), the Java the
published classes are compiled for, because a dependency built for a newer Java would only fail at
run time. When a red run got as far as writing test or coverage reports, they are attached to the
run as the artifact `test-reports` (`test-reports-java-21` for the run on Java 21). A build which
stops earlier, at Spotless or the compiler, has none, and its log is the place to read. The job
`workflow-lint` checks every workflow of the repository with actionlint and every script of `bin/`
with shellcheck. The job `examples` installs the adapter of the same commit without its tests and
runs `mvn -f examples/pom.xml verify`. That walks through every example's README against an engine
of its own, see [The examples](#the-examples). A red run attaches the failsafe reports as the
artifact `examples-reports`.

The build reads the VanillaBP snapshots from the snapshot repository of Maven Central, which needs
no login. So a pull request from a fork builds exactly like one from this repository.

**Every push to `main`** runs `.github/workflows/publish-snapshots.yaml`, which builds once more and
publishes the snapshots and the coverage reports, see the sections "Using the snapshots" and "Test
coverage". It deploys with the workflow's `GITHUB_TOKEN`.

**Every night** at 02:37 UTC, `.github/workflows/nightly.yaml` runs what no pull request should
wait for, in jobs which do not depend on one another. GitHub starts a scheduled run late, by hours
on some nights. From Monday to Saturday a job runs only where something it builds on changed since
its last run in the same week, and the nightly runs every job on Sunday (decision 68 in
[`DECISIONS.md`](./DECISIONS.md)). Its badge at the top shows the last run on `main`. A pull request
which changes the workflow, a script it runs (`bin/what-the-nightly-builds-on.sh`,
`bin/resolve-latest-engine.sh`, `bin/build-blueprints-on-zenbpm.sh`, `bin/zenbpm-engine.sh`) or the
blueprints' overlay `blueprints-overlay/` runs it too, so a change of the nightly is seen working
before it is merged; no other pull request starts it. A person can start it on `main` from the
Actions tab. A pull request and a person's start run every job as well. Its jobs:

- `what-changed`: decides which of the other jobs run. Each of them waits for it and for nothing
  else. `bin/what-the-nightly-builds-on.sh` reads four inputs, without Docker and without a login:

  - the commit of `main`;
  - the newest snapshot of every VanillaBP artifact the root POM names, by its timestamp and build
    number in Maven Central's snapshot repository;
  - the digest of `ghcr.io/pbinitiative/zenbpm:dev`;
  - the newest commit of the blueprints' `main`.

  Every job gets a key of the inputs it builds on. The commit and VanillaBP are inputs of every job,
  the engine's digest of `latest-engine` only, and the blueprints' commit of `blueprints` only. The
  pinned engine moves only with a commit, so it is no input of its own. A job runs where its key is
  not in the workflow's cache. Once its build passed, the job saves its key there, on `main` only. A
  job whose build failed saves nothing and runs again the next night, so the run and the badge stay
  red until the build passes: GitHub reports a skipped job as passed, so a skipped red job would
  turn them green. `latest-engine` is the exception, see below. Every key also holds the week, which
  begins on Sunday, so the run of Sunday finds no key of an earlier night. A build of Sunday which
  fails saves nothing, and the job runs again on Monday. A run a person starts finds the keys of its
  week. Where its build fails against inputs a night of that week passed with, the next nights still
  skip the job until something changes or until Sunday. Only the person who started the run sees it
  red. Where an input cannot be read, its jobs run: not knowing what changed is no reason to skip a
  build. They also run where `what-changed` itself failed, or where GitHub evicted the key from the
  cache. The summary line names the inputs and which jobs run.

- `pinned-engine`: the build of `checks.yaml`'s job `build`, against the pinned engine. With nobody
  waiting for it, a test which fails now and then shows up here, on Sunday at the latest, and not
  in somebody's pull request.

- `latest-engine`: the same build against the engine's newest build of its `main`, the moving tag
  `ghcr.io/pbinitiative/zenbpm:dev`. `bin/resolve-latest-engine.sh` turns the tag into the image's
  digest and the commit it was built from, starts it once to read the commit the engine reports, and
  writes all three into the run's summary together with the version the engine reports and whether
  that commit is the pin. The build then runs with `-Dzenbpm.image` and `-Dzenbpm.commit` set to
  them and without the coverage gate: the gate compares the README and the copies of the engine's
  contract with the engine the build runs against, which here is another one on purpose, and
  coverage is the pinned build's business. The job may fail without turning the run red
  (`continue-on-error`), because nothing in this repository changed. A red job is the earliest
  warning that an engine change breaks the adapter: a person reads it and takes the finding to the
  engine's maintainers. It saves its key also after a failed build, so it reports a new build of the
  engine in the night after it appeared, and after that once a week, on Sunday. The next nights skip
  the job until the engine, VanillaBP or this repository changes again, or until Sunday. The
  workflow itself opens no issue and writes nowhere (decision 62 in
  [`DECISIONS.md`](./DECISIONS.md)).

- `native-image`: the two Maven calls of the section "Native image", with Mandrel for Java 25
  installed on the runner by `graalvm/setup-graalvm` and `-Dquarkus.native.container-build=false`,
  so the image is built with a local GraalVM rather than in the builder container. When it fails,
  the binary's log and the test reports are attached as the artifact `native-image-reports`.

- `blueprints`: the [VanillaBP blueprints](https://github.com/vanillabp-blueprints/blueprints) on
  ZenBPM. It installs the adapter of this checkout without its tests and runs
  `bin/build-blueprints-on-zenbpm.sh`, which clones the blueprints' newest `main`, applies the
  overlay of `blueprints-overlay/` and builds every twin of a blueprint which runs on ZenBPM with
  `-Pzenbpm` against an engine of its own, the pinned one, which `bin/zenbpm-engine.sh` starts.
  The blueprints read VanillaBP from Maven Central's snapshots and the adapter from the local
  repository, so the job needs no token. A blueprint which fails, or a change of the blueprints the
  overlay cannot follow, turns the run red; the build logs and test reports of every twin, and the engine's log of
  each failed one, are attached as the artifact `blueprints-reports`. The wiki page
  [Blueprints](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/wiki/Blueprints) lists the
  blueprints and why some do not run on ZenBPM (decision 64 in [`DECISIONS.md`](./DECISIONS.md)).

Each job ends with a line in the run's summary: what it ran against and how it ended, also where
it failed before its build; `latest-engine` writes the line of its script before. A failed build
attaches its test reports (`pinned-engine-reports`, `latest-engine-reports`). Every job reads with
the workflow's token and writes nothing but its key into the workflow's cache.
`NightlyWorkflowTest` in `test-coverage-report/coverage-gate` holds the workflow to this section:

- the triggers, with the two schedules;
- the jobs it names;
- the jobs' independence, with every job waiting only for `what-changed` and running where it failed;
- that `what-changed` decides for every job, and that every job saves its key after a passed
  build, `latest-engine` also after a failed one, and only on `main`;
- the one job which may fail, and no step of another job which could hide a failure;
- the native build reaching its integration test;
- the blueprints built by the script after the adapter was installed;
- the permissions and the summary line.

`NightlyChangeDetectionTest` runs what decides: `bin/what-the-nightly-builds-on.sh` against
stand-ins for curl, git and date, and the bash of the step `decide` of `what-changed` as the
workflow holds it. It holds which inputs each job's key is built from, that a key holds from Sunday
to Saturday, that an input which cannot be read leaves the keys of its jobs empty, that a weekday
runs only the jobs whose key is new, and that Sunday, a pull request and a person's start run every
job.

`BlueprintsOverlayTest` applies the overlay to a small checkout of the blueprints' shape in every
build, and `EnginePinTest#theBlueprintsEngineIsThePin` holds the engine script to the pin.

## Releasing

No version of the adapter is released yet. The first release is the maintainers' step, and what it
needs is listed at the end of this section. `.github/workflows/release.yaml` is the only way a release
is published, to this repository's GitHub Packages (decision 65 in [`DECISIONS.md`](./DECISIONS.md)).

**A pushed tag** `v<major>.<minor>.<patch>`, for example `v2.0.0`, runs the job `build`, which

1. reads the version from the tag, `v2.0.0` gives `2.0.0`, and refuses a tag of another form;
2. refuses an engine pin which is no engine release: `zenbpm.version` of the root POM has to name a
   release tag of the engine, `zenbpm.commit` the commit that tag points at, and `zenbpm.image` the
   digest of the tag's image, as the section [Supported engine version](#supported-engine-version)
   says (decision 66 in [`DECISIONS.md`](./DECISIONS.md));
3. reads the release's notes from the message of the annotated tag on GitHub, and refuses a
   lightweight tag or an empty message;
4. builds that version with `-Drevision`, with every test, the integration tests in Docker and the
   coverage gate, deploys it into a local directory only, and checks that exactly the published files
   arrived: the jar, the sources jar and the POM of the core, `spring-boot`, `quarkus` and
   `quarkus-deployment`, and the parent's POM;
5. refuses a POM of the release which names a snapshot;
6. uploads the staged files into another local directory with the command the release uploads with,
   and compares what arrived with what was staged.

Each of these steps stops a release before anything is published. Then the job `publish`, the only
job which may write (`contents: write` and `packages: write`), uploads exactly the files `build`
staged and tested to this repository's GitHub Packages, with Maven's `deploy:deploy-file` and the
workflow's token, and creates the GitHub release with the tag's message as its notes.

**Every other run is a dry run**, which publishes nothing: a dispatch on `main`, from the Actions tab
or with `gh workflow run release.yaml` (`-f tag=v2.0.0` rehearses that tag), and a pull request which
changes `.github/workflows/release.yaml`, `.github/workflows/settings.xml`, `bin/release.sh` or
`bin/published-artifacts.sh`. Without a tag it rehearses the version the root POM's `revision` leads
to. It runs every step of `build`, writes each refusal into the run's summary instead of stopping, and
goes on, so its summary shows what a release of that commit would meet. Today a release would be
refused for these reasons: the engine pin is a build of the engine's `main`, the POMs name
VanillaBP's `2.0.0-SNAPSHOT`, and the tag does not exist. The README names the first two exactly
while the root POM gives them (`ReleaseWorkflowTest#theReadmeNamesTodaysRefusalsWhileTheyHold`).
A pull request which changes only the root POM's publication settings (`distributionManagement`,
`deployAtEnd`, the flatten plugin) does not run the dry run, because nearly every update of a
dependency changes the root POM too; such a change is rehearsed by a dispatch of its branch,
`gh workflow run release.yaml --ref <branch>`.

`bin/release.sh` holds the steps, one command each, and a person runs them the same way, for example
`bin/release.sh engine`. `ReleaseScriptTest` in `test-coverage-report/coverage-gate` runs every
command against stand-ins for git, curl, the GitHub CLI and Maven, and checks that a pin on a build
of the engine's `main` is refused (`ReleaseScriptTest#aPinOnTheEnginesMainIsRefused`).
`ReleaseWorkflowTest` holds the workflow to this section: its triggers, the refusals before the
build which stop a release and not a dry run, the job `publish` as the only one which uploads and
only for a pushed tag, no other workflow which publishes a release, and the root POM's `revision`,
which has to stay a snapshot, because `publish-snapshots.yaml` deploys it as it stands. Every job
writes into the run's summary.

What the first release needs, all of it the maintainers':

1. VanillaBP releases `spi-for-java` and `adapter-platform-integration` `2.0.0` on Maven Central, and
   `spi-for-java.version` and `adapter-platform.version` of the root POM name that release.
2. The engine pin moves to an engine release, as the section
   [Supported engine version](#supported-engine-version) says, and `renovate.json` gets the entry
   which follows the engine's releases (section [Dependency updates](#dependency-updates)).
3. In the same pull request, the README's sections [Dependencies](#dependencies),
   [Using the snapshots](#using-the-snapshots) and this one, and the wiki's page `Home` in `wiki/`,
   name `2.0.0` and the engine release instead of `2.0.0-SNAPSHOT` and "not released yet".
   `ReleaseWorkflowTest#theReadmeNamesTodaysRefusalsWhileTheyHold` fails while this section still
   names a reason which no longer holds.
4. A dispatch of the dry run on `main` shows no refusal but the missing tag.
5. An annotated tag `v2.0.0` with the release's notes is pushed on a commit of `main`. The workflow
   releases the commit of any tag `v*`, so a tag ruleset which lets only maintainers create `v*`
   tags, and an environment with required reviewers for the job `publish`, are recommended; both
   are settings of the repository. `UPGRADE.md` already says that there is nothing to upgrade from.

If the job `publish` fails after it uploaded a part of the release, running it again cannot
upload that version once more, because GitHub Packages does not replace a version of a package it
holds. Delete the versions the failed run uploaded in the repository's Packages and run the job
again. Where only the GitHub release is missing, create it by hand from the run's artifact
`release`, which holds the notes: `gh release create v2.0.0 --verify-tag --notes-file notes.md`.

Publishing to Maven Central as well needs the organisation's namespace and signing secrets there,
and is left open (decision 65).

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
failing it. Two modules are deliberate exceptions, each named in the gate with its reason:
`engine-test-support`, which starts the engine for the integration tests and which no application
runs, and `quarkus/native-image-tests`, whose application runs as a native binary, which no Java
agent measures. An exception which names no module with production sources, or gives no reason,
fails the gate too. All of these are `CoverageGateTest`; the failure paths of the last one are held by
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

## The wiki

The pages of the [wiki](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/wiki) are written
in this repository, one file per page in [`wiki/`](wiki), named as the wiki names the page, and they
are reviewed and checked in a pull request like code (decision 63 in
[`DECISIONS.md`](./DECISIONS.md)). After the merge, whoever merged copies exactly the merged `wiki/`
into the wiki repository, `pbinitiative/zenbpm-vanillabp-adapter.wiki`, and pushes it; today that is
a step of the implementation pipeline after each merge. Nothing is written on the wiki directly,
because the next copy overwrites it.

A page links another one in the wiki's form, `[Configuration](Configuration)`, and the repository
with a full address of `main`. `DocumentationTest` in `test-coverage-report/coverage-gate` reads this
README and every page and fails when a test they name does not exist, a link leads to no page or
heading, a row of `GAPS.md` has no entry on `Deviations`, the table on `Configuration` and the keys
the code reads differ, or `Engine-APIs` misses a call of the REST client or an endpoint of the
engine. What it cannot check is whether a sentence says what its test holds.

## The blueprints on ZenBPM

The [VanillaBP blueprints](https://github.com/vanillabp-blueprints/blueprints) have no profile for
ZenBPM of their own; the overlay in [`blueprints-overlay/`](blueprints-overlay) gives them one, and
the nightly's job `blueprints` builds them with it (decision 64 in [`DECISIONS.md`](./DECISIONS.md)).
What a user reads about it is on the wiki page
[Blueprints](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/wiki/Blueprints). The overlay
is:

- `ZenBpmOverlay.java`, a program run as a source file, which adds the profile `zenbpm`, the
  `application-zenbpm.yaml` and the models for ZenBPM beside what a blueprint has for Camunda 8,
  and names a user task its form names by a task definition. Spotless formats it like the sources
  of the modules.
- `blueprints.txt`, one line per blueprint directory: `runs`, or `not-runnable` with its reason in
  the table of the wiki page `Blueprints`, which `BlueprintsOverlayTest` holds to the list.
- `variants/` and `variants.txt`: a model which has to differ on ZenBPM, at the path of the model
  in a twin with `zenbpm` for `camunda8`, and the SHA-256 of the Camunda 8 model it was written
  from, `sha256sum` of that file. Write it from the Camunda 8 model, say in an XML comment why it
  differs, keep the comment which names its origin and licence (the blueprints are Apache-2.0, see
  [`NOTICE`](NOTICE)), and give it a diagram if it has none (`bin/add-bpmn-diagram.py`, see [`AGENTS.md`](AGENTS.md#bpmn-files-carry-their-diagram)).
- `settings.xml`, the Maven settings of the blueprints' build: Maven Central and its snapshots,
  nothing else.

When the nightly reports that the overlay no longer fits, its message names what changed and what
to do: a new blueprint goes into `blueprints.txt` and the page's table, a changed model's variant
gets the change and the new hash. `bin/build-blueprints-on-zenbpm.sh <directory> [<twin>...]` runs
what the job runs, on a laptop with Docker, after the adapter was installed as the page says.

## The examples

[`examples/`](examples) holds complete applications which use the adapter as an application does,
each a business story on Spring Boot (`springboot/`) and on Quarkus (`quarkus/`). They are not
modules of the root POM. Each twin inherits `spring-boot-starter-parent` or imports `quarkus-bom`
and names the adapter by its version, so it can be copied out as it is. `examples/pom.xml` only
lists them, and one `mvn -f examples/pom.xml verify` builds all of them (decision 67 in
[`DECISIONS.md`](./DECISIONS.md)). What a user reads about them is on the wiki page
[Examples](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/wiki/Examples).

The root build still holds them to this repository's rules where those fit an application:
Spotless formats their Java, `BpmnDiagramTest` checks their models, and `MethodOrderTest` and
`TestVisibilityTest` read them. Two rules are left out on purpose. No coverage report reads them,
because they are applications and not code of the adapter. And their tests use the platform's test
support, not `SuppressOutputExtension` of VanillaBP's test utilities, which an application copied
out would not have. `ExamplesTest` checks that

- every example has both twins and is listed in `examples/pom.xml`;
- the twins deploy the same models and configure VanillaBP alike;
- they use the Spring Boot, Quarkus, VanillaBP and adapter versions of the root POM;
- both twins answer on the port the example's `requests.http` sends to;
- every test an example's README or `requests.http` cites exists in both twins.

`EnginePinTest#theExamplesNameThePin` holds the engine image their POMs and READMEs name to the
pin, so moving the pin moves them as well. Renovate reads their POMs and leaves their
platform versions to the root POM.

A new example is a directory `examples/<name>/` with a `README.md`, a `requests.http`, the twins
`springboot/` and `quarkus/` on a port of the example's own, and their modules in
`examples/pom.xml`. Write the model once and copy it into both
twins, and give each twin an integration test which starts the engine of the property
`zenbpm.image` and walks through the README. The job `examples` of `checks.yaml` builds it on
every pull request.

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

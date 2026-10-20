# Decision log

Decisions this repository's code points at. A number is handed out once and never reused or
renumbered, so a citation stays resolvable; a decision which gets overturned keeps its entry,
marked as superseded and naming the entry which replaced it.

A citation in code reads `see decision 3 in the repository's DECISIONS.md`, and it names an entry
of THIS repository only.

The numbers were handed out by the implementation plan in [`docs/`](docs/README.md), so that every
story could cite its decision before the code existed. An entry is added here by the story which
first relies on it, under the number the plan gave it; that is why numbers are missing below. The
drafts of the missing ones are in
[`docs/architecture/02-design-decisions.md`](docs/architecture/02-design-decisions.md).

### 1. The adapter is native, and the Camunda 8 adapter is its template

ZenBPM is a remote, at-least-once, polling BPMS with a query API, which is the shape the Camunda 8
adapter was written for. It is NOT built on the Process-Engine-API adapter: no Process-Engine-API
implementation for ZenBPM exists, the adapter would have had to write one against its own private
command classes, and every capability the PEA cannot express (finding a workflow, listeners,
versions, pushing variables, typed failures) is one ZenBPM's REST API offers and the PEA layer would
have hidden. What is borrowed from the PEA adapter is the raw-XML handling, because a model type of
a Camunda artifact would tie this adapter to Camunda for no reason.

### 3. The REST client is the adapter's own, the gRPC stubs are generated

The adapter uses about twenty REST endpoints. The official Java client trails the engine by two
minors, changed its deploy contract with 1.8, and knows nothing about Quarkus; a generated REST
client would bring a generator into every build and its own reflection into every native image. So
the REST side is a thin `java.net.http` + Jackson client written against the copy of
`openapi/api.yaml` this repository pins, with one method per endpoint and one exception carrying the
status code. The job stream is generated from the pinned `zenbpm.proto`, because that is what
protobuf is for. The pinned copies name the engine commit they came from, and a test diffs them
against the image the integration tests run.

*The last sentence is superseded by decision 18: the engine does not serve its OpenAPI document, so
the copies are checked against the engine's files of the pinned commit instead.*

### 9. Job delivery takes the stream, with a lock the adapter sizes and renews

Only the gRPC stream hands a job to one client at a time; the REST list of jobs is a plain query
which two nodes would answer alike. Every job type is subscribed with a lock duration and a cap of
active jobs, `lock_duration_ms` = the resolved `job-timeout` and `max_active_jobs` = the handler
slots, which the engine keeps once per client id and job type. A job which waits in front of busy
slots is no risk, because the handler extends its lock when it starts and at half the timeout while
it runs, so a handler always works under a full lock. Every command back to the engine goes over
REST, the lock extensions included with the stream's client id, so that the one classification of
decision 10 reads a status code for each of them, and a completion does not depend on the stream
being up.

So the stream sends nothing but subscriptions and unsubscriptions, and an error frame which names
no job answers one of them. The engine takes either without an answer and refuses one with an error
which names its job type only in prose, so the stream cannot tell which one was refused. It logs the
error and opens its call again, which subscribes every job type it still holds again. What the lock cannot cover is a change
of the partition leader, which forgets every lock; the second delivery which follows is the
at-least-once residual.

*"`max_active_jobs` = the handler slots" is superseded in part by decision 37: the cap of a job type
is raised by the number of its jobs parked for their asynchronous completion.*

### 10. What the engine did is read from status codes, not from message text

The engine writes its reasons as prose, and any version of it may reword them. So what a failed call
means is decided by the status of the answer, the method and the path of the call, in one place,
`ZenBpmErrors`, and nothing reads the engine's `message`. A `404` on a job means that the job is
gone, and so does a `409` on its completion or failure, because the engine answers it there for a
command the job no longer takes, and repeating the command cannot change that. A `404` on a message
means that nothing waits for it yet, and a `409` on a lock extension means that the lock is lost.
`400`, `413`, `415` and a key which is not a number are permanent: every attempt is refused the same
way, so an outbox entry is blocked after the first. Everything else may be repeated, `500` and `502`
included, because repeating is the safe answer to a failure which may not come again. The table,
with the reason of each row, is the README's section "Which phase-two failures are repeated".

The first exception of the REST client in a chain of causes answers alone, whatever is wrapped
around it or lies beneath it. Where the engine answered with a status the call does not expect,
that status decides, even when the HTTP client then timed out on the body.

*Superseded by decision 30, which keeps this rule and adds the `404` of a start to the permanent
answers.*

### 12. User-task notifications are polled, not streamed

A ZenBPM user task is a job, typed by its `zenbpm:taskDefinition` or, where it names none, by the
engine's default `user-task-type`, which the scoping leaves as it is, so every application on the
engine shares it. Subscribing that type on the job stream would lock the job for the whole life of
the user task, renewed by the adapter for days, and it would still not say when a user task is
taken away, because the stream never hands out a job the engine terminated. At the pinned commit
`bdd795a3` a cancelled instance and an interrupting boundary event set the job `terminated`
(`handleProcessInstanceInnerCancel` in `pkg/bpmn/engine.go`, `pkg/bpmn/events.go`), and so does a
failure of the job with an error code which a boundary error event or an error event sub-process
catches (`processBoundaryErrorEvent` in `pkg/bpmn/jobs_api.go`,
`pkg/bpmn/engine_error_event_subprocess.go`); nothing is pushed. So the stream never subscribes a
user task's type, and one `ZenBpmUserTaskPoller` per adapter id lists the jobs instead.

The parts below the plan left open were decided by the implementation pipeline in place of the
maintainers, as their policy for the autonomous run asks.

- **What is looked for.** Wiring asks the platform whether a method serves each user task of a
  claimed process, by its task definition and by its element id, and remembers the job type of
  each one served as the deployed model gives it. A user task no method serves, one marked
  `implemented-externally`, is never looked for. The user tasks of a process id a module declares
  without a model are those of the task definitions the held models give to a user task, read
  where decision 41 reads them. Each distinct type is looked for once per adapter id.
- **A look.** Every `user-task-poll-interval` (`PT5S` by default) the poller lists the `active` and
  the `terminated` jobs of each type, page by page to the last. The engine filters jobs by neither
  time nor key, and a key cursor would miss a job created long ago and terminated late, so a look
  costs more the more terminated user tasks the engine keeps: their jobs are deleted only with
  their instance's history (`DeleteProcessInstancesJobs` in
  `internal/cluster/partition/partition_persistence.go`), which an engine without
  `PERSISTENCE_INSTANCE_HISTORY_TTL` keeps forever, unless `history-time-to-live` is set. A job of
  another element type which shares the type, a task of an older version, is left to the stream.
  `PT0S` switches the looks off, and the start warns about it and about an interval below a second.
- **Whose user task.** A job names no process, so the instance cache reads its instance, and the
  job goes to the module which claims the instance's process and looks for the type in it. A job of
  a process no running module claims is never handed to the core, and is not handed over again
  until a module of the adapter id starts, because the platform starts the modules one after the
  other, and the module which claims it may start later than the first look.
- **Once per node.** A job and its event, once the core took the notification or there is nobody to
  notify, go into a set of deliveries. The set forgets a delivery once two looks in a row which
  listed every job listed it no more: its job left the state, or was deleted with its instance's
  history, and is never listed so again. Two looks, because a listing by pages may pass over a job
  while jobs before it leave the state. The plan asked for a set of 100,000 keys which forgets the
  one used longest ago; a look lists the jobs in the same order every time, so once the engine
  listed one more than the bound, such a set would forget each delivery before the next look reached
  it again and hand every listed job over at every look. The set holds what the engine lists
  instead, which a look reads anyway. A job seen `active` is still told as `CANCELED` once it is
  `terminated`; a `terminated` job never seen `active`, after a restart or where the user task came
  and went between two looks, is told as `CANCELED` only, because a creation told for a task already
  gone would set a person to work on it. The core runs a method which takes `CANCELED` for such a
  task as well, and records it. For the same reason a job told as `CANCELED` is never told as
  `CREATED` afterwards, where a node which lags behind (GAPS 20) lists it active again: the core
  checks no closed record before it runs a creation.
- **Once per application.** The delivery id is the job key and the event, `<key>-created` and
  `<key>-canceled`, which no delivery of a job of the stream, a key alone, can be. The core answers a
  notification it recorded from its record, so a restarted node tells nothing twice, as long as the
  record lives. A record the core no longer holds, after `vanillabp.delivery.retention` or once the
  end of its workflow released it, is told again after a restart where the engine still lists the
  job: keep `history-time-to-live` below the retention to rule that out.
- **Threads.** The looks are timed by the timing threads of the adapter id and run on a virtual
  thread of their own; the next look is timed when one ends. Each notification is handed to a
  handler slot with `tryExecute`, which never waits: where no slot is free the look ends, and the
  rest waits for the next one. A job whose notification still runs is not handed over twice.
- **Start and stop.** A module is registered with the poller once its job types are subscribed, and
  a module with user tasks and no job type starts its processing for them alone. Its notifications
  run inside its drain, so the shutdown waits for them (decision 39), and its stop unregisters it
  before the handler hears of the stop; the looks end with the last module.
- **Failures.** A look the engine does not answer is never read as an empty one: nothing is
  remembered, a WARN says it once per outage, and the next look asks again. A failed read of one
  job's instance skips that job only. A notification handed over and not told, because its method
  threw, its instance could not be read or its module stops, is handed over again: by the next
  look first, and after each further failure by a look which waits twice as many looks, at most 64.
  Every look starts at the first page, so a notification which failed at every look would take a
  handler slot at every look before the ones behind it, and enough of them would keep every
  notification behind them from ever running. The failure of a method is said at WARN with its
  stack trace, and a failure right after another one of the method at DEBUG.
- **Limits.** A cancellation is told only while the engine lists the terminated job: a
  `history-time-to-live` shorter than the time from the cancellation to the next look which
  reaches it, a node's downtime included, deletes the job first, and the cancellation is never
  told. A creation and a cancellation reach the application one interval and the time of a look
  after the look before, and later where every handler slot is taken or the engine does not
  answer. Two nodes of the application know nothing of each other's set: where a user task came
  and went between two looks, one node may tell the cancellation and another, reading a lagging
  node of the engine, the creation after it.
- **A wake-up.** Once the core took a notification, it is handed the module's open-task probe of the
  process, as after every delivery of a job and as the Camunda 8 adapter does after a user-task
  listener. The probe answers `CANNOT_SAY` for a user task, so the poller is the only source of a
  user task's `CANCELED`, and the platform's contract allows no second one.

A notification cannot raise a BPMN error into the workflow; a method which raises one is warned
about and the user task waits on.

*"A record the core no longer holds, after `vanillabp.delivery.retention` or once the end of its
workflow released it, is told again after a restart" and "the poller is the only source of a user
task's `CANCELED`" are superseded in part by decision 53: the report of the end of a workflow tells
the user tasks it took away as cancelled, and the poller then tells them neither before nor after a
restart.*

### 13. A class opens its fields one by one, not as a whole

The deployment service, the process service and the engine client of this adapter hold many
fields, most of them collaborators nobody outside the class needs. Which of them a caller may read
belongs to the surface of the class, so an accessor is declared per field, and Lombok's `@Getter`
on the class is refused even where an IDE offers it: it would publish the current field list and
then keep publishing whatever field a later change adds.
`@SuppressWarnings("LombokGetterMayBeUsed")` on such a class keeps that offer from coming back.

### 14. A start asks the engine for numbers, and asks as many on the last day as on the first

While an application boots, the adapter answers questions which read what the engine has stored,
and that grows for as long as the application is in production: how many workflows still run on an
old version, how many tasks are open for a process. A start of ten seconds must not become one of
two minutes because the application did its job for two years. The platform states this rule for
every adapter in its own DECISIONS.md, as decision 19 "A start asks for numbers, and asks as many
questions on the last day as on the first". The Camunda 8 adapter states it as its decision 13, "A
start asks the cluster for numbers, and asks as many of them on the last day as on the first".

For ZenBPM a quantity is read from what the engine counts: the `totalCount` of a search which asked
for a small page, or a statistics endpoint. The items of the page which came back are never counted.
Counting them would grow with the data and would be capped by the page size. On ZenBPM it would also
be wrong. A search over the engine's partitions applies the page size to every partition, so one
page holds up to that many items per partition. `Page.totalCount()` of the REST client is where such
a number comes from.

What does grow is the number of versions of a process the engine holds, one per deployment which
changed a model, and the questions about older versions grow with it. That is on purpose: those
questions are what the check is for.

### 15. One pinned engine version, no release lines

The adapter needs at least the engine's job lock per subscription with its extension (engine commit
`071460cc`: lock per subscription, lock extension, `lock_until`), because the adapter's task
delivery is built on it, and no release carried it when the adapter was started. So during
development the pin is a commit of the engine's `main` and the digest of the image built from it;
before the adapter's first release it becomes an engine release tag. At startup the adapter logs the
engine's version and commit from `GET /system/status` and warns where the commit is not the tested
one. It does not compare versions: the version an engine on `main` reports is the last release's,
the same for every build since. Beyond that the adapter compiles against no engine artifact, so no
pin decides anything else; the REST contract is the copy of `api.yaml` this repository ships, and
the integration tests run against the pinned image. Supported means tested: the pinned image and
nothing newer. Release lines are what Camunda 8 needs because its client is the minimum cluster;
here they would be branches for a contract which has no stability promise yet. If an engine minor
breaks the surface the adapter uses, the answer is a new adapter release against the new pin, and
this entry is revisited.

### 16. The adapter follows VanillaBP's adapter conventions although another organisation owns it

`zenbpm-vanillabp-adapter` belongs to the ZenBPM maintainers at pbinitiative, implements VanillaBP's
adapter SPI and is read by people who know the Camunda and Process-Engine-API adapters. It therefore
keeps their shape: the `core` / `spring-boot` / `quarkus` split with platform modules which only
construct and register, Spotless with the platform's formatting conventions, coverage measured per
platform with the same gate and rule, `test-utils` in every test, a `DECISIONS.md` as the only thing
code cites, a user-facing wiki and a contributor-facing README, and configuration under
`vanillabp.adapters.<id>.*` validated at startup with guiding messages. What differs is what
ownership decides: the licence, the coordinates (groupId `org.pbinitiative.zenbpm`, packages
`org.pbinitiative.zenbpmadapter`), the CI and where releases are published. The Go conventions of
the engine repository do not reach into this one.

*"Spotless with the platform's formatting conventions" is superseded in part by decisions 20, 22
and 23: the formatter is the platform's, but it indents by four spaces instead of two, a wrapped
parameter list closes on a line of its own, one parameter stays on the declaration's line, and a
method body has no blank line at its start or end.*

### 17. Code adapted from the Apache-2.0 adapters keeps its licence and its notice

The repository is MIT-licensed, and a good part of its first version is copied and adapted from
`camunda8-adapter` and `process-engine-api-adapter`, both Apache 2.0. Apache 2.0 allows that inside
an MIT project as long as the licence text and the attributions travel with the code, so every
adapted file keeps any header it had, `LICENSE-APACHE-2.0` holds the licence text, and `NOTICE`
names the two origins. The VanillaBP adapters write no header into their Java files, so an adapted
Java file gets one naming its origin, that it was adapted, and
`SPDX-License-Identifier: Apache-2.0`. A file written from scratch carries the MIT header with
`SPDX-License-Identifier: MIT`. Which is which is decided when the file is created and never
changed afterwards, because the origin of a file is a fact and not a preference.

### 18. The pinned copies are checked against the engine's files of the pinned commit

Supersedes the last sentence of decision 3. The copies of `api.yaml` and `zenbpm.proto` name the
engine commit and image they came from. `bin/check-engine-copies.sh`, run by the pull-request check,
compares them with the engine's files at that commit; the proto may differ by its header and its
`java_package` line only. `EngineUnderTestIT` proves that the pinned image reports that commit, so
the copies are the contract of the image the tests run. A diff against the running engine needs the
engine to serve its OpenAPI document, which it does not; the generated `GetSpec()` only feeds its
request validator. Once it serves the document, that diff replaces the check against the commit.

### 19. Tests come first, helpers last, private methods after the others

A reader opens a test class to learn what is tested, and any other class to learn what it offers.
So in a test class the lifecycle methods (`@BeforeEach` and the like) come before the first test,
and every other method follows the last test: argument sources of parameterized tests, assertion
helpers and private methods alike. In every class, main or test, the private methods follow the
non-private ones. Constructors are not methods for this rule; they stay next to the fields. The rule
covers the members of a top-level class. `MethodOrderTest` of the coverage gate checks it on every
build, because a rule that is only written down drifts. Nested classes are reviewed by eye.

### 20. Java is indented by four spaces

Supersedes in part decision 16, which kept the formatting conventions of the VanillaBP platform. The
owners of this repository chose four spaces per indentation level for every Java file, main and
test, because that is how most Java is written outside the VanillaBP repositories, and so how most
of this repository's readers read it. `formatting_conventions.xml` stays the platform's file with
`indentation.size` and `tabulation.size` raised from 2 to 4. A wrapped line still continues two
levels deeper than its start, now 8 spaces, so it stays apart from the body of a block. Spotless
applies and checks it on every build, like the rest of the file. Everything else of decision 16
stands.

### 21. Test classes and their test methods are package-private

JUnit 5 runs test classes and test and lifecycle methods which are not `public`, so `public` on them
says nothing, and IDEs and static analysis flag it as redundant. Test classes and their `@Test`,
`@ParameterizedTest`, `@BeforeEach`, `@AfterAll` and similar methods are therefore declared without
a visibility modifier. Classes in test sources which hold no test, such as shared helpers, keep the
visibility their callers need. `TestVisibilityTest` of the coverage gate checks it on every build.

### 22. A wrapped parameter list closes on a line of its own

Supersedes in part decision 16, like decision 20. Where the parameters of a method, a constructor
or a record are wrapped, each stands on a line of its own one level (four spaces) deeper than the
declaration, and the closing parenthesis returns to the declaration's indentation, followed by the
`throws` clause and the opening brace:

```java
private ProductionModules.Report report(
    final String platform,
    final String... aggregated
) throws IOException {
```

The parameters then read as a list of their own, the line with `) {` separates them from the body,
and adding a parameter changes one line instead of two. `formatting_conventions.xml` says so with
"indent by one" on the three parameter alignments and `separate_lines_if_wrapped` for the
parentheses of method and record declarations; Spotless applies and checks it on every build. A
declaration short enough for one line stays on one line.

*Superseded in part by decision 23. The last sentence never held: the formatter wraps every list of
parameters, however short. Since decision 23, a list of one parameter stays on the declaration's
line, and a list of two or more is wrapped as shown here.*

### 23. One parameter stays on the declaration's line, a body has no blank line at its ends

Supersedes in part decision 22 and, like it, decision 16. The owners of this repository chose this
layout for every Java file, main and test. A method, constructor or record with one parameter keeps
it on the line of the declaration. With two or more, each parameter stands on a line of its own as
decision 22 shows, however short the list. The body of a method or constructor starts right after
`{` and ends right before `}`, without a blank line at either end:

```java
private static ProcessInstanceRef start(final String businessKey) {
    return client.createInstance(CreateInstanceRequest.latestVersionOf("contract-task"));
}

private FakeEngine(
    final HttpServer server,
    final ExecutorService handlers
) {
    this.server = server;
    this.handlers = handlers;
}
```

One parameter on a line of its own costs two lines and gives no list to read, and a blank line at
the edge of a body separates nothing. Spotless applies and checks both on every build.
`formatting_conventions.xml` removes the blank lines: it sets both
`number_of_blank_lines_at_beginning_of_method_body` and
`number_of_blank_lines_at_end_of_method_body` to -1. The Eclipse formatter cannot wrap by the
number of parameters, so it still wraps every list. A `replaceRegex` step of Spotless in the root
POM then joins a list of one parameter back onto its declaration, even where that line grows longer
than the 120 columns the formatter aims at.

### 24. `by-adapter` stays the default and is refused while deploying

VanillaBP's contract for adapters says what a BPMS without an isolation of its own does about the
default mode of name-clash avoidance: it keeps `by-adapter` and refuses it while deploying, with
`scoping.validateNativeIsolationSupported(...)`, whose message names `use-prefix` and `none` as the
two ways out. ZenBPM has no tenant and no namespace, so that is its case, and the
Process-Engine-API adapter does the same. Making `use-prefix` the default instead would let an
application which moves to ZenBPM from another adapter end up with prefixed identifiers without
anybody choosing them. The price is one line per adapter id, for example
`vanillabp.adapters.zenbpm.name-clash-avoidance: use-prefix`, which may also be set per workflow
module or workflow. The message which ends the start names that line, and the user documentation
says so on the first page an application reads, once it is written.

A mode set for one workflow is refused as well where it is `by-adapter`, because the platform's
check reads the mode of the workflow module, and the workflow would otherwise be deployed with
plain identifiers which nothing isolates.

The plan drafted this decision the other way round, as decision 2 with `use-prefix` as the default.
That draft was superseded by this entry before any code relied on it, so it never entered this log.

### 25. The aggregate id is the business key and a variable, and a workflow the engine started is found through its key

The engine finds an instance by its `businessKey` and never by a variable. So every start the
application makes carries the aggregate's id twice: as the business key, and as a variable named
after the aggregate's id attribute, after the values the aggregate shares, which the correlation
keys the adapter injects read (`ZenBpmSharedValues`). The plan's draft of decision 6, which derived
the aggregate of a workflow the engine started from its instance key, never entered this log: the
platform derives no id from a key any more.

A workflow the engine starts itself, by a message or a timer, has no business key, so no search
finds it. It is found through the key of its instance, which the core hands to
`awarenessOfWorkflow` as its fourth argument: from the row written when the workflow started, from
the record of a task's delivery, or from the election cache. The id of a workflow is never its
instance key, so the key is a hint, and it may only make a YES shorter. The probe answers `ACTIVE`
where the instance runs, belongs to a process of the scope and names the aggregate by its business
key or its id variable, and leaves everything else to the business-key search: another state,
because an aggregate may carry a second workflow once its first ended and the key may name the
first; another process or another aggregate, because the core hands the same key to every adapter
of its list; a `404`, which cannot tell a workflow the engine removed after it ended from one it
never held; a `502`, which the engine answers for a key of a partition it does not have; and every
failure. So the key never turns an answer into `UNKNOWN_TO_BPMS`, as the platform's contract for
adapters asks, and the Camunda 8 adapter does the same.

*Planned:* the marker job of the start event (decision 26 of the plan's drafts) builds the context
of such a workflow and hands it to `startWorkflowByBpms`. The application's method names the
aggregate, and the adapter writes `result.variables()` into the instance with `PATCH
/v1/process-instances/{key}/variables` before it completes the marker job, so a second delivery
finds the id and answers `created() == false`. A crash between the commit of the aggregate and that
`PATCH` lets the second delivery build a second aggregate, unless the application's id is
deterministic; Camunda 8 has the same window. Until then, a workflow the engine starts gets no
aggregate.

*The planned paragraph is built: the handler of the start marker is decision 26 and decision 46,
and it writes the variables only where the instance does not carry the id yet.*

### 26. Lifecycle notifications are marker service tasks after every start event and before every end event

The engine has no execution listeners and no event stream (GAPS 8), so the adapter learns about the
start and the end of a workflow from service tasks of its own which it writes into the model. Their
jobs are ordinary jobs, delivered at least once, and completing them is what lets the workflow go on
or end. VanillaBP expects to hear about EVERY start of a workflow, plain and message starts
included, because a workflow somebody started past VanillaBP needs an aggregate as much as one a
timer started. So a start marker follows every start event (none, message, timer) of the top-level
process, wherever `startsAWorkflowOfItsOwn` says the process starts a workflow of its own.
Sub-processes and event sub-processes get none, because the engine runs them as instances of their
own, and a called process gets neither marker. An end marker goes in front of every end event of the
top-level process wherever `workflowEndedHandlerExists` answers yes, which it also does when
`vanillabp.delivery.release-on-workflow-end` is on. Every start event is reported to
`validateBpmsInitiatedStarts`, those of called processes included, so the core can refuse a start
the engine fires by itself where no aggregate could be built. A workflow ends `COMPLETED` or
`CANCELED`; a cancelled instance passes no marker (GAPS 9). For the application's own start the
marker finds the name there already, and `created() == false` is the normal answer. The cost is one
job and one transaction per started workflow, the price VanillaBP names for Camunda 8.

What markers cannot report: a workflow of a version deployed before the markers existed passes
none, so it gets no `@WorkflowEnded` and writes no start row. Polling the states of the instances
was rejected, because it grows with the number of instances and cannot name the end event, and so
was reading the engine's database, a schema of another product.

The plan drafted decision 5 for markers at timer starts and ends only. This entry replaced it
before any code relied on it, so decision 5 never entered this log.

*Decided by the implementation pipeline in place of the maintainers:* the end marker is written in
the same change as the handler which serves its job. A marker nobody serves stops every workflow in
front of its end event, so until that change no end marker is written, and an application is told
nothing about the end of a workflow.

*"An end marker goes in front of every end event of the top-level process" is superseded in part by
decision 52: an error end event gets none, and an end event reports the end of the workflow only
where nothing else of it runs.*

### 27. A failed handler is failed at the engine, with retries and backoff

Since the engine counts retries and waits a backoff (engine commit `bc3384b3`, contained in the
pinned `bdd795a3`), the adapter does what the Camunda 8 adapter does: a `@WorkflowTask` method which
threw, after the core rolled its transaction back, is reported with `POST /v1/jobs/{key}/fail`
without an error code. The plan's draft of decision 8, which left such a job to its lock and counted
the attempts in the adapter, never entered this log, and neither did its local retry or its key
`max-redeliveries`.

The failure carries no `retries`: the engine reads an absent value as one less than now, and with
retries left it keeps the job active and hands it out again after the backoff, to whichever node
has a slot; at the last one it raises an incident naming the attempts and the message. The message
names the task, the aggregate and the exception's type and message, because a
`NullPointerException` alone says nothing. The failure names its delivery (`deliveryToken`), so a
failure repeated after its answer was lost counts once and the engine answers it like the first,
and a failure of a delivery the engine handed out again is answered `409` and changes nothing: the
newer delivery decides. It names the job stream's client id as well. At the pinned commit a failure
which names its delivery releases that delivery's lock whoever reports it, and one which names none
releases it only where the client id is the lock's holder (`releaseLockOfFailedDelivery` in
`internal/cluster/jobmanager/server.go`); without both the lock stands until it lapses, whatever the
backoff says (measured: the job came back after its job-timeout of thirty seconds). `retryBackoff`
is sent where a level of the configuration sets `retry-backoff`, in whole seconds; decision 38 says
why there is no default.

The adapter keeps no state per job. A delivery for a workflow this application does not own
(`DeliveryOfAnUnknownWorkflowException`) is failed with `retries: 0` and the exception's message,
and logged as a WARN without a stack trace: an operator sees an incident naming the reason at once.
That is stricter than Camunda 8, which counts such a job down like any other failure, and it is
chosen on purpose, because no retry can make the workflow known.

### 28. The workflow id is the key of the root instance of the task's own process

The adapter names one workflow by one id wherever it names it: `reportStartedWorkflow` after a
start, and, once they are built, the delivery of a task (`TaskInvocationContext.getWorkflowId`), the
end of a workflow (`WorkflowEndedContext.getWorkflowId`) and the start marker's `nativeInstanceId`.
VanillaBP finds the open tasks of a workflow by that id and cancels them when the workflow ends, so
all of them must name the same value.

The engine runs an embedded sub-process, an event sub-process and every iteration of a
multi-instance element as a child instance of the same process (`processType` `subprocess` and
`multiInstance`), and a job names the child instance it waits in, not the root (`ZenBpmProbesIT`
against the pinned engine). So `ZenBpmInstanceCache.workflowIdOf` walks up
`parentProcessInstanceKey` while the instance is such a child, and stops at a root instance
(`default`) or at an instance a call activity started (`callActivity`). A task of a called process
names the called instance, because VanillaBP cancels the open tasks of exactly that instance when
it ends (platform decision 124). A start creates a root instance, so its key is the workflow id as
it is.

A job carries no key of its root instance (GAPS 22), so the walk reads the facts the cache keeps,
and only an instance on the way which the cache does not hold costs a request. An instance the
engine does not know ends the walk without an answer and is not kept, because a node which lags
behind may know it a moment later.

### 29. A BPMN file carries its diagram

The owners of this repository chose this for every BPMN file in it, test fixtures included. A BPMN
file is read by people as much as by the engine: a reviewer opens it in a modeler, a fixture shows
the shape of the case it tests, and a file without diagram interchange opens as an empty canvas. So
every `.bpmn` file carries a `bpmndi:BPMNDiagram`: a shape with bounds for every flow node, boundary
event and pool, an edge with at least two waypoints for every sequence flow, and nothing drawn which
the model does not hold. Save a file from a modeler, or give a file written by hand a generated
diagram with `bin/add-bpmn-diagram.py`; the engine ignores it either way.

Two things are left out. A file which is no model on purpose, such as one which is not well-formed
XML because its refusal is what a test checks, is named with its reason in the check. And BPMN
written inline in a Java test is a fragment of the one construct the test is about, which nobody
opens in a modeler. `BpmnDiagramTest` of the coverage gate checks every `.bpmn` file of the
repository on every build.

### 30. What the engine did is read from status codes, and a start of a process it does not hold is permanent

Decided by the implementation pipeline in place of the maintainers, as their policy for the
autonomous run asks. It supersedes decision 10 and repeats all of it, so a reader needs this entry
only.

The engine writes its reasons as prose, and any version of it may reword them. So what a failed call
means is decided by the status of the answer, the method and the path of the call, in one place,
`ZenBpmErrors`, and nothing reads the engine's `message`. A `404` on a job means that the job is
gone, and so does a `409` on its completion or failure, because the engine answers it there for a
command the job no longer takes, and repeating the command cannot change that. A `404` on a message
means that nothing waits for it yet, and a `409` on a lock extension means that the lock is lost.
`400`, `413`, `415`, a key which is not a number, and a `404` of a start (`POST
/v1/process-instances`) are permanent: every attempt is refused the same way, so an outbox entry is
blocked after the first. Everything else may be repeated, `500` and `502` included, because
repeating is the safe answer to a failure which may not come again. The table, with the reason of
each row, is the README's section "Which phase-two failures are repeated".

The first exception of the REST client in a chain of causes answers alone, whatever is wrapped
around it or lies beneath it. Where the engine answered with a status the call does not expect,
that status decides, even when the HTTP client then timed out on the body.

What is new against decision 10 is the start. The engine answers a start with `404` where it holds
no process of the id: nothing deployed the workflow module to this engine, the deployment failed
and the application booted anyway because its `deployment-failure` policy is `warn`, or the engine
lost its data. Only a deployment changes that, and the next start of the application is what
deploys. So repeating the start until the outbox gives up would only delay the message an operator
needs. The start's handler rethrows the answer with a message naming the workflow module, the
process, the identifier the engine was asked for and the key `deployment-failure`, and the
engine's own answer stays its cause, so the classification still reads the status. The Camunda 8
adapter makes the same answer permanent (`Camunda8RefusedStart`), and the platform's contract for
adapters names "an identifier which does not exist" as a failure to report as not repeatable.

The engine starts an instance on the leader of a partition, and reads the process there with no
wait for replication (pinned commit `bdd795a3`: `ZenNode.CreateInstance` picks a partition leader,
`Server.GetRandomEngine` takes only engines of partitions the node leads, and
`FindLatestProcessDefinitionById` reads the local store). A deployment writes the process to every
partition through its leader and answers only after each one took it. So a `404` is not a node which
lags behind a deployment, which a repetition would cure. What remains is a change of the partition
leader in a cluster of several nodes between a deployment and a start moments later: the new leader
may not have applied the deployment yet. Such a start is blocked although the process is there, and
an operator starts the workflow again. The risk is accepted, because it needs a leader change in the
seconds after a deployment, while the permanent answer saves every application which starts against
the wrong engine from an outbox repeating a start for as long as it keeps the entry.

### 31. `history-time-to-live` is sent in whole seconds, and `PT0S` is sent too

Decided by the implementation pipeline in place of the maintainers, as their policy for the
autonomous run asks.

The engine takes the time to live of a started workflow as its own text (`30d`, `1M5d8h`), reads it
into a duration of nanoseconds, and keeps it in whole seconds, cut off by
`int64(historyTTL.Seconds())` (pinned commit `bdd795a3`: `ParseTTL` in
`internal/cluster/types/ttl.go`, `resolveHistoryTTL` in `pkg/bpmn/engine.go`, the column
`history_ttl_sec`). So the adapter sends the resolved `history-time-to-live` as whole seconds,
`2592000s` for `P30D`, which the engine reads back exactly, and nothing where no level sets the key,
so the engine's own default applies.

A value the engine would change is refused while the application starts, naming the key: one with a
fraction of a second, because the engine would cut it without a word, `PT0.5S` to no time at all;
and one above `PT2562047H47M16S`, about 292 years, the most a duration of nanoseconds holds, because
the engine would refuse every start with `400`. Both are typos rather than intentions, and the
validation already refuses a negative value the same way.

`PT0S` is sent, as `0s`. The engine treats an explicit zero as a time to live of its own: it keeps
the ended workflow until its next clean-up, which runs every thirty seconds, while without the key
its default applies, which may keep the workflow forever. An application which wants nothing kept
says so with `PT0S`. `ZenBpmTimeToLiveIT` holds both against the pinned engine.

### 32. A task id of a partition the engine does not have is unknown to it

Decided by the implementation pipeline in place of the maintainers, as their policy for the
autonomous run asks.

The core asks every adapter of a workflow's list about a task with the same task id, where no
record of a delivery routes the question: `WorkflowLocator` walks the prioritized adapters and hands
each of them the id the call names (`MigrationProcessService#probe` of the platform). So the task
probes of this adapter are asked about ids which another adapter handed out: a job key of Camunda 8,
or a key of another ZenBPM engine, which may name a partition this engine does not have. A key
carries the partition which holds it in bits 12 to 21 (`zenflake.GetPartitionId` in
`pkg/zenflake/snowflake.go` at the pinned commit `bdd795a3`), and the engine answers a read of a
job or an instance of such a partition with `502 CLUSTER_ERROR`, the answer it also gives where a
partition it has cannot be reached (`ZenBpmRestClientContractIT`). Read as unavailable, as decision
30 reads every `502`, such a key would stop the walk for good: `BPMS_UNAVAILABLE` must not fall back
to the next adapter, so the adapter which holds the task would never be asked.

So after a `502` for a job, the task probes read the engine's partitions from `GET /system/status`.
A key whose partition the engine does not report is `UNKNOWN_TO_BPMS`: this engine handed it out to
nobody. A `502` for a partition the engine reports stays `BPMS_UNAVAILABLE`, and so does a status
which does not come or names no partition at all: an engine always has a partition, so a status
without one says nothing about the key. The extra request is made only after a `502`, so a task id of this engine costs
what it cost before. A partition the engine adds later is reported by the next status, which is read
fresh every time and never kept. Decision 30 is not changed: what a failed call means is still read
from its status, and this entry only answers what the task probes make of a `502` once they asked
the engine one more question. The workflow probe needs none of this, because a key there may only
make an `ACTIVE` answer shorter, and every `502` falls through to the search (decision 25).

### 33. A job whose instance names no workflow aggregate is failed without retries

Decided by the implementation pipeline in place of the maintainers, as their policy for the
autonomous run asks.

The handler of a job names the workflow aggregate by the variable which carries the aggregate's id,
else by the business key, read in the job's own instance and, for an instance which is part of its
workflow, in the workflow's root instance. An instance which has neither was started past VanillaBP,
over the engine's REST API or by a tool, and nothing about the job will change that: every delivery
of it finds the same instance.

Leaving such a job to its lock would hand it out again after every `job-timeout`, for as long as the
instance lives, and nothing but a WARN in the log of one node would ever say so. So the handler fails
it at once, with `retries: 0`, so the engine raises an incident, and with a message which names the
instance, the missing business key and the name of the id variable, so the operator who reads the
incident can set the variable and resolve it, or cancel the instance. The failure names the
delivery's `deliveryToken`, so a failure repeated after a lost answer counts once, and the job
stream's `clientId`, so the stream's lock is released. The log line is a WARN without a stack
trace, because the message says all there is. The Camunda 8 adapter fails such a job as well.

A job the platform's core refuses because this application holds no aggregate of the id it names
(`DeliveryOfAnUnknownWorkflowException`) is failed the same way, with the core's message: the cause
is as permanent for the delivery, and the incident carries the core's explanation where an operator
reads it.

### 34. Before a completion, an update which finds no instance is repeated, one which finds it ended ends the delivery

Decided by the implementation pipeline in place of the maintainers, as their policy for the
autonomous run asks.

Before a job is completed, the handler writes the values the aggregate shares into the engine
(`PATCH /v1/process-instances/{key}/variables`, decision 35). At the pinned commit `bdd795a3` the
engine reads the instance first, on a follower of its partition (`GetProcessInstance` with
`GetPartitionFollower` in `internal/cluster/node.go`), and answers `404` where that node does not
hold it and `409` where its state is neither active nor failed; only then does it update it on the
leader (`UpdateProcessInstanceVariables` in `internal/rest/server.go`). Measured against the pinned
image (`ZenBpmJobHandlingIT`): a completed and a cancelled workflow are answered `409`, and a key
nobody holds `404`.

The engine keeps an instance after it ended until its history is removed, so for an instance whose
job was just delivered a `404` means a node which has not caught up yet (GAPS 20), not an instance
which is gone. So the handler repeats the update within the job's lock, through the same bounded
repetition as every command of a delivery, and leaves the job to its lock where the node still has
not caught up. The same holds for the read of the job's instance before the invocation: a `404`
there is repeated and then left to the lock, not taken as a cancelled instance, which the engine
answers with its state.

A `409` is final: a node which lags behind knows an instance in an earlier state, never in a later
one. The workflow ended or was cancelled while the handler ran, which is what at-least-once delivery
leaves over, like a completion which finds the job gone. The handler logs it at WARN and does not
complete the job, which the engine would refuse anyway.

### 35. The shared values are written into every instance from the task's own up to its workflow's root

Decided by the implementation pipeline in place of the maintainers, as their policy for the
autonomous run asks.

Measured against the pinned engine (`ZenBpmJobHandlingIT`): an instance of an embedded sub-process
holds a copy of the variables of the instance it is part of, taken when it started. A variable
written into the root afterwards does not reach the copy, and what is written into the sub-process,
by an update or with the completion of its job, is gone when it ended. A job of a task inside a
sub-process names the sub-process's instance (GAPS 22).

So writing the values a handler computed into the job's own instance alone would leave a gateway
behind the sub-process deciding on the values of the last start, and writing them into the root
alone would leave a gateway inside the sub-process on its copy. The handler therefore writes them
into every instance the walk to the workflow id passes (decision 28): the job's own instance first,
the workflow's root last, and only then completes the job. A call activity ends the walk as it ends
the workflow: what the called workflow computed reaches its caller through the output mappings of
the call, as the model says. A job of a task outside any sub-process costs one update, as before;
each level of nesting costs one more, and the walk itself reads only instances the cache does not
hold yet.

### 36. A job's lock is counted on the application's clock, and each extension names its delivery

Decided by the implementation pipeline in place of the maintainers, as their policy for the
autonomous run asks.

A job the job stream delivers carries `lock_until`, the moment its lock lapses on the clock of the
partition's leader. The engine's own description of the field (`WaitingJob` in
`pkg/zenclient/proto/zenbpm.proto` at the pinned commit `bdd795a3`) says to count the lock duration
of the subscription from the moment of receipt where the clocks of engine and application cannot be
trusted to agree. The adapter does that in every case: the lock of a delivery lapses at its receipt
plus the `job-timeout` it was subscribed with, and an extension lasts from the moment it was sent
plus the `job-timeout`. The dispatcher, the lock renewal and the bounded repetition of a handler's
commands all go by that moment.

Taking the earlier of the engine's moment and the counted one would guard against one case only, a
`job-timeout` above the engine's `jobManager.maxLockDurationMs` (24 hours by default), which the
engine lowers every lock to. It would harm a far more ordinary one: an engine clock which runs behind
the application's makes every lock look shorter by as much, so the renewal extended more often the
more the clocks drifted apart, and without any pause once the drift reached the `job-timeout`, from
which on the dispatcher also dropped every job before its handler started. So the engine's moment is only compared:
where it is earlier than the counted one by more than a second, a WARN says so once per adapter id,
naming both causes and the key to lower.

Every extension of a lock names the delivery it belongs to (`deliveryToken` of
`POST /v1/jobs/{key}/extend-lock`). Where a delivery's lock lapsed and the engine handed the job to
the same client again, an extension for the earlier delivery is answered `409` instead of extending
the later delivery's lock (measured against the pinned image, `ZenBpmJobLockIT`), so the handler of
the earlier delivery learns that a second delivery may run beside it, and does not keep the later
one's lock for it.

### 37. The cap of active jobs of a type is raised by its jobs parked for their asynchronous completion

Decided by the implementation pipeline in place of the maintainers, as their policy for the
autonomous run asks. It supersedes in part decision 9, whose cap of active jobs was the number of
handler slots.

A task the application completes later is parked: its handler returns, and the job's lock is
extended by `async-task-lock-renewal`, an hour by default. The engine counts every job whose lock
has not lapsed against the cap of active jobs of its client and job type, a parked one included,
although no handler runs for it (`capacityLocked` in `internal/cluster/jobmanager/server.go` at the
pinned commit `bdd795a3`). With a cap of the handler slots, as many tasks left open of one type as
there are slots would stop every further job of that type for as long as they stay open: after a
lock lapses, the engine hands out the oldest job first, which is the parked one again. Both reviews
of the story measured it against the pinned engine, the second job of a type never arrived while
the first was parked under one handler slot.

So the job stream keeps the jobs it parked, each with the moment its lock ends on the application's
clock (decision 36), and subscribes their type again with its cap raised by the number of them
whose lock has not lapsed. The engine replaces the settings of a client which subscribes a type it
already subscribed (`subscribeClient` at the same commit). A parked job stops counting when it
arrives again or its lock lapsed; one completed or taken away meanwhile counts until
its lock would have lapsed, which lets in at most one job too many per such task, and that job
waits for a free slot like any other. A reconnect sends the raised cap. The handler slots still
bound how many handlers run.

The engine lowers a cap above its `JOB_MANAGER_MAX_ACTIVE_JOBS_CAP` (1000 by default) to it, so past
that number of parked jobs of one type a new job waits again until a parked lock lapses. The stream
WARNs once where it asks for more, naming that setting and `async-task-lock-renewal`; the adapter
cannot read the engine's setting. GAPS 28 records the cause.

Subscribing every type with the engine's largest cap instead was rejected: it lets one node of the
application take every waiting job of a type, while the other nodes stay idle and the jobs wait in
front of its busy slots.

*"one completed or taken away meanwhile counts until its lock would have lapsed" is superseded in
part by decision 40: a parked job whose task the application completes or cancels on the node which
parked it stops counting as soon as the engine dropped its lock.*

### 38. The adapter writes no default of retries into a model and sends a backoff only where one is configured

Decided by the implementation pipeline in place of the maintainers, as their policy for the
autonomous run asks.

How many attempts a job has is the model's (`zenbpm:taskDefinition retries`), else the engine
operator's (`JOBS_DEFAULT_RETRIES`, 1 by default at the pinned commit). The adapter neither writes a
default `retries` into a task definition while it rewrites the model nor sends a number with a
failure. The Camunda 8 adapter writes none either and leaves the count to the model and Zeebe;
decision 4 of the plan's drafts never writes over what the modeller wrote; and the engine has a
setting for exactly this. The price is the engine's default of one attempt: a model without
`retries` on an engine left at its default raises an incident at the first handler which throws.
The README says so, with the two ways out: `retries` in the model, or `JOBS_DEFAULT_RETRIES=3` at
the engine for Zeebe's default.

`retry-backoff` has no default either. Where no level of the configuration sets it, the failure
carries no `retryBackoff`, and the engine applies the `retryBackoff` of the task's definition, else
its `JOBS_DEFAULT_RETRY_BACKOFF`. The engine prefers a backoff the failure carries over the model's
(`backoffForAttempt` in `pkg/bpmn/job_retries.go`), so an adapter default would replace every
policy a modeller wrote, a list of growing waits included. The Camunda 8 adapter sends a default of
ten seconds, because Camunda 8 has no backoff in the model to fall back to. So `retry-backoff`, like
`history-time-to-live`, is a key set only by somebody who configures this adapter: written without
`rest-address`, it means a configuration which was started and has to be finished. The engine caps
any backoff at its `JOBS_MAX_RETRY_BACKOFF` (a day by default), so the adapter warns about no upper
bound, and it refuses a fraction of a second while the application starts, which the engine would
refuse with `400`.

### 39. A shutdown waits once for the handlers of every workflow module, and fails no job it cut off

Decided by the implementation pipeline in place of the maintainers, as their policy for the
autonomous run asks; the Camunda 8 adapter decided the same (its decisions 6 and 51).

The platform stops the workflow modules one after another. Each stop unsubscribes the module's job
types and marks the module as stopping; the stop of the last running module of an adapter id then
waits for the handlers of every module which stopped, all together, at most `shutdown-grace`, and
names each handler which still runs after it with its job key, task and workflow aggregate. A wait
per module would spend the grace once per module, and only the whole shutdown has a budget the
application can compare the number with: the time the runtime grants the application to stop
before it kills it, Kubernetes' `terminationGracePeriodSeconds`, thirty seconds by default. Spring
Boot's `spring.lifecycle.timeout-per-shutdown-phase` is not such a budget here: the platform's
deployment service implements only the synchronous `SmartLifecycle#stop()`, which Spring runs to
its end before it starts to count that timeout (`DefaultLifecycleProcessor#doStop`, Spring
Framework 7.0.9). So `shutdown-grace` stays at the adapter level and is not resolved over the four
levels, and one above thirty seconds is warned about at startup. Every run of a handler is waited
for on its own: a delivery of a job whose lock lapsed may run beside the earlier one on the same
node, and the one which returns first does not end the wait for the other.

While a module stops, a job which reaches its handler is not started, an outcome a handler reached
is still reported, because its work is committed, and a handler which threw is not failed at the
engine: the failure would spend one of the job's retries (decision 27) for a cause which is the
shutdown, not the job. The module's state is read after the handler returned, so a handler which
started before the stop and threw during it costs no retry either. The client factory closes the job
stream after the wait, and the engine then drops every lock of the adapter's client
(`removeClient` in `internal/cluster/jobmanager/server.go` at the pinned commit `bdd795a3`): the
jobs a cut-off handler held and the ones which waited for a handler slot go to another node at once
(measured: within about a tenth of a second, against a job-timeout of a minute), with their retries
untouched. The delivery record of the platform decides whether the work runs again.

### 40. A task the application completes or cancels is ended without a delivery, and stops counting where it was parked

Decided by the implementation pipeline in place of the maintainers, as their policy for the
autonomous run asks. It supersedes in part decision 37, whose parked job counted until its lock
would have lapsed once its task was completed.

The application completes or cancels a task it left open through the process service, on
whichever node of the application it runs, and it holds the task id only, not the delivery of the
job stream which parked it. At the pinned commit `bdd795a3` the engine completes a job whoever asks
and without any delivery, and drops its lock (`completeJob` and `releaseLock` in
`internal/cluster/jobmanager/server.go`: "a REST client which never held the lock may complete a
job"). It fails a job whoever asks as well, but a failure which names no delivery releases the lock
only where the client id it names holds it (`releaseLockOfFailedDelivery`), and a command it refuses
because the job ended drops the lock too (`releaseLockOfEndedJob`). A job which ended without a
command, taken away by an event of the model or by the end of its workflow, keeps its lock until the
lock lapses: nothing else removes it. `ZenBpmJobLockIT` measured the three cases against the pinned
image: a completion by a client which names no client id, and a failure which names the holder's
client id, let the holder's next job of the type in at once; a failure which names another client
id leaves the slot taken.

So phase two of a completion and of a cancellation sends no delivery token, and a cancellation, a
failure with the error code, names the job stream's client id of the node which sends it. Reading
the job's current token and sending it was rejected: where the engine hands the job out again
between the read and the failure, it answers the failure `409`, which reads as a job which ended,
and the cancellation would be lost.

Where the engine dropped the lock, after a completion, after a cancellation sent from the node which
parked the job, or after a command refused because the job ended, that node ends the job's count at
once, so the cap of its type does not let in a job more than it has slots. Where nothing was sent,
because the job had ended already, the count stays until the lock would have lapsed, as long as the
engine counts it. On any other node nothing is counted: there the holder's count of a completed job
lets in at most one job too many per such task until the lock would have lapsed, which waits for a
free slot like any other, as decision 37 allows; a cancellation sent from another node leaves the
engine's lock and the holder's count alike.

### 41. A process id the application declares without a model is served where a model the engine holds makes its task definitions tasks

Decided by the implementation pipeline in place of the maintainers, as their policy for the
autonomous run asks.

A workflow module may declare a BPMN process id under which it deploys nothing, which is how a
renamed process keeps being served: the workflows started before the rename still run in the
engine, on the models an earlier version of the application deployed under the old id. Under
`use-prefix` a job type carries the process it was deployed with, so the jobs of those workflows
carry names no subscription of the deployed processes asks for, and nobody notices, because a job
nobody takes has not failed. The platform names, per such id, what the application's methods serve
(`taskWiringOfProcessesNobodyDeployed`): today the task definition of every method, and nothing for
a method wired by element id. The adapter composes a job type from each, scoped by the id as the
deployment of the id scoped it, which is the shape of the Camunda 8 adapter (its decision 19).

Composing alone is not enough on this engine. At the pinned commit `bdd795a3` the engine creates a
user task as a job like any other (`createUserTask` calls `createInternalTask` in
`pkg/bpmn/usertasks.go`), typed by its `zenbpm:taskDefinition`, which the deployment scoped like the
type of a task, and the job stream hands out every job of a subscribed type. So a type composed from
the task definition of a method serving a user task is that user task's type, and the job stream
would hand the user task to a handler, which would run it and complete it like a service task: a
task meant for a person done by nobody. Camunda 8 opens both kinds of subscription and lets the
wrong one stay idle, because there a user task is no job.

So the adapter reads the models the engine holds under the id, every version, because the
workflows still running may run on any of them: one request for the versions and one per version.
A composed type is subscribed only where one of those models gives it to an element which creates a
job for a worker and none of them gives it to a user task: where one version gives a type to a task
and another to a user task, a job of that type cannot be told apart by its type, so the type is not
subscribed and the workflows of the version with the task stand still at it. One they give to a
user task is named at startup and left to the handling of user tasks, which reads it from the same
models; one they give to no element is named as well and subscribes nothing, because no job of
those workflows carries it. A task served elsewhere names no task definition and gives no type. Where the models cannot be read,
nothing is subscribed for the id and a WARN says so, because a guess could serve a user task as a
service task; the next start of the application serves those workflows. The models are read while
the workflow module starts its processing, right after its deployment reached the engine.

A method wired by element id names no task definition, so no job type can be composed for it, and
its workflows stand still; the start says so and names both ways out, as Camunda 8 does: wire the
method by task definition, or keep deploying the old model under its old id until its workflows
ended. Where a composed type is one a deployed process subscribes already, under `none` or under
`use-prefix` without per-process task definitions, nothing is subscribed twice, but the declared id
is recorded with its types all the same, because a job goes to the workflow module which serves the
process of its instance. A job of those workflows carries the variables of its scope, and inside a
multi-instance body only what the model held maps into it, which the version of the application
that deployed it wrote for the names its methods read then.

### 42. Phase one of a correlation judges the message by every version the engine holds of the module's processes

Decided by the implementation pipeline in place of the maintainers, as their policy for the
autonomous run asks.

The engine keeps no message nothing waits for: it answers `404` and drops it (pinned commit
`bdd795a3`: `publishCorrelatedMessageByName` and `publishDefinitionMessageByName` of
`internal/cluster/node.go` find no subscription, and `POST /v1/messages` carries no time to live).
So a message name no model waits for, a typo or a renamed message, would make phase two repeat its
publication for as long as `vanillabp.outbox.wait-for-visibility-at-most` allows, about four hours
by default, before the entry is blocked. Phase one refuses such a name where the application called,
and a name the newest version of a process starts it with as well, naming `startWorkflowByMessage`,
also where an older version waits for it: the engine hands a message no workflow waits for under its
key to a start event of the same name, and only the newest version of a process holds start
subscriptions (`ImportProcessDefinition` of `pkg/bpmn/definition_import.go` removes those of the
version before), so a correlation which comes before its workflow waits would start a new workflow.
It never asks the engine whether something waits: a subscription may not exist yet.

Which models count decides whether a correct correlation is refused. A workflow still running may
run on an older version of its process and wait for a message the deployed version renamed or
dropped, so the models of this start alone would refuse a correlation its workflow waits for. So the
deployment reads, once it took every file, the messages of every version the engine holds of each
process it deployed: the deployed version from the answer the deployment got back, and every other
version with one request for the list and one per version; the engine counts versions from one, so a
first version asks nothing more. So every start of the application costs one request per version of
each deployed process, a number which grows with every deployment of a changed model. That is the
answer of the Camunda 8 adapter, which judges the name by the models its cluster holds (its decision
19), read here while deploying. Another node, of a newer version of the application, may deploy a
newer version later, which waits for a message the picture of this start does not know. So, as on
Camunda 8, a refusal rests only on models read when it is made: before phase one refuses a name, it
reads every version the engine holds of the module's processes again, which is the only request
phase one makes, and only on its way to a refusal. Where the picture is not complete the check says
nothing, because a check which cannot see every model that may carry the name must never refuse: the
workflow module deployed no process in this start, a version could not be read (a WARN names it, and
the next start reads it again), or the module serves a process it brought no model for, such as the
old id of a renamed process (decision 41), whose models this reading does not cover. A message the
models declare under the plain name is compared by the name the engine holds, the one phase two
publishes.

### 43. A `404` on publishing a message is handed back without counting, although the engine also answers it after a correlation

Decided by the implementation pipeline in place of the maintainers, as their policy for the
autonomous run asks.

At the pinned engine a `404` from `POST /v1/messages` has two causes. Mostly nothing waits for the
message under its key yet, and nothing was published. But where the message reached the waiting
element and the workflow failed right behind it on something the engine does not find, a called
process which is not deployed for example, the engine answers the same `404` for a publication
which correlated (GAPS 29). Decision 30 reads a failure from status, method and path alone, and the
two answers differ only in the engine's prose, so the adapter cannot tell them apart.

The publication is handed back as a `PhaseTwoRetryLater` in both cases. A counted failure instead
would block the entry after a few attempts and so lose the message published before its workflow
waits for it, which is what the outbox stands in for while the engine keeps no message (GAPS 5).
It would not remove the second receipt either, because a counted failure is repeated too. What the
second cause costs instead: the entry is repeated, with a message which names an incident behind the
waiting element as the other reading, until `vanillabp.outbox.wait-for-visibility-at-most` passed,
and where the workflow, after the incident was resolved, waits for the same message under the same
key within that window, a repetition is received a second time. A contract test of the client holds
the engine's answer, so a version of the engine which answers otherwise is noticed when the pin
moves.

### 44. The start markers are written while wiring, under a job type which names the process

Decided by the implementation pipeline in place of the maintainers, as their policy for the
autonomous run asks.

The markers of decision 26 are written in `wireBpmn`, right after `validateTaskWiring` of the
process, and not while preparing, as the plan had it. The core answers `startsAWorkflowOfItsOwn`
by whether the wiring of the process was validated (`isACalledProcess` of the platform's
`WorkflowTaskRegistry`), so asked in `prepareBpmn` it answers yes for a called process as well, and
the called process would get a start marker which hands the core a name its aggregate does not
know. The Camunda 8 adapter writes its start listener at the same point. A process nobody claims is
not wired, so it gets no marker.

The job type of the start markers of a process is `vanillabp__started__` followed by the id under
which the engine holds the process, one type per process and the same in every mode of
name-clash avoidance. `scopedTaskDefinition` would give every process the plain type
`vanillabp__started` under `none`, so the markers of every application which deploys to the engine
under `none` would share one type, and the job of another application's marker would wait out its
lock at a node which cannot serve it. The listener types of the Camunda 8 adapter carry the process
id for the same reason.

What is written carries ids which start with `vanillabp__`: a marker is
`vanillabp__started__<start event id>`, the flow from the start event to it the marker's id
followed by `__flow`. Where an element of the marker's id exists, nothing is written, so a model
which carries its markers is left as it is. The marker takes over every flow which left the start
event, those named by the event's `outgoing`, which the engine follows, and those whose `sourceRef`
names it. It is drawn as a shape of 60 by 40 points, its left edge 80 points right of the start
event's left edge and its middle on the start event's middle, and the edge which left the start
event leaves the marker. Nothing else of the diagram is moved, so a marker may cover an element the
modeller put closer than that; the plan's architecture had the shape copied from its neighbour,
which would cover the neighbour whole. A model without a shape for the start event gets none for
its marker.

### 45. A process called on an aggregate of its own is started without the caller's name

Decided by the implementation pipeline in place of the maintainers, as their policy for the
autonomous run asks.

The engine starts a called instance with the caller's business key and a copy of the caller's
variables, whatever the called process is (pinned commit `bdd795a3`: `resolveChildBusinessKey` and
`createCallActivity` of `pkg/bpmn/sub_process.go`). A process which is a workflow of its own, on an
aggregate of its own, would so hand the core its caller's name at its start marker, and the core
refuses a name no aggregate of the called process's class carries; the marker's job would end in an
incident. So where a claimed process calls a process of the same workflow module which the
platform answers `startsAWorkflowOfItsOwn` for, and which does not share the caller's aggregate
(`workflowsShareTheWorkflowAggregate`), its call activity gets `zenbpm:in businessKey=""`, which
the engine takes as an empty key, and an input mapping `source="=null"` of the called process's
aggregate id variable, which hides the caller's variable of that name in the called instance. The
called process's `@WorkflowStartedByBpms` method then builds its aggregate, as on Camunda 7, whose
call activity hands on no business key. The engine copies back only the outputs a call activity
maps (`PropagateOnlyMappedOutputs`), so the caller keeps its own id. The models are changed in
`deployResources`, once every process of the workflow module is wired, because the core answers
`startsAWorkflowOfItsOwn` correctly only for a process whose wiring it validated.

A call activity is left as the modeller wrote it where the modeller wrote a `zenbpm:in` of their
own, where it names its process by an expression or not at all, and where the called process
belongs to another workflow module or application, which the boot cannot judge; a mapping of the
id variable the modeller wrote stays as well. Camunda 8 hands on the caller's variables too and does
nothing about it.

### 46. The start marker writes the name only where the instance does not carry it

Decided by the implementation pipeline in place of the maintainers, as their policy for the
autonomous run asks.

The plan wrote the variables of `startWorkflowByBpms`'s result into the instance where `created()`
is true. But the core answers `created() == false` also where the instance carries a business key
and no id variable, and where the application's method named the workflow after an aggregate which
exists; in both cases the instance would go on without the variable its tasks and correlation keys
read the aggregate by. So the handler writes `result.variables()` with `PATCH
/v1/process-instances/{key}/variables` wherever the instance does not carry the result's id under
its name, and completes the job without variables. The application's own start carries the id, so
it costs the job and one transaction and no update.

Whether the instance carries the id is read from the instance, not from the job. The engine stores
the variables of a job when it creates the job and hands those out on every delivery (pinned commit
`bdd795a3`: `InputVariables` in `pkg/bpmn/jobs.go`, sent as stored by
`internal/cluster/jobmanager/server.go`); an update of the instance does not change them. So the
context handed to the core carries the job's variables with the id variable the instance holds now,
which the instance cache reads again wherever it did not hold the id. A second delivery after the
update, after a completion which did not reach the engine or a lock which was lost, therefore finds
the id and builds nothing, and writes nothing. It builds a second aggregate only where a node which
lags behind (GAPS 20) answers that read before it has seen the update, unless the application's id
is deterministic; like the window between the aggregate's commit and the update, which decision 25
names, this is what at-least-once delivery leaves over.

The marker's job is otherwise served like the job of a task: inside the module's drain, under the
renewal of its lock, with its commands repeated within the lock. A refusal or a method which threw
fails the job as decision 27 says, with the `retry-backoff` resolved for the task name
`vanillabp__started`, and not while the module shuts down; the job type is subscribed with the
`job-timeout` resolved for that name. The marker's job runs in the workflow's root instance,
because a marker is written into the process itself, so that instance's key is the
`nativeInstanceId`. A start event the deployed model does not hold, of an older version of the
process, is reported as a plain one. A process the module declares without a model, such as the
old id of a renamed process, subscribes the type of its start markers where a model the engine
holds under the id carries one, with the start events of the held models which carry them: a
workflow started right before the rename may still wait at its marker. A start marker needs no task
definition, so the held models are read for it also where every method serving the id is wired by
element id. Where no held model carries one, or where the platform hands no invoker of starts and
nothing could answer the marker's job, nothing of the kind is subscribed.

### 47. A start by a message is published one at a time per name, and its `404` is an ordinary failure

Decided by the implementation pipeline in place of the maintainers, as their policy for the
autonomous run asks.

The engine starts a workflow by a message through the subscription of the start event (pinned
commit `bdd795a3`): `publishDefinitionMessageByName` of `internal/cluster/node.go` reads the
subscription the name points to under the empty key, and `handleStartEventInstanceCreation` of
`pkg/bpmn/start_event_instance_creation_handler.go` marks it consumed and creates a new one before
it creates the instance. Two start messages of one name which arrive at the same moment both read
the old subscription, and the second finds it consumed: under the lock of that subscription, where
the engine starts nothing and answers `201`, or when it looks the subscription up by its key, where
it answers `404`. Against the pinned image, 40 start messages of one name sent at once were
answered 26 times `201` and 14 times `404`, and started 5 workflows; sent one after the other, 40
started 40. The answer names no instance (GAPS 1), so a `201` which started nothing cannot be told
from one which did, and nothing can repeat it.

So each node of the application publishes the start messages of one name one after the other: one
lock per name as the engine holds it, shared by every adapter id, held for the publication only;
the aggregate is loaded before. The outbox dispatches the entries of different aggregates on
parallel lanes, so without the lock a single node loses starts. The nodes of an application still
publish at the same moment, which is what remains (GAPS 30).

The plan made a `404` of a start message permanent. At the pin three things answer it: no start
event takes the name (nothing deployed starts the process with it, or another node deployed a
version without it after phase one judged the name, decision 42); a start on another node which
took the subscription at the same moment; and a workflow the engine created and failed right
behind the start event on something it does not find, which `Server.PublishMessage` of
`internal/cluster/server/server.go` answers as for a correlation (GAPS 29). A repetition cures the
second, which a permanent answer would block; only a deployment cures the first, which a retry-later
without counting would wait out for hours, the answer decision 43 gives a correlation, whose `404`
mostly means "not yet". The third does not happen to a process this adapter wired: its start marker
stands right behind every start event (decision 26), so the engine stops at the marker's job before
anything behind it runs, and answers `201`. So a `404` of a start message is an ordinary failure:
the engine's answer stays the cause of an exception which names the deployment, the start on
another node and `vanillabp.outbox.block-after-attempts`; it is repeatable and counted, so the
outbox repeats it with its backoff and blocks the entry after its attempts. A repetition first
passes the core's question whether the start happened already, the start row and then
`awarenessOfWorkflowForRedispatch`, which under `message-start-lookup: scan` finds a running
workflow by its id variable. Where the platform hands no invoker of starts, so no marker is written,
the third case costs a second workflow at each repetition.

### 48. A message start event which maps its outputs passes the aggregate id on

Decided by the implementation pipeline in place of the maintainers, as their policy for the
autonomous run asks.

The engine applies the output mappings of a message start event to the variables of the message
before it creates the instance (pinned commit `bdd795a3`: `handleStartEventInstanceCreation` and
`mapStartEventTriggerVariables` of `pkg/bpmn/start_event_instance_creation_handler.go`, through
`PropagateMappedOutputsOrAll` of `pkg/bpmn/runtime/varholder.go`). Where the event maps no output,
the instance gets every variable of the message; where it maps any, it gets the mapped ones only.
A start by a message sends the aggregate id in its variables, because the engine takes no business
key with a message (GAPS 1), and the start marker behind the event (decision 26) is how the core
learns which aggregate the instance belongs to. An output mapping a modeller wrote for a business
variable would drop the id, and the marker would hand the core a start which names no aggregate:
the core takes it for a workflow started past VanillaBP, and without a method to build an
aggregate for such a start the marker's job ends in an incident, while the aggregate the
application saved never gets its start row.

So while wiring a claimed process whose start the platform hears, every message start event of
the process itself which maps outputs, but none to the aggregate's id variable, gets one more
output, `source="=<id variable>" target="<id variable>"`, behind the modeller's. An event without
outputs is left as it is, because it passes every variable on by itself, and so is an event whose
modeller maps the id variable, whose mapping is theirs. A message which does not carry the id
variable, sent past VanillaBP, still starts the instance: the engine maps the missing variable to
nothing, as it does for any source the message does not hold, and the marker reports the start as
one nobody named, as before.

### 49. A push of the workflow writes the root and every instance which runs below it

Decided by the implementation pipeline in place of the maintainers, as their policy for the
autonomous run asks.

The engine evaluates a gateway's condition on the variables of the instance its token runs in, and
an instance it starts below another one holds a copy of its own (decision 35). Measured against the
pinned engine (`ZenBpmAggregateChangedIT`, `ZenBpmRestClientContractIT`): a value written into the
root does not reach a gateway inside a running embedded sub-process, and an iteration of a
multi-instance sub-process holds its item and what the element maps in, and once it waited it
reads nothing else of its workflow (GAPS 31). A write into one parallel iteration is seen by that
iteration alone and is not lost. A write into the instance of a multi-instance body reaches an
iteration which starts later only through an input mapping of the element, which the engine
evaluates in that instance.

So a push without a task writes the workflow's root and then every instance below it which runs
(`active` or `failed`), at any depth: embedded and event sub-processes, multi-instance bodies and
their iterations, and called instances. Each instance is written before the instances below it are
read, so one started meanwhile copies the new values from its parent. An instance a call activity
started on another aggregate is not written and not entered: what runs there is not this
aggregate's business, as the Camunda 8 adapter stops at a called process which does not carry the
aggregate. The engine hands a called instance a copy of its caller's variables, the caller's id
variable included, and decision 45 empties only the business key and the called process's own id
variable, so neither the caller's id variable nor the business key can tell. Where a workflow
service claims the called process, the platform's wiring decides, as it does for the rewrite of
decision 45 while deploying: the called instance is entered only where the called process shares
the aggregate of the process of the instance which called it. A called process the platform does
not know is entered only where its business key or the aggregate's id variable names the
aggregate. An instance which ended between its reading and its writing (`409`) is passed over with
everything below it; a `404` is thrown, because a node which lags behind answers it for an
instance it does not know yet (decision 34), and the outbox repeats the push.

The root is the instance of the key VanillaBP wrote down for the workflow, where it runs, belongs to
the process and names the aggregate (decision 25), else the instance the business-key search finds
which the process was started with or a call activity started (a called process runs a workflow of
its own; the probes find it too), not an embedded sub-process or an iteration, else, under
`message-start-lookup: scan`, the running instance of a process a message starts whose id variable
names the aggregate, as the probes find a workflow. Where none runs, the push is a warned no-op,
because the core found the workflow when it dispatched the push. Where the engine did not answer for
the key and nothing else finds the workflow, that failure is thrown and the outbox repeats the push:
a workflow the engine started has no business key, so the key is the only way to it, and an engine
which did not answer says nothing about whether the workflow ended. The probes keep taking such a
failure as no answer of the key, because their search reports an engine it cannot ask.

The cost is one read of the children per instance which runs, a leaf included, and one write per
instance which runs. A push into the scope of a task stays a write of the instance the task's job
waits in and nothing else: the task's completion writes the values up to the root (decision 35).
It is not skipped while a handler holds the task, which the plan asked for: no field of the row the
core hands over (`PhaseTwoRequest#taskRecord()`) says that a handler holds it, and a missing row
says nothing either (no delivery log, a row removed by retention), so skipping would lose pushes; a
handler which runs sees the variables of its job's creation either way. A job the engine answers
`404` for is thrown and repeated by the outbox: the engine keeps a job until the history of its
instance is removed, so the answer comes from a node which lags behind (decision 34).

### 50. A user task the application cancelled is told as cancelled, as Camunda 7 tells it

Decided by the implementation pipeline in place of the maintainers, as their policy for the
autonomous run asks.

`cancelUserTask` fails the user task's job with the BPMN error the application named, and the
engine sets a job `terminated` once a boundary error event or an error event sub-process catches
the code (`processBoundaryErrorEvent` in `pkg/bpmn/jobs_api.go`,
`pkg/bpmn/engine_error_event_subprocess.go` at the pin). So the user-task poller lists it as
terminated and tells the application `CANCELED` (delivery id `<key>-canceled`, decision 12), a
moment after the application cancelled the user task itself.

That notification is told. The Camunda 7 adapter does the same: `handleBpmnError` deletes the task,
and its `delete` listener delivers `CANCELED` (`Camunda7TaskProcessingIT#cancelUserTaskRoutesBoundary`
and its Quarkus twin assert it). The Camunda 8 adapter has no answer, because no cluster up to 8.9
can cancel a Camunda-managed user task by BPMN error. The platform takes the notification without
harm: it is a delivery of its own (the event is part of the delivery key), so it runs the method
with `CANCELED` once and records it, and it closes the records of the user task which phase two of
the cancellation closed already. A method which asks for `CANCELED` therefore hears of a
cancellation it caused itself, as on Camunda 7, and one which must tell the two apart keeps what it
did in its aggregate.

Suppressing it was rejected. A node could remember the keys it failed, but only for a while and only
on that node: the poller of another node of the application, or the same node after a restart,
would still tell it, so the application could not rely on the silence and would need the
aggregate's memory all the same. And a user task cancelled by an error the model does not catch
raises an incident instead and stays `failed`, so it is told nothing.

A completion is never told as a cancellation: the engine sets a completed job `completed`, which
the poller does not list.

### 51. The probes and the checks of a task and of a user task find no job of the other kind

Decided by the implementation pipeline in place of the maintainers, as their policy for the
autonomous run asks.

A user task is a job of the engine, as a service task is, so one key could be handed to the
commands of either kind and the engine would take it. The platform keeps the two apart: an
application names a task id to `completeTask` or `cancelTask` and a user-task id to
`completeUserTask` or `cancelUserTask`, the platform asks `awarenessOfTask` for the first and
`awarenessOfUserTask` for the second, and on every other engine the two kinds of id live in
namespaces of their own, so the command of the other kind finds nothing. Where the platform's record
of deliveries says that the id belongs to the other kind, it does not answer the election from the
record but asks the adapters, and it says so in its `TaskNotFoundException` only where every
adapter answers that it does not know the task (`DeliveryRecords#locate`,
`MigrationProcessService#unknownToEveryBpms`). An adapter which answered both probes alike would let
`completeTask` complete a user task on ZenBPM, which fails on every other engine, and would cost the
application the platform's message which names the right method.

So both probes read the job and answer `UNKNOWN_TO_BPMS` where its element type says the other kind
(`USER_TASK` is a user task, every other type a task), without reading its instance, and the check
before the commit of each kind throws `TaskNotFoundException` for a job of the other kind, whatever
its state, naming the element and the methods which end it. The check says the same where phase one
runs without a probe, as it does where a record of the platform which names no kind answers the
election. The handlers of both kinds are one class,
built once per kind, whose messages say "task" or "user task".

### 52. The end marker reports an end only where nothing else of the workflow runs, and no error end event gets one

Decided by the implementation pipeline in place of the maintainers, as their policy for the
autonomous run asks.

The end markers of decision 26 are written in `wireBpmn`, right after `validateTaskWiring`, for the
reason decision 44 gives for the start markers: the core answers `workflowEndedHandlerExists` with
no for a called process only once the wiring of the process was validated. The job type is
`vanillabp__ended__` followed by the id under which the engine holds the process, a marker is
`vanillabp__ended__<end event id>`, the flow from it to the end event the marker's id followed by
`__flow`, and an element of the marker's id is never written twice. The marker takes over every
flow which led to the end event, the event keeps its definition, and the marker is drawn as a shape
of 60 by 40 points whose right edge is 80 points left of the end event's right edge, its middle on
the event's middle.

**Which end events.** A none, a message and a terminate end event of the process itself get one. An
error end event gets none. Where nothing catches its error, the pinned engine raises an incident
there (`unhandled error end event <id>: <code>`) and the instance stays active without a token,
which `ZenBpmMarkerTasksIT#anUncaughtErrorEndEventRaisesAnIncident` measures; Zeebe raises an
incident as well, and the Camunda 8 adapter, whose listener sits on the process, then reports
nothing. Where an error event sub-process of the process catches the error, the workflow goes on in
the sub-process. In neither case does the workflow end at the error end event.

**When the end is reported.** A terminate end event ends the workflow whatever else of it runs, so
its marker reports at once. Every other end event ends the branch which reaches it, and the engine
completes the instance only where no token, no job and no event sub-process of it is left
(`handlePlainEndEvent` in `pkg/bpmn/engine.go`). So before it reports, the handler reads where the
tokens of the instance stand (`activeElementInstances` of `GET /v1/process-instances/{key}`):

- A token stands at any element but an end marker or an end event (where the job of a message end
  event holds it): another branch goes on. The job is completed without a report, and the end the
  last branch reaches is reported.
- No token stands anywhere else, but a child instance of the instance is active: an event
  sub-process, which no token waits for, still runs, and the workflow ends once it ended. The job
  is left to its lock, so the engine hands it out again after its `job-timeout`, until the
  sub-process ended.
- Nothing else runs: the end is reported. A read which a lagging node answers without the
  instance leaves the job to its lock as well.

The context names the end as `COMPLETED`, the end event, the job's instance as the workflow (a
marker sits in the process itself, so that is the root of decision 28), the instance's business key
where it has a non-empty one, its version and the moment of the report as the end time. The core
reports every task it believes open in the workflow as cancelled before the `@WorkflowEnded` method
runs, and the job is completed only after the core committed, so the instance ends after the
report. A report which threw fails the job as decision 27 says, with a message naming the
`@WorkflowEnded` method and the `retry-backoff` resolved for the task name `vanillabp__ended`, and
not while the module shuts down; the type is subscribed with the `job-timeout` resolved for that
name. An aggregate the application deleted is skipped by the core, and an instance which names no
aggregate is not reported; in both cases the job is completed and the workflow ends. A process
declared without a model subscribes the type of the end markers where a model the engine holds
under the id carries them and the platform wants to hear about the end of the id's workflows, as
decision 46 says for the start markers.

**What this leaves over.** Two branches which reach their end markers at the same moment may both
report the end, which the platform delivers at least once anyway. A message end event's own job
runs after the report, because no marker can stand behind an end event. A workflow which ends in an
interrupting event sub-process, or in an error event sub-process which caught an error end event,
passes no end marker of the process itself and is not reported; Camunda 8 reports it, because its
listener sits on the process. A read of the tokens which a lagging node answers (GAPS 20) can show
a branch where it stood a moment ago, so where two branches end at once, both markers may see the
other one still running and the end is not reported.

### 53. A user task the end of its workflow took away is not told as cancelled by the poller

Decided by the implementation pipeline in place of the maintainers, as their policy for the
autonomous run asks.

The report of the end of a workflow (decision 52) makes the core report every task it believes open
in that workflow as cancelled, user tasks included: the notification of a user task's creation left
an open record which names the root instance as the workflow (decision 28). The poller of decision
12 would then tell the same cancellation again, under its own delivery id `<key>-canceled`, which
the core runs as a delivery of its own: without this rule the application heard it twice in
`ZenBpmUserTaskRestartIT#aUserTaskTheEndTookAwayIsToldOnceAcrossARestart`, without any restart, and
the record of the second one outlived the release of `vanillabp.delivery.release-on-workflow-end`.

So the notifier tells no cancellation of a user task whose workflow's root instance completed and
holds a completed job of the process's end markers, wherever the process writes end markers: the
end marker's job is completed only after the core committed the report. It asks the engine for the
root instance and for that job, two reads per such cancellation. Every other cancellation is told
as before: one of a cancelled instance, which passes no marker (GAPS 9), one of a workflow which
goes on, and one of a process whose end nobody listens to.

What this leaves over: a user task which came and went between two looks of a workflow which ended
at a reported end is told nothing, because the core held no record of it. And where a branch's end
marker completed without a report and the workflow then ended in an event sub-process, which is not
reported (decision 52), its user tasks are not told as cancelled either.

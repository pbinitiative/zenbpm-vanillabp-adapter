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

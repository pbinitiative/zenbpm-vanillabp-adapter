# Draft `DECISIONS.md` of `zenbpm-vanillabp-adapter`

These are the entries the plan expects the repository to carry once its stories are done. Numbers are
handed out here so stories can already say `see decision 5 in the repository's DECISIONS.md`; the
ZenBPM maintainers, who own this repository, confirm or strike an entry before the story which first
cites it is merged (open questions 8 and 10 name the two the VanillaBP project should hear about).
Entries 16 and 17 were added when the ownership was settled on 2026-09-13. The form follows the
other adapters: a decision earns a number when several places rely on it, an entry is superseded and
never edited.

### 1. The adapter is native, and the Camunda 8 adapter is its template

ZenBPM is a remote, at-least-once, polling BPMS with a query API, which is the shape the Camunda 8
adapter was written for. It is NOT built on the Process-Engine-API adapter: no Process-Engine-API
implementation for ZenBPM exists, the adapter would have had to write one against its own private
command classes, and every capability the PEA cannot express (finding a workflow, listeners, versions,
pushing variables, typed failures) is one ZenBPM's REST API offers and the PEA layer would have hidden.
What is borrowed from the PEA adapter is the raw-XML handling, because a model type of a Camunda
artifact would tie this adapter to Camunda for no reason.

### 2. The default name-clash mode is `use-prefix`, and `by-adapter` is refused

ZenBPM has no tenant or namespace, so `by-adapter` cannot be served. Every other adapter defaults to
`by-adapter` because version 1 deployed into a tenant named after the workflow module and an application
upgrading without touching its configuration has to find its running workflows again. No version-1
application ran on ZenBPM, so there is nothing to keep finding, and refusing the SPI's default at every
boot would make every application write the same line. `use-prefix` scopes process ids, message names,
error codes, task definitions, called elements and decision ids with the module id, which keeps two
modules apart on one engine, and `none` is reported per module until `accept-unscoped-identifiers` says
the identifiers are unique. Configuring `by-adapter` at any level ends the boot naming the two ways out.

### 3. The REST client is the adapter's own, the gRPC stubs are generated

The adapter uses about twenty REST endpoints. The official Java client trails the engine by two
minors, changed its deploy contract with 1.8, and knows nothing about Quarkus; a generated REST client
would bring a generator into every build and its own reflection into every native image. So the REST
side is a thin `java.net.http` + Jackson client written against the copy of `openapi/api.yaml` this
repository pins, with one method per endpoint and one exception carrying the status code. The job
stream is generated from the pinned `zenbpm.proto`, because that is what protobuf is for. The pinned
copies name the engine version they came from, and a test diffs them against the image the
integration tests run.

### 4. The deployed model is rewritten, once per file, idempotently, and never over what the modeller wrote

Like Camunda 8, ZenBPM answers only what its protocol carries, so what an embedded engine gives for
free is put INTO the model before it reaches the engine: the scoped identifiers of decision 2, a
`zenbpm:subscription` correlation key on every message a catch element waits for, one input mapping per
`@TaskParam` name so a job carries what its handler reads, the `zenbpm:in businessKey` of a call
activity on the same aggregate, and the marker tasks of decision 5. A model rewritten this way is a new
version in the engine, which the engine tells apart from an unchanged one by content, so an unchanged
boot deploys nothing and a re-wired model is not rewritten twice. A message shared by a start event and
a catch element is refused rather than rewritten, see GAPS 4.

### 5. Lifecycle notifications are marker service tasks, because the engine has no listeners

ZenBPM offers neither execution listeners nor an event stream. Where the application wants to hear
that a workflow ended, a service task of the adapter's own is inserted in front of every end event of
the top-level process; where a timer starts a workflow and an aggregate has to be built, one is inserted
right after the start event. Their jobs are ordinary jobs, delivered at least once, and completing them
is what lets the instance end respectively continue. What this cannot report is written down: a
cancelled instance runs no marker, so `TERMINATED` never arrives, and a workflow started before the
markers existed reports nothing. Polling instance states was rejected because it scales with the
number of instances and cannot name the end event; a CDC consumer was rejected because it reads
database rows of another product's schema.

### 6. The aggregate id is the business key AND a variable; a BPMS-initiated workflow's id is its instance key

The engine finds an instance by `businessKey` and never by a variable, so the aggregate id travels as
the business key with every start the application makes. It travels as a variable named after the
aggregate's id attribute as well, because that is what the injected correlation key reads and what
every other remote adapter does. An instance the engine starts itself carries no business key and none
can be set afterwards, so its aggregate id IS the process instance key, which the marker job of
decision 5 reports as the natural identity; the workflow probe therefore asks by business key first
and, where the id is a number, by instance key second. The message-started workflow is the one shape
neither lookup reaches, see GAPS 1.

### 7. Shared values are written before the job is completed, because job outputs only travel through mappings

`POST /jobs/{key}/complete` hands its variables to the task's output mappings, and a task without
mappings propagates nothing, so a gateway right behind a `@WorkflowTask` would decide on stale values.
The adapter therefore writes the shared values with `PATCH /process-instances/{key}/variables` and then
completes the job with the same values. Both are idempotent, which is what an at-least-once handler
needs: a repeated PATCH writes the same state, a repeated completion is answered with 201. Injecting an
output mapping per shared attribute was rejected: which attributes are shared depends on the aggregate
class and its annotations, not on the model.

### 8. A failed handler is not failed at the engine, because the engine has no retries

`POST /jobs/{key}/fail` without an error code creates an incident at once. A handler which threw is
therefore not reported. Instead the adapter extends the job's lock by the backoff (`retry-backoff`,
doubling to five minutes): an extension is counted from now (E13.1), so the engine hands the job out
again after exactly the backoff, to whichever node has a slot, and counts the attempts locally per job
key. After `max-redeliveries` the job IS failed, so an operator sees an
incident naming the aggregate instead of a job which fails forever. The `fail` with an error code is
untouched: it is the BPMN error the model asked for.

### 9. Job delivery takes the stream, with a lock the adapter sizes and renews

Only the gRPC stream hands a job to one client at a time; the REST list is a plain query two nodes
would answer identically. Every job type is subscribed with `lock_duration_ms` = the resolved
`job-timeout` and `max_active_jobs` = the handler slots (E13.1). A job which waits in the queue in
front of busy slots is harmless, because the handler extends its lock when it starts and at half the
timeout while it runs, so the lock a handler works under is always a full one. Every command back to
the engine goes over REST - the lock extensions included, with the stream's client id - so that the
one classification of decision 10 reads a status code for all of them and a completion survives a
stream reconnect. What the lock cannot cover is a leader change, which forgets every lock; the second
delivery that follows is the documented at-least-once residual.

### 10. What the engine did is read from status codes, not from message text

A `404` on a job is "gone" and consumed; a `404` on a message is "nobody waits yet" and becomes a
retry-later with the visibility window; `400`, `413`, `415` and a key which is not a number are permanent
and block an outbox entry after one attempt; everything else, `500` and `502` included, is repeated. The
engine puts its reasons into prose which any patch release may reword, so no classification reads a
message. Where the engine answers `500` for a job in the wrong state, that is repeated too, and the
retry ends in the "gone" answer once the job settles.

### 11. Ownership is decided by the scoped process id of the CALL, never by a key

Instance keys and job keys are unique per engine and carry no scope. Two adapter ids may address one
engine under different prefixes, and two workflow modules of one id may reuse an aggregate id. Every
probe therefore compares the instance's `bpmnProcessId` with the scoped ids of the `WorkflowScope` it
was given before it answers anything but `UNKNOWN_TO_BPMS`. The instance is read once per key and
cached, which is what makes the comparison affordable on every task delivery as well.

### 12. User-task notifications are polled, not streamed

A ZenBPM user task is a job. Subscribing its type on the stream would hold a lock for the whole life of
every open user task, renewed by the adapter for days, and it would still not tell the adapter when a
user task is terminated, because a terminated job is never delivered. So a poller lists the user-task jobs of the deployed
modules at `user-task-poll-interval`, remembers what it reported per node, and lets the platform's
delivery record answer whatever a restart forgets. A `terminated` job is the `CANCELED` notification
the engine cannot push.

### 13. A class opens its fields one by one, not as a whole

Copied from Camunda 8 decision 4 word for word: accessors per field, no class-level `@Getter`, so a
later field does not become public by accident.

### 14. A start asks for numbers, and asks as many on the last day as on the first

Copied from Camunda 8 decision 13: every quantity the boot asks the engine for is a `totalCount` or a
statistics endpoint, never a page transferred to be counted; what may grow with time is the number of
versions of a process, which is what the check is for.

### 15. One pinned engine version, no release lines

The lowest engine the adapter accepts is the first release carrying E13.1 (commit `071460cc`: lock
per subscription, lock extension, `lock_until`), because the task delivery of decision 9 is built on
it; the adapter asks `GET /system/status` at startup and ends the boot guiding where the engine is
older. Beyond that the adapter compiles against no engine artifact, so no pin decides anything else; the
REST contract is the copy of `api.yaml` this repository ships, and the integration tests run against the
image of that version. Supported means tested: the pinned image and nothing newer. Release lines are
what Camunda 8 needs because its client is the minimum cluster; here they would be branches for a
contract which has no stability promise yet. If an engine minor breaks the surface the adapter uses,
the answer is a new adapter release against the new pin, and this entry is revisited.

### 16. The adapter follows VanillaBP's adapter conventions although another organisation owns it

`zenbpm-vanillabp-adapter` belongs to the ZenBPM maintainers at pbinitiative, implements VanillaBP's
adapter SPI and is read by people who know the Camunda and Process-Engine-API adapters. It therefore
keeps their shape: the `core` / `spring-boot` / `quarkus` split with platform modules which only
construct and register, Spotless with the platform's formatting conventions, coverage measured per
platform with the same gate and rule, `test-utils` in every test, a `DECISIONS.md` as the only thing
code cites, a user-facing wiki and a contributor-facing README, and configuration under
`vanillabp.adapters.<id>.*` validated at startup with guiding messages. What differs is what
ownership decides: the licence, the coordinates (`org.pbinitiative.zenbpmadapter`), the CI and where
releases are published. The Go conventions of the engine repository do not reach into this one.

### 17. Code adapted from the Apache-2.0 adapters keeps its licence and its notice

The repository is MIT-licensed, and a good part of its first version is copied and adapted from
`camunda8-adapter` and `process-engine-api-adapter`, both Apache 2.0. Apache 2.0 allows that inside an
MIT project as long as the licence text and the attributions travel with the code, so every adapted
file keeps its original header, `LICENSE-APACHE-2.0` holds the licence text, and `NOTICE` names the two
origins. A file written from scratch carries the MIT header. Which is which is decided when the file is
created and never changed afterwards, because the origin of a file is a fact and not a preference.

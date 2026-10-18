# E2 - Engine client (plain Java, `core`)

**Goal.** Everything the adapter needs to talk to one ZenBPM engine, per adapter id, without any
platform: configuration and its validation, the REST client, the job stream, the error classification,
the engine wait, health, and the executor the handlers run on.

**Why before the platforms.** The platform modules only construct and register; if the client layer
is complete first, the registration stories are thin and the SPI stories never touch transport code.

**Done when.** A plain JUnit test can configure a client, deploy a model, start an instance, receive
its job on the stream and complete it over REST against the container, and every status code the
engine can answer is classified in one place.

**E2 done 2026-10-15**, with S2.4.1 merged to `main` as `b3e49d2` (pull request #22), the last of
its stories. The criterion holds on GitHub: `ZenBpmClientFactoryIT` builds the clients through the
factory, waits for the pinned engine, deploys a model, starts an instance, receives its job on the
stream and completes it over REST, in both the check and the publication of that commit; the status
codes are classified in `ZenBpmErrors` alone (S2.2.2). See S2.4.1 below.

Reference implementations to read before starting: `Camunda8AdapterConfiguration`,
`Camunda8ClientFactory`, `Camunda8ClientFactoryRegistry`, `Camunda8StartupValidation`,
`Camunda8Errors`, `Camunda8ClusterWait`, `Camunda8Health`, `Camunda8Executor`,
`Camunda8ExecutionModel`, `Camunda8VirtualThreadExecutor` in `camunda8-adapter/core`.

---

## F2.1 Configuration model

### S2.1.1 `ZenBpmAdapterConfiguration` with startup validation

- [ ] **Depends on:** S1.1.1.

**Instructions**

1. `org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration`: a plain class holding the keys of
   `architecture/01-architecture.md` section 6 with their defaults (`restAddress`, `grpcAddress`,
   `grpcPlaintext`, `clientId`, `requestTimeout`, `startupWait`, `workerThreads`,
   `workerThreadsBound`, `retryBackoff`, `maxRedeliveries`, `shutdownGrace`,
   `workflowVisibilityTimeout`, `userTaskPollInterval`, `historyTimeToLive`,
   `instanceCacheMaxEntries`, `messageStartLookup` = `REFUSE | SCAN`). Accessors per field
   (decision 13). Built by both platforms from their overlay.
2. `validate(adapterId, isNowhereFirst, deploymentFailurePolicy)` returns a `ValidationResult` of
   kind `UNCONFIGURED` (no `rest-address`: message names `vanillabp.adapters.<id>.rest-address` and
   the YAML to add), `INCONSISTENT` (malformed URL, `grpc-address` without a port, `worker-threads`
   below 1 or above `worker-threads-bound`, `request-timeout` below one second, negative durations,
   `client-id` blank) with a message listing EVERY defect and the key of each, or `COMPLETE`. Never
   echo a value which could be a credential (there are none today; keep the rule anyway).
3. Derivations: `grpcAddress` defaults to the host of `rest-address` and port 9090; `clientId`
   defaults to `<applicationName>-<adapterId>-<8 random hex>` where the platform passes the
   application name (a `Supplier<String>`); `restBaseUri()` appends `/v1` unless the address already
   ends with it (then say so at INFO once).
4. `instanceIdentity()`: the normalised `rest-address` (scheme, lower-cased host, explicit port,
   trailing slash removed). Two ids with equal identities address one engine (S3.3.1).

**Tests** (`ZenBpmAdapterConfigurationTest`)

- each defect named with its key; two defects reported in one message;
- the unconfigured message contains the YAML block with the id substituted;
- derivations of `grpc-address` and `client-id`; identity normalisation (`HTTP://Host:8080/` equals
  `http://host:8080`).

**Acceptance criteria**

- [x] Every key of the architecture table has a field, a default and a validation where one applies.
  Read as the sixteen keys instruction 1 lists: each has a field, an accessor pair and a default
  (`aRestAddressAloneIsComplete` asserts all of them). The table's other keys arrive with the
  stories which give them a meaning: `job-timeout`, `async-task-lock-renewal` and
  `async-task-max-age-action` with E6, `shared-engine` with S3.3.1 (which dropped it: the mode
  tells two ids on one engine apart),
  `message-start-lookup-max-instances` with S7.1.2; `type`, `name-clash-avoidance` and
  `deployment-failure` are core keys. `accept-unscoped-identifiers` was named by no story and is
  now instruction 5 of S4.2.1 (and the module level in S3.1.1's overlay).
- [x] Messages contain the full property key for every named defect and never a value of `client-id`
  (treated like a credential, it may carry a hostname somebody considers internal). Each of 49
  defect rows asserts its line `  - vanillabp.adapters.orders.<key> ...` and the remedy that line
  names. `valuesWhichMayBeSecretAreNeverRepeated` sets a user and password in `rest-address` and a
  host in `grpc-address`, and none of them is in the message. Its client-id half cannot fail today:
  the only `client-id` defect is a blank one, which has no value to repeat. No validation message
  repeats an address either, only numbers and durations.

**Implemented 2026-10-13.** `ZenBpmAdapterConfigurationTest`, 58 cases, green; the full
`mvn install` builds green and the coverage gate measures 99.83 % on both platforms. Seen to fail,
each with the source changed back afterwards: a user in `rest-address` accepted, a blank
`client-id` accepted, `/v1` appended to an address carrying it, a scheme in `grpc-address`
accepted, degrading regardless of the policy, and a bound beside platform threads accepted. The
first run caught a real bug: the missing address was listed as a defect, so no configuration was
ever unconfigured.

**Answers to two reviews, 2026-10-13.** Every finding was reproduced against the built class before
it was changed. `ZenBpmAdapterConfigurationTest` now has 93 cases; `mvn clean install` builds green
and the coverage gate measures 100 % of 1,476 instructions on both platforms, no branch missed (the
first record's 1,169 came from an incremental build; both reviewers measured 1,167 from clean).
Seen to fail, each restored afterwards: a value defect degrading, a started configuration counted
as unconfigured, escapes not decoded, every escape decoded, an escaped non-ASCII letter decoded, a
path or query in `grpc-address` accepted, a user in `grpc-address` accepted, a blank
`worker-threads` refused, the TLS notice despite a `grpc-address`, doubled slashes kept.

- Degrading (the user's decision): only a defect in how the engine is reached (`rest-address`,
  `grpc-address`, `client-id`) lets a nowhere-first id with `deployment-failure: warn` boot
  degraded. A number or duration which cannot work ends the boot for every id, as in Camunda 8's
  `Camunda8StartupValidation` and as architecture section 6 already said for the addresses.
- `grpc-address` is parsed as the authority of a URI. `zen bpm:9090`, `[not-ipv6]:9090`, `[::1`,
  `2001:db8::1`, `::1:9090`, `host:+9090`, `zenbpm/path:9090`, `zenbpm?a:9090` and
  `user:password@zenbpm:9090` were COMPLETE and are refused now. gRPC 1.81's channel builder,
  probed with the same inputs, throws "Invalid DNS name" for `zen_bpm:9090`, `host:+9090` and
  `::1:9090` and builds `10.0.0.1:9090`, `[2001:db8::1]:9090` and `zenbpm-grpc.example:443`; the
  validation agrees with it on all six.
- `getWorkerThreadsBound()` answers the key as written (`null` where unset), as decision 13 asks
  an accessor per field; `workerThreadsBoundInUse()` derives the bound. Before, the getter answered
  4 for a default configuration, which Camunda 8's `resolve` would refuse. S2.4.2 now says which one
  to pass.
- A key without a default (`grpc-address`, `client-id`, `history-time-to-live`) set without
  `rest-address` is INCONSISTENT: the configuration was started and has to be finished. Keys with a
  default cannot be told from unset, because the setters map `null` to the default.
- A blank `worker-threads` is the default, as in Camunda 8's `resolve`; a blank `grpc-address` has
  its own message ("remove the key to have it derived").
- Every message names the fix: each negative duration its default (`history-time-to-live`: the
  engine's own), `max-redeliveries` and `instance-cache-max-entries` their default, both port
  messages an example. A host `java.net.URI` cannot read (`http:zenbpm`, `http:///v1`, a port too
  large for a number, an underscore) says "names no valid host and port". A `worker-threads` too
  large for a number says so, instead of "neither a number nor 'virtual'".
- The `/v1` check and `restBaseUri()` use one normalised path: an escaped ASCII letter, digit or
  `-._~` is the character itself (RFC 3986, section 6.2.2.2), repeated and trailing slashes go,
  every other escape stays upper-cased, so `%2F` never becomes a separator. `/%76%31` and `//v1//`
  are one engine with `/v1`.
- A new notice: an `https` `rest-address` beside a derived, plaintext job stream.
- Javadoc: `history-time-to-live` resolves per module or workflow, not per task (architecture
  table); the class says it is not a binding target; a validation message, not the startup INFO line
  of a working configuration, is what never repeats an address or the client id (architecture
  section 6 now says both); behaviour of later stories is no longer described as present.
- Plan: S3.1.1 instruction 4 reads `endsTheBoot()` and logs `notices()` instead of repeating the
  rule (S3.2.1 says "same validation as Spring"), its boot test covers a value defect which still
  ends the boot and a use of an unconfigured adapter; S5.1.1 converts `history-time-to-live` to the
  engine's text form (`types.ParseTTL`, whole seconds) and decides what a value below one second
  and `PT0S` mean; S6.2.1 warns at startup about a `retry-backoff` above the 5-minute cap; S8.1.1
  warns about a poll interval below one second and tests `PT0S`.
  *Note from S6.2.1, 2026-10-18:* S6.2.1 has no such WARN. The cap was the plan's local retry's;
  the engine caps a backoff itself at `JOBS_MAX_RETRY_BACKOFF`, so the adapter refuses only a
  negative `retry-backoff` and a fraction of a second (decision 38).
- Method order (Copilot's suggestion 3): first answered as "the engine's Go rule only", then
  adopted by the user's decision as decision 19 (tests first, helpers after the last test, private
  methods after the others), described in `AGENTS.md` under "Where methods go". The three argument
  sources of `ZenBpmAdapterConfigurationTest` and the private helper of `GeneratedStubPackageTest`,
  the one other class which broke the rule, moved below their tests. `MethodOrderTest` (coverage
  gate) checks every top-level class on each build. Seen to fail, each restored afterwards: both
  classes as they were (naming the four methods and their lines), the `@AfterAll` of
  `EngineUnderTestIT` moved below a test, a private method above the public ones of
  `ZenBpmAdapterConfiguration`.
- `bin/check-deploy-safety.sh` had been broken since the first commit of this story, which the
  review round found when the script ran after the coverage gate gained `MethodOrderTest`: its red
  run adds one untested method and expected "0.00 %", which held only while `core` had no code.
  Beside 1,476 covered instructions that method left the coverage at about 99.7 %, the gate stayed
  green and the script reported "the build with an untested method passed the coverage gate". The
  red run now raises both thresholds to 100 % and expects the gate's own "below the 100 %" message;
  both runs pass again (the README says so too). A check of the red run with `deployAtEnd`
  switched off was not possible: `PublicationSafetyTest` then fails the green run first.
- Two more rules by the user's decision: decision 20 indents Java by four spaces (superseding the
  formatting part of decision 16; every Java file reformatted by Spotless, the probe class of
  `bin/check-deploy-safety.sh` too), decision 21 drops `public` from test classes and their test
  and lifecycle methods (twelve test classes; the configuration test already had it from the
  IDE). `TestVisibilityTest` checks decision 21; `MethodOrderTest` reads members at the new
  indentation through `JavaMembers` and fails when it finds no test or private method at all,
  which is what it would have done silently after the reformatting. Seen to fail, each restored
  afterwards: a `public` test class, a `public` test method, a helper above a test, a lifecycle
  method below a test, the parser set back to two spaces.
- Decision 22 by the user's decision: a wrapped parameter list of a method, constructor or record
  stands one level (four spaces) deeper and `)` closes it on a line of its own, followed by
  `throws` and `{`. `formatting_conventions.xml` sets "indent by one" (alignment 49 -> 53) on the
  three parameter alignments and `separate_lines_if_wrapped` on
  `parentheses_positions_in_method_delcaration` (Eclipse's own spelling) and
  `..._in_record_declaration`; Spotless rewrote every declaration. A scratch class proved the
  constructor case, which the repository does not have yet; `spotless:check` refuses the old form.
- Not changed: the adapter id stays a parameter of `validate` and `clientIdInUse`, as the story's
  signature and Camunda 8 have it.

**Pull request #10, 2026-10-13.** The first check run, 38429186986, failed in Spotless: the code
example of decision 22 in `DECISIONS.md` was indented, which the Markdown formatter refuses, and the
last full build had run before the example was written. Run 38429430229 on `7af9c74` is green on
both JDKs: the engine copies match commit `bdd795a3`, `EngineUnderTestIT` ran against Docker (3
tests), `ZenBpmAdapterConfigurationTest` 93 tests, `MethodOrderTest` and `TestVisibilityTest`
pass, and the gate measures 100 % on both platforms, of 1,478 instructions on JDK 25 and 1,476 on
JDK 21 (the two compilers emit different bytecode). CodeRabbit first skipped the review ("manual
review required for this OSS repository"), then reviewed `ab06ceb` with two minor findings, both
valid and fixed: the S5.1.1 note now names the engine files behind the TTL claims, and
`JavaMembers` reads `@org.junit.jupiter.api.Test` and the other annotations by their full name too
(a probe test, `public` and written with the full name, passed both checks before and failed them
after).

Where the code differs from the instructions:

- `validate(adapterId, nowhereFirst, deploymentFailureWarns)` takes a boolean, not the policy:
  `DeploymentFailurePolicy` lives in the platform's runtime module, which `core` does not depend on
  (Camunda 8's `Camunda8StartupValidation` does the same).
- `ValidationResult(kind, message, endsTheBoot, notices)`: `endsTheBoot` is false for UNCONFIGURED,
  and for INCONSISTENT only where every defect is one of how the engine is reached and the id is
  nowhere first with `deployment-failure: warn`, so E3 reads the decision instead of repeating it.
  The INFO lines (an address ending with `/v1`, an `https` address beside a plaintext job stream)
  are notices in the result, logged once by the platform at startup.
- A missing `rest-address` next to another defect, or next to a key without a default, is
  INCONSISTENT, listing the missing key first.
- Setters take `null` as "restore the default", so the overlays of E3 hand on unset keys without
  knowing a default.
- `worker-threads-bound` is Camunda 8's rule, so S2.4.2 copies `Camunda8ExecutionModel` without
  a change of contract: the bound may be set only beside `worker-threads: virtual` (default 4,
  at least 1). With platform threads their number is the bound, so a bound beside them is refused
  whatever its size, instead of being ignored. That replaces the instruction's "`worker-threads`
  above `worker-threads-bound`", which could only happen with platform threads.
- Further checks, all naming their key and the fix: `rest-address` with a scheme other than
  http(s), a user, a query or fragment, no valid host and port, a port outside 1 to 65535;
  `grpc-address` which is not a URI authority of host and port; `worker-threads` neither a number
  nor `virtual`; negative `max-redeliveries` and `instance-cache-max-entries` (0 is allowed).
- `restBaseUri()` and `instanceIdentity()` are the same normalised form, so `http://host:8080`,
  `http://host:8080/v1`, `HTTP://Host:8080/` and `http://host:8080/%76%31` are one engine.

**Done 2026-10-13**, merged to `main` as `f61ee24` (pull request #10, together with decisions 19 to
22). On that commit (GitHub keeps run logs for 90 days, so what they showed is written here as well):

- `Checks`, run
  [38440149514](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/actions/runs/38440149514):
  green on both JDKs. `bin/check-engine-copies.sh` reported both copies as the engine's files of
  `bdd795a3...`. `ZenBpmAdapterConfigurationTest` ran 93 tests, `MethodOrderTest` 3 and
  `TestVisibilityTest` 2. The gate measured 100 % on both platforms, of 1,478 instructions on JDK
  25 and 1,476 on JDK 21.
- `Publish snapshots`, run
  [38440149861](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/actions/runs/38440149861):
  green. `EngineUnderTestIT` ran 3 tests, none skipped. The core snapshot it published,
  `2.0.0-20261013.173739-6`, contains `ZenBpmAdapterConfiguration` with its nested
  `ValidationResult` and `MessageStartLookup`. Both report pages answer HTTP 200.

**Amended 2026-10-15.** Three of the keys change meaning with the adapter SPI of 2026-10-12:

- `max-redeliveries` goes with the E13.3 twin (decision 27): the engine counts the retries itself.
  `retry-backoff` stays, as the `retryBackoff` the adapter sends with a failure instead of backing
  off locally.
- `message-start-lookup` stays. `scan` stays an opt-in until E13.4 lands and is removed then. Its
  default changes in S7.1.2, because the start markers of decision 26 record the instance key of a
  message-started workflow.
- The validation result is reported through the platform's `StartupReport` (topic
  `CONFIGURATION`, the adapter id as scope, never inside the text) once E3 injects that bean, and no
  longer logged by the adapter. The record and its three outcomes stay as they are.

---

## F2.2 REST client

### S2.2.1 `ZenBpmRestClient` over `java.net.http`

- [ ] **Depends on:** S1.2.1, S2.1.1.

**Instructions**

1. `org.pbinitiative.zenbpmadapter.client.ZenBpmRestClient` built from the configuration: one `HttpClient`
   (HTTP/1.1, connect timeout = `request-timeout`), Jackson `ObjectMapper` configured to ignore
   unknown properties (the engine adds fields between minors), request timeout on every call,
   `Accept: application/json`. One method per endpoint the adapter uses, returning small records in
   `org.pbinitiative.zenbpmadapter.client.api` (never the engine's JSON as a map):

   | Method | Endpoint |
   |---|---|
   | `ready()` -> `Readiness(status, reasons)` | `GET /system/health/ready` (accept 200 and 503 as answers, not failures) |
   | `status()` -> `EngineStatus(version, commit, nodes)` | `GET /system/status` |
   | `deployBpmn(bytes)` -> `DeployedResource(key, created)` | `POST /process-definitions` octet-stream; 200 -> `created=false`, 201 -> `true` |
   | `deployDmn(bytes)` -> `DeployedResource` | `POST /dmn-resource-definitions` |
   | `processDefinition(key)` -> `ProcessDefinitionRef(key, bpmnProcessId, version, versionTag, bpmnData)` | `GET /process-definitions/{key}` |
   | `processDefinitions(bpmnProcessId, onlyLatest)` -> `List<ProcessDefinitionRef>` (paged until exhausted) | `GET /process-definitions` |
   | `processDefinitionStatistics(key)` -> `DefinitionStatistics(active, incidents, completed...)` | `GET /process-definitions/{key}/statistics` |
   | `createInstance(request)` -> `ProcessInstanceRef(key, definitionKey, bpmnProcessId, version, versionTag, businessKey, state, variables, parentKey, processType)` | `POST /process-instances` |
   | `instance(key)` -> `Optional<ProcessInstanceRef>` (404 -> empty) | `GET /process-instances/{key}` |
   | `instances(filter)` -> `Page<ProcessInstanceRef>` with `totalCount` | `GET /process-instances` with `businessKey`, `bpmnProcessId`, `processDefinitionKey`, `state`, `createdFrom`, `size` |
   | `children(key)` -> `List<ProcessInstanceRef>` | `GET /process-instances/{key}/child-processes` |
   | `patchVariables(key, map)` | `PATCH /process-instances/{key}/variables` |
   | `history(key, page)` -> `Page<HistoryEntry>` | `GET /process-instances/{key}/history` |
   | `job(key)` -> `Optional<JobRef(key, type, state, elementId, elementType, elementInstanceKey, instanceKey, assignee, inputVariables, createdAt)>` | `GET /jobs/{key}` |
   | `jobs(filter)` -> `Page<JobRef>` | `GET /jobs` with `jobType`, `state`, `processInstanceKey` |
   | `completeJob(key, variables)` | `POST /jobs/{key}/complete` |
   | `failJob(key, errorCode, variables)` | `POST /jobs/{key}/fail` (errorCode may be null) |
   | `publishMessage(name, correlationKey, variables)` | `POST /messages` |
   | `extendJobLock(key, clientId, duration)` -> `Instant lockUntil` | `POST /jobs/{key}/extend-lock {clientId, lockDuration}` (ISO-8601); `200 {lockUntil}` (E13.1) |
   | `openApi()` -> `Optional<String>` | `GET /v1/openapi` if the engine serves it (open question 12); empty on 404 |

2. Any non-2xx answer (except where the table says otherwise) throws
   `ZenBpmApiException(status, engineCode, engineMessage, method, path)`; I/O and timeouts throw
   `ZenBpmUnavailableException(cause, method, path)`. Both carry the adapter id. The message of each
   names method and path, never the body verbatim beyond the engine's `message` field.
3. Partition-paged endpoints (`/process-instances`, `/jobs`): read `totalCount` from the page
   metadata and iterate partitions; a helper `Page.totalCount()` is what counts, never `items.size()`
   (decision 14).
4. `JobRef.inputVariables` and instance `variables` are `Map<String, Object>` decoded by Jackson
   (numbers as `Long` where integral, else `Double`; document it).

**Tests**

- `ZenBpmRestClientContractIT` (Docker): deploys a fixture model, exercises every method once against
  the container, asserts shapes (`created` on the second identical deploy is `false` and the key
  equal; `instances(businessKey=)` finds the started instance; `job()` of a waiting job is `active`;
  `completeJob` twice answers normally; `publishMessage` with nothing waiting throws a 404
  `ZenBpmApiException`; `patchVariables` is visible in `instance()`).
- `ZenBpmRestClientTest` with `com.sun.net.httpserver.HttpServer` on a free port: status mapping,
  timeout -> `ZenBpmUnavailableException`, unknown JSON fields ignored, `/v1` appended once.

**Acceptance criteria**

- [x] Every endpoint of `analysis/02-spi-requirements-mapping.md` the design names is a method here
  and is covered by the contract IT. All twenty of the table are methods, and
  `ZenBpmRestClientContractIT` calls each against the pinned engine (18 tests). The mapping also
  names `GET /process-definitions/statistics`, as one of two ways to answer `activeInstanceCountOf`;
  S10's plan takes the other, `instances(processDefinitionKey, state=active, size=1).totalCount()`,
  so the endpoint has no method.
- [x] No method returns the engine's JSON untyped; no method reads a message text to decide anything.
  Answers are decoded into package-private `ZenBpmWire` records named after the `api.yaml` schemas
  and handed out as records of `client.api`; only variables are maps, as instruction 4 asks. The
  client decides by status alone; the engine's `message` goes into the exception for people.
- [x] A request longer than `request-timeout` ends in `ZenBpmUnavailableException` within the timeout
  plus one second. `ZenBpmRestClientTest` with a timeout of one second: an answer which never comes
  ends through the HTTP client's own timeout, a body which stops after its headers through the
  client's wait 250 ms later; both tests fail preemptively after two seconds. A status the call
  does not expect, whose body stops, ends as `ZenBpmApiException` with that status in the same time.

**Implemented 2026-10-13.** `ZenBpmRestClientTest` (81 cases, fake engine on a port the system
picks) and `ZenBpmRestClientContractIT` (15 cases, Docker) are green; `mvn clean install` builds
green, the coverage gate measures 100 % of 4,240 instructions on both platforms, and
`bin/check-deploy-safety.sh` passes both runs. Seen to fail, each restored afterwards: no wait for a
body which stopped (the test failed after two seconds instead of hanging, which the first version of
it did), unknown fields refused, `/v1` kept in the engine address, no widening of `Integer`, the
total of a partition ignored, the total of a plain page ignored, no cancel of a call which ran out
of time (caught only once the close test stalled a body; with no headers at all the HTTP client's
own timeout had ended the call already), a `null` variable dropped, the interrupt flag lost.

What the contract test found about the engine at `bdd795a3`:

- A job carries a copy of its scope's variables, with the targets of its element's input mappings
  set on top (`pkg/bpmn/jobs.go`, `runtime.NewVariableHolder`): a job of a task without mappings
  carries the instance variable `orderId`. The analysis and S4.2.3 assumed an empty job; S4.2.3 has
  a note.
- `GET /process-instances/{key}` and `GET /jobs/{key}` answer a key of a partition the engine does
  not have (partition 0, a key of another engine) with 502 `CLUSTER_ERROR`, not 404. A key carries
  its partition in bits 12 to 21; the test uses `1 << 12` for a key nobody holds. Notes under S2.2.2
  and S5.2.1.
- A whole number sent as `1000.0` comes back as `1000`, and `GET /v1/openapi` is a 404; the test of
  the latter fails once an engine serves its document (decision 18).
- Every number is a Go `float64` in the engine: `2^53 + 1` comes back as `2^53`,
  `12345678901234567890` as `12345678901234567000`, and `10^22` with an exponent, so as a `Double`
  (found by a review, 2026-10-14, and pinned by the contract test).
- An unknown process definition is a 404, an unknown message correlation a 404, a lock extension by
  another client a 409, a second completion of a job 201.

Where the code differs from the instructions:

- `Readiness(ready, reasons)`: `ready` is status 200 rather than the `status` text, which says the
  same.
- `EngineStatus(version, commit, nodes)`: the commit as the engine reports it (12 characters), the
  node ids sorted.
- `DefinitionStatistics` is per element (`elements` -> `ElementCounts(active, incidents)`), because
  S5.2.2 sums the job-producing elements; the pinned engine answers no completed or terminated
  counts.
- `ProcessInstanceRef` also carries `createdAt` (S9 orders children by it); its `state` and
  `processType` and `JobRef.state` are enums. A value the pinned API does not know makes the answer
  unreadable rather than guessed.
- The filters are records, `InstanceFilter` and `JobFilter`, with `page` and `size` (1 to 100, the
  size per partition, refused before sending); every search asks for key order, so its paging
  neither repeats nor skips. The history can only be ordered by `createdAt`, in milliseconds and
  without a second key, so entries of one millisecond may repeat or go missing at a page boundary.
  `Page` carries `last`; `processDefinitions` and `children` read to the end, `history(key, page)`
  reads 100 entries a page.
- `failJob(key, errorCode, variables)` as the story names it, without `clientId`, `message`,
  `retries` or `deliveryToken`. E6 adds what it needs once it settles how a failure and the engine's
  retries (E13.3) work together.
- `extendJobLock(key, clientId, duration)` sends no `deliveryToken` either. With the token, a
  renewal by a handler whose lock lapsed answers 409 even where the job came back to the same
  client; without it, that renewal extends the second delivery's lock. E6 decides whether its
  renewal sends the token, together with `failJob`.
- A 2xx answer which cannot be read is a `ZenBpmUnavailableException` too, but it names the status:
  the engine did what was asked, only the body is lost. An interrupted call is one as well, and the
  interrupt is kept.
- Numbers in variables: Jackson's `USE_LONG_FOR_INTS` refuses a whole number beyond a `Long`, which
  the engine writes for a large one, so the client widens `Integer` itself and keeps a
  `BigInteger`. Nested objects and arrays are unmodifiable.
- `/system` is resolved beside `/v1`, so a path in front of the engine (a proxy) is kept for both.
- Decision 14 is now in `DECISIONS.md`, adapted from the plan's draft, because `Page` cites it.
- `core` takes `engine-test-support` and Testcontainers as test dependencies, runs failsafe and
  carries the logback template. `FakeEngine` is a test helper of its own, for S2.4.1's fake-server
  tests too.

**Answers to two reviews, 2026-10-14.** Every finding was checked against the source, the engine
at `bdd795a3` or the running engine before it was changed. `ZenBpmRestClientTest` now has 94 cases
and `ZenBpmRestClientContractIT` 18. Seen to fail, each restored afterwards: a lock duration sent
unrounded (the engine answered 400 to `PT10M0.5S` in the contract test), a duration of zero sent,
`null` read for a required field, a key of `7.9` read as `7`, the status of a stalled or broken
answer lost, the status not kept when the headers arrive, a call after `close()` sent, the status
of an unreadable answer dropped.

- `extendJobLock` sends whole seconds, rounded up, since the engine reads no fraction of a second.
  A duration of zero or less is refused before sending; `null` sends no duration, so the
  subscription's applies.
- `ZenBpmUnavailableException.getStatus()` names the status where one arrived (the user's
  decision). A start answered 201 whose body cannot be read is a start which happened.
- A status the call does not expect is the engine's answer even where its body stalls or breaks
  off: `ZenBpmApiException` with that status and no engine code (the user's decision). An expected
  status whose body does not arrive stays unavailable, naming the status.
- A required field holding `null` and a whole number with a fraction no longer decode; Jackson's
  `required` checked only that a field is present.
- A call after `close()` throws `IllegalStateException`, not a sign of an unavailable engine.
- `createdFrom` is inclusive (`created_at >=`), and the Javadoc says so now.
- The contract test now also covers `onlyLatest = true` over two versions and a search paged one
  instance at a time.
- Not changed: the BPMN fixtures carry no `BPMNDiagram`. That rule belongs to the engine's Go
  tests; neither this repository nor the VanillaBP adapters ask it of their test fixtures.
  (*Note, 2026-10-16:* reversed by the maintainer. Decision 29 asks every BPMN file of this
  repository for its diagram, and the contract fixtures carry one now.)

**CodeRabbit on the pull request, 2026-10-14.** `history(key, page)` sent a page below 1, which the
pinned engine answers with 400 (probed); it is refused before sending now, as the filters do.
`ZenBpmRestClientTest` has 96 cases; seen to fail with the check switched off.

**Done 2026-10-14**, merged to `main` as `0759616` (pull request #12, together with decision 23).
On that commit (GitHub keeps run logs for 90 days, so what they showed is written here as well):

- `Checks`, run
  [38558179625](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/actions/runs/38558179625):
  green on both JDKs. `bin/check-engine-copies.sh` reported both copies as the engine's files of
  `bdd795a3...`. `ZenBpmRestClientTest` ran 96 tests, `ZenBpmRestClientContractIT` 18, none
  skipped. The gate measured 100 % on both platforms, of 4,537 instructions on JDK 25 and 4,535 on
  JDK 21.
- `Publish snapshots`, run
  [38558179618](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/actions/runs/38558179618):
  green. `ZenBpmRestClientContractIT` ran 18 tests against the pinned engine, none skipped. The core
  snapshot it published, `2.0.0-20261014.114013-8`, contains `ZenBpmRestClient`, both exceptions
  and the records of `client.api`. Both report pages answer HTTP 200 and show 0 of 4,537
  instructions missed.

**Amended 2026-10-15.** E13.3 was already in the pinned engine `bdd795a3` when this story was
built, and E6 settles how a failure works: the engine counts the retries (decision 27). So
`failJob` gains what the pinned `POST /jobs/{jobKey}/fail` takes: `retries`, `retryBackoff`,
`message`, `deliveryToken` and `clientId`. Without `clientId` the lock stands until it lapses,
whatever `retryBackoff` says. `JobRef` gains `retries`, `retryAt` and `lastFailureMessage`, which
the pinned `Job` carries. The E13.3 twin makes both changes, with contract tests against the pinned
engine; nothing else of this story changes.

### S2.2.2 `ZenBpmErrors` - the one classification

- [ ] **Depends on:** S2.2.1.

**Instructions**

`org.pbinitiative.zenbpmadapter.client.ZenBpmErrors` with static predicates walking the cause chain:

| Predicate | True for |
|---|---|
| `permanentFailure(Throwable)` | `ZenBpmApiException` 400, 413, 415; `NumberFormatException` (a key which is not a number) |
| `isGoneJob(Throwable)` | `ZenBpmApiException` 404 raised by a job endpoint |
| `nobodyWaits(Throwable)` | `ZenBpmApiException` 404 raised by `POST /messages` |
| `lockLost(Throwable)` | `ZenBpmApiException` 409 raised by `extend-lock`: the lock lapsed, the job was completed or failed, or another client holds it - stop renewing, a second delivery may be under way |
| `isUnavailable(Throwable)` | `ZenBpmUnavailableException`; `ZenBpmApiException` 502, 503, 504; `SocketTimeoutException`, `HttpTimeoutException`, `ConnectException` |
| `isRepeatable(Throwable)` | everything not permanent (500 included, decision 10) |

The exception knows its `method` and `path`, which is how a 404 on a job is told from one on a message
without reading text.

*Found by S2.2.1's contract test, 2026-10-13.* `GET /process-instances/{key}` and `GET /jobs/{key}`
answer a key whose partition bits (12 to 21) name a partition the engine does not have with 502
`CLUSTER_ERROR` ("partition not found"), not with 404. That covers partition 0, which holds only
what every partition shares, and a key another engine handed out. By this table it is unavailable,
so a probe would answer BPMS_UNAVAILABLE for a key which is simply not this engine's. S5.2.1 and
S5.2.2 decide what such a key means; telling it apart without reading the message needs the key's
partition compared with the partitions `GET /system/status` reports.

*Found by two reviews of S2.2.1, 2026-10-14.* `ZenBpmUnavailableException.getStatus()` names the
status where the engine's answer arrived and only its body was lost or unreadable. A 2xx status
there means the engine did what was asked: a repeated `createInstance` would start a second
instance, and a value the pinned API does not know stays unreadable however often it is asked. The
table decides whether such an exception is repeatable. A status the call does not expect is a
`ZenBpmApiException` even where its body stalled, so its row applies as it stands.

**Tests** (`ZenBpmErrorsTest`): a table test per status code and per exception shape, plain and
wrapped in `CompletionException` and `RuntimeException`.

**Acceptance criteria**

- [x] The table in the README section "Which phase-two failures are repeated" and this test agree line
  by line. `ZenBpmErrorsTest#theReadmeAndThisTestNameTheSameRows` reads the table out of
  `README.md` and compares its first two cells, row and "Read as", with the rows the table test
  holds; a row renamed or relabelled in the README fails it.
- [x] No predicate inspects a message string. `ZenBpmErrors` reads `getStatus()`, `getMethod()`
  and `getPath()` of `ZenBpmApiException` and the types of the other exceptions, nothing else.
  `nothingIsReadFromAMessage` gives a `500` the engine code `NOT_FOUND` and the message "job 42
  not found", and a plain exception the words of a `404` on a message: both stay merely
  repeatable.

**Implemented 2026-10-14.** `ZenBpmErrorsTest` (36 cases, 37 since the reviews, every case plain, in
a `CompletionException`, in a `RuntimeException` and wrapped twice) and `ZenBpmErrorsIT` (8 cases
against the pinned engine) are green; `mvn clean install` builds green, the coverage gate measures
100 % of 4,760 instructions on both platforms, and `bin/check-deploy-safety.sh` passes both runs.
Seen to fail, each restored afterwards: a `409` of a completion or failure not read as a gone job
(the unit table and the IT), no guard against a ring of causes (the test fails after ten seconds
instead of hanging), a cause below the client's exception answering instead of it, `413` not
permanent, `502` not unavailable, the method not checked, a `null` path not guarded, every `404`
read as a gone job, a job path matched loosely, a `NumberFormatException` ignored, an
`HttpTimeoutException` ignored, a `ZenBpmUnavailableException` answering only through its cause, a
README row relabelled and a README row renamed.

What the IT found the pinned engine answers (`bdd795a3`):

- A completion and a failure of a job the engine does not hold are `404`, and so is a lock
  extension of one (`jobIsUnknown` in `internal/cluster/server/server.go`).
- A completion and a failure of a job which failed with an incident are `409` (the engine's
  `ErrJobInTerminalState`, raised for a completed, terminated or failed job, see
  `ensureJobStillWaits` in `pkg/bpmn/jobs_api.go`). A completion of a completed job is answered
  `201` again (S2.2.1's contract test). The engine already does what E13.10 asks of it for these two
  calls, so a `409` of either is a gone job here, as E13.10's adapter twin says.
- A lock extension of a job which waits but was never delivered over the job stream is `409`, the
  same answer as a lapsed lock or a lock of another client.
- A `historyTimeToLive` the engine cannot parse is `400`; a body one byte above the engine's
  default `MaxRequestBodyBytes` (10 MiB) is `413`; a start of a process the engine does not hold is
  `404`, not `400` (S5.1.1 has a note).
- `415` could not be produced through the client, which always sends the content type the call
  needs; the table test holds it.

Where the code differs from the instructions:

- `isGoneJob` also reads a `409` of `POST /v1/jobs/{key}/complete` and `.../fail` (above), and a
  `404` of every call under `/v1/jobs/{key}`, the lock extension included. `lockLost` is the `409`
  of the lock extension alone; a `404` there is a gone job, not a lost lock.
- The first `ZenBpmApiException` or `ZenBpmUnavailableException` in the chain of causes answers
  alone. A status the call did not expect whose body stalled carries the HTTP client's timeout as
  its cause, and the user's decision of S2.2.1 says the status is the answer; without the rule,
  `isUnavailable` would find the timeout beneath it. The JDK's `SocketTimeoutException`,
  `HttpTimeoutException` and `ConnectException` answer only where no exception of the client is in
  the chain.
- Settling the note on unreadable `2xx` answers: a `ZenBpmUnavailableException` is unavailable and
  repeatable whatever status it names. Making it permanent would block an outbox entry whose
  operation the engine carried out, and `ADAPTER-AUTHORS.md` asks the permanent list to hold only
  what the BPMS answers the same way every time. Not every repetition is harmless: a read is
  answered again and a completion of a completed job is answered `201`, but a start is guarded only
  by the core asking the adapter's redispatch probe first (`ADAPTER-AUTHORS.md`, "The redispatch
  probe must never be optimistic"; a wrong "unknown" costs a second workflow), a message may be
  correlated twice (the engine's `POST /v1/messages` carries no message id), and a failure without
  a delivery token counts twice (`JobFailByKey` in `pkg/bpmn/jobs_api.go`). E5, E6 and E7 decide
  what their callers accept.
- An expected `404` (of `instance()`, `job()`, `openApi()`) whose body stalled stays a
  `ZenBpmUnavailableException` naming `404`, as S2.2.1 decided, so it is unavailable and not a gone
  job.
- The note on `502` for a key of a partition the engine does not have stands: it reads as
  unavailable, `ZenBpmErrorsIT` pins that, and the README says so. S5.2.1 and S5.2.2 still decide
  what such a key means.
- Decision 10 is now in `DECISIONS.md`, adapted from the plan's draft to the pinned engine: a
  `409` on a job's completion or failure, not a `500`, is how the engine says a job no longer waits.

**Answers to two reviews, 2026-10-14.** Every finding was checked against the source and the
engine at `bdd795a3`. Valid and fixed:

- A failure `ZenBpmErrors` reads without the client (a JDK timeout, a refused connection, a
  `NumberFormatException`) wrapped *around* an exception of the client used to answer in its place,
  against the rule above; reproduced in JShell (a timeout around a `400` read as unavailable, a
  `NumberFormatException` around an engine nobody answers for read as permanent). Now only an
  exception of the client ends the walk, the first other failure answers where the chain holds
  none, and `aFailureWrappedAroundTheClientsExceptionDoesNotHideIt` holds the shapes, a ring among
  them.
- Decision 10 said a `409` of a completion or failure means "the job no longer waits", but the
  engine answers `409` there also for `ErrDeliverySuperseded`, a failure naming a delivery token
  another delivery replaced, while the job waits for that newer delivery. The decision now says the
  engine answers it for a command the job no longer takes; the javadoc and the README name both
  reasons. Notes added under S6.2.1 and E13.10.
- Decision 10 said an arrived status decides even when the body stalled; that holds only for a
  status the call does not expect, so it says so now.
- The README and the javadoc said repeating after an unreadable `2xx` "does no harm"; they now
  name the duplicates it may cause (above).
- The README guard compared maps, so it missed a reordered or a repeated row; it compares the rows
  in order now and refuses a row named twice. The `409` branch of `isGoneJob` checked the method
  without a test needing it; a row pins it. `ZenBpmErrorsIT` and `ZenBpmRestClientContractIT`
  share their helpers in `ContractFixtures`. The class javadoc says what to hand in, since every
  `NumberFormatException` reads as a key which is not a number.
- The README names the trade-off of a `404` or `409` a proxy answers (user's decision: record it,
  do not read the engine's `code`).

Not changed: the draft of decision 10 in `docs/architecture/02-design-decisions.md` stays as
drafted, like the drafts of the decisions adopted before it, and the row of the three JDK transport
failures stays as this story names it until a caller shows which exceptions it meets unwrapped. Seen to fail, each restored afterwards:
a JDK failure ending the walk again, the last instead of the first such failure answering, the
`409` branch ignoring the method, a README row moved and a README row repeated. After the answers
`mvn clean install` builds green (`ZenBpmErrorsTest` 37 cases, `ZenBpmErrorsIT` 8,
`ZenBpmRestClientContractIT` 18), the coverage gate measures 100 % of 4,764 instructions on both
platforms, and `bin/check-deploy-safety.sh` passes both runs.

**Done 2026-10-14**, merged to `main` as `4d49033` (pull request #14). On that commit (GitHub keeps
run logs for 90 days, so what they showed is written here as well):

- `Checks`, run
  [38572248054](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/actions/runs/38572248054):
  green on both JDKs. `bin/check-engine-copies.sh` reported both copies as the engine's files of
  `bdd795a3...`. `ZenBpmErrorsTest` ran 37 tests, `ZenBpmErrorsIT` 8, `ZenBpmRestClientTest` 96 and
  `ZenBpmRestClientContractIT` 18, none skipped. The gate measured 100 % on both platforms, of 4,764
  instructions on JDK 25 and 4,762 on JDK 21.
- `Publish snapshots`, run
  [38572248272](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/actions/runs/38572248272):
  green. `ZenBpmErrorsIT` ran 8 tests and `ZenBpmRestClientContractIT` 18 against the pinned engine,
  none skipped. The core snapshot it published, `2.0.0-20261014.132959-10`, contains `ZenBpmErrors`
  next to the REST client and its exceptions, and no test class. Both report pages answer HTTP 200
  and show 0 of 4,764 instructions missed.

---

## F2.3 Job stream

### S2.3.1 `ZenBpmJobStream`

- [ ] **Depends on:** S1.2.2, S2.2.1.

**Instructions**

1. `org.pbinitiative.zenbpmadapter.client.ZenBpmJobStream`: owns one `ManagedChannel` (plaintext or TLS per
   `grpc-plaintext`) and one bidirectional `JobStream` call with metadata `client_id`. API:
   `subscribe(jobType, lockDuration, maxActiveJobs)` / `unsubscribe(jobType)` with a reference count
   per type (two modules may share a type only where scoping is `none`; the count keeps the second
   unsubscribe from cutting the first module off; two subscribers of one type asking for different
   settings end the boot naming both, the engine keeps one setting per client and type),
   `onJob(Consumer<WaitingJob>)`, `close()`. The settings travel as `lock_duration_ms` and
   `max_active_jobs` of `StreamSubscriptionRequest` (E13.1) and are re-sent with every
   resubscription after a reconnect. The stream is opened lazily on the
   first subscription and reopened after a failure with an exponential backoff (1 s doubling to 30 s,
   jitter), re-sending every subscription with a reference count above zero. Reconnects are counted
   (`ZenBpmMetrics.streamReconnects`, E11) and logged at WARN once per outage and INFO when back.
2. `WaitingJob` is the generated message; the stream hands it on unchanged, `lock_until` included,
   to the consumer on a TIMING thread of the executor (S2.4.2), never on the gRPC transport thread,
   and never blocks. The receive instant is recorded next to it, so the lock can be counted from
   receipt with the subscribed duration where the clocks of engine and application differ (the
   engine's own recommendation).
3. `ErrorResult` frames are logged with code and message and, where they follow a subscribe, fail the
   subscription with a guiding exception naming the job type.
4. The client id must be unique per node: a second stream with the same id is rejected by the
   engine; log that rejection with the property key `vanillabp.adapters.<id>.client-id` and the
   default derivation.
5. The generated stubs need a `protobuf-java` runtime at least as new as the `protoc` which
   generated them (`protobuf.version` of the root POM). An application whose dependencies resolve
   an older one fails with protobuf's own error at the first stream class it touches. Check it at
   startup (Spring) / build time (Quarkus): compare `com.google.protobuf.RuntimeVersion` with the
   gencode version and name the dependency to raise and the version needed.

**Tests**

- `ZenBpmJobStreamIT` (Docker): deploy a model with one service task (type `t1`), subscribe `t1`,
  start an instance over REST, receive the `WaitingJob` within 5 s, complete it over REST, assert the
  instance completed; then stop the container (`engine.stop()`), start a new one, assert the stream
  reconnected and receives the next job (this proves the backoff path against a real engine).
- `ZenBpmJobStreamTest` with an in-process `io.grpc.inprocess` server implementing `ZenBpm`:
  reference counting, resubscription after reconnect (settings included), an error frame failing a
  subscription, `close()` ending the call, conflicting settings of one type refused.
- `ProtobufRuntimeCheckTest`: a runtime older than the gencode version is refused with the guiding
  message; the build's own runtime passes.
- `ZenBpmJobLockIT` (Docker): a subscription with a 2 s lock redelivers an unfinished job to a second
  client after about 2 s and not before; `extendJobLock` by 5 s keeps it away; `409` after the lock
  lapsed; closing the first stream hands its locked job to the second client at once.

**Acceptance criteria**

- [x] A job created while the stream is subscribed arrives; one created while the stream is down
  arrives after the reconnect (the engine hands undelivered jobs out on the next poll).
  `ZenBpmJobStreamIT#aJobArrivesAndItsCompletionCompletesTheInstance` receives the job within 5 s,
  completes it over REST and reads the instance `COMPLETED`.
  `#theStreamComesBackAfterTheEngineWasReplaced` cuts a TCP relay between stream and engine, stops
  the engine, starts a new one and creates a job on it while the relay is still cut, then restores
  the relay: the job arrives after the reconnect, and the log holds the WARN and the INFO.
- [x] Two subscriptions and one unsubscription of the same type leave the type subscribed.
  `ZenBpmJobStreamTest#aSharedTypeStaysSubscribedUntilTheLastSubscriberLeaves`: no unsubscription
  reaches the engine until the second subscriber leaves.
- [x] Each subscription carries the lock duration and the active-job cap it was given, and they
  survive a reconnect. `#everySubscriptionCarriesItsSettings` and
  `#aReconnectSubscribesEveryTypeAgain` compare the requests the engine received;
  `ZenBpmJobLockIT` shows the engine keeping a 2 s lock.
- [x] The consumer runs on an adapter thread, proven by asserting the thread name prefix.
  `ZenBpmJobStreamTest#aJobReachesTheConsumerOnATimingThread` and the first IT assert
  `zenbpm-<id>-timing-`. The tests name those threads themselves; the adapter's own get their names
  from S2.4.2, whose note asks for the same prefix.

**Implemented 2026-10-14.** `ZenBpmJobStream`, `ReceivedJob` and `ProtobufRuntimeCheck` in
`client`. `ZenBpmJobStreamTest` (29 cases, against an engine played in the same JVM with
`grpc-inprocess`, which this story adds as a test dependency), `ProtobufRuntimeCheckTest` (5),
`ZenBpmJobStreamIT` (2) and `ZenBpmJobLockIT` (3) against the pinned engine are green;
`mvn clean install` builds green, the coverage gate measures 100 % of 6,044 instructions on both
platforms, and `bin/check-deploy-safety.sh` passes both runs. Seen to fail,
each restored afterwards: no resubscription after a reconnect (the unit tests and
`ZenBpmJobStreamIT`), no lock duration in a subscription (the unit tests and `ZenBpmJobLockIT`,
whose job then stays locked for the engine's default of 30 s), a job handed over on the gRPC
thread, a cancel instead of a half-close on `close()`, every `UNKNOWN` read as a refused client id,
a lock duration rounded down, no conflict check, no reference count, an error frame which does not
reopen the call, the client-id WARN and the outage WARN repeated, no INFO once a frame arrives or
once the call stayed open, no reconnect count, no reset of the channel's own backoff, no cancel of a
pending reconnect on `close()`, a reconnect already due opening a call after `close()`, a job after
`close()` handed on, and a subscription during an outage opening a second call.

What the engine at `bdd795a3` does, read in `internal/grpc/server.go` and
`internal/cluster/jobmanager/`:

- A subscription the engine takes is answered with nothing. One it refuses gets an `ErrorResult`
  without a code, without a job and without a lock, whose message alone names the job type; it
  refuses one only where the client is not registered, and its handler registers the client before
  it reads the first request. A second call with a client id it serves ends at once with `UNKNOWN`
  (`failed to add client: ...`), the status every error of its handler gets; a panic would be
  `INTERNAL`.
- An unsubscription keeps the locks of jobs delivered before; the end of the call releases them at
  once (`removeClient` drops the client's entries of `distributedJobs`). Half-closing the call ends
  it: the handler returns once the client stops sending.
- The engine's gRPC server keeps grpc-go's default keepalive policy, which ends a connection whose
  client pings more often than every five minutes.

Where the code differs from the instructions:

- `subscribe(subscriber, jobType, lockDuration, maxActiveJobs)` and `unsubscribe(subscriber,
  jobType)` name who subscribes, so the conflict message names both subscribers, and the reference
  count is the set of subscribers: a module subscribing or unsubscribing twice changes nothing,
  where a bare count would cut another module off. The last unsubscription keeps the call open.
- **Error frames (the user's decision, 2026-10-14):** a refusal cannot fail its subscription,
  because nothing says which subscription it answers (above) and decision 10 forbids reading the
  prose. So an error frame naming neither a job nor a lock is logged at ERROR with code, message and
  every subscribed job type, and the call is cancelled and opened again, which subscribes every
  type again. One naming a job or a lock answers a request the stream never sends and is logged at
  WARN. Decision 9 is now in `DECISIONS.md`, adapted from the plan's draft with this consequence;
  E13.11 asks the engine to answer subscriptions.
- The stream counts as back on the first frame of a reopened call which is no refusal, or once the
  call stayed open for two seconds after its connection was ready (since the review answers below),
  because the engine sends nothing on a call it took, and ends one it refuses at once. The pause before an attempt is 1 s doubling to 30 s, cut short by a random part of up to a
  fifth. Before each attempt the stream resets the channel's own connect backoff, which grows to two
  minutes: measured with the engine away for three seconds, a channel without the reset found the
  engine back after 2 to 2.7 s instead of 0.1 s
  (`#anEngineBackInReachIsFoundOnTheNextAttempt`).
- A refused client id is told by `UNKNOWN` before the call counted as taken (see above), and logged
  at WARN once per outage with `vanillabp.adapters.<id>.client-id` and the default derivation; the
  stream keeps trying, since after a lost connection the engine may still hold the old call.
- The executor of S2.4.2 does not exist yet, so the stream takes a `ScheduledExecutorService`, the
  adapter id's timing threads: jobs are handed over with `execute`, reconnects scheduled with
  `schedule`. A job or a reconnect it refuses while it shuts down is logged; the job is left to its
  lock. Notes added under S2.4.1 and S2.4.2.
- `ZenBpmMetrics` arrives with E11, so the stream counts its attempts in `reconnects()`; note added
  under S11.1.1.
- The receive instant travels with the job as `ReceivedJob(WaitingJob job, Instant receivedAt)`.
  The constructor takes the client id resolved (`clientIdInUse`), note under S2.4.1. A lock duration
  is sent in whole milliseconds rounded up, zero asks for the engine's default, and a negative
  duration or cap is refused before anything is sent.
- `close()` half-closes the call, so the engine hands this stream's locked jobs to other clients at
  once, and waits up to five seconds for the engine to end it before it cuts the connection.
- `ProtobufRuntimeCheck` asks protobuf's own `RuntimeVersion.validateProtobufGencodeVersion`, the
  call the generated classes make when they load, with the version of the `protoc` of this build,
  which the build filters into `adapter-zenbpm.properties` as `protobuf.gencode`. A runtime older
  than 4.26.0 has no `RuntimeVersion` and is refused as well. The stream's public constructor runs
  the check before it touches a generated class; calling it at startup (Spring Boot) and at build
  time (Quarkus) belongs to E3, notes added under S3.1.1 and S3.2.1.
- `ZenBpmJobStreamIT` replaces the engine behind a TCP relay rather than on a fixed port: a new
  container maps its port anew, and the relay keeps the stream out of reach while the job is
  created, so the job is certainly created while the stream is down. `ZenBpmJobLockIT` lets the
  first stream unsubscribe after it received the job, so that the job, once its lock lapsed, can
  only go to the second stream (the engine hands jobs round robin).

**Answers to two reviews, 2026-10-14.** Every finding was checked against the source, grpc-java
1.81.0, grpc-go 1.84.0 and the engine at `bdd795a3`, and reproduced where it could be. A regression
test was written first and seen to fail on the reviewed commit, then the fix made it pass. Valid and
fixed:

- A job waiting for a timing thread was handed on after `close()`, or after its call was lost,
  although the end of the call had given it back to the engine. Each job now carries its call, and
  a timing thread hands it on only while that call is the open one
  (`#aQueuedJobIsDroppedWhenTheStreamCloses`, `#aQueuedJobIsDroppedWhenItsCallIsLost`). A
  consumer already running when the stream closes runs to its end.
- An error frame refusing a subscription ended the outage before it reopened the call, so an engine
  refusing every subscription was tried every second for ever, with an INFO, an ERROR and a WARN
  each time. A refusal no longer counts as an answer (`#refusalsKeepTheOutage`: one WARN, growing
  pauses).
- The refusal cancelled the call outside the stream's lock while the call stayed the open one, so a
  `subscribe`, an `unsubscribe` or `close()` meanwhile met grpc-java's `call was cancelled`, and a
  `close()` which threw left the channel open (reproduced: 6 of them in one second of
  `#noSendMeetsACancelledCall`). The call is now cancelled and forgotten in one step under the lock.
- A call counted as back two seconds after it was opened, even while its connection still waited
  for the engine's first HTTP/2 frame; the stream then never tried again. Reproduced with a proxy
  which accepts and never answers: the channel stayed `CONNECTING`, "is back" was logged, and the
  stream stayed silent. The two seconds now start once the connection is ready, and a call whose
  connection is not ready within twenty seconds counts as failed
  (`#aConnectionNobodyAnswersIsNoReturn`).
- A quiet, healthy call which later ended with `UNKNOWN` was reported as a refused client id,
  because the engine sends nothing on a call it took. `UNKNOWN` now reads as a refused client id
  only before the call counted as taken (`#aLateUnknownOnAQuietCallIsAnOrdinaryOutage`).
- A lock duration of `Long.MAX_VALUE` milliseconds and a fraction was rounded up past the largest
  `long` into a negative number, which the engine reads as its default.
- **Keepalive (the user's decision: now).** The "Not done" note this block replaces was wrong: a
  connection a network dropped silently is not noticed when the engine sends a job, since nothing
  reaches the client. Netty sets `SO_KEEPALIVE`, but Linux probes only after two hours. The channel
  now pings after 5 min 30 s of silence and gives up after 20 s without an answer
  (`#aSilentlyDroppedConnectionIsNoticed`, with the shortest interval gRPC allows, 10 s). grpc-go,
  which the engine keeps at its defaults, sends `GOAWAY too_many_pings` after the third ping which
  comes sooner than five minutes after the one before. Measured against the pinned engine once, not
  in the build (18 minutes): a stream pinging every minute got that `GOAWAY` after four minutes, a
  stream with the adapter's interval stayed up for 18 minutes and still received a job afterwards.
  E13.12 asks the engine to ping its side as well.
- **Inbound size (the user's decision: no limit).** gRPC refuses a message above 4 MiB unless the
  client raises the limit, and a job carries a copy of its scope's variables. The channel now takes
  messages of any size (`#thePingsAndTheJobSizeSuitTheEngine` receives a 5 MiB job). Measured on the
  way: the pinned engine refuses a start or a variable update above 4 MiB with `500` (its gRPC between
  the nodes keeps the default), so no job of that size can be made today; notes under S5.1.1 and
  E13.13.
- A refused unsubscription gets the same error as a refused subscription; the ERROR, decision 9 and
  E13.11 now say both.
- The constructor's promise to refuse an old protobuf runtime had no test.
  `#buildingOnAnOldProtobufRuntimeIsRefused` builds the stream through a class loader which hides
  `RuntimeVersion` and loads the generated classes itself, so a check moved behind a generated
  class fails it.
- Long messages were joined with `+`; they are text blocks now, as everywhere else in the
  repository. The job types in log lines are a copy, a Javadoc line was rewrapped, and
  `ZenBpmJobLockIT` measures the locks on the application's clock alone. The thread-name criterion
  says who names the threads.

Seen to fail, each restored afterwards: a queued job handed on after its call ended, a refusal
counted as back, a cancelled call left as the open one, a quiet call never counting as taken, the
two seconds counted from the opening, no deadline for a connection, no keepalive, gRPC's default
inbound limit, the protobuf check skipped, and the rounding overflow. After the answers `mvn clean
install` builds green (`ZenBpmJobStreamTest` 38 cases, `ProtobufRuntimeCheckTest` 5,
`ZenBpmJobStreamIT` 2, `ZenBpmJobLockIT` 3), the coverage gate measures 100 % of 6,186 instructions
on both platforms, and `bin/check-deploy-safety.sh` passes both runs.

Not changed: one engine claim of a review is not right. It said Netty does not set `SO_KEEPALIVE`;
grpc-java's `NettyClientTransport` does. The finding stands, because Linux sends the first probe
after two hours.

Before the merge, CodeRabbit found that the notes on variables above 4 MiB under S5.1.1 and E13.13
named no engine file, which `docs/README.md` requires. They now cite the inter-node gRPC server and
client, the `ZenNode` methods which turn the failed call into a technical error, and the REST body
limit, all at the pinned commit `bdd795a3`.

**Done 2026-10-14**, merged to `main` as `92fb7f2` (pull request #16). On that commit (GitHub keeps
run logs for 90 days, so what they showed is written here as well):

- `Checks`, run
  [38632291025](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/actions/runs/38632291025):
  green on both JDKs. `bin/check-engine-copies.sh` reported both copies as the engine's files of
  `bdd795a3...`. `ZenBpmJobStreamTest` ran 38 tests, `ProtobufRuntimeCheckTest` 5,
  `ZenBpmJobStreamIT` 2 and `ZenBpmJobLockIT` 3, none skipped. The gate measured 100 % on both
  platforms, of 6,186 instructions on JDK 25 and 6,182 on JDK 21.
- `Publish snapshots`, run
  [38632291055](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/actions/runs/38632291055):
  green. `ZenBpmJobStreamIT` ran 2 tests and `ZenBpmJobLockIT` 3 against the pinned engine, none
  skipped. The core snapshot it published, `2.0.0-20261014.202810-12`, contains `ZenBpmJobStream`,
  `ReceivedJob` and `ProtobufRuntimeCheck`, its `adapter-zenbpm.properties` carries
  `protobuf.gencode=4.35.0`, and it holds no test class. Both report pages answer HTTP 200 and show
  0 of 6,186 instructions missed.

---

## F2.4 Client lifecycle, engine wait, health, executor

### S2.4.1 Factory, registry, engine wait, health, environment info

- [ ] **Depends on:** S2.2.2, S2.3.1.

**Instructions**

1. `ZenBpmClientFactory(adapterId, configuration, executor)`: builds `ZenBpmRestClient` and
   `ZenBpmJobStream` eagerly (building contacts nothing), exposes `rest()`, `stream()`,
   `configuration()`, `isConfigured()`; `close()` closes the stream and then the HTTP client, after
   stopping whatever module never reached `stopWorkflowProcessing` (a hook the deployment service
   registers, with a WARN like Camunda 8's).
2. `ZenBpmClientRegistry`: `getFactory(adapterId)` (guiding failure for an unknown id),
   `factories()`, `sharesEngineWith(adapterId)` (identity comparison).
3. `ZenBpmEngineWait.awaitReady(factory)`: poll `ready()` every 2 s until 200, until `startup-wait`
   is used up (then throw naming address, time waited, last answer, and the key
   `vanillabp.adapters.<id>.startup-wait`), or until `permanentFailure` (throw at once). Log before
   the first attempt and every 10 s. Called once per adapter id by the deployment service (E4),
   remembered so a second module does not wait again.
4. `ZenBpmHealth.check(factory)` -> `AdapterHealth`: unconfigured -> UNKNOWN with the missing key;
   200 -> UP with the engine version; 503 -> DOWN with the reasons; unreachable -> DOWN with the
   exception's message; bounded by `request-timeout`; never throws. Built with the factory methods
   `AdapterHealth.up/down/unknown` and `AdapterHealth.detailsBuilder()`; since 2026-09-24 the
   details keep the order they were added in.
5. `ZenBpmEnvironmentInfo.logOnce(factory, startupReport)`: one INFO line per adapter id naming
   REST address, gRPC address, client id, engine version and commit (from `status()`) and the
   commit the adapter was tested with (`zenbpm.engine` of `adapter-zenbpm.properties`). Where the
   two commits differ, that is a finding, so it goes to the platform's `StartupReport` as `warn`
   with topic `INFRASTRUCTURE` and the scope `zenbpm adapter '<id>'`, never inside the text. The
   INFO line stays a log line: the `StartupReport` javadoc keeps reports about the adapter doing its
   work (which engine it reached) out of the block. Not a comparison of versions: while the pin is a
   `main` build, the engine's version names the last release and is the same for every build since.
   E3 hands the bean over; a test builds a small collecting `StartupReport` of its own.
6. What stays in the log: the engine wait's progress lines of step 3. The block of the
   `StartupReport` is written once, at the end of the start, which is too late for progress. The
   wait's failure stays a thrown exception, because nothing after it can run.

*Note from S2.3.1, 2026-10-14.* `ZenBpmJobStream(adapterId, configuration, clientId, executor)`
takes the client id resolved, so the factory calls `configuration.clientIdInUse(adapterId,
applicationName)` and needs the application name the platform knows (E3 names its source). The
`executor` is the adapter id's timing threads as a `ScheduledExecutorService`, which S2.4.2 has to
offer; the stream hands every job over on them and schedules its reconnects there. The stream's
constructor runs `ProtobufRuntimeCheck` (S2.3.1), so building a factory refuses a protobuf runtime
which cannot run the stream, before anything is contacted.

*Note from S2.4.2, 2026-10-15.* The factory builds the executor with
`ZenBpmExecutor.of(adapterId, ZenBpmExecutionModel.resolve(adapterId, configuration))` and hands
the stream `executor.timing()`. Building it starts no thread: both kinds start theirs on first use.
The drain of S6.2.2 has already waited `shutdown-grace` for the handlers when `close()` closes the
stream, so `close()` then calls `executor.shutdown()` and does not wait the grace again. The timing
threads stop by themselves once the last handler returned and drop what is due later;
`awaitTermination` waits for both. A receiver of `timing()` cannot shut it down.

**Tests**

- `ZenBpmEngineWaitIT` (Docker): start the container 3 s AFTER the wait began (a `CompletableFuture`
  starting it late), the wait returns; a wait with `startup-wait=PT2S` against a closed port fails
  naming the key.
- `ZenBpmHealthTest` (fake HTTP server): the four outcomes. `ZenBpmClientFactoryTest`: building
  contacts nothing (fake server counts zero requests), `close()` order.

**Acceptance criteria**

- [x] Building a factory for a complete configuration never opens a connection.
  `ZenBpmClientFactoryTest#buildingContactsNothing` builds one against a fake REST engine and a port
  which counts the connections it takes: 300 ms later neither saw anything. The same test then
  makes one REST call and one subscription and sees both arrive, so the two counters do count.
- [x] The wait's failure message names address, elapsed time, last answer and the property key.
  `ZenBpmEngineWaitTest#aWaitWhichRunsOutSaysEverything` matches the whole message: the engine's
  address, `'vanillabp.adapters.orders.startup-wait: PT0.3S'`, the time waited, the last answer
  (`not ready (no cluster leader elected)`) and the keys to correct or raise.
  `ZenBpmEngineWaitIT#aShortWaitAgainstAClosedPortFails` fails after `PT2S` against a closed port
  and names the key, and `#theWaitReturnsForAnEngineStartedLate` returns once the pinned engine
  reports ready, started 3 s after the wait had named itself and while the test saw it still
  waiting.
- [x] Health never throws and returns within `request-timeout` + 1 s against a black-hole address.
  `ZenBpmHealthTest#anEngineWhichNeverAnswersIsDownInTime` points the check at a port which takes
  the connection and never answers. With `request-timeout: PT1S` the check returns DOWN within the
  two seconds `assertTimeoutPreemptively` allows, naming `got no answer within PT1S`. A connection
  which never opens ends the same way, because the REST client bounds the whole call by the same
  deadline. `#aClosedFactoryIsDown` holds that a call which throws is DOWN, not an exception.
- [x] A commit mismatch arrives as one `warn` of topic `INFRASTRUCTURE` in the `StartupReport`, with
  the adapter id as its scope; the matching case reports nothing there.
  `ZenBpmEnvironmentInfoTest#anotherCommitIsOneWarning` finds exactly that one finding, with scope
  `zenbpm adapter 'orders'` and a text which does not name the adapter id;
  `#oneLineNamesTheEnvironment` and `#theCommitIsComparedIgnoringCase` find nothing reported for the
  tested commit.

**Implemented 2026-10-15.** `ZenBpmClientFactory`, `ZenBpmClientRegistry`, `ZenBpmEngineWait` and
`ZenBpmEnvironmentInfo` in `client`, `ZenBpmHealth` in `health`. All but `ZenBpmEnvironmentInfo`
are adapted from Camunda 8's classes and keep their licence (decision 17). `ZenBpmClientFactoryTest`
(10 cases; 13, 15 and 16 after the review, the re-review and CodeRabbit's review),
`ZenBpmClientRegistryTest` (5; then 6 and 7), `ZenBpmEngineWaitTest` (15; then 23 and 24),
`ZenBpmEnvironmentInfoTest` (8; then 10), `ZenBpmHealthTest` (9; then 11 and 12) and
`ZenBpmEngineWaitIT` (2) are green; `ZenBpmClientFactoryIT` (1) came with the review. `mvn clean
install` builds green with every integration test run and none skipped, the coverage gate measures
100 % of 8,148 instructions on both platforms, and `bin/check-deploy-safety.sh` passes both runs.

Seen to fail, each restored afterwards. In the factory: a REST call while it is built; the REST
client closed before the stream, or the executor not shut down; the hooks run in the order they
were registered, a failing hook not caught, a hook not removed, the hooks run after the stream was
closed, or no WARN about them; a second `close()` closing again; a client handed out without its
configuration. In the wait: a permanent answer waited out, a ready engine asked again, a failed
wait not remembered, a second module asking the engine itself instead of waiting for the first one,
a line on every attempt, the interrupt flag dropped, a pause running past the end of the wait, and
a wait which never ends. In the environment info: every commit taken for the tested one, a commit
of fewer than seven characters taken for one, the case compared, the engine asked twice, a failed
status call let through, another topic, and an adapter id without clients asked. In the health
check: a failure let through, an adapter id without clients reported as anything but UNKNOWN, an
engine which is not ready reported UP, the version left out. In the registry: two factories of one
adapter id accepted, the addresses compared as written, an adapter id sharing an engine with
itself or with one without clients, and a failing factory keeping the others open. A wait which
never ends used to hold the build up instead of failing it: `ZenBpmEngineWaitTest` now carries a
class-wide `@Timeout(30)`, and the two tests which time an end use `assertTimeoutPreemptively`.

Where the code differs from the instructions:

- **The factory builds its executor and takes the application name.** The constructor is
  `ZenBpmClientFactory(adapterId, configuration, applicationName)`, as the notes of S2.3.1 and
  S2.4.2 ask, not `(adapterId, configuration, executor)`. An adapter id which is not configured, or
  boots degraded, gets `ZenBpmClientFactory.unconfigured(adapterId, configuration, message)`. That
  factory builds nothing, and `rest()`, `stream()`, `executor()` and `clientId()` throw the message
  of the validation, which E3 and E5 rely on. The job stream is built before the REST client,
  because the stream is what refuses an unfit protobuf runtime, and the HTTP client starts a thread
  of its own when it is built. Note under S3.1.1.
- **`close()` order**: the modules which did not stop (WARN, newest hook first), the stream,
  `executor.shutdown()` without a second grace, the REST client. The hooks run without a lock of
  the factory held, so a hook may wait for the module's own stop. A client which fails to close
  does not keep the others open; the first failure is thrown with the later ones suppressed. Once
  `close()` began, `workflowModuleStarted` refuses a module, naming it (note under S4.3.3).
- **Health takes the version from startup (the user's decision).** `/system/health/ready` names no
  version, and `/system/status` is the engine's diagnostic endpoint, not its readiness contract. A
  second call would allow twice `request-timeout`. So the check asks `ready()` alone and names the
  version and commit `ZenBpmEnvironmentInfo` read at startup, as `versionAtStart` and
  `commitAtStart`; they are left out where that read did not happen. An adapter id which boots
  degraded is UNKNOWN like an unconfigured one, with its validation message, and names its address
  where `rest-address` is valid. Every answer of an adapter id with clients names the job stream's
  state as `jobStream`, because UP covers REST alone (note under S11.1.1).
- **Messages and the health name the engine by `engineAddress()`**, `rest-address` normalised
  without `/v1`, which the configuration gained for it; the readiness probe does not live under
  `/v1`. A message which embeds the REST client's leaves out the adapter id that one starts with
  (`ZenBpmErrors.withoutAdapterId`), so it is not named twice.
- **The commits are compared by prefix.** The engine reports its commit shortened to twelve
  characters (`shortCommitIDLength` in `internal/rest/system_status.go` at `bdd795a3`). So a commit
  is the tested one where it has at least seven characters and is the beginning of the pin,
  ignoring case, the rule `EngineUnderTestIT` uses. A `status()` call which fails is logged at WARN
  and reports nothing.
- **The wait remembers its answer, a failure too (changed after the review, see below).** Under
  `deployment-failure: warn` the start goes on after a failed wait, and every further module would
  otherwise wait `startup-wait` again. So after a failed wait a later module asks once, bounded by
  `request-timeout`: it takes an engine which became ready meanwhile, and otherwise fails at once
  with the first failure as its cause. A refusal no repetition changes is final, and nobody asks
  again. An interrupt is no answer of the engine and is not remembered. A factory closed while the
  application stops ends every later wait the same way, at its first attempt. A second module which arrives during the wait waits for its answer and asks
  nothing itself; it waits on a lock, so it can be interrupted. `startup-wait: PT0S` asks once. The
  last pause ends with the wait, so the last attempt is made when the wait runs out.
  `startup-wait` limits when an attempt starts, not how long it runs: an attempt under way when it
  runs out ends at its own deadline, and a ready answer is taken whenever it comes. So a failing
  wait takes at most `startup-wait` + `request-timeout` + 0.25 s. The 0.25 s is what the REST
  client gives a body which stopped arriving.
- **`ZenBpmEngineWaitIT` starts the engine behind a relay.** Testcontainers knows the mapped port
  only once the container runs, so the wait asks a `TcpRelay` on a port of its own, which refuses
  connections until the engine runs, and is pointed at it then.
- **Nothing called `ZenBpmEnvironmentInfo.logOnce`**: note under S4.3.2, which now calls it right
  after the wait.
- `FakeEngine` is public, because `ZenBpmHealthTest` lives in package `health`.

**Changed after the review of 2026-10-15.** Two reviews (kept outside the repository) found 23
points; what was valid is fixed, with the user's decisions on the three which were design choices.
- The factory's `close()` ran the module hooks holding the factory's monitor, which the hook's own
  registration needs, so a hook waiting for the module's own stop waited in vain (a deadlock
  without a bound). The hooks now run after the monitor is released
  (`ZenBpmClientFactoryTest#aHookMayWaitForTheModulesOwnStop`). A module which registers once
  `close()` began is refused (`#aModuleCannotStartOnceClosingBegan`), and a client which fails to
  close no longer keeps the others open (`#aFailingCloseDoesNotKeepTheOthersOpen`).
- The wait: after a failed wait a later module asks once (the user's decision), a refusal stays
  final, an interrupt is not remembered, a closed factory ends a wait with its next attempt instead
  of at `startup-wait`, and a module waiting for another one's wait can be interrupted (a lock
  instead of a monitor). `aSecondModuleWaitsForTheFirstOnesAnswer` now finds the second thread
  queued at that lock instead of sleeping 300 ms. That `startup-wait` limits when an attempt starts,
  not how long it runs, is written down and proven (`#aLateReadyAnswerIsTaken`,
  `#aFailingWaitEndsWithinStartupWaitAndRequestTimeout`).
- Health names the job stream's state as `jobStream` (`ZenBpmJobStream.state()`, the user's
  decision); whether a long outage turns it DOWN is left to E11. UNKNOWN names a valid address.
  `ZenBpmHealthTest#aReadyEngineWithAStreamDownSaysSo` reproduces the review's case: REST ready, a
  gRPC port which refuses, UP with `jobStream: down since ...`.
- Messages name the engine by `engineAddress()` and no longer repeat the adapter id; an empty
  registry says that no id is configured.
- The seven-character rule of the commit comparison was tested with an empty commit only, so
  "a commit of fewer than seven characters taken for one" above was not true: `>= 1` survived.
  `ZenBpmEnvironmentInfoTest#aShortCommitIsNoMatch` now covers six characters and
  `#sevenCharactersAreAMatch` seven, and both directions of that mutation fail.
- `ZenBpmEngineWaitIT#theWaitReturnsForAnEngineStartedLate` starts the engine only after the wait
  named itself and the test saw it still waiting 3 s later. `ZenBpmClientFactoryIT` runs the path
  E3 and E4 use against the pinned engine: wait, environment info with no finding (the engine's own
  twelve characters against the full pin), health UP with `commitAtStart`, a job received through
  `factory.stream()` and completed through `factory.rest()`, `jobStream: connected`, and health
  DOWN once the engine stopped. That is roadmap row 11's "health DOWN/UP" against the real engine.
- Not changed: `logOnce` reads the tested commit outside its guard, but a factory with clients
  cannot exist without the descriptor it reads (the job stream's `ProtobufRuntimeCheck` reads the
  same file in the constructor), so that path cannot be reached. The POM's licence comment is
  corrected; how the published POMs name both licences is open with the maintainers.

Seen to fail after the review, each restored: the hooks run under the monitor; a registration
after `close()` accepted; the cleanup stopped at the first failure, or later failures dropped; a
follower waiting uninterruptibly; a later module not asking once more; a refusal not final, also
when met by the module which asked once more; an interrupt remembered; a closed factory waited out;
the first failure replaced by a later one; six characters taken for a commit, or seven not enough;
`jobStream` missing on UP; the address of UNKNOWN taken as written; the adapter id repeated in a
health description; the stream's state ignoring whether the call was taken, an outage or `close()`;
an empty registry listing no ids; every message cut, or a failure without a message read as
`null`; the engine address keeping `/v1`. A check that a closed factory's failure is not
remembered survived, because every wait of a closed factory ends with its first attempt anyway: the
check was removed rather than kept.

`mvn clean install` builds green after the changes: core 425 unit tests and 34 integration tests,
none skipped, `ZenBpmClientFactoryIT` and both `ZenBpmEngineWaitIT` cases among them against
Docker; the coverage gate measures 100 % of 8,481 instructions on both platforms, and
`bin/check-deploy-safety.sh` passes both runs.

**Changed after the re-review of 2026-10-15.** Four minor points and six nits, each verified, the
four minor ones first reproduced by a test which failed:
- A hook which threw an `Error` skipped the cleanup, and a second `close()` could not repair it. The
  hooks are now the first step of the cleanup, which catches `Error` as well and throws the first
  failure with the later ones suppressed
  (`ZenBpmClientFactoryTest#aHookWhichThrowsAnErrorDoesNotKeepTheClientsOpen`).
- A registration removed its hook by `equals`, so closing one registration twice removed another
  registration of the same hook object. Each call of `workflowModuleStarted` now hands out its own
  registration, removed by identity (`#aRegistrationRemovesItsOwnHookOnly`).
- The plan said that a failure caused by closing the factory is not remembered. It is; nothing can
  tell, because every wait of a closed factory ends at its first attempt. The sentences here and
  under S4.3.2 are corrected.
- `unconfigured(id, null, message)` let `ZenBpmHealth.check` throw a `NullPointerException`. It now
  refuses a `null` configuration
  (`#aFactoryWithoutClientsNeedsTheMessageAndTheConfiguration`); the S3.1.1 note says so.
- Nits: a thread which is interrupted gets an answer the adapter id has already instead of a
  failure (`ZenBpmEngineWaitTest#aKnownAnswerIsNotFailedByAnInterrupt`); the javadoc of `close()`
  says that a second `close()` on another thread does not wait for the first; clearer names
  (`logTheEnvironmentOnce`, `whyNotReady`, `whatTheEngineSays`) and sentences; the S3.1.1 note says
  to close the factories where the registry refuses them; `ZenBpmClientFactoryIT` also finds
  `jobStream: down since ...` once the engine stopped.

Seen to fail, each restored: the cleanup not catching an `Error`, or swallowing it; the hooks run
before the cleanup instead of as its first step; a registration removing every registration of its
hook object; an unconfigured factory taking no configuration; a known answer refused to an
interrupted thread. `mvn clean install` builds green: core 428 unit tests and 34 integration tests,
none skipped; the coverage gate measures 100 % of 8,544 instructions on both platforms, and
`bin/check-deploy-safety.sh` passes both runs.

**Changed after CodeRabbit's review of PR #22.** Two comments, both valid, each first reproduced by
a test which failed:
- `ZenBpmClientRegistry.close()` caught `RuntimeException` only, so an `Error` which a factory
  passes on stopped the later factories from closing. It now closes every factory and throws the
  first `Error` with the later ones suppressed
  (`ZenBpmClientRegistryTest#anErrorDoesNotKeepTheOtherFactoriesOpen`).
- The configuration is mutable, and a `rest-address` made invalid after the clients were built made
  `ZenBpmHealth.check` throw before its `try`. It now leaves the address out instead
  (`ZenBpmHealthTest#anAddressMadeInvalidLaterDoesNotThrow`). The same test found that the
  factory's `close()` read the address from the configuration for its log line, just before
  closing the REST client, so the client stayed open: the factory now keeps the address its clients
  were built with (`ZenBpmClientFactoryTest#aChangedConfigurationDoesNotKeepTheRestClientOpen`).

Seen to fail, each restored: an `Error` swallowed by the registry, later ones dropped, the health
reading the address unguarded, and `close()` reading it from the configuration. `mvn clean install`
builds green: core 431 unit tests and 34 integration tests, none skipped; the coverage gate measures
100 % of 8,577 instructions on both platforms, and `bin/check-deploy-safety.sh` passes both runs.

**Done 2026-10-15**, merged to `main` as `b3e49d2` (pull request #22, after two reviews, a
re-review and CodeRabbit's review). On that commit (GitHub keeps run logs for 90 days, so what they
showed is written here as well):

- `Checks`, run
  [38764463540](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/actions/runs/38764463540):
  green on both JDKs. `bin/check-engine-copies.sh` reported both copies as the engine's files of
  `bdd795a3...`. `ZenBpmClientFactoryTest` ran 16 tests, `ZenBpmClientRegistryTest` 7,
  `ZenBpmEngineWaitTest` 24, `ZenBpmEnvironmentInfoTest` 10, `ZenBpmHealthTest` 12,
  `ZenBpmEngineWaitIT` 2 and `ZenBpmClientFactoryIT` 1, none skipped (core: 431 unit tests and 34
  integration tests). The gate measured 100 % on both platforms, of 8,577 instructions on JDK 25 and
  8,573 on JDK 21.
- `Publish snapshots`, run
  [38764463212](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/actions/runs/38764463212):
  green, with the same test counts, none skipped. The core snapshot it published,
  `2.0.0-20261015.155451-18`, contains `ZenBpmClientFactory` (with `Registration`,
  `WorkflowModuleShutdown` and `WorkflowModuleShutdownRegistration`), `ZenBpmClientRegistry`,
  `ZenBpmEngineWait` (with `Memory`, `Pacing` and `Refused`), `ZenBpmEnvironmentInfo` and
  `health/ZenBpmHealth`, names `zenbpm.engine=bdd795a399b6484d062d52ac90c0d0725cb0ad91` in its part
  descriptor, and holds no test class. Both report pages answer HTTP 200 and show 0 of 8,577
  instructions missed.

### S2.4.2 Executor and execution model

- [ ] **Depends on:** S2.1.1.

**Instructions**

Copy `Camunda8ExecutionModel`, `Camunda8Executor`, `Camunda8PlatformThreadExecutor` and
`Camunda8VirtualThreadExecutor` into `org.pbinitiative.zenbpmadapter.client` under the `ZenBpm` prefix, keeping
their contract: `worker-threads` platform threads or `virtual` bounded by `worker-threads-bound`; two
platform timing threads (`zenbpm-<id>-timing-N`) which no handler may occupy; `tryAcquireSlot()` /
`releaseSlot()` for the dispatcher (E6) to accept a job only while a slot is free; gauges for busy and
free slots. Drop what is Camunda-specific (the client's own executor hand-over).
`ZenBpmAdapterConfiguration.validate` already reports both keys with the same rule (a bound only
beside `virtual`), so call `ZenBpmExecutionModel.resolve` with `getWorkerThreads()` and the raw
`getWorkerThreadsBound()` (null where unset), never with `workerThreadsBoundInUse()`; its checks
stay as the backstop for a caller which skipped the validation.

*Note from S2.3.1, 2026-10-14.* The job stream takes the timing threads as a
`ScheduledExecutorService` named `zenbpm-<id>-timing-N`: it hands jobs over with `execute`, which
must never wait (an unbounded queue, as `ScheduledThreadPoolExecutor` has), and schedules its
reconnects with `schedule`. A job or a reconnect the executor refuses because it shuts down is
logged and dropped, which `ZenBpmJobStreamTest` holds.

**Tests**: copies of `Camunda8ExecutorTest`, `Camunda8VirtualThreadExecutorTest`,
`Camunda8PlatformThreadExecutorTest` and `Camunda8ExecutionModelTest` adjusted to the new names,
including the values which end the boot (`worker-threads: 0`, `virtual` with a bound of 0).

**Acceptance criteria**

- [x] More submissions than slots block the submitter rather than queuing (the dispatcher will not
  submit without a slot, but the executor itself must not grow a queue either).
  `ZenBpmExecutorTest#moreSubmissionsThanSlotsBlockTheSubmitter`, for both models: with two slots
  and both handlers inside, the third `execute` has not returned, its thread waits, and the handler
  runs once a slot is back; never more than two ran at once. The dispatcher does not wait at all:
  `#tryExecuteNeverWaits` gets `false` at once where every slot is taken (since the review answers
  below).
- [x] The timing threads are never used for a handler (assert by thread name in the test).
  `#theTimingIsNotStarvedByBusyHandlers` holds two timing tasks inside at the same time, on
  `zenbpm-orders-timing-0` and `-1`, while every handler is inside on `zenbpm-orders-handler-N`;
  `#aDispatcherToldAboutFreedSlotsRunsEveryJob` runs forty jobs through two slots and finds every
  one on a handler thread. `ZenBpmJobStreamTest` and `ZenBpmJobStreamIT` now take the executor's own timing
  threads, so their thread-name assertions hold for the adapter's threads, not for ones the tests
  name.

**Implemented 2026-10-14.** `ZenBpmExecutionModel`, `ZenBpmExecutor`,
`ZenBpmPlatformThreadExecutor` and `ZenBpmVirtualThreadExecutor` in `client`, adapted from Camunda
8's four classes and under its licence (decision 17). `ZenBpmExecutionModelTest` (25 cases),
`ZenBpmExecutorTest` (30), `ZenBpmPlatformThreadExecutorTest` (3) and
`ZenBpmVirtualThreadExecutorTest` (3) are green; `mvn clean install` builds green, the coverage gate
measures 100 % of 6,925 instructions on both platforms, and `bin/check-deploy-safety.sh` passes both
runs. Seen to fail, each restored afterwards: no wait for a handler thread, handlers run on the
timing threads, slots beyond the bound, a slot given back which nobody took, no guard against a
handler submitting a handler, cancelled timing tasks kept, a handler's exception not caught, no look
for a shutdown while waiting, a thread permit lost on a refusal, the interrupt flag dropped, a
thread permit kept after the handler (the build hangs), a bound of zero accepted, other thread
names, the virtual model building platform threads, a pool of one thread, and in the model a bound
beside platform threads accepted, the bound in use handed on instead of the bound as written, zero
threads and a zero bound accepted.

Where the code differs from the instructions:

- **No `ScheduledExecutorService` of its own.** Camunda 8's executor is one, because the Camunda
  client takes a single executor for its polls and its handlers. Nothing here takes one: the timing
  threads are `timing()`, a `ScheduledThreadPoolExecutor` of two daemon threads behind an
  unconfigurable view (a cancelled task leaves its queue), and the handlers go to
  `execute(Runnable)`. So the gate which held a poll back while every slot was busy is gone with the
  hand-over: `max_active_jobs` takes its place (decision 9, and the executor row of
  `docs/analysis/03-template-assessment.md`).
- **One count of slots, which the executor keeps (since the review answers below).** A slot is
  taken before a handler is handed to the handler threads and given back once it returned.
  `tryExecute(handler)` takes a free slot or answers `false` at once; `execute(handler)` waits for
  one in the submitting thread, which the criterion above asks for, and checks for a shutdown every
  100 ms while it waits (an interrupt refuses it, the interrupt kept). `onSlotFreed(listener)` tells
  the dispatcher when it can hand over the next job. `tryAcquireSlot()` / `releaseSlot()` of the
  instructions are gone, and so is a slot given back by the handler. Camunda 8's virtual model takes
  its permit inside the virtual thread, because the thread which submits there belongs to the
  client; here the dispatcher's thread does not wait either, since it uses `tryExecute`. The platform
  pool keeps a queue with one place per thread for the handler which arrives while the thread before
  it is still on its way back; it takes memory only for what it holds.
- **Neither a handler nor a timing thread can wait for a slot.** With every slot taken a handler
  would wait for the slot it holds itself, and a timing thread would hold the job stream back for as
  long as a handler runs, so `execute` on either throws `IllegalStateException`; `tryExecute` may
  be called there (the timing threads since the second answers below). Note under S6.1.1.
- **An exception which escapes a handler is logged at ERROR** with the adapter id, a checked one
  thrown sneakily included, and the thread serves the next handler. An `Error` is logged with the
  adapter id as well and then passed on, so its thread ends (since the second answers below).
  Camunda 8 left all of it to the thread's default handler.
- **The timing threads outlive the handlers (since the review answers below).** `shutdown()` takes
  no new handler, and the timing threads stop once the last running handler returned, dropping
  what is due later. Whoever receives `timing()` can neither resize it nor shut it down.
- `getWaiting()` is gone: what waits for a slot is the dispatcher's queue, and nothing waits in the
  executor. `ZenBpmExecutor.of(adapterId, model)` builds the executor a model names, and
  `ZenBpmExecutionModel.resolve(adapterId, configuration)` hands on `getWorkerThreads()` and the raw
  `getWorkerThreadsBound()`, as the instruction asks. The executor is no `java.util.concurrent.Executor`
  any longer (since the review answers below): nothing hands it on as one, and a caller which knows
  only that interface would be surprised by an `execute` which waits and refuses handler threads. Its default is
  `ZenBpmAdapterConfiguration.DEFAULT_WORKER_THREADS`, so the two cannot drift apart, and
  `#theModelRefusesWhatTheValidationRefuses` checks thirteen pairs of values: the model refuses
  exactly what the validation refuses. A number too large for an `int` is called too large, not a
  word. The thread names are `zenbpm-<id>-timing-N` and `zenbpm-<id>-handler-N`, counted from 0;
  no thread starts before the first use. Notes added under S2.4.1, S6.1.1, S8.1.1 and S11.1.1.

**Answers to two reviews, 2026-10-15.** Every finding was checked against the source and JDK 25,
and each one which could be reproduced was first written as a test which failed on the reviewed
commit `6e00ca9`: `execute(null)` accepted, a checked exception thrown sneakily costing its platform
thread unlogged, a timing task due in an hour holding `awaitTermination` up, a try-with-resources
on `timing()` shutting the timing threads down, a slot holder waiting a whole handler's runtime
behind a handler without a slot, `worker-threads: 2147483647` ending in `OutOfMemoryError` (which
took the whole test JVM down), and a handler handing work to the timing threads after `shutdown()`
logged as a failed handler. That two timing threads run at once held already; only the test did not
show it. Valid and fixed:

- **One count of slots (the user's decision).** Slots and the handler limit were two counts, so a
  dispatcher holding a slot could wait in `execute` on a timing thread for as long as a handler
  which took no slot ran, and two such waits stopped the job stream's hand-over and reconnects. E8's
  poller would have done just that. Now the slots are the limit, and the executor takes and gives
  them back (above); `#tryExecuteNeverWaits` takes one slot with each method and finds the third
  refused at once.
- **The timing threads outlive the handlers (the user's decision).** `shutdown()` stopped both at
  once, so a handler which handed its next job to the timing threads after it got a
  `RejectedExecutionException` and an ERROR, and a lock renewal could not run. A delayed task kept
  the timing threads, and so `awaitTermination`, waiting until it was due. Now they stop after the
  last handler and drop what is due later (`#theTimingOutlivesTheHandlers`,
  `#aDelayedTimingTaskDoesNotHoldTheShutdownUp`, which also fails where `awaitTermination` ignores
  the timing threads). Nobody is told about a slot freed during a shutdown, and a listener refused
  because of one is logged at DEBUG, not as an error. The S2.4.1 note waited `shutdown-grace` a
  second time after the drain of S6.2.2 had; it no longer does.
- `execute` and `tryExecute` refuse `null` at once, before they wait or take a slot.
- A handler's checked exception thrown sneakily is caught and logged like any other
  (`ZenBpmPlatformThreadExecutorTest#aSneakyCheckedExceptionKeepsTheThread`).
- The platform pool's queue was an `ArrayBlockingQueue`, which allocates every place at once; it is
  a `LinkedBlockingQueue` of the same capacity now (`#theLargestNumberOfThreadsWorks`).
- `shutdownNow()` returned the handlers which never started as well, and running one marked the
  calling thread as a handler thread for good. It returns the timing tasks only, and its Javadoc
  says that a dropped handler's slot stays taken.
- `timing()` refuses `shutdown`, `shutdownNow` and therefore `close` with
  `UnsupportedOperationException` (`#theTimingThreadsStayWithTheExecutor`).
- "Slots" and "bound" named one number twice; the executor speaks of slots everywhere now
  (`getSlots()`, as the model's `slots()`). `ZenBpmExecutionModel.describe()` had no caller and is
  gone; the story which logs the model can bring it back.
- Tests: two timing tasks now wait for each other, so a single timing thread fails the test; the
  handler of `#shutdownNowInterruptsAndHandsBack` waits on a latch instead of sleeping, and every
  latch is released in `finally`; the dispatcher test records refusals and asserts there were none;
  `assertNull` where it fits. The class comments of the two subclass tests are wrapped again.
- Wording: the class comment says how jobs of several types wait with the dispatcher, the wait
  "checks whether the executor is shutting down", and the sentence about the handler threads is no
  longer inverted.

Seen to fail, each restored afterwards: `null` taken by either method, only unchecked exceptions
caught, the timing threads shut down or shut down at once by their receiver, delayed tasks kept,
the timing threads stopped together with the handlers or while one still runs, nobody stopping
them after a hand-over the shutdown overtook (`#aHandOverOvertakenByTheShutdownStillStopsTheTiming`,
with handler threads which call `shutdown()` before they refuse), the listener told during a
shutdown, its refusal logged as an error or its exception let through, `awaitTermination` ignoring
the timing threads, `tryExecute` waiting or ignoring a shutdown, `execute` taking no slot, a slot
kept after the handler or after a refusal, no guard on a handler thread, no check for a shutdown
while waiting, the interrupt flag dropped, a cancelled task kept, the busy slots counted wrong, the
`ArrayBlockingQueue` back, and a pool of one thread. After the answers `mvn clean install` builds
green (`ZenBpmExecutorTest` 37 cases, `ZenBpmExecutionModelTest` 25,
`ZenBpmPlatformThreadExecutorTest` 5, `ZenBpmVirtualThreadExecutorTest` 3, `ZenBpmJobStreamTest`
38, `ZenBpmJobStreamIT` 2, `ZenBpmJobLockIT` 3), the coverage gate measures 100 % of 6,958
instructions on both platforms, and `bin/check-deploy-safety.sh` passes both runs.

Not changed: none of the findings was wrong. Copilot's recommendation to keep timing work which an
in-flight handler still needs is what the shutdown above does.

**Answers to two re-reviews, 2026-10-15.** The same two reviewers read `c87ceca`. Every finding
was checked against the source, and each was first written as a test which failed on that commit;
the one which shows the dispatcher's rule failed once the rule was taken out of the test's
dispatcher. Valid and fixed:

- **`shutdownNow()` lost the timing tasks it promises to hand back** (both reviews). It interrupted
  the handlers before it stopped the timing threads, and a handler which ended in between gave the
  last slot back and stopped them the orderly way, which drops what is due later. The first clean
  build of one reviewer failed on it. `shutdownNow()` now marks itself before the shutdown flag,
  and a handler which sees the one sees the other and leaves the timing threads alone.
  `#shutdownNowKeepsTheTimingTasksOfAHandlerEndingMeanwhile` forces the moment with handler threads
  whose `shutdownNow` returns only after the handler ended. Measured on the side, 600 runs per
  model of the real executors: 7 of 1,200 lost the task before, none after.
- **The dispatcher shown to E6 could strand a job.** It took a job, was refused, and put the job
  back without looking again; a slot freed in between had its listener find nothing. The test's
  dispatcher is now `WaitingJobs`, which puts the job back before it looks for a free slot again,
  and `#aJobPutBackAfterARefusalIsNotStranded` opens that window on purpose. The rule is in the
  Javadoc of `onSlotFreed` and in the S6.1.1 note.
- A listener's checked exception thrown sneakily escaped the log and cost its platform thread; the
  listener's catch takes every `Exception` now, as the handler's does.
- An `Error` of a handler reached only the JVM's default handler, without the adapter id; it is
  logged with it and passed on.
- `execute` on a timing thread waited for a slot and held every reconnect back meanwhile; it is
  refused there now with a message which names `tryExecute`, as on a handler thread.
- An interrupted submitter was refused although a slot was free, with a message saying it had
  waited; a free slot is taken at once now, and the interrupt is kept.
- The model named the bound first where `worker-threads` was a word beside a bound; it names the
  word first, as the validation does (a fourteenth pair in `#theModelRefusesWhatTheValidationRefuses`).
- `awaitTermination` failed with `ArithmeticException` for a timeout beyond about 292 years; it
  counts any such timeout as the longest one.
- Tests: `#theTimingIsNotStarvedByBusyHandlers` asserted the test's own latch where it meant the busy
  slots; the sneaky thrower is one shared `SneakyThrow`; `onSlotFreed` says it replaces a listener
  set before.
- Recorded for E6 rather than fixed here: a job which waits in the dispatcher can see its lock lapse
  before its handler starts; note under S6.1.1. Decision 9 accepts the second delivery that follows.

Seen to fail, each restored afterwards: `shutdownNow()` not marked for the handlers, a listener's
catch for unchecked exceptions only, an `Error` not logged or swallowed, no guard on a timing
thread or the timing threads not remembered, no free slot taken before the wait, the timeout not
counted as the longest one, the model checking the bound before the word, and `WaitingJobs` giving
up without its second look. After the answers `mvn clean install` builds green
(`ZenBpmExecutorTest` 50 cases, `ZenBpmExecutionModelTest` 27, `ZenBpmPlatformThreadExecutorTest`
7, `ZenBpmVirtualThreadExecutorTest` 3, `ZenBpmJobStreamTest` 38, `ZenBpmJobStreamIT` 2,
`ZenBpmJobLockIT` 3), the coverage gate measures 100 % of 7,032 instructions on both platforms, and
`bin/check-deploy-safety.sh` passes both runs. Not changed: none of the findings was wrong.

**Done 2026-10-15**, merged to `main` as `18e15ec` (pull request #18). On that commit (GitHub keeps
run logs for 90 days, so what they showed is written here as well):

- `Checks`, run
  [38708643232](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/actions/runs/38708643232):
  green on both JDKs. `bin/check-engine-copies.sh` reported both copies as the engine's files of
  `bdd795a3...`. `ZenBpmExecutorTest` ran 50 tests, `ZenBpmExecutionModelTest` 27,
  `ZenBpmPlatformThreadExecutorTest` 7, `ZenBpmVirtualThreadExecutorTest` 3, `ZenBpmJobStreamIT` 2
  and `ZenBpmJobLockIT` 3, none skipped. The gate measured 100 % on both platforms, of 7,032
  instructions on JDK 25 and 7,028 on JDK 21.
- `Publish snapshots`, run
  [38708643091](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/actions/runs/38708643091):
  green, with the same test counts, none skipped. The core snapshot it published,
  `2.0.0-20261015.085130-14`, contains `ZenBpmExecutionModel`, `ZenBpmExecutor` (with its
  `TimingThreads`), `ZenBpmPlatformThreadExecutor` and `ZenBpmVirtualThreadExecutor`, and it holds
  no test class. Both report pages answer HTTP 200 and show 0 of 7,032 instructions missed.

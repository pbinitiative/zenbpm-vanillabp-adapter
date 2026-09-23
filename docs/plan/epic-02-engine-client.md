# E2 - Engine client (plain Java, `core`)

**Goal.** Everything the adapter needs to talk to one ZenBPM engine, per adapter id, without any
platform: configuration and its validation, the REST client, the job stream, the error classification,
the engine wait, health, and the executor the handlers run on.

**Why before the platforms.** The platform modules only construct and register; if the client layer
is complete first, the registration stories are thin and the SPI stories never touch transport code.

**Done when.** A plain JUnit test can configure a client, deploy a model, start an instance, receive
its job on the stream and complete it over REST against the container, and every status code the
engine can answer is classified in one place.

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

- [ ] Every key of the architecture table has a field, a default and a validation where one applies.
- [ ] Messages contain the full property key for every named defect and never a value of `client-id`
  (treated like a credential, it may carry a hostname somebody considers internal).

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
   | `status()` -> `EngineStatus(version, nodes)` | `GET /system/status` |
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

- [ ] Every endpoint of `analysis/02-spi-requirements-mapping.md` the design names is a method here
  and is covered by the contract IT.
- [ ] No method returns the engine's JSON untyped; no method reads a message text to decide anything.
- [ ] A request longer than `request-timeout` ends in `ZenBpmUnavailableException` within the timeout
  plus one second.

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

**Tests** (`ZenBpmErrorsTest`): a table test per status code and per exception shape, plain and
wrapped in `CompletionException` and `RuntimeException`.

**Acceptance criteria**

- [ ] The table in the README section "Which phase-two failures are repeated" and this test agree line
  by line.
- [ ] No predicate inspects a message string.

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

**Tests**

- `ZenBpmJobStreamIT` (Docker): deploy a model with one service task (type `t1`), subscribe `t1`,
  start an instance over REST, receive the `WaitingJob` within 5 s, complete it over REST, assert the
  instance completed; then stop the container (`engine.stop()`), start a new one, assert the stream
  reconnected and receives the next job (this proves the backoff path against a real engine).
- `ZenBpmJobStreamTest` with an in-process `io.grpc.inprocess` server implementing `ZenBpm`:
  reference counting, resubscription after reconnect (settings included), an error frame failing a
  subscription, `close()` ending the call, conflicting settings of one type refused.
- `ZenBpmJobLockIT` (Docker): a subscription with a 2 s lock redelivers an unfinished job to a second
  client after about 2 s and not before; `extendJobLock` by 5 s keeps it away; `409` after the lock
  lapsed; closing the first stream hands its locked job to the second client at once.

**Acceptance criteria**

- [ ] A job created while the stream is subscribed arrives; one created while the stream is down
  arrives after the reconnect (the engine hands undelivered jobs out on the next poll).
- [ ] Two subscriptions and one unsubscription of the same type leave the type subscribed.
- [ ] Each subscription carries the lock duration and the active-job cap it was given, and they
  survive a reconnect.
- [ ] The consumer runs on an adapter thread, proven by asserting the thread name prefix.

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
   exception's message; bounded by `request-timeout`; never throws.
5. `ZenBpmEnvironmentInfo.logOnce(factory)`: one INFO line per adapter id naming REST address, gRPC
   address, client id, engine version (from `status()`) and the pinned version the adapter was tested
   with, plus a WARN where the two minors differ.

**Tests**

- `ZenBpmEngineWaitIT` (Docker): start the container 3 s AFTER the wait began (a `CompletableFuture`
  starting it late), the wait returns; a wait with `startup-wait=PT2S` against a closed port fails
  naming the key.
- `ZenBpmHealthTest` (fake HTTP server): the four outcomes. `ZenBpmClientFactoryTest`: building
  contacts nothing (fake server counts zero requests), `close()` order.

**Acceptance criteria**

- [ ] Building a factory for a complete configuration never opens a connection.
- [ ] The wait's failure message names address, elapsed time, last answer and the property key.
- [ ] Health never throws and returns within `request-timeout` + 1 s against a black-hole address.

### S2.4.2 Executor and execution model

- [ ] **Depends on:** S2.1.1.

**Instructions**

Copy `Camunda8ExecutionModel`, `Camunda8Executor`, `Camunda8PlatformThreadExecutor` and
`Camunda8VirtualThreadExecutor` into `org.pbinitiative.zenbpmadapter.client` under the `ZenBpm` prefix, keeping
their contract: `worker-threads` platform threads or `virtual` bounded by `worker-threads-bound`; two
platform timing threads (`zenbpm-<id>-timing-N`) which no handler may occupy; `tryAcquireSlot()` /
`releaseSlot()` for the dispatcher (E6) to accept a job only while a slot is free; gauges for busy and
free slots. Drop what is Camunda-specific (the client's own executor hand-over).

**Tests**: copies of `Camunda8ExecutorTest`, `Camunda8VirtualThreadExecutorTest`,
`Camunda8PlatformThreadExecutorTest` and `Camunda8ExecutionModelTest` adjusted to the new names,
including the values which end the boot (`worker-threads: 0`, `virtual` with a bound of 0).

**Acceptance criteria**

- [ ] More submissions than slots block the submitter rather than queuing (the dispatcher will not
  submit without a slot, but the executor itself must not grow a queue either).
- [ ] The timing threads are never used for a handler (assert by thread name in the test).

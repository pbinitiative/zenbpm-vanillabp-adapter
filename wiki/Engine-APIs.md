Every call this adapter makes against a ZenBPM engine, when it makes it and why. Hand this page to
whoever puts a proxy in front of the engine or unlocks its endpoints: an endpoint missing here is one
the adapter never calls.

The adapter uses two ports of the engine:

- **REST** at `rest-address`, under the API version `/v1`, apart from the two endpoints of
  `/system`. Every command the adapter sends, the completion of a job and the renewal of its lock
  included, goes over REST, so that one classification of the engine's answers covers all of them
  ([decision 9](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/blob/main/DECISIONS.md#9-job-delivery-takes-the-stream-with-a-lock-the-adapter-sizes-and-renews),
  [decision 30](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/blob/main/DECISIONS.md#30-what-the-engine-did-is-read-from-status-codes-and-a-start-of-a-process-it-does-not-hold-is-permanent)).
  The REST client is checked against the pinned engine, one call each, by
  `ZenBpmRestClientContractIT`.
- **gRPC** at `grpc-address`, for the job stream alone.

A proxy has to pass the status codes of the engine through as they are: the adapter reads what the
engine did from the status, the method and the path of a call, never from its text. A proxy which
answers `404` for a path it does not route makes the adapter believe a job is gone, see
[README: Which phase-two failures are repeated](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/blob/main/README.md#which-phase-two-failures-are-repeated).

## The job stream

|        Call        |                                           When                                           |                                                                                                                                                                                                                                                                                                                          Why                                                                                                                                                                                                                                                                                                                          |
|--------------------|------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `ZenBpm/JobStream` | from the start of the first workflow module of an adapter id until the adapter id closes | one bidirectional gRPC call per adapter id and node, under the metadata `client_id`; the adapter sends only subscriptions over it, `TYPE_SUBSCRIBE` with the `lock_duration_ms` of `job-timeout` and the `max_active_jobs` of the handler slots plus the tasks of the type parked for their asynchronous completion, sent again when that number changes (`ZenBpmJobStreamTest#aParkedJobRaisesTheCapOfItsType`), and `TYPE_UNSUBSCRIBE` when a workflow module stops, and receives the jobs of the subscribed types (`ZenBpmJobStreamTest#everySubscriptionCarriesItsSettings`, `ZenBpmJobStreamIT#aJobArrivesAndItsCompletionCompletesTheInstance`) |

A stream which breaks is opened again, and every type is subscribed again
(`ZenBpmJobStreamTest#aReconnectSubscribesEveryTypeAgain`). The stream must stay open for as long as
the application runs, so a proxy in front of it must not close a quiet gRPC call after a timeout of
its own; where it does, the adapter opens it again, and the jobs arrive late for that moment.

## What the adapter calls over REST

<!-- calls: DocumentationTest compares this table with the calls of the adapter's REST client -->
| Call | When | Why |
|---|---|---|
| `GET /system/health/ready` | while the first deployment waits for the engine, and on every health check | to deploy only once the engine is ready (`startup-wait`), and to answer the health check |
| `GET /system/status` | once at the start, and when a task id names a partition the engine does not hold | to log and check the engine's version and commit, and to know the engine's partitions |
| `POST /v1/process-definitions` | at the start, once per BPMN file of a workflow module | to deploy the file; an unchanged file gets no new version |
| `POST /v1/dmn-resource-definitions` | at the start, once per DMN file of a workflow module | to deploy the decisions |
| `GET /v1/process-definitions` | at the start, before phase one refuses a message, and while the viewer reads | to list the versions of a process, with their version tags |
| `GET /v1/process-definitions/{key}` | at the start, after each BPMN file is deployed and once per older version the start reads; at the end of a workflow, for an iteration and while the viewer reads, once per version each | to read a model the engine holds, the markers the adapter wrote included |
| `GET /v1/process-definitions/{key}/statistics` | when VanillaBP asks for the open tasks of a process | to count the tasks without reading pages of instances |
| `POST /v1/process-instances` | in phase two of a start | to start the workflow, under the aggregate's id as its business key |
| `GET /v1/process-instances` | when VanillaBP asks whether a workflow exists, for a push, for the viewer, under `message-start-lookup: scan`, and at the start for each older version | to find the workflow of an aggregate by its business key, page by page, and to count the running workflows of an older version from the total of a page of one |
| `GET /v1/process-instances/{key}` | once per instance a job names, when a workflow is looked up by the instance key its start row recorded, while the viewer reads, and at the end of a workflow or for an iteration | to read an instance's process, version, parent and business key, kept in a cache, and where its tokens stand |
| `PATCH /v1/process-instances/{key}/variables` | before a job is completed or failed with an error code, for a push, and at a start marker | to write the values the aggregate shares into the workflow |
| `GET /v1/process-instances/{key}/child-processes` | for a push, at the end of a workflow, for an iteration, and while the viewer reads | to find the instances which run below an instance |
| `GET /v1/process-instances/{key}/history` | for the total of an iteration, and while the viewer reads | to read what the instance did |
| `GET /v1/process-instances/{key}/incidents` | while the viewer reads | to name an incident nobody resolved |
| `GET /v1/jobs/{key}` | before a task left open is completed or cancelled, when VanillaBP asks whether a task exists or, after a delivery, whether the other tasks of the workflow are still open, for a push from a task, and after a BPMN error to see whether nothing caught it | to read a job by its key |
| `GET /v1/jobs` | every `user-task-poll-interval`, and for the iterations of a multi-instance task | to list the user tasks of a type, active and terminated, and the jobs of an instance |
| `POST /v1/jobs/{key}/complete` | when a handler returned, and when the application completes a task it left open | to complete the job |
| `POST /v1/jobs/{key}/fail` | when a handler threw, a BPMN error, and a job the adapter refuses at once | to spend an attempt, to route an error, or to raise an incident |
| `POST /v1/jobs/{key}/extend-lock` | while a handler runs, and for a task left open | to keep the job locked to this node |
| `POST /v1/messages` | in phase two of a correlation and of a start by a message | to publish the message |
<!-- /calls -->

## What the adapter never calls

Every other endpoint of the engine's REST API. A proxy may refuse them all:

<!-- never: DocumentationTest checks that every endpoint of the engine stands on this page -->
- `GET /v1/dmn-resource-definitions`, `GET /v1/dmn-resource-definitions/{key}`,
  `POST /v1/decision-definitions/{decisionId}/evaluate`, `GET /v1/decision-instances`,
  `GET /v1/decision-instances/{key}`: the engine evaluates a decision a business rule task names
  itself;
- `GET /v1/process-definitions/statistics`;
- `POST /v1/process-instances/{key}/cancel`: VanillaBP never cancels a workflow, and the adapter
  cannot tell a cancelled one to anybody ([GAPS 9](Deviations#gaps-9-a-cancelled-workflow-tells-nobody));
- `DELETE /v1/process-instances/{key}/variables/{variableName}`: a value the aggregate stops sharing
  stays in the workflow;
- `GET /v1/process-instances/{key}/jobs`, `GET /v1/process-instances/{key}/statistics`, and the
  event subscriptions `GET /v1/process-instances/{key}/event-subscriptions/messages`,
  `GET /v1/process-instances/{key}/event-subscriptions/timers` and
  `GET /v1/process-instances/{key}/event-subscriptions/errors`;
- `POST /v1/jobs/{key}/assign`, `POST /v1/jobs/{key}/retries` and `GET /v1/jobs/{key}/failures`:
  assignees and retries are for operators;
- `POST /v1/incidents/{key}/resolve`: an incident is resolved by an operator, after which the engine
  hands the job out again;
- `POST /v1/modify/start-process-instance` and `POST /v1/modify/process-instance`;
- `POST /v1/tests/{nodeId}/start-pprof-server` and `POST /v1/tests/{nodeId}/stop-pprof-server`;
- `GET /v1/openapi`, which the engine does not serve; only a test of the REST client asks for it
  (`ZenBpmRestClientContractIT#theEngineDoesNotServeItsOpenApiDocument`).

<!-- /never -->

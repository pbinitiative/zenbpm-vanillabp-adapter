# Draft `GAPS.md` and wiki `Deviations` page

`GAPS.md` is the contributor list: what the engine cannot do, evidence in the engine's source, and how
the adapter behaves. The `Deviations` wiki page says the same in one sentence per gap for a user, with
an outlook where the engine-enablement epic has one. Both are seeded here so every story which meets a
gap cites the same number.

## GAPS.md (draft)

| # | Gap | Evidence (zenbpm/) | Adapter behaviour | Engine-enablement | ZenBPM dev discussion notes |
|---|---|---|---|---|---|
| 1 | An instance the engine starts itself (timer or message start) carries no business key, and none can be set afterwards | `openapi/api.yaml` `PublishMessageRequest` has no `businessKey` and the answer carries no instance key; `POST /process-instances` is the only place it is set, `PATCH /process-instances/{key}` does not exist; no variable filter (`internal/sql/queries/process_instance.sql`) | the start marker (decision 26) records the instance key in the application's start row, and the probe asks by that key first (decision 25). That works while the row lives: `vanillabp.delivery.workflow-start-retention` (default `P30D`, `0` keeps it for good) or `vanillabp.delivery.keep-workflow-start-while-aggregate-exists`; open-task records give the key as well. Not reached: an application without a delivery log, the moments between publishing a start message and its marker, a re-dispatched message start. `message-start-lookup: scan` stays an opt-in walk over the active instances for message-started workflows until E13.4 lands (built by S7.1.2, 2026-10-19: `start-row` is the default, `refuse` refuses every start by a message, and `scan` finds a running workflow by its id variable, also before a start is sent again) | E13.4 (medium): `PATCH /v1/process-instances/{key}` accepting `businessKey` first; alternatively `businessKey` on `POST /v1/messages`, answering with the started instance's key |  |
| 2 | ~~The job lock is 30 s and cannot be extended~~ **Closed by E13.1** (engine commit `071460cc`, 2026-09-23). What remains: the lock lives in the partition leader's memory, so a leader change redelivers every open job at once | `internal/cluster/jobmanager/server.go` (in-memory `distributedJobs`) | subscription lock = `job-timeout`, renewed at half while a handler runs; open `@TaskId` tasks parked by `async-task-lock-renewal`; a second delivery after a leader change is answered from the record where the first committed, and named by the core where it did not | E13.1 done; persisting locks is a possible follow-up | |
| 3 | ~~At most ten active jobs per client id, across all job types~~ **Closed by E13.1**: `max_active_jobs` per subscription, counted per job type | `jobmanager/server.go` | every type subscribed with `max_active_jobs` = the handler slots | E13.1 done | |
| 4 | A message with an unmatched correlation key falls back to a start subscription of the same name | `internal/cluster/node.go` `publishCorrelatedMessageByName` (fallback with warning) | a message name used by a start event AND a catch element in one module is refused while deploying; correlating a message which is only a start message fails in phase one | E13.8 a strict mode on publish | |
| 5 | A message nobody waits for is refused (404) and lost | `internal/cluster/node.go` `publishCorrelatedMessageByName`, `publishDefinitionMessageByName` (entered in `GAPS.md` by S7.1.1 with the evidence at the pin) | phase two answers `PhaseTwoRetryLater(workflow-visibility-timeout)`; the outbox is the buffer, bounded by `vanillabp.outbox.wait-for-visibility-at-most` (about four hours by default); a retry-later counts no attempt | E13.8 buffering with a TTL | |
| 6 | ~~No retries: a failed job is an incident~~ **Closed by E13.3** (engine commit `bc3384b3`, 2026-10-12, contained in the pin `bdd795a3`): `fail` takes `retries`, `retryBackoff`, `message` and `deliveryToken` | `pkg/bpmn/engine.go` TODO, `jobs_api.go` `JobFailByKey` (before E13.3) | a thrown handler is failed without an error code and without `retries`, with the configured `retry-backoff` where a level sets one, a message naming task, aggregate and exception, the delivery token and `clientId`; the number of attempts is the model's or the engine's `JOBS_DEFAULT_RETRIES` (decisions 27 and 38). No local retry and no `max-redeliveries` | E13.3 done; adapter twin built (S6.2.1, 2026-10-18) | |
| 7 | Job outputs reach the process only through output mappings | `pkg/bpmn/runtime/varholder.go:107`, `jobs_api.go:285` | `PATCH variables` before `complete` (decision 7) | E13.2 propagate-all where a task has no mappings | |
| 8 | No execution or task listeners, no event stream | no `executionListener`/`taskListener` in `pkg/`, `internal/`, `openapi/` | marker service tasks after every start event of the top-level process and before every none, message and terminate end event (decisions 26 and 52); models deployed before the markers report nothing, and neither does a workflow which ends in an event sub-process | E13.6 listener jobs or a job-like end event | |
| 9 | A cancelled instance tells nobody | `pkg/bpmn/engine_api.go` `CancelInstanceByKey`: jobs `terminated`, no notification | an instance cancelled at the engine runs no marker, so `@WorkflowEnded` never reports `CANCELED` and its service tasks hear nothing; user tasks learn it by polling. A task taken away while its workflow goes on (an interrupting boundary or event sub-process) is reported `CANCELED` by the core at the next delivery of the same workflow, through the open-task probe (S6.1.3); a workflow which ends normally gets its open tasks cancelled before `@WorkflowEnded` | E13.6 | |
| 10 | No user-task lifecycle events | user task = job of type `user-task-type`; no forms, candidates not stored | notifications by polling (decision 12), `PT5S` default | E13.5 | |
| 11 | No tenant or namespace | no such field anywhere | `by-adapter` stays VanillaBP's default and is refused while deploying; every application configures `use-prefix` or `none`, one line per adapter id (decision 24) | none planned | |
| 12 | No authentication and no TLS on the public ports | `internal/rest/server.go` middleware chain; `grpc.NewServer()` | no `auth.*` keys; the wiki names a proxy | E13.9 | |
| 13 | No signals | `pkg/bpmn/model/bpmn20/events.go` `TUnsupportedEventDefinition` | `SEND_SIGNAL` not in the handler map; a model with a signal event is refused before deployment, a signal start or end event as well, which the engine would run as a plain one | none planned | [Implement only manual tasks maybe?] |
| 14 | Constructs the engine refuses or runs differently from the model: conditional, escalation, compensation and cancel events; script, manual and plain tasks, complex gateways, ad-hoc sub-processes, transactions; standard loops, completion conditions, loop cardinalities; conditions outside exclusive and inclusive gateways or without `=`; values Camunda 8 evaluates as FEEL (timers, message names, called processes, job types, decision ids, candidate groups) | `pkg/bpmn/unsupported_elements_test.go`; `helper.go` `ResolveReferences`; start and end event definitions dropped in `events.go`; `conditions.go`; `timer.go` | refused before deployment with element ids, in every process of the file | none | |
| 15 | A job carries no process id, version, business key or element instance key on the stream | `WaitingJob` in `zenbpm.proto` | one `GET /process-instances/{key}` per instance key, cached; activation id = job key until the stream carries the element instance key | E13.6 richer `WaitingJob` | [Think about it] |
| 16 | Multi-instance iterations report no index and no total | `pkg/bpmn/multi_instance.go`: index from history, no `nrOfInstances` | element from the child instance's variables; index and total from the parent's child list, cached | E13.7 `loopCounter` and `nrOfInstances` variables | [Check with a team] |
| 17 | No idempotent start | no request id; business key not unique | redispatch probe by business key, counting only instances created at or after the planned moment (`createdFrom`); a re-dispatched message start is found only where its start marker already ran, or under `message-start-lookup: scan` where its workflow runs or was created from the planned moment; start messages of one name at the same moment lose starts unseen across nodes (GAPS 30), so each node publishes them one after the other (decision 47); the at-least-once residual of the outbox remains | E13.4 unique business key option | [Think about it] |
| 18 | One process per file | `pkg/bpmn/model/bpmn20/core.go` single `Process`; `isExecutable` read nowhere | a file holding an executable process and any other process is refused naming every process, because the engine would read them as one | none | |
| 19 | An instantiating receive task starts an instance nobody built an aggregate for | `pkg/bpmn/receive_task_instance_creation.go` | refused before deployment in a claimed process | E13.6 | [Low priority] |
| 20 | Follower reads may lag the leader | `internal/cluster/partition/partition_persistence.go` `ConsistencyLevel_NONE` | `workflow-visibility-timeout` `PT2S` | none needed | [For Alisher for later consideration] |
| 21 | No API stability promise | 1.8 changed the deploy content type | one pinned engine version per release (decision 15) | contract tests against the pinned image | |
| 22 | A job carries no root process instance key | `WaitingJob` in `zenbpm.proto`, the job resource of `openapi/api.yaml` | the workflow id is the root instance key, found by walking `parentProcessInstanceKey` up to a call activity and cached (decision 28) | E13.14 `rootProcessInstanceKey` on the job | |
| 26 | A start or a variable update whose variables are larger than 4 MiB is answered `500`, although REST takes 10 MiB | `ZenNode.CreateInstance` and `ZenNode.ModifyProcessInstance` in `internal/cluster/node.go`; gRPC's default limit kept by `Server.Open` (`internal/cluster/server/server.go`) and `ClientManager.newClient` (`internal/cluster/client/client_manager.go`); `HttpServer.MaxRequestBodyBytes` (`internal/config/config.go`) | not measured by the adapter (S5.1.1); the `500` is repeatable, so the outbox repeats the start until it blocks the entry | E13.13: `413`, or a gRPC limit as large as the REST one | |
| 27 | A business rule task hands its decision's result to the process only through an output mapping; `resultVariable` alone sets nothing in the process | `PropagateOnlyMappedOutputs` in `pkg/bpmn/runtime/varholder.go`, called by the business rule task of `pkg/bpmn/engine.go`; `test/e2e/business_rule_variables_test.go` | not detected while deploying; the model maps the result variable (S5.3.1) | propagate the result variable where the task has no output mapping; the mechanism of GAPS 7, whose E13.2 names job completion only and which the maintainers lean against (open question 4) | |

## Deviations (wiki page draft)

Where this adapter does not deliver what VanillaBP's platform-wide contract promises. Everything not
listed works as the VanillaBP wiki describes it; the two-phase start and the at-least-once dispatch are
what a remote BPMS looks like.

1. **A workflow the engine starts itself (by a timer or a message) is found again only through the
   instance key recorded at its start**, for as long as the application keeps that row
   (`vanillabp.delivery.workflow-start-retention`, 30 days by default, `0` for good). For a
   message-started workflow, `message-start-lookup: scan` adds a walk over the active instances.
   Outlook: a business key the adapter can set afterwards (GAPS 1).
2. **A change of the engine's partition leader hands every open job out again**, a job whose handler is
   still running included, because the engine keeps job locks in the leader's memory. VanillaBP names
   both deliveries in the log, and a delivery arriving after the first one committed is answered from
   the record. Handlers may run as long as they need otherwise: the adapter renews their lock
   (`job-timeout`, GAPS 2).
3. **`sendSignal` is not supported**; the engine has no signals (GAPS 13).
4. **A message may reach the engine before its subscription** and is then refused; VanillaBP repeats it
   for the visibility window until `vanillabp.outbox.wait-for-visibility-at-most`, about four hours
   by default (GAPS 5).
5. **A message name may not be both a start event and a catch event in one workflow module** (GAPS 4).
6. **A technical failure of a handler counts the engine's retries down** (E13.3, decision 27). A
   task whose model sets no `retries` gets the engine's default, which is one attempt: its first
   failure raises an incident. Set `retries` on the task definition, or `JOBS_DEFAULT_RETRIES` at
   the engine (decision 38).
7. **`@TaskEvent CANCELED` reaches a service task only at the next delivery of the same
   workflow, or when the workflow ends at an end event**, and not at all when the workflow is
   cancelled at the engine; `@WorkflowEnded` reports no `CANCELED` workflow (GAPS 8, 9).
8. **User-task notifications arrive with a delay** of up to `user-task-poll-interval` (GAPS 10).
9. **Only `use-prefix` and `none` keep workflow modules apart**; the engine has no tenant.
   `by-adapter` stays VanillaBP's default and is refused while deploying, so every application
   states one of the two, one line per adapter id (GAPS 11).
10. **The adapter sends no credentials**; put the engine behind an authenticating proxy (GAPS 12).
11. **Multi-instance index and total cost a lookup** per parent instance on the first delivery (GAPS 16).
12. **Workflows started before the adapter deployed its markers** get no `@WorkflowEnded` and record
    no instance key at their start, and a process a workflow module declares without a model (a
    renamed process) serves only the markers a model the engine holds under its id carries (GAPS 8).
    A workflow which ends in an interrupting event sub-process, or in an error event sub-process
    which caught an error end event, gets no `@WorkflowEnded` either, and an error end event which
    nothing catches raises an incident instead of ending the workflow, as on Camunda 8 (decision
    52). A message end event's own task runs after `@WorkflowEnded`, and two branches which end at
    the same moment may report the end twice.
13. **Conditional events, escalations, compensation, script tasks and signals** are refused while
    deploying, because the engine has none (GAPS 13, 14).
14. **An expression in the model which reads an attribute the aggregate does not share is not found
    while starting**; it fails at runtime as a FEEL error. Like Camunda 8, the adapter does not tell
    VanillaBP which aggregate paths its expressions read.
15. **A workflow whose shared values are larger than 4 MiB cannot be started**: the engine refuses
    the start with a technical failure, which VanillaBP repeats until the outbox blocks the entry
    (GAPS 26).
16. **A business rule task needs an output mapping for its result**: without one, the decision's
    result stays inside the task, where Camunda 8 hands it to the process under the task's
    `resultVariable`. Map it, for example `<zenbpm:output source="=rating" target="rating" />`
    (GAPS 27).
17. **A gateway inside a multi-instance body reads a value of the workflow only where the
    multi-instance element maps it in** (`zenbpm:input`): an iteration starts with its item and
    what the element maps in, and once it waited it reads nothing else. A push of the workflow
    writes every iteration which runs, so their gateways read the pushed value; an iteration which
    starts later reads it only through such a mapping (GAPS 31).

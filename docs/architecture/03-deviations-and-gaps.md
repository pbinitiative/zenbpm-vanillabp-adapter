# Draft `GAPS.md` and wiki `Deviations` page

`GAPS.md` is the contributor list: what the engine cannot do, evidence in the engine's source, and how
the adapter behaves. The `Deviations` wiki page says the same in one sentence per gap for a user, with
an outlook where the engine-enablement epic has one. Both are seeded here so every story which meets a
gap cites the same number.

## GAPS.md (draft)

| # | Gap | Evidence (zenbpm/) | Adapter behaviour | Engine-enablement | ZenBPM dev discussion notes |
|---|---|---|---|---|---|
| 1 | A message-started instance carries no business key and cannot be found afterwards | `openapi/api.yaml` `PublishMessageRequest` has no `businessKey`; `POST /process-instances` is the only place it is set; no variable filter (`internal/sql/queries/process_instance.sql`) | `startWorkflowByMessage` works, but the probe finds such a workflow only through the bounded client-side scan behind `message-start-lookup: scan` (default `refuse`, which fails the start with a guiding message) | E13.4 `businessKey` on `POST /v1/messages` or a variable filter | |
| 2 | ~~The job lock is 30 s and cannot be extended~~ **Closed by E13.1** (engine commit `071460cc`, 2026-09-23). What remains: the lock lives in the partition leader's memory, so a leader change redelivers every open job at once | `internal/cluster/jobmanager/server.go` (in-memory `distributedJobs`) | subscription lock = `job-timeout`, renewed at half while a handler runs; open `@TaskId` tasks parked by `async-task-lock-renewal`; a second delivery after a leader change is answered from the record where the first committed, and named by the core where it did not | E13.1 done; persisting locks is a possible follow-up | |
| 3 | ~~At most ten active jobs per client id, across all job types~~ **Closed by E13.1**: `max_active_jobs` per subscription, counted per job type | `jobmanager/server.go` | every type subscribed with `max_active_jobs` = the handler slots | E13.1 done | |
| 4 | A message with an unmatched correlation key falls back to a start subscription of the same name | `internal/cluster/node.go` `publishCorrelatedMessageByName` (fallback with warning) | a message name used by a start event AND a catch element in one module is refused while deploying; correlating a message which is only a start message fails in phase one | E13.8 a strict mode on publish | |
| 5 | A message nobody waits for is refused (404) and lost | `node.go:844,867` | phase two answers `PhaseTwoRetryLater(workflow-visibility-timeout)`; the outbox is the buffer, bounded by `vanillabp.outbox.block-after-attempts` | E13.8 buffering with a TTL | |
| 6 | No retries: a failed job is an incident | `pkg/bpmn/engine.go` TODO, `jobs_api.go` `JobFailByKey` | a thrown handler is not reported; local backoff, `max-redeliveries`, then `fail` | E13.3 retries on the task definition | |
| 7 | Job outputs reach the process only through output mappings | `pkg/bpmn/runtime/varholder.go:107`, `jobs_api.go:285` | `PATCH variables` before `complete` (decision 7) | E13.2 propagate-all where a task has no mappings | |
| 8 | No execution or task listeners, no event stream | no `executionListener`/`taskListener` in `pkg/`, `internal/`, `openapi/` | marker service tasks for `@WorkflowEnded` and timer starts (decision 5); models deployed before the markers report nothing | E13.6 listener jobs or a job-like end event | |
| 9 | A cancelled instance tells nobody | `pkg/bpmn/engine_api.go` `CancelInstanceByKey`: jobs `terminated`, no notification | `@TaskEvent CANCELED` never arrives for service tasks; `@WorkflowEnded` never reports `TERMINATED`; user tasks learn it by polling | E13.6 | |
| 10 | No user-task lifecycle events | user task = job of type `user-task-type`; no forms, candidates not stored | notifications by polling (decision 12), `PT5S` default | E13.5 | |
| 11 | No tenant or namespace | no such field anywhere | `by-adapter` refused, `use-prefix` is the default (decision 2) | none planned | |
| 12 | No authentication and no TLS on the public ports | `internal/rest/server.go` middleware chain; `grpc.NewServer()` | no `auth.*` keys; the wiki names a proxy | E13.9 | |
| 13 | No signals | `pkg/bpmn/model/bpmn20/events.go` `TUnsupportedEventDefinition` | `SEND_SIGNAL` not in the handler map; a model with a signal event is refused before deployment | none planned | [Implement only manual tasks maybe?] |
| 14 | No conditional, escalation or compensation events, no script or manual tasks, no complex gateway | `pkg/bpmn/unsupported_elements_test.go`, docs matrix | refused before deployment with element ids | none | |
| 15 | A job carries no process id, version, business key or element instance key on the stream | `WaitingJob` in `zenbpm.proto` | one `GET /process-instances/{key}` per instance key, cached; activation id = job key until the stream carries the element instance key | E13.6 richer `WaitingJob` | [Think about it] |
| 16 | Multi-instance iterations report no index and no total | `pkg/bpmn/multi_instance.go`: index from history, no `nrOfInstances` | element from the child instance's variables; index and total from the parent's child list, cached | E13.7 `loopCounter` and `nrOfInstances` variables | [Check with a team] |
| 17 | No idempotent start | no request id; business key not unique | redispatch probe by business key; the at-least-once residual of the outbox remains | E13.4 unique business key option | [Think about it] |
| 18 | One executable process per file | `pkg/bpmn/model/bpmn20/core.go` single `Process` | a file with two executable processes is refused naming both | none | |
| 19 | An instantiating receive task starts an instance nobody built an aggregate for | `pkg/bpmn/receive_task_instance_creation.go` | refused before deployment | E13.6 | [Low priority] |
| 20 | Follower reads may lag the leader | `internal/cluster/partition/partition_persistence.go` `ConsistencyLevel_NONE` | `workflow-visibility-timeout` `PT2S` | none needed | [For Alisher for later consideration] |
| 21 | No API stability promise | 1.8 changed the deploy content type | one pinned engine version per release (decision 15) | contract tests against the pinned image | |

## Deviations (wiki page draft)

Where this adapter does not deliver what VanillaBP's platform-wide contract promises. Everything not
listed works as the VanillaBP wiki describes it; the two-phase start and the at-least-once dispatch are
what a remote BPMS looks like.

1. **A workflow started by message cannot be found again** unless `message-start-lookup: scan` is set,
   which walks the active instances of that process. Outlook: a business key on the publish command or
   a variable filter in the engine (GAPS 1).
2. **A change of the engine's partition leader hands every open job out again**, a job whose handler is
   still running included, because the engine keeps job locks in the leader's memory. VanillaBP names
   both deliveries in the log, and a delivery arriving after the first one committed is answered from
   the record. Handlers may run as long as they need otherwise: the adapter renews their lock
   (`job-timeout`, GAPS 2).
3. **`sendSignal` is not supported**; the engine has no signals (GAPS 13).
4. **A message may reach the engine before its subscription** and is then refused; VanillaBP repeats it
   for the visibility window until `vanillabp.outbox.block-after-attempts` (GAPS 5).
5. **A message name may not be both a start event and a catch event in one workflow module** (GAPS 4).
6. **A technical failure of a handler does not count retries down**: the engine has none, so the adapter
   backs off locally and files an incident after `max-redeliveries` (GAPS 6).
7. **`@TaskEvent CANCELED` is not delivered for service tasks**, and `@WorkflowEnded` reports no
   `TERMINATED` workflow (GAPS 8, 9).
8. **User-task notifications arrive with a delay** of up to `user-task-poll-interval` (GAPS 10).
9. **Only `use-prefix` and `none` keep workflow modules apart**; the engine has no tenant (GAPS 11).
10. **The adapter sends no credentials**; put the engine behind an authenticating proxy (GAPS 12).
11. **Multi-instance index and total cost a lookup** per parent instance on the first delivery (GAPS 16).
12. **Workflows started before the adapter deployed its markers** get no `@WorkflowEnded`, and a
    process a workflow module declares without a model (a renamed process) gets none either (GAPS 8).
13. **Conditional events, escalations, compensation, script tasks and signals** are refused while
    deploying, because the engine has none (GAPS 13, 14).

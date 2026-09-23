# Which existing adapter the ZenBPM adapter is built from

## The earlier statement, and why it is re-examined

`.claude/skills/vanillabp-bpms-characteristics/SKILL.md` says: "Decided: the ZenBPM adapter will be
built on the Process-Engine-API adapter (the first - and currently only - PEA-based BPMS adapter),
adding the BPMS-specific parts on top." No `DECISIONS.md` of any repository carries that sentence, and
the same skill hedges a few lines earlier ("a real BPMS-specific adapter built on top of the PEA adapter
would be slimmer but still needs BPMS-specific escape hatches"). It is a snapshot, not a numbered
decision, and it predates two facts established here:

1. **No Process-Engine-API implementation for ZenBPM exists**, neither from bpm-crafters nor from
   pbinitiative (workspace-wide search for `bpm-crafters`, `processengineapi`,
   `process-engine-api`). Building "on the PEA adapter" would therefore mean writing a ZenBPM
   implementation of seven PEA interfaces FIRST, and one which dispatches on the adapter's own
   command classes (`PeaStartProcessCommand`, `PeaCompleteTaskCmd`, ...) and meta conventions
   (`bpmnProcessId` in task meta) - a private protocol between two of our own modules, not a
   generic PEA implementation anybody else could use.
2. **The PEA adapter's shape is the shape of its 22 gaps.** It has no connection configuration, no
   startup validation of a connection, no client lifecycle, no health, no metrics, no error
   classification, no query-based workflow probe, no visibility delay, no engine-side viewer, no
   version catalog, no concurrent-token report, no BPMS-initiated start and no workflow-ended
   delivery, no aggregate push, no polling or streaming worker with lock handling. Every one of
   those is something ZenBPM CAN do through its REST API (`GET /v1/process-instances?businessKey=`,
   `.../history`, `.../incidents`, `GET /v1/process-definitions`, `PATCH .../variables`, `POST
   /v1/messages`, HTTP status codes), so a PEA layer in between would only remove information, and
   every feature would need an escape hatch around it.

The Camunda 8 adapter, by contrast, is a complete worked example of a **remote, at-least-once,
polling BPMS with a query API**, which is what ZenBPM is. Its decision log entries 1-9 and 13-20 are
statements about remote BPMS in general, and its class map has a counterpart for every row of the
mapping in `02-spi-requirements-mapping.md`.

## Decision taken for this plan

**Build `zenbpm-vanillabp-adapter` natively, on the Camunda 8 adapter as the structural template,
and borrow the engine-neutral raw-XML utilities from the Process-Engine-API adapter.** The ZenBPM
adapter depends on neither of them as an artifact; both are copied-and-adapted code, cited in the
README as the origin. This is written up as draft decision 1 in
`architecture/02-design-decisions.md` and as open question 8 for the maintainer, because it
contradicts the skill sentence, and the skills `vanillabp-bpms-characteristics` and
`vanillabp-adapter-building` have to be updated with the outcome.

## What comes from where

| Concern | Taken from | Adapted how |
|---|---|---|
| Repository layout, root POM (Spotless, JaCoCo, per-platform coverage reports, `coverage-gate`, `test-utils`), `flatten` versioning | `camunda8-adapter` | drop the `line-*` profiles and the per-line source folders; the engine version is a single pinned property |
| Spring Boot registration (`AdapterConfigurationBase` announcement, `BeanRegistrar` with `AdapterBeanRegistrarSupport.forEachConfiguredAdapterId`, overlay `@ConfigurationProperties("vanillabp")`) | `camunda8-adapter` / `camunda7-adapter` | keys renamed; C7's `applicationBean` helper shows how to fail guiding when an application bean is ambiguous |
| Quarkus extension (`IntegrationProcessor` build items, `@Singleton` list producers with literal `Object` type arguments, `StartupObserver` for eager validation, RUN_TIME `@ConfigMapping` overlay never injected) | `camunda8-adapter` (`camunda7-adapter` for the observer) | the client producer builds a `ZenBpmClientRegistry` |
| Per-id client factory and registry, eager construction, startup validation with the three outcomes (unconfigured -> WARN and boot, inconsistent -> fail unless nowhere-first with `warn`, complete -> client built), never echoing a credential | `Camunda8ClientFactory`, `Camunda8ClientFactoryRegistry`, `Camunda8StartupValidation`, `Camunda8AdapterConfiguration` | fewer keys; no auth block in release 1 |
| The start waits once for its engine (`startup-wait`) | `Camunda8ClusterWait` (decision 17) | asks `GET /system/health/ready` |
| Error classification read from codes, one class serving outbox and worker commands | `Camunda8Errors` (decision 16) | HTTP codes only; 404 is retry-later for messages and "gone" for jobs |
| Handler executor with bounded slots, two platform threads for timing, `worker-threads` and `virtual`, fetch only what a slot can run | `Camunda8Executor`, `Camunda8ExecutionModel`, `Camunda8VirtualThreadExecutor` (decisions 7, 18) | with E13.1 each job type is subscribed with `max_active_jobs` = slots and the adapter renews the lock of a queued job when its handler starts, which takes the place of Camunda 8's "poll only while a slot is free" |
| Job handler: new transaction, invoke, commit, then report; outcome mapping; delivery id; retries and backoff | `Camunda8JobHandler`, `Camunda8CommandRetry` (decision 9) | no retries at the engine, so the backoff is local and `fail` is the escalation |
| Drain on shutdown, never fail a job while shutting down | `Camunda8Drain` (decision 6) | closing the stream releases the adapter's locks, so the next node gets the jobs at once |
| Ownership by scope, never by key; probes compare the scoped process id of the CALL | `Camunda8DeployedProcesses`, decision 3 | instance lookup gives the `bpmnProcessId` directly |
| Model rewriting at deployment: scoped identifiers, injected correlation keys, injected listeners for lifecycle, injected multi-instance mappings; idempotent and never overwriting what the modeller wrote | decision 5 | listeners become marker service tasks; multi-instance mappings become instance-cache reads |
| Viewer from two sources (what this boot deployed, the engine for the rest) | `Camunda8WorkflowViewer` | REST endpoints replace the query API |
| Version catalog with cached versions and on-demand lookup, only ACTIVE definitions, counts instead of pages | `Camunda8ProcessVersions` (decisions 13, 15) | `GET /v1/process-definitions` has no state, so "deleted" does not arise |
| Workers for renamed processes composed from `taskWiringOfProcessesNobodyDeployed` | decision 19 | same |
| Message-declaration check in phase one instead of an engine preflight | `Camunda8MessageDeclarationTest` | plus the start-message collision check ZenBPM needs |
| Test shapes: `@Testcontainers(disabledWithoutDocker = true)`, `ClusterUnderTest`, one workflow module id per scenario, `@DirtiesContext` per real engine, Quarkus `QuarkusProdModeTest` with introspection endpoints | `camunda8-adapter` tests | image from a filtered `zenbpm-engine.properties` |
| DOM-based BPMN reading and rewriting without an engine model artifact (`PeaScoping`, `PeaStartEvents`, StAX process extraction, DOCTYPE hardening) | `process-engine-api-adapter` | extended by `calledDecision`, `calledElement@processId`, `subscription` injection, marker tasks, io mappings |
| `fetch-variables`-style four-level resolver and both overlays | `process-engine-api-adapter` (`PeaFetchVariables`) | reused as the pattern for every scoped property |
| `DmnDecisionIds` for the file, own rewrite of `calledDecision` | core + Camunda 8 | same |

## What is deliberately NOT taken

- Camunda 8's release lines, the API-identity script and the Renovate line preset: ZenBPM has no
  client the adapter compiles against, so no artifact pins a minimum engine version. Revisit only if
  an engine minor breaks the surface the adapter uses (open question 9).
- Camunda 8's `auth.*` block and SaaS mode: the engine has no authentication.
- Camunda 8's model type `io.camunda.zeebe.model.bpmn.BpmnModelInstance`: it would tie the adapter to
  a Camunda artifact. The model type is the adapter's own `ZenBpmBpmnModel` wrapping a `Document`.
- Camunda 8's GraalVM substitutions for compression libraries: the adapter uses `java.net.http`
  and `grpc-java`; what a native image needs is decided in its own story (E11).
- The PEA mock engine: tests run against the real engine in a container, which is cheap for ZenBPM
  (one process, single node, sub-second boot).

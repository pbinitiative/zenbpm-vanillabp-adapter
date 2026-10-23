# Roadmap: epics, milestones and the order of stories

## Epics

| Epic | Name | What is true when it is done | File |
|---|---|---|---|
| E1 | Repository and workspace foundation | the repository builds green with an empty core locally and in GitHub Actions at `pbinitiative/zenbpm-vanillabp-adapter`, publishes snapshots and coverage pages from `main`, is a workspace submodule, and can start the engine in a container from a test | [epic-01-foundation.md](epic-01-foundation.md) |
| E2 | Engine client | configuration, REST client, job stream, error classification, engine wait, health and executor exist in plain Java and are proven against the container | [epic-02-engine-client.md](epic-02-engine-client.md) |
| E3 | Platform registration | an application with the adapter on the classpath boots on Spring Boot and Quarkus, discovers one adapter per id, validates its configuration at startup and reports health - with every SPI method still a guiding stub | [epic-03-platform-registration.md](epic-03-platform-registration.md) |
| E4 | Deployment pipeline | BPMN and DMN of a workflow module are read, rewritten (scoped, correlation keys, task parameters), wired against the core and deployed; unsupported models are refused before the engine sees them | [epic-04-deployment-pipeline.md](epic-04-deployment-pipeline.md) |
| E5 | Workflow start and the election | a workflow starts through the two-phase outbox and every probe answers honestly and scoped, on both platforms | [epic-05-start-and-probes.md](epic-05-start-and-probes.md) |
| E6 | Task processing | `@WorkflowTask` methods run at least once, outcomes reach the engine, asynchronous tasks are completed and cancelled, tasks the engine no longer has are reported, failures are failed at the engine with retries and backoff and end in incidents, shutdown drains | [epic-06-task-processing.md](epic-06-task-processing.md) |
| E7 | Messages and the pushed aggregate | correlation, start by message and `aggregateChanged` work with the engine's message semantics | [epic-07-messages-and-push.md](epic-07-messages-and-push.md) |
| E8 | User tasks | CREATED and CANCELED notifications, completion and cancellation by BPMN error | [epic-08-user-tasks.md](epic-08-user-tasks.md) |
| E9 | Lifecycle markers and multi-instance | `@WorkflowEnded`, the report of every start of a workflow (a timer-started one and one started past VanillaBP get the aggregate the application builds) and the multi-instance annotations, all through model rewriting; its start half (S9.1.1, S9.3.1) runs before S7.1.2 | [epic-09-lifecycle-and-multi-instance.md](epic-09-lifecycle-and-multi-instance.md) |
| E10 | Versions and the viewer | version tags, the check for old versions, renamed processes, and the viewer/history API | [epic-10-versions-and-viewer.md](epic-10-versions-and-viewer.md) |
| E11 | Operations and hardening | metrics, the election with two ids and next to another BPMS, native image, coverage at the rule | [epic-11-operations-and-hardening.md](epic-11-operations-and-hardening.md) |
| E12 | Documentation and release | wiki, README, decision log, blueprints profile, Renovate preset, first publication | [epic-12-documentation-and-release.md](epic-12-documentation-and-release.md) |
| E13 | Engine enablement | the engine changes which turn nine `PARTIAL` rows into `SUPPORTED`, each with the adapter story consuming it | [epic-13-engine-enablement.md](epic-13-engine-enablement.md) |

## Milestones

| Milestone | Contains | Demo |
|---|---|---|
| M1 "It boots" | E1 (incl. a green pull-request check and published snapshots), E2, E3 | an application with `vanillabp.adapters.zenbpm.rest-address` boots on both platforms, reports health, and says what is not configured |
| M2 "It starts" | E4, E5 | a workflow module deploys, `startWorkflow` creates an instance after the commit, a rolled-back start creates nothing, the probe finds the workflow |
| M3 "It works" | E6 | a service task runs a `@WorkflowTask`, a gateway behind it sees the shared values, a BPMN error routes a boundary event, an asynchronous task is completed later, a redelivery is skipped |
| M4 "It talks" | E7, E8, and the start half of E9 (S9.1.1, S9.3.1) | messages correlate, a workflow started by message is found again, the aggregate is pushed, user tasks notify and complete |
| M5 "It is complete against the SPI" | the rest of E9, E10 | `@WorkflowEnded`, multi-instance, versions, viewer; every row of the mapping is SUPPORTED or documented |
| M6 "It ships" | E11, E12 | metrics, election ITs, nightly against the engine's newest `main` build (`:dev`), native image, coverage at the rule, wiki, the blueprints on ZenBPM through an overlay in the nightly, release workflow and first release; the first release is left to the maintainer (see S12.4.1), so M6 is reached except for it |
| E13 runs in parallel from M2 on; each engine change lands with its adapter twin | | |

## Dependency graph

```
E1 ─▶ E2 ─▶ E3 ─▶ E4 ─▶ E5 ─▶ E6 ─┬─▶ E7 ─┬─▶ E9 ─▶ E10 ─▶ E11 ─▶ E12
                                   └─▶ E8 ─┘
E13.x may start once E2 exists (they need the client to prove the engine change); each lands
together with its adapter twin story, which supersedes the fallback story of E6-E9.
E13.1 already landed in the engine (2026-09-23, commit 071460cc) before any adapter story ran, so
E2, E6 and E8 are written against it directly and have no fallback for the old 30-second lock.
E13.3 landed as well (2026-10-12, commit bc3384b3, inside the pin bdd795a3): S6.2.1 is written as
its twin, without a local retry.
The start half of E9 (S9.1.1 markers, S9.3.1 their handler) depends on E4 and S6.1.1 only and runs
before S7.1.2, because the start marker records the key of a workflow started by message.
```

Inside an epic the stories are numbered in execution order; a story names the stories it depends on
where the dependency is not simply the previous one.

## Global story order (the sequence a single team follows)

| # | Story | Epic | Increment proven by |
|---|---|---|---|
| 1 | S1.1.1 Repository skeleton | E1 | `mvn install` green on an empty core, coverage gate present |
| 2 | S1.1.2 Workspace membership, locally | E1 | wiki checked out, local `AGENTS.md` and skill drafts; nothing committed to the superproject, no `.gitmodules` entries |
| 3 | S1.3.1 Pull-request check | E1 | a green check on GitHub resolving `io.vanillabp:*` from VanillaBP's packages (from Maven Central's snapshot repository since 2026-10-09), Spotless red on a misformatted branch |
| 4 | S1.3.2 Snapshot publication and coverage pages | E1 | `2.0.0-SNAPSHOT` in pbinitiative's packages, both report pages and badges live |
| 5 | S1.2.1 Pinned engine contract and container helper | E1 | a test starts the engine image and reads `/system/health/ready` |
| 6 | S1.2.2 gRPC stubs from the pinned proto | E1 | stubs compile, Spotless ignores generated code |
| 7 | S2.1.1 Configuration model and validation | E2 | unit tests for the three outcomes and the identity |
| 8 | S2.2.1 REST client | E2 | contract IT against the container for every endpoint used |
| 9 | S2.2.2 Error classification | E2 | table test per status code |
| 10 | S2.3.1 Job stream client | E2 | IT receives a job and completes it over REST |
| 11 | S2.4.1 Client factory, registry, engine wait, health | E2 | IT waits for a late container; health DOWN/UP |
| 12 | S2.4.2 Executor and execution model | E2 | unit tests for slots, virtual mode, timing threads |
| 13 | S3.1.1 Spring Boot registration and smoke tests | E3 | discovery, two ids, three validation outcomes, health boot |
| 14 | S3.2.1 Quarkus extension and extension tests | E3 | discovery, overlay typo fails, validation outcomes |
| 15 | S3.3.1 Distinct instances | E3 | same address with the same mode ends the boot, two modes boot, both platforms |
| 16 | S4.1.1 Model reader | E4 | unit tests per element kind on fixture models |
| 17 | S4.1.2 Unsupported constructs refused | E4 | one refusal per construct with element ids |
| 18 | S4.2.1 Scoping and name-clash modes | E4 | rewritten XML deploys under prefixes; `by-adapter` refused |
| 19 | S4.2.2 Correlation keys and the start/catch collision | E4 | injected subscription; collision refused |
| 20 | S4.2.3 Task-parameter input mappings | E4 | a job carries the declared names |
| 21 | S4.3.1 Wiring calls | E4 | unwired task ends the boot; concurrent tokens reported |
| 22 | S4.3.2 Deploy resources and versions | E4 | Spring deployment IT, Quarkus pipeline test |
| 23 | S4.3.3 Start and stop processing skeleton | E4 | job types subscribed and unsubscribed per module |
| 24 | S5.1.1 START_WORKFLOW handler and shared values | E5 | instance only after commit, none after rollback |
| 25 | S5.2.1 Instance cache and workflow probes | E5 | the start row's instance key (it may only confirm an active workflow) and the business-key search, scope refusal |
| 26 | S5.2.2 Task probes, redispatch probe, visibility delay | E5 | unit tests with the fake server; IT for the task probe |
| 27 | S5.3.1 Exemplary E2E on both platforms (start) | E5 | Spring IT and Quarkus prod-mode test |
| 28 | S6.1.1 Dispatcher and job handler happy path | E6 | task matrix IT: service, send, business-rule job; gateway sees the values |
| 29 | S6.1.2 BPMN error and pending outcomes | E6 | boundary routed; open task stays open |
| 30 | S6.1.3 Report the tasks the engine no longer has | E6 | a task canceled by the model is reported `CANCELED` at the next delivery of its workflow |
| 31 | S6.2.1 Failures at the engine with retries and backoff (the E13.3 twin) | E6 | thrown handler is retried by the engine after the backoff, incident at zero retries |
| 32 | S6.2.2 Drain and shutdown | E6 | cut-off handler costs no failure |
| 33 | S6.3.1 Complete and cancel asynchronous tasks | E6 | pre-commit check, phase two, gone job tolerated |
| 34 | S6.4.1 Workers for renamed processes | E6 | composed job types opened, limits named |
| 35 | S6.5.1 Inbound idempotency and restart delivery, both platforms | E6 | redelivery skipped, survives restart |
| 36 | S7.1.1 CORRELATE_MESSAGE | E7 | model check, retry-later on 404, dedup by correlation id |
| 37 | S9.1.1 Marker task insertion | E9 | every top-level start event and end event marked; rewritten model deploys, idempotent, DI valid |
| 38 | S9.3.1 The start marker's handler | E9 | a timer start gets the aggregate the method built, its name written back, found through the start row; an own start builds nothing |
| 39 | S7.1.2 START_WORKFLOW_BY_MESSAGE with `message-start-lookup` | E7 | starts; found again through the start row its start marker wrote; `scan` an opt-in |
| 40 | S7.2.1 AGGREGATE_CHANGED global and task-scoped | E7 | conditional gateway after push; child scope |
| 41 | S7.3.1 Messages and push E2E on Quarkus | E7 | prod-mode twins |
| 42 | S8.1.1 User-task poller | E8 | CREATED and CANCELED arrive once |
| 43 | S8.2.1 Complete and cancel user tasks | E8 | boundary routed by cancel |
| 44 | S8.3.1 User tasks E2E on both platforms | E8 | |
| 45 | S9.2.1 Workflow-ended handler | E9 | `@WorkflowEnded` called with the end event id |
| 46 | S9.4.1 Multi-instance context | E9 | element, index, total bound |
| 47 | S9.5.1 Lifecycle E2E on both platforms | E9 | |
| 48 | S10.1.1 Version catalog and tags | E10 | tag ranges resolve |
| 49 | S10.2.1 Old versions, renamed process catalog, open task count | E10 | startup report names old versions |
| 50 | S10.3.1 Viewer and history | E10 | definitions, XML, history incl. call activity |
| 51 | S10.4.1 Versions and viewer E2E on both platforms | E10 | |
| 52 | S11.1.1 Metrics | E11 | meters where Micrometer is present |
| 53 | S11.2.1 Startup-message review | E11 | CapturedOutput asserts every guiding message |
| 54 | S11.3.1 Election ITs | E11 | two ids on one engine; zenbpm next to the double |
| 55 | S11.4.1 Native image | E11 | native build runs the lifecycle flow |
| 56 | S11.5.1 Coverage at the rule | E11 | both platforms above 90 |
| 57 | S11.6.1 Nightly workflow | E11 | pinned engine, engine `:dev`, native image, each with a summary line |
| 58 | S12.1.1 Wiki and README | E12 | pages exist, every claim names its test |
| 59 | S12.2.1 Blueprints profile `-Pzenbpm` (an overlay in this repository, built by the nightly) | E12 | the blueprints run on the pinned engine in the adapter's nightly |
| 60 | S12.3.1 Renovate, local workspace notes, VanillaBP wiki row | E12 | `renovate.json` reviewed and held by `RenovateConfigurationTest`; local notes re-read and their corrections written down; left to the maintainer: applying the local notes, asking VanillaBP for the `BPMS-adapters` wiki row (text in the story), the engine's `customManagers` entry with the re-pin |
| 61 | S12.4.1 Release workflow and first release | E12 | `release.yaml` built and proven by its dry run, which publishes nothing (decisions 65, 66); the first release is NOT made: left to the maintainer after VanillaBP's 2.0.0 and the engine re-pin, then a pushed `v2.0.0` builds, publishes to GitHub Packages and creates the release |

E13 stories are ordered by value in their own file and slot in wherever the engine work is done.

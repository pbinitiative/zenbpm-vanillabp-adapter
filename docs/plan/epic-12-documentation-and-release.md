# E12 - Documentation and release

**Goal.** A user finds everything on the wiki, a contributor everything in the README and the decision
log, the blueprints run on ZenBPM, Renovate keeps the engine pin honest, and `2.0.0` is published.

Rule of the `vanillabp-conventions` skill: every sentence promising behaviour names the test which
holds it or says it is an assumption and what would disprove it.

## S12.1.1 Wiki and README

- [ ] **Depends on:** E11.

Wiki (`zenbpm-adapter.wiki`): `Home` (getting started per platform, minimal configuration = one
`rest-address`, the outbox requirement, what a handler may assume about its thread with the 30-second
lock, the engine version tested), `Configuration` (every key of the architecture table with level and
default; the BPMN model for ZenBPM: `zenbpm:taskDefinition type` names the task definition, user tasks
by type, no correlation key has to be modelled, what reaches the engine; keeping workflow modules
apart: `use-prefix` default, `none`; timeouts and worker settings; boot behaviour; what an operator
gets to see), `Deviations` (from `architecture/03-deviations-and-gaps.md`, each entry linking the
README section), `Engine-APIs` (every REST endpoint and the stream the adapter uses, when and why,
what it never calls - the page to hand to whoever puts a proxy in front), `_Footer`.

README: status, supported engine version (tested, not newer), coordinates, configuration, behaviour
per section of the C8 README structure with ZenBPM's facts (deployment, two-phase start, the probes
and the two lookups, task processing with PATCH-then-complete, the lock and the slots, failures and
incidents, messages without buffering, user-task polling, markers, versions, viewer, testing), the
decision log pointer, known deviations with the `GAPS.md` numbers, building, test coverage.
`DECISIONS.md` completed with every entry of the draft the code cites; `GAPS.md` completed;
`UPGRADE.md` gets its first entry when something breaks.

**Acceptance criteria**: every claim names its test; a reviewer following the wiki alone boots an
application against a fresh engine container.

## S12.2.1 Blueprints profile `-Pzenbpm` (a pull request to the VanillaBP project)

- [ ] **Depends on:** S12.1.1 (a released or snapshot artifact a foreign build can resolve).

`blueprints` belongs to `vanillabp-blueprints`, so this story is a pull request there, prepared and
tested in the workspace checkout: a fourth profile next to `camunda7`, `camunda8`,
`process-engine-api` selecting `org.pbinitiative.zenbpmadapter:zenbpm-adapter-spring-boot` resp. the
Quarkus pair, `resources-location` `<module>/processes/zenbpm` where a model differs (most blueprint
models use `zeebe:` extensions which the engine reads; a model with a signal or a conditional event
needs a ZenBPM variant or is documented as not runnable there), a `bin/zenbpm_engine.sh` starting the
container for CI like `camunda8_cluster.sh`, and the CI matrix entry. `blueprints/README.md` and
`CONTRIBUTING.md` name the profile; `blueprints-organisation-page/AGENTS.md` if it lists BPMS. The
blueprints' CI has to read `pbinitiative`'s packages (or Maven Central once S12.4.1 publishes there),
which the pull request says.

**Acceptance criteria**: `./mvnw install -Pzenbpm` is green in the blueprints CI after the pull
request is merged; until then, green locally against the workspace checkout.

## S12.3.1 Renovate, workspace docs and the VanillaBP-side pages

- [ ] **Depends on:** S12.1.1.

`renovate.json` reviewed against the finished dependency tree (the engine pin's custom manager, the
VanillaBP platform version, the Spring Boot and Quarkus BOMs which have to move together with the
platform). Root `AGENTS.md` and `README.md` of the workspace updated with the final facts. Two pull
requests to the VanillaBP project: the two skills of S1.1.2 re-read against the finished adapter, and
a row for this adapter on the platform wiki's `BPMS-adapters` page linking `pbinitiative/zenbpm-adapter`.

## S12.4.1 Release workflow and the first release

- [ ] **Depends on:** S12.2.1, S12.3.1. **Open question 17** decides the target.

`.github/workflows/release.yaml`, `on: push: {tags: ['v*']}`: checks out the tag, sets `-Drevision`
from the tag (`v2.0.0` -> `2.0.0`), runs the full build with Docker ITs, `mvn deploy` to the release
target - GitHub Packages of `pbinitiative` at least, Maven Central under `org.pbinitiative` if the
organisation's namespace and signing key are available (the Java client is published there, so the
process exists) - and creates the GitHub release with the tag's notes. The wiki and README name
`2.0.0` and the engine version tested; the blueprints' `zenbpm-adapter.version` points at it. A
`UPGRADE.md` header says there is nothing to upgrade from.

**Acceptance criteria**: an application depending on
`org.pbinitiative.zenbpmadapter:zenbpm-adapter-spring-boot:2.0.0` boots against the pinned engine
image; the release workflow is the only path a release takes.

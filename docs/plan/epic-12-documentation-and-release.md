# E12 - Documentation and release

**Goal.** A user finds everything on the wiki, a contributor everything in the README and the decision
log, the blueprints run on ZenBPM, Renovate keeps the engine pin honest, and `2.0.0` is published.

Rule of the `vanillabp-conventions` skill: every sentence promising behaviour names the test which
holds it or says it is an assumption and what would disprove it.

## S12.1.1 Wiki and README

- [ ] **Depends on:** E11.

Wiki (`zenbpm-vanillabp-adapter.wiki`): `Home` (getting started per platform, minimal configuration
= one `rest-address`, the outbox requirement, what a handler may assume about its thread with the
job lock the adapter sizes by `job-timeout` and renews, and the leader-change residual, the engine version tested), `Configuration` (every key of the architecture table
with level and default; the BPMN model for ZenBPM: `zenbpm:taskDefinition type` names the task
definition, user tasks by type, no correlation key has to be modelled, what reaches the engine;
keeping workflow modules apart: `by-adapter` stays the default and is refused while deploying, so
every application chooses `use-prefix` or `none` - one line per adapter id, e.g.
`vanillabp.adapters.zenbpm.name-clash-avoidance: use-prefix` (decision 24); `Home` says so too,
because it is the first thing an application runs into; timeouts and worker settings; boot
behaviour; what an operator gets to see), `Deviations` (from
`architecture/03-deviations-and-gaps.md`, each entry linking the README section), `Engine-APIs`
(every REST endpoint and the stream the adapter uses, when and why, what it never calls - the page
to hand to whoever puts a proxy in front), `_Footer`.

README: status, supported engine version (tested, not newer), coordinates, configuration, behaviour
per section of the C8 README structure with ZenBPM's facts (deployment, two-phase start, the probes:
the instance key from the start row, which may only confirm an active workflow, then the
business-key search, decision 25; the start marker and what it costs, decision 26; task processing
with PATCH-then-complete, the lock and the slots, failures, retries and incidents, decision 27; the
workflow id, decision 28; messages without buffering, user-task polling, markers, versions, viewer,
testing), the decision log pointer, known deviations with the `GAPS.md` numbers (GAPS 1, 5, 6, 9,
11, 15 and 22 as the draft now reads them; deviation 14), building, test coverage.
`DECISIONS.md` completed with every entry of the draft the code cites; `GAPS.md` completed;
`UPGRADE.md` gets its first entry when something breaks.

*Note from S3.1.1, 2026-10-15.* The `Configuration` page says the difference between the platforms
for a misspelt key under `vanillabp.adapters.<id>.`: Spring Boot ignores it, so the adapter id
stays unconfigured and the start warns about the key it misses
(`ZenBpmStartupValidationBootTest#aMisspeltKeyIsIgnored`); Quarkus fails the start naming it
(S3.2.1's `ZenBpmUnknownKeyTest`), unless another extension of the application declares a
build-time configuration root under `vanillabp`, as the Camunda 8 adapter does: then Quarkus too
ignores the key, warning only that it does, and the start warns about the key the adapter misses
(found in the reviews of S3.2.1 with a scratch boot beside Camunda 8's extension). The page says
so, because an application moved from Camunda 8 to ZenBPM has both extensions while it migrates.
Until the page exists, the javadoc of `VanillaBpZenBpmProperties` says it.

*Note from S3.2.1, 2026-10-16.* Two more differences of the platform belong on the same page. On
Quarkus an application whose classpath holds the adapter but which configures adapter ids of
another type only does not start: the platform wants an id of every adapter extension on the
classpath and names `vanillabp.adapters.*.type=zenbpm` (`ZenBpmWithoutAdapterIdsTest`); Spring Boot
starts it, and the adapter has nothing. And on Quarkus every configured id has to be listed in
`vanillabp.prioritized-adapters`. An application which configures nothing starts on both platforms
with the derived id `zenbpm`.

**Acceptance criteria**: every claim names its test; a reviewer following the wiki alone boots an
application against a fresh engine container.

## S12.2.1 Blueprints profile `-Pzenbpm` (a pull request to the VanillaBP project)

- [ ] **Depends on:** S12.1.1 (a released or snapshot artifact a foreign build can resolve).

`blueprints` belongs to `vanillabp-blueprints`, so this story is a pull request there, prepared and
tested in the workspace checkout: a fourth profile next to `camunda7`, `camunda8`,
`process-engine-api` selecting `org.pbinitiative.zenbpm:zenbpm-vanillabp-adapter-spring-boot`
resp. the Quarkus pair, `resources-location` `<module>/processes/zenbpm` where a model differs (most
blueprint models use `zeebe:` extensions which the engine reads; a model with a signal or a
conditional event needs a ZenBPM variant or is documented as not runnable there), a
`bin/zenbpm_engine.sh` starting the container for CI like `camunda8_cluster.sh`, and the CI matrix
entry. `blueprints/README.md` and `CONTRIBUTING.md` name the profile;
`blueprints-organisation-page/AGENTS.md` if it lists BPMS. The blueprints' CI has to read
`pbinitiative`'s packages (or Maven Central once S12.4.1 publishes there), which the pull request
says.

**Acceptance criteria**: `./mvnw install -Pzenbpm` is green in the blueprints CI after the pull
request is merged; until then, green locally against the workspace checkout.

## S12.3.1 Renovate, local workspace notes and the VanillaBP wiki row

- [ ] **Depends on:** S12.1.1.

`renovate.json` reviewed against the finished dependency tree (the VanillaBP platform version, the
Spring Boot and Quarkus BOMs which have to move together with the platform). Once the engine pin is
a release tag (S12.4.1), a `customManagers` regex entry follows `pbinitiative/zenbpm`'s GitHub
releases (datasource `github-releases`) for the pin, and the pull request it opens is completed by
hand with the two contract copies of S1.2.1. The local workspace notes of S1.1.2 (root `AGENTS.md`,
the two skill edits) re-read against the finished adapter; they stay local, because the workspace
superproject belongs to the VanillaBP project and no pull request to it is planned. One request to
the VanillaBP project: a row for this adapter on the platform wiki's `BPMS-adapters` page linking
`pbinitiative/zenbpm-vanillabp-adapter`.

## S12.4.1 Release workflow and the first release

- [ ] **Depends on:** S12.2.1, S12.3.1. **Open question 17** decides the target.

Before the first release the engine pin leaves `main`: the maintainers tag an engine release
containing the pinned commit (or a newer one, re-pinned by the S1.2.1 steps), and the root POM gets
`zenbpm.version` = that tag with `zenbpm.image` = `ghcr.io/pbinitiative/zenbpm:${zenbpm.version}`;
`zenbpm.commit` stays as the commit the tag points at. An adapter release never names a `dev` digest
as its supported engine.

`.github/workflows/release.yaml`, `on: push: {tags: ['v*']}`: checks out the tag, sets `-Drevision`
from the tag (`v2.0.0` -> `2.0.0`), runs the full build with Docker ITs, `mvn deploy` to the release
target - GitHub Packages of `pbinitiative` at least, Maven Central under `org.pbinitiative` if the
organisation's namespace and signing key are available (the Java client is published there, so the
process exists) - and creates the GitHub release with the tag's notes. The wiki and README name
`2.0.0` and the engine version tested; the blueprints' `zenbpm-vanillabp-adapter.version` points at
it. A `UPGRADE.md` header says there is nothing to upgrade from.

**Acceptance criteria**: an application depending on
`org.pbinitiative.zenbpm:zenbpm-vanillabp-adapter-spring-boot:2.0.0` boots against the pinned
engine RELEASE image; the release workflow is the only path a release takes.

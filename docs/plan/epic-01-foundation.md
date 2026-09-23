# E1 - Repository and workspace foundation

**Goal.** A repository which builds green with an empty core - locally AND in GitHub Actions at
`pbinitiative/zenbpm-vanillabp-adapter` - is wired into the workspace superproject, carries the
documents every adapter carries, and can start the ZenBPM engine from a test.

**Why first.** Every later story adds tests which need the coverage gate, the Spotless rules, the
container helper and a green pull-request check; adding those later means touching every module
twice. And because the repository builds against `io.vanillabp:*` snapshots which live in ANOTHER
organisation's GitHub Packages, the CI's access to them is the first thing which can fail, so it is
proven before any Java exists.

**Done when.** `mvn install` is green in the new repo, the pull-request workflow is green on GitHub,
snapshots and coverage pages are published from `main`, the superproject's warmup builds it, and one
test has talked to a ZenBPM container.

The repository already exists (`https://github.com/pbinitiative/zenbpm-vanillabp-adapter`, default
branch `main`, `LICENSE` = MIT, a one-line `README.md`, a Java `.gitignore`) and is checked out in
the workspace; the plan lives in its `docs/` folder. Stories below start from that state.

Conventions for every story of this plan: Java 21, Spotless with the copied `formatting_conventions.xml`
(import order `java,javax,org,com,at.phactum`, one fluent call per line, `String#formatted`, text
blocks), every test class starts with `@ExtendWith(SuppressOutputExtension.class)`, names read like
sentences, no citation but `see decision N in the repository's DECISIONS.md`, and every README or
wiki sentence promising behaviour names the test which holds it.

---

## F1.1 Repository skeleton

### S1.1.1 Create the repository and its build

- [ ] **Depends on:** nothing.

**Instructions**

1. In the existing checkout: keep `LICENSE` (MIT); add `LICENSE-APACHE-2.0` (the Apache licence
   text) and a `NOTICE` naming `vanillabp/camunda8-adapter` and
   `vanillabp/process-engine-api-adapter` as the origin of adapted code (draft decision 17); copy
   from `camunda8-adapter`: `readme/` (replace the VanillaBP headline by a ZenBPM one or drop it),
   `formatting_conventions.xml`, the Maven parts of `.gitignore` (merge into the existing Java one,
   keep `/zenbpm-vanillabp-adapter.iml`), `test-coverage-report/` (three modules). Rename every
   `camunda8`/`Camunda8` occurrence; every copied Java file keeps its Apache header, every new file
   gets an MIT header. The workflows are F1.3, not copied here.
2. Root `pom.xml`: groupId `org.pbinitiative.zenbpmadapter`, artifactId
   `zenbpm-vanillabp-adapter-parent`, version `${revision}` with
   `<revision>2.0.0-SNAPSHOT</revision>`, `flatten-maven-plugin` (`resolveCiFriendliesOnly`),
   modules `core`, `spring-boot`, `smoke-test`, `quarkus/runtime`, `quarkus/deployment`,
   `quarkus/integration-tests`, `test-coverage-report`. Import the Spring Boot BOM and the Quarkus
   BOM in the versions the platform uses (read them from `adapter-platform-integration/pom.xml` at
   the time of the story), manage `spi-for-java`, `vanillabp-adapter-spi`,
   `vanillabp-spring-boot-integration`, `vanillabp-quarkus-integration`,
   `vanillabp-quarkus-integration-deployment`, `test-utils`, `testcontainers`,
   `testcontainers-junit-jupiter`, `grpc-*`, `protobuf-java`. Properties `zenbpm.version` (see
   S1.2.1), `coverage.threshold.spring-boot` = `coverage.threshold.quarkus` = 85, `coverage.rule` =
   90. No `line-*` profiles, no `build-helper` per-line sources.
3. Copy the Spotless, JaCoCo (`@{jacoco.agent}`, excludes `**/it/**`, `**/test/**`), surefire and
   failsafe configuration from the Camunda 8 parent; add `**/generated-sources/**` to the Spotless
   excludes (S1.2.2 puts stubs there).
4. `core/pom.xml`: artifactId `zenbpm-vanillabp-adapter`, dependencies `vanillabp-adapter-spi`,
   `spi-for-java`, `slf4j-api`, `jackson-databind` (provided by both platforms; declare it `compile`
   here, the platforms manage the version), `grpc-netty-shaded`, `grpc-protobuf`, `grpc-stub`,
   `protobuf-java`, `lombok` optional; resource filtering on for
   `META-INF/vanillabp/adapter-zenbpm.properties`:

   ```properties
   adapter.version=${project.version}
   platform.version=${adapter-platform.version}
   zenbpm.engine=${zenbpm.version}
   ```

5. `core/src/main/java/org/pbinitiative/zenbpmadapter/ZenBpmAdapter.java` with `ADAPTER_TYPE =
   "zenbpm"` and a javadoc saying what the type means (the id defaults to it).
6. Root documents: `AGENTS.md` (copy the Camunda 8 one, adjust names, add one paragraph: this
   repository is owned by the ZenBPM maintainers, follows the VanillaBP adapter conventions by
   decision 16, and the engine's Go conventions do not apply), `DECISIONS.md` with the entries 1, 3,
   13, 15, 16 and 17 of `architecture/02-design-decisions.md` (the ones this story already relies on),
   `GAPS.md` with the header and entries 11-14, 18 and 21 (the ones which need no code to be true),
   `UPGRADE.md` with a header only, `README.md` replacing the one-liner: Status "Skeleton", the module
   table, the build order (`spi-for-java` -> `adapter-platform-integration` -> this repository, with
   the note that the two upstream repositories are VanillaBP's and their snapshots come from GitHub
   Packages), the engine pin, a "Licence" section (MIT plus the Apache-2.0 attribution) and a "Test
   coverage" section copied and adjusted. The `docs/` folder stays as the plan; the README links it.
7. `renovate.json` of the repository's own (`config:recommended`, Maven and GitHub Actions managers,
   `dependencyDashboard`), plus a `customManagers` regex entry which updates `zenbpm.version` from the
   GitHub releases of `pbinitiative/zenbpm` (datasource `github-releases`, `extractVersion`
   `^v(?<version>.*)$` if the property is written without the `v`; decide one spelling and use it in
   the image tag as well). VanillaBP's shared preset is not used: it carries rules for VanillaBP's
   own release trains.

**Tests**

- `test-coverage-report/coverage-gate`: `CoverageGateTest` (with `@PrintsWhenPassing`) and
  `TestClassConventionsTest`, copied from Camunda 8. With no production code the gate has nothing to
  measure; make sure it reports "no classes" as green rather than dividing by zero (check how the
  Camunda 8 gate behaves on an empty report and adjust).

**Acceptance criteria**

- [ ] `mvn install` is green from a clean local repository which holds `spi-for-java` and
  `adapter-platform-integration` installed.
- [ ] `mvn spotless:check` passes; a deliberately misformatted file fails the build.
- [ ] Both coverage reports and the gate module exist and run.
- [ ] `META-INF/vanillabp/adapter-zenbpm.properties` in the built core jar carries resolved values.
- [ ] `DECISIONS.md`, `GAPS.md`, `AGENTS.md`, `README.md`, `UPGRADE.md`, `NOTICE`,
  `LICENSE-APACHE-2.0` exist with the content above.

### S1.1.2 Finish the workspace membership

- [ ] **Depends on:** S1.1.1 pushed to `main` (a recursive clone needs the commit on the remote).

**Instructions**

The superproject already lists `zenbpm-vanillabp-adapter` in `.gitmodules` (uncommitted) and the
checkout exists. Like `zenbpm`, the adapter is a LOCAL member of the workspace only: the
superproject's GitHub CI (`update-submodules.yml`) is not meant to see either of the two
pbinitiative repositories, and their directories stay ignored at the top level (root `AGENTS.md`
says so for `zenbpm`). What is missing, as of 2026-09-13:

1. `.gitmodules`: the entry says `branch = master` while the repository's default branch is `main`;
   change it to `main` (`git submodule set-branch -b main zenbpm-vanillabp-adapter`) or
   `git submodule update --remote` will fail. Add `zenbpm-vanillabp-adapter.wiki` (`master`, created
   empty on GitHub first) if the wiki is to be checked out like the other adapters' wikis. To keep
   the CI's `git submodule update --init --remote --recursive` from touching the two pbinitiative
   entries once `.gitmodules` is committed, give both `update = none`; a developer initialises them
   explicitly with `git submodule update --init --checkout zenbpm zenbpm-vanillabp-adapter`, which
   overrides the setting.
2. `.gitignore`: NO re-include for `/zenbpm-vanillabp-adapter/` (everything at top level is ignored
   by `/*`, and that is intended here, as for `/zenbpm/`). Only the `.gitmodules` entry travels.
3. Root `AGENTS.md`: the paragraph which names `zenbpm` as a deliberately not re-included, locally
   checked out submodule gains `zenbpm-vanillabp-adapter` (owned by pbinitiative, Java, follows the
   VanillaBP adapter conventions, builds with `cd zenbpm-vanillabp-adapter && mvn install` after the
   platform, ITs need Docker for `ghcr.io/pbinitiative/zenbpm`). Root `README.md`: no row in the
   submodule table, which lists what a recursive clone gets; one sentence under it names the two
   local-only members.
4. `dev-containers/devcontainers-config.json`: `repos` gains `zenbpm-vanillabp-adapter` (`main`) and
   `zenbpm-vanillabp-adapter.wiki` (`master`); `builds` gains
   `{ "repo": "zenbpm-vanillabp-adapter", "mvn-goal": "compile" }` after the platform. A missing
   source repository is skipped silently by the tooling, so a workspace without the local checkout
   is unaffected.
5. Skills: the skills live in the workspace superproject, which the VanillaBP project maintains.
   Draft the two changes and propose them (open question 8): in
   `.claude/skills/vanillabp-bpms-characteristics/SKILL.md` replace the "ZenBPM (future)" section
   and the cheat-sheet column with the facts of `analysis/01-zenbpm-capabilities.md` (remote, REST +
   gRPC stream, at-least-once, job lock per subscription and extendable (E13.1), no listeners, no signals, no tenant, `use-prefix`
   default, owned by pbinitiative) and replace "Decided: built on the PEA adapter" with decision 1's
   outcome; in `vanillabp-adapter-building` add `zenbpm-vanillabp-adapter` to the repository list
   with its organisation and groupId and mention the raw-XML model type as the third shape next to
   Camunda's model and PEA's bytes.
6. Commit the superproject (`chore: add zenbpm-vanillabp-adapter submodule`).

**Acceptance criteria**

- [ ] `git submodule update --init --checkout zenbpm-vanillabp-adapter` checks the repository out on
  `main` in a fresh workspace clone; a plain `--recurse-submodules` clone and the superproject's CI
  leave it alone.
- [ ] `git submodule update --remote --merge zenbpm-vanillabp-adapter` advances it locally.
- [ ] The devcontainer warmup builds it after the platform (verify by reading the config; a spawn is
  optional).
- [ ] Both skill changes are drafted and handed to the VanillaBP project; where they are accepted,
  the "built on PEA" sentence is gone or marked superseded.

---

## F1.3 GitHub workflows from the first commit

The repository lives at `pbinitiative`, builds against snapshots published by `vanillabp`, and tests
against an engine image published by `pbinitiative`. Three things therefore have to be proven before
the first Java class: the pull-request check can read VanillaBP's snapshots, it can run Docker, and
`main` publishes what a consumer and a badge read. The workflows are shaped further in E4 (Docker ITs
appear), E11 (nightly and native), E12 (release) and E13 (a trigger from the engine's CI).

### S1.3.1 Pull-request and main check

- [ ] **Depends on:** S1.1.1 (a POM to build).

**Instructions**

1. `.github/workflows/settings.xml` (Maven settings, committed): a `<server id="vanillabp-github">` with
   `${env.VANILLABP_PACKAGES_USER}` / `${env.VANILLABP_PACKAGES_TOKEN}` and a repository entry for
   `https://maven.pkg.github.com/vanillabp/*` (snapshots enabled), so `io.vanillabp:*:2.0.0-SNAPSHOT`
   resolves. GitHub Packages needs a token even for public packages: a classic PAT with
   `read:packages` of ANY GitHub account works (open question 16 asks the VanillaBP project whether a
   dedicated read-only account should be provided; until then a maintainer's own PAT is stored as the
   two repository secrets `VANILLABP_PACKAGES_USER` and `VANILLABP_PACKAGES_TOKEN`).
2. `.github/workflows/checks.yaml`, `on: [pull_request, push: {branches: [main]},
   workflow_dispatch]`, `concurrency` cancelling in-progress runs per ref, `permissions: contents:
   read`. One job `build` on `ubuntu-latest` (Docker is available there): `actions/checkout@v7`,
   `actions/setup-java@v6` (Temurin 21, `cache: maven`), `docker login ghcr.io` with the workflow's
   `GITHUB_TOKEN` (pulls of the public engine image stay under the rate limit), then `mvn -B -s
   .github/workflows/settings.xml --update-snapshots install` (this runs Spotless `check`, unit tests,
   the ITs once E4 adds them, and the coverage gate). On failure upload
   `**/target/surefire-reports`, `**/target/failsafe-reports` and `**/target/site/jacoco*` as an
   artifact (`actions/upload-artifact@v4`), the way `process-engine-api-adapter` does.
3. Branch protection on `main` requires the `build` check (documented in the README's "Contributing"
   section; the setting itself is done in the repository settings by a maintainer).
4. The README gets a build badge for `checks.yaml`.

**Acceptance criteria**

- [ ] A pull request with the S1.1.1 skeleton is green, and the log shows `io.vanillabp:*` resolved
  from `maven.pkg.github.com`.
- [ ] A deliberately misformatted file on a branch turns the check red at the Spotless step.
- [ ] The failure artifact is uploaded on a red run (prove it once with the misformatted branch plus a
  failing test).

### S1.3.2 Snapshot publication and coverage pages

- [ ] **Depends on:** S1.3.1.

**Instructions**

1. `.github/workflows/publish-snapshots.yaml`, `on: push: {branches: [main]}` and
   `workflow_dispatch`,
   `permissions: {contents: read, packages: write, pages: write, id-token: write}`: build as in
   S1.3.1, then `mvn -B -s .github/workflows/settings.xml deploy -DskipTests` to
   `https://maven.pkg.github.com/pbinitiative/zenbpm-vanillabp-adapter` (the
   `distributionManagement` of the root POM names it; the `GITHUB_TOKEN` authenticates through a
   second `<server id="github">` in the same settings file), then publish
   `test-coverage-report/spring-boot/target/site/jacoco-aggregate` and the Quarkus twin to GitHub
   Pages as `spring-boot-report/` and `quarkus-report/` (`actions/upload-pages-artifact` +
   `actions/deploy-pages`, Pages source "GitHub Actions").
2. The README gets the two coverage badges reading
   `https://pbinitiative.github.io/zenbpm-vanillabp-adapter/spring-boot-report/index.html` and
   `.../quarkus-report/index.html` with the regex of the Camunda 8 badges.
3. Consumers of the snapshot need the same kind of token for `pbinitiative`'s packages; the README's
   coordinates section says so and shows the `settings.xml` snippet.

**Acceptance criteria**

- [ ] After a push to `main`, `zenbpm-vanillabp-adapter-parent:2.0.0-SNAPSHOT` is listed under the
  repository's Packages and both report pages answer.
- [ ] The badges render on the README.

---

## F1.2 Engine test infrastructure

### S1.2.1 Pin the engine contract and start it from a test

- [ ] **Depends on:** S1.1.1.

**Instructions**

1. Decide the pin (open question 9): the first RELEASED tag carrying E13.1 (engine commit
   `071460cc`, 2026-09-23: lock per subscription, lock extension, `lock_until`), which the adapter
   requires (decision 15) - the tag after `v1.7.0`, which `VERSION` calls `v1.8.0` and which also
   brings the `application/octet-stream` deploy contract. Until it is tagged, build the image from
   `main` for local runs and keep this story's CI on a commit-pinned image; never pin `v1.7.0`. Write the pin ONCE as `zenbpm.version` in the root
   POM and derive `zenbpm.image` = `ghcr.io/pbinitiative/zenbpm:${zenbpm.version}`.
2. Copy `zenbpm/openapi/api.yaml` of that tag to `core/src/main/zenbpm/api.yaml` and
   `zenbpm/pkg/zenclient/proto/zenbpm.proto` to `core/src/main/proto/zenbpm.proto`, each with a
   header line naming the tag and the commit. These copies ARE the contract the adapter is written
   against (decision 3).
3. New module `engine-test-support` (artifact `zenbpm-vanillabp-adapter-engine-test-support`, listed
   in the coverage gate's exceptions as a test-only module) holding `EngineUnderTest`: a
   Testcontainers `GenericContainer` on the image, env `REST_API_ADDR=:8080`, `GRPC_API_ADDR=:9090`,
   `CLUSTER_RAFT_BOOTSTRAP_EXPECT=1`, `POLL_TIMER_DELAY_SECONDS=1`,
   `PERSISTENCE_INSTANCE_HISTORY_TTL=0`, exposed ports 8080 and 9090, wait strategy HTTP
   `/system/health/ready` = 200, and accessors `restAddress()`, `grpcAddress()`. Read the image from
   a filtered `zenbpm-engine.properties` (`engine.image=${zenbpm.image}`), never from a literal
   (Camunda 8's `camunda8-cluster.properties` pattern). Provide `EngineLog` which attaches a log
   consumer at DEBUG only, so a failing test can print the engine's log through
   `SuppressOutputExtension`.
4. A `logback-test.xml` template quieting `org.testcontainers`, `tc`, `com.github.dockerjava` at WARN,
   to be copied into every module with ITs.
5. One IT in `engine-test-support` itself: `EngineUnderTestIT` boots the container and asserts
   `/system/health/ready` answers 200 and `/system/status` names a version equal to the pin.
   `@Testcontainers(disabledWithoutDocker = true)`, `SuppressOutputExtension` FIRST.

**Acceptance criteria**

- [ ] `mvn install` with Docker runs the IT green; without Docker it is skipped, not failed.
- [ ] The image tag in the log of the IT equals `zenbpm.version`; no test file contains a version.
- [ ] `api.yaml` and `zenbpm.proto` are present with their provenance header.
- [ ] README section "Supported engine version" names the pin and says "tested, not newer".

### S1.2.2 Generate the gRPC stubs from the pinned proto

- [ ] **Depends on:** S1.2.1.

**Instructions**

1. In `core/pom.xml` add `io.github.ascopes:protobuf-maven-plugin` (or `org.xolstice`'s, whichever
   the Quarkus BOM plays with; verify a Quarkus build of `quarkus/runtime` still resolves the
   generated classes) generating Java and gRPC stubs from `src/main/proto/zenbpm.proto` into
   `target/generated-sources/protobuf`, package `org.pbinitiative.zenbpmadapter.client.grpc` (set
   `option java_package` in the copied proto; the header names this as the one deliberate edit).
2. Spotless excludes generated sources; JaCoCo excludes `io/vanillabp/zenbpm/client/grpc/**` (the
   report would otherwise count unused stub methods as missed).
3. A unit test `ZenBpmGrpcStubsTest` asserting the stub class `ZenBpmGrpc` has the `JobStream`
   method descriptor (proves the generation ran, and fails when the proto is replaced by one which
   renamed it).

**Acceptance criteria**

- [ ] `mvn install` compiles the stubs without a locally installed `protoc`.
- [ ] `mvn spotless:check` ignores the generated code.
- [ ] The coverage gate does not count stub classes.

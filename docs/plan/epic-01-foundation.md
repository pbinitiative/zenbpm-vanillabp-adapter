# E1 - Repository and workspace foundation

**Goal.** A repository which builds green with an empty core - locally AND in GitHub Actions at
`pbinitiative/zenbpm-vanillabp-adapter` - is part of the local workspace, carries the
documents every adapter carries, and can start the ZenBPM engine from a test.

**Why first.** Every later story adds tests which need the coverage gate, the Spotless rules, the
container helper and a green pull-request check; adding those later means touching every module
twice. And because the repository builds against `io.vanillabp:*` snapshots which live in ANOTHER
organisation's GitHub Packages, the CI's access to them is the first thing which can fail, so it is
proven before any Java exists.

**Done when.** `mvn install` is green in the new repo, the pull-request workflow is green on GitHub,
snapshots and coverage pages are published from `main`, the local workspace builds it after the
platform, and one test has talked to a ZenBPM container.

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
   from `camunda8-adapter`: `formatting_conventions.xml`, the Maven parts of `.gitignore` (merge
   into the existing Java one, keep `/zenbpm-vanillabp-adapter.iml`), `test-coverage-report/` (three
   modules). Rename every `camunda8`/`Camunda8` occurrence. The Camunda 8 Java files carry no
   header, so a copied Java file gets one naming its origin with `SPDX-License-Identifier:
   Apache-2.0`, and every new file gets the MIT header (`AGENTS.md` shows both). `readme/` is not
   copied: it holds the VanillaBP headline and the Phactum logo, and this README shows no images.
   The workflows are F1.3, not copied here.
2. Root `pom.xml`: groupId `org.pbinitiative.zenbpm` (the group of the engine's Java client; the
   root package stays `org.pbinitiative.zenbpmadapter`), artifactId
   `zenbpm-vanillabp-adapter-parent`, version `${revision}` with
   `<revision>2.0.0-SNAPSHOT</revision>`, `flatten-maven-plugin` in mode `oss` (the published POM
   stands on its own: no parent, every version written out, as `camunda8-adapter` does it),
   modules `core`, `spring-boot`, `smoke-test`, `quarkus/runtime`, `quarkus/deployment`,
   `quarkus/integration-tests`, `test-coverage-report`. Import the Spring Boot BOM and the Quarkus
   BOM in the versions the platform uses (read them from `adapter-platform-integration/pom.xml` at
   the time of the story), manage `spi-for-java`, `vanillabp-adapter-spi`,
   `vanillabp-spring-boot-integration`, `vanillabp-quarkus-integration`,
   `vanillabp-quarkus-integration-deployment`, `test-utils`, `testcontainers`,
   `testcontainers-junit-jupiter`, `grpc-*`, `protobuf-java`. Properties
   `coverage.threshold.spring-boot` = `coverage.threshold.quarkus` = 85, `coverage.rule` = 90; the
   engine pin (`zenbpm.commit`, `zenbpm.image`) is S1.2.1's, so this story needs no engine. No
   `line-*` profiles, no `build-helper` per-line sources.
3. Copy the Spotless, JaCoCo (`@{jacoco.agent}`, excludes `**/it/**`, `**/test/**`), surefire and
   failsafe configuration from the Camunda 8 parent; add `**/generated-sources/**` to the Spotless
   excludes (S1.2.2 puts stubs there).
4. `core/pom.xml`: artifactId `zenbpm-vanillabp-adapter`, dependencies `vanillabp-adapter-spi`,
   `spi-for-java`, `slf4j-api`, `jackson-databind` (provided by both platforms; declare it `compile`
   here, the platforms manage the version), `grpc-netty-shaded`, `grpc-protobuf`, `grpc-stub`,
   `protobuf-java`, `lombok` `provided` (Maven does not copy the optional flag out of a
   `dependencyManagement`, so `optional` would leave Lombok in the published POM); resource
   filtering on for
   `META-INF/vanillabp/adapter-zenbpm.properties`:

   ```properties
   adapter.version=${project.version}
   platform.version=${adapter-platform.version}
   ```

   S1.2.1 adds `zenbpm.engine=${zenbpm.commit}` together with the pin.

5. `core/src/main/java/org/pbinitiative/zenbpmadapter/ZenBpmAdapter.java` with `ADAPTER_TYPE =
   "zenbpm"` and a javadoc saying what the type means (the id defaults to it).
6. Root documents: `AGENTS.md` (copy the Camunda 8 one, adjust names, add one paragraph: this
   repository is owned by the ZenBPM maintainers, follows the VanillaBP adapter conventions by
   decision 16, and the engine's Go conventions do not apply), `DECISIONS.md` with the entries 1, 3,
   13, 15, 16 and 17 of `architecture/02-design-decisions.md` (the ones this story already relies
   on), `GAPS.md` with the header and entries 11-14, 18 and 21 (the ones which need no code to be
   true), `UPGRADE.md` with a header only, `README.md` replacing the one-liner: Status "Skeleton",
   the module table, the build order (`spi-for-java` -> `adapter-platform-integration` -> this
   repository, with the note that the two upstream repositories are VanillaBP's and their snapshots
   come from GitHub Packages), a "Supported engine version" heading saying "not pinned yet" (S1.2.1
   fills it), a "Licence" section (MIT plus the Apache-2.0 attribution) and a "Test coverage"
   section copied and adjusted. The `docs/` folder stays as the plan; the README links it.
7. `renovate.json` of the repository's own (`config:recommended`, Maven and GitHub Actions managers,
   `dependencyDashboard`). VanillaBP's shared preset is not used: it carries rules for VanillaBP's
   own release trains. The engine pin is NOT left to Renovate while it is a `main` commit: the pin,
   the image digest and the two contract copies move together in one hand-made commit (S1.2.1 says
   how). A `customManagers` entry for engine releases is added once the pin is a release tag
   (S12.3.1).

**Tests**

- `test-coverage-report/coverage-gate`: `CoverageGateTest` (with `@PrintsWhenPassing`) and
  `TestClassConventionsTest`, copied from Camunda 8. With no production code the gate has nothing to
  measure. The Camunda 8 gate reads an empty report as 0 % and would fail; here the two threshold
  tests print why and are reported as skipped, like a run which stops before `verify`, so an empty
  report neither fails the build nor reads as a checked one. `PublishedPomsTest` comes along too.

**Acceptance criteria**

- [x] `mvn install` is green from a clean local repository which holds `spi-for-java` and
  `adapter-platform-integration` installed.
- [x] `mvn spotless:check` passes; a deliberately misformatted file fails the build.
- [x] Both coverage reports and the gate module exist and run.
- [x] `META-INF/vanillabp/adapter-zenbpm.properties` in the built core jar carries resolved values.
- [x] `DECISIONS.md`, `GAPS.md`, `AGENTS.md`, `README.md`, `UPGRADE.md`, `NOTICE`,
  `LICENSE-APACHE-2.0` exist with the content above.

**Done 2026-10-05**, merged to `main` as `e403ac6` (pull request #2, together with S1.3.1 and the
answers to two reviews). Checked locally on JDK 25 and JDK 21 and in CI.

### S1.1.2 Finish the workspace membership, locally

- [ ] **Depends on:** S1.1.1 pushed to `main`.

**Instructions**

The workspace superproject is `vanillabp/development-workspace`, which the VanillaBP project owns.
Nothing about the two pbinitiative repositories is committed there, and no pull request to it is
planned: `zenbpm` and `zenbpm-vanillabp-adapter` are plain clones next to the submodules, with no
gitlink in the superproject's index, and their directories stay ignored by the top-level `/*` of
`.gitignore`. So `.gitmodules` entries for them do nothing for `git submodule`, the superproject's
CI (`update-submodules.yml`) never sees them, and a recursive clone of the workspace does not get
them. Everything below stays an uncommitted change of the local workspace, as the root `AGENTS.md`
and the two `zenbpm-*` skills already are.

1. Local `.gitmodules`: no entries for the pbinitiative repositories. Entries without a gitlink do
   nothing for `git submodule`, but as an uncommitted edit of a tracked file they would be picked up
   by `git commit -a` and would stop `git pull` whenever the superproject changes `.gitmodules`. The
   root `AGENTS.md` documents the local members instead. (Written first as "change both entries to
   `branch = main`"; dropped after the review of 2026-10-05.)
2. Wiki: clone `git@github.com:pbinitiative/zenbpm-vanillabp-adapter.wiki.git` next to the adapter
   as `zenbpm-vanillabp-adapter.wiki/` (the wiki exists since 2026-10-04 with a `Home` page). The
   top-level `/*` ignores it like the adapter.
3. Local root `AGENTS.md`: the paragraph which names `zenbpm` as a locally checked out member gains
   `zenbpm-vanillabp-adapter` and its wiki (owned by pbinitiative, Java, follows the VanillaBP
   adapter conventions, builds with `cd zenbpm-vanillabp-adapter && mvn install` after the platform,
   ITs need Docker for `ghcr.io/pbinitiative/zenbpm`). The root `README.md` stays untouched: its
   submodule table lists what a recursive clone gets, and neither pbinitiative repository is part of
   one.
4. Optional, for those who use the devcontainer warmup: in the local
   `dev-containers/devcontainers-config.json`, `repos` gains `zenbpm-vanillabp-adapter` (`main`) and
   `zenbpm-vanillabp-adapter.wiki` (`master`), and `builds` gains
   `{ "repo": "zenbpm-vanillabp-adapter", "mvn-goal": "compile" }` after the platform. A missing
   source repository is skipped silently by the tooling.
5. Skills (open question 8): draft the two changes as uncommitted edits in the workspace, where
   agents working there read them at once. In
   `.claude/skills/vanillabp-bpms-characteristics/SKILL.md` replace the "ZenBPM (future)" section
   and the cheat-sheet column with the facts of `analysis/01-zenbpm-capabilities.md` (remote, REST +
   gRPC stream, at-least-once, job lock per subscription and extendable (E13.1), no listeners, no
   signals, no tenant, `use-prefix` default, owned by pbinitiative) and replace "Decided: built on
   the PEA adapter" with decision 1's outcome; in `vanillabp-adapter-building` add
   `zenbpm-vanillabp-adapter` to the repository list with its organisation and groupId and mention
   the raw-XML model type as the third shape next to Camunda's model and PEA's bytes. The skills
   live in the superproject, so the edits stay local like the rest of this story.

**Acceptance criteria**

- [x] `git status` of the superproject shows no new tracked path and no gitlink for either
  pbinitiative repository; `git -C zenbpm-vanillabp-adapter pull` advances the adapter on `main`.
- [x] The local `.gitmodules` has no pbinitiative entries (`git diff .gitmodules` is empty).
- [x] `zenbpm-vanillabp-adapter.wiki/` is checked out and ignored by the superproject.
- [x] Where the devcontainer config was changed, it builds the adapter after the platform (verify by
  reading the config; a spawn is optional).
- [x] Both skill changes are drafted locally; the "built on PEA" sentence is gone or marked
  superseded in the local copy.

**Done 2026-10-05** in the maintainer's workspace, nothing committed to the superproject:
`.gitmodules` is the superproject's own again, without pbinitiative entries; the wiki (one `Home`
page) is cloned and ignored by the top-level `/*`; the root `AGENTS.md` (itself an ignored local
file) names the adapter and its wiki as local members; the devcontainer config was left unchanged
(optional step, not used). `.gitignore` stays as the superproject has it: the only local ignores,
the two `zenbpm-*` skills, are in `.git/info/exclude`, which git never commits. The repository's
default workflow token permission was set to `read` the same day (both workflows declare their own).
In the skills, `vanillabp-bpms-characteristics` has a ZenBPM section and cheat-sheet column with the
facts of `analysis/01-zenbpm-capabilities.md`, and `vanillabp-adapter-building` names the
repository, its organisation and groupId and the raw-XML model type. The "built on the PEA adapter"
sentence had already been replaced upstream by "native, like the Camunda adapters".

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

1. `.github/workflows/settings.xml` (Maven settings, committed), shaped like
   `camunda8-adapter/.github/workflows/github-packages-settings.xml`: an active profile with
   `central` plus two snapshot-only repository entries, `vanillabp-adapter-platform-integration`
   (`https://maven.pkg.github.com/vanillabp/adapter-platform-integration`: adapter SPI, both
   platform integrations, `test-utils`) and `vanillabp-spi-for-java`
   (`https://maven.pkg.github.com/vanillabp/spi-for-java`), and one `<server>` per entry with
   `${env.VANILLABP_PACKAGES_USER}` / `${env.VANILLABP_PACKAGES_TOKEN}`, so
   `io.vanillabp:*:2.0.0-SNAPSHOT` resolves. One entry per repository, not a `vanillabp/*` wildcard:
   that is the form the Camunda 8 CI proves every day. GitHub Packages needs a token even for public
   packages, and only a CLASSIC personal access token: fine-grained tokens are refused by the Maven
   registry. A classic token with nothing but `read:packages`, of ANY GitHub account, works (open
   question 16 asks the VanillaBP project whether a dedicated read-only account should be provided;
   until then a maintainer's own PAT is stored as the two repository secrets
   `VANILLABP_PACKAGES_USER` and `VANILLABP_PACKAGES_TOKEN`).
2. `.github/workflows/checks.yaml`, `on: [pull_request, push: {branches: [main]},
   workflow_dispatch]`, `concurrency` per ref cancelling in-progress runs on pull requests only (a
   commit on `main` keeps its result), `permissions: contents: read`. One job `build` on
   `ubuntu-latest` (Docker is available there) with `timeout-minutes: 45`: `actions/checkout@v7`
   with `persist-credentials: false`, `actions/setup-java@v6` (Temurin 25.0.4, `cache: maven`; the
   classes are compiled with `--release 21`, as in the VanillaBP repositories),
   `docker/login-action` for `ghcr.io` with the workflow's `GITHUB_TOKEN` (pulls of the public
   engine image stay under the rate limit), then `mvn -B -s .github/workflows/settings.xml
   --update-snapshots install` (this runs Spotless `check`, unit tests, the ITs once E4 adds them,
   and the coverage gate). The package token is handed to that step only. A pull request from a fork
   gets no secrets; there the job builds `spi-for-java` and `adapter-platform-integration` from
   source (`install -DskipTests`) and runs the build without the settings file. On failure upload
   `**/target/surefire-reports`, `**/target/failsafe-reports`, `**/target/site/jacoco*` and
   `test-coverage-report/*/report` as the artifact `test-reports` (`actions/upload-artifact@v7`,
   `if-no-files-found: warn`), the way `process-engine-api-adapter` does. The same job runs a second
   time on Temurin 21 (`build on Java 21`, not required), because compiling with `--release 21` does
   not prove that the dependencies run on 21. A second job `workflow-lint` runs `actionlint` over
   the workflows, which Spotless does not read.
3. Branch protection on `main` requires the `build` check (documented in the README's "Contributing"
   section; the setting itself is done in the repository settings by a maintainer).
4. The README gets a build badge for `checks.yaml`.

**Acceptance criteria**

- [x] A pull request with the S1.1.1 skeleton is green on a runner with an empty Maven cache,
  without building the VanillaBP snapshots from source. The log cannot show the downloads (the build
  runs with `--no-transfer-progress`), but no `io.vanillabp` `2.0.0-SNAPSHOT` exists on Maven
  Central, so such a run can only have read them from `maven.pkg.github.com` with the token.
- [x] The fork path (no package token) is green: proven by a pull request from a fork.
- [x] A deliberately misformatted file on a branch turns the check red at the Spotless step.
- [x] The failure artifact is uploaded on a red run (prove it once with the misformatted branch plus a
  failing test).

**Done 2026-10-05**, merged with S1.1.1. The evidence, one run per criterion (GitHub keeps run logs
for 90 days, so what each run showed is written here as well):

| Criterion | Run | What it showed |
|---|---|---|
| Green on an empty Maven cache with the token | [37218953892](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/actions/runs/37218953892) | `maven cache is not found`, no from-source step in that revision, `BUILD SUCCESS`: the snapshots came from GitHub Packages |
| Fork path | [37353630927](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/actions/runs/37353630927) | Pull request #4 from the fork `alisku/zenbpm-vanillabp-adapter`: the package token arrived empty, the from-source step ran (cloned and installed `spi-for-java` and `adapter-platform-integration`), `mvn install` without the settings file was green on Java 25 and 21, 3 min 43 s instead of about 1 min. The run started without a maintainer's approval |
| Red at Spotless | [37220646960](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/actions/runs/37220646960) | A misformatted `ZenBpmAdapter.java`: `spotless:check` failed on the core with "format violations"; no reports, so nothing uploaded |
| Reports uploaded on a red run | [37220753137](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/actions/runs/37220753137) | A deliberately failing test: the artifact `test-reports` (78 KB) holds its Surefire `.txt` and `.xml` |

The ruleset on `main` (created 2026-10-04) requires `build` and a pull request.

### S1.3.2 Snapshot publication and coverage pages

- [ ] **Depends on:** S1.3.1.

**Instructions**

1. `.github/workflows/publish-snapshots.yaml`, `on: push: {branches: [main]}` and
   `workflow_dispatch`, `permissions: contents: read` at the top, `concurrency` that does not cancel
   a running publish (a cancelled upload would leave the modules' metadata pointing at different
   builds; a run still waiting is replaced by a newer push, which is harmless; the group carries the
   ref, so a dispatch on another branch never replaces a waiting run of `main`). Job `publish` runs
   on `main` only, also when dispatched (`if: github.ref == 'refs/heads/main'`), with `packages:
   write`, `timeout-minutes: 45`, checkout without persisted credentials and the JDK of
   `checks.yaml`, but no ghcr.io login: in this job the token may write packages, and no test pulls
   the engine image yet (S1.2.1 adds the first one and decides on the login there). A step fails
   with a message naming the two secrets when either is missing, then ONE `mvn -s
   .github/workflows/settings.xml --update-snapshots deploy`. The root POM pins
   `maven-deploy-plugin` 3.1.4 with `deployAtEnd`, so nothing is uploaded unless the last module,
   the coverage gate, passed (instead of the plan's earlier `install` followed by `deploy
   -DskipTests`, which builds twice and still uploads per module). The target is
   `https://maven.pkg.github.com/pbinitiative/zenbpm-vanillabp-adapter` (the
   `distributionManagement` of the root POM), authenticated with the `GITHUB_TOKEN` through a second
   `<server id="github">` in the settings file; the VanillaBP secrets reach that one step only, the
   `GITHUB_TOKEN` otherwise only `actions/checkout`, which does not keep it. Then
   `test-coverage-report/spring-boot/report` and the Quarkus twin (the `outputDirectory` of both
   report POMs, not `target/site`) go to GitHub Pages as `spring-boot-report/` and `quarkus-report/`
   (`actions/upload-pages-artifact`, and `actions/deploy-pages` in a second job which alone gets
   `pages: write` and `id-token: write`; Pages source "GitHub Actions", the `github-pages`
   environment accepts `main` only).
2. The README gets the two coverage badges reading
   `https://pbinitiative.github.io/zenbpm-vanillabp-adapter/spring-boot-report/index.html` and
   `.../quarkus-report/index.html`. Not with the regex of the Camunda 8 badges: that one needs a
   number followed by `%` and finds nothing while a report holds no instruction (JaCoCo writes
   `n/a`). The badge reads the first column after `Total` instead, which is instruction coverage and
   is either `NN%` or `n/a`.
3. Consumers of the snapshot need the same kind of token for `pbinitiative`'s packages; the README's
   section "Using the snapshots" says so and shows the `settings.xml` snippet.

**Acceptance criteria**

- [x] After a push to `main`, `zenbpm-vanillabp-adapter-parent:2.0.0-SNAPSHOT` is listed under the
  repository's Packages and both report pages answer.
- [x] The badges render on the README.
- [x] A red coverage gate publishes nothing. Proven before the merge with
  `mvn deploy -DaltDeploymentRepository=github::file://...` and an untested method in the core: the
  build failed at the gate and the target directory stayed empty, while the green build deployed
  the parent, the core, `spring-boot`, `quarkus` and `quarkus-deployment` (jar, sources jar, POM)
  and nothing of the test, report and gate modules. Repeatable since: `bin/check-deploy-safety.sh`
  does both runs on a copy of the working tree (16 s), and `PublicationSafetyTest` fails every build
  in which `deployAtEnd` or the gate's place as the last module is gone. Both were seen to fail with
  `deployAtEnd` switched off: the script found the core's jar, sources jar and POM deployed by a
  red build.
- [x] The badge regex reads `94%` from the live Camunda 8 report through shields.io, and `0%` and
  `n/a` from a report of this repository with and without an instruction (checked before the
  merge). `PublicationSafetyTest` repeats the local half on every build, with each badge's
  expression taken from the README: `94%`, `0%` and `n/a` footers, and the report the build just
  wrote.

**Done 2026-10-06**, merged to `main` as `40eddd2` (pull request #5, after two reviews and
CodeRabbit). The first publication, run
[37420738285](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/actions/runs/37420738285)
of `Publish snapshots` on that commit (GitHub keeps run logs for 90 days, so what it showed is
written here as well):

| Criterion | What showed it |
|---|---|
| Snapshot published | Job `publish` green in 1 min 42 s; Maven logged "Deferring deploy ... at end" for exactly the parent, the core, `spring-boot`, `quarkus` and `quarkus-deployment`, the gate's two lines ("the report holds no instruction"), then `BUILD SUCCESS`. The repository's Packages page lists exactly those five packages (`org.pbinitiative.zenbpm.zenbpm-vanillabp-adapter`, `-parent`, `-spring-boot`, `-quarkus`, `-quarkus-deployment`) |
| Both report pages answer | Job `deploy-coverage-reports` green; `.../spring-boot-report/index.html` and `.../quarkus-report/index.html` under `https://pbinitiative.github.io/zenbpm-vanillabp-adapter/` answer HTTP 200 |
| The badges render | shields.io renders "Coverage Spring Boot: n/a" and "Coverage Quarkus: n/a", the value of a report without an instruction |

`Checks` on the same commit (run 37420738324) is green as well.

A consumer was simulated on 2026-10-06 as well: a throw-away project depending on
`zenbpm-vanillabp-adapter-spring-boot` and `zenbpm-vanillabp-adapter-quarkus` `2.0.0-SNAPSHOT`, an
empty local Maven repository, and the `settings.xml` of the README's section "Using the snapshots"
plus the two VanillaBP registries, with a token carrying `read:packages`. 113 dependencies resolved;
the adapter's three artifacts came from its own registry, the VanillaBP ones from VanillaBP's, and
no parent POM of the adapter was needed. The snippet had the repository's releases switched on,
which made Maven ask GitHub Packages for every release dependency first (1,317 refused lookups, six
minutes); with releases switched off, as the README now shows, the same resolve took one minute.

---

## F1.2 Engine test infrastructure

### S1.2.1 Pin the engine contract and start it from a test

- [ ] **Depends on:** S1.1.1.

**Instructions**

1. Take the pin (open question 9): the newest commit of the engine's `main` at the time this story
   runs, not a release tag. `v1.8.0` (2026-09-14) predates E13.1 (engine commit `071460cc`,
   2026-09-23: lock per subscription, lock extension, `lock_until`), which the adapter requires
   (decision 15), and E13.3 (job retries) is about to be merged to `main` as well; never pin
   `v1.8.0` or older. The engine's CI publishes every `main` commit as the MOVING tag
   `ghcr.io/pbinitiative/zenbpm:dev` (`release-dev.yaml`), so the pin is that image's digest, taken
   together with the commit it was built from:

   ```bash
   docker pull ghcr.io/pbinitiative/zenbpm:dev
   docker inspect --format '{{index .RepoDigests 0}}' ghcr.io/pbinitiative/zenbpm:dev
   docker inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' \
     ghcr.io/pbinitiative/zenbpm:dev
   ```

The revision label is the commit; check that it is the head of `origin/main` (a commit whose
`release-dev` run was cancelled by a newer push has no image, so take the newest one which has).
Write both ONCE in the root POM (S1.1.1 left them out) and add `zenbpm.engine=${zenbpm.commit}` to
`adapter-zenbpm.properties`: `zenbpm.commit` = the full 40-character commit and `zenbpm.image` =
`ghcr.io/pbinitiative/zenbpm@sha256:<digest>` (no tag in front of the `@`: Testcontainers'
`DockerImageName` refuses `name:tag@digest`). Moving the pin later repeats these steps and step 2 in
one commit. When the engine tags a release containing the pinned commit, the pin becomes that tag
(S12.4.1 does it at the latest).
2. Copy `openapi/api.yaml` and `pkg/zenclient/proto/zenbpm.proto` of exactly that commit
   (`git -C zenbpm show <commit>:openapi/api.yaml`, not the working tree) to
   `core/src/main/zenbpm/api.yaml` and `core/src/main/proto/zenbpm.proto`, each with a header line
   naming the commit, its date and the image digest. These copies ARE the contract the adapter is
   written against (decision 3).
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
5. The first test pulling `ghcr.io/pbinitiative/zenbpm` arrives with this story. `checks.yaml` logs
   in to ghcr.io already; `publish-snapshots.yaml` deliberately does not (its token may write
   packages, see S1.3.2). Decide in this story whether its anonymous pull is enough or whether it
   gets a login with a token which can only read.
6. One IT in `engine-test-support` itself: `EngineUnderTestIT` boots the container and asserts
   `/system/health/ready` answers 200 and that `git.commitId` of `/system/status` (the engine
   reports the commit shortened) is a prefix of `zenbpm.commit`. Not `build.version`: on `main` it
   names the last release's `VERSION` and cannot tell two `main` builds apart.
   `@Testcontainers(disabledWithoutDocker = true)`, `SuppressOutputExtension` FIRST.

**Acceptance criteria**

- [x] `mvn install` with Docker runs the IT green; without Docker it is skipped, not failed. Green:
  3 tests in about 5 s, alone and within the whole `mvn install`. Without Docker (the build run in a mount
  namespace with an empty `/run`, so `/var/run/docker.sock` does not exist): Testcontainers logs
  "Could not find a valid Docker environment", the IT reports 3 skipped, `BUILD SUCCESS`.
- [x] The image in the log of the IT is `zenbpm.image`, the commit the engine reports is
  `zenbpm.commit`; no test file contains a commit, digest or version. The IT asserts both instead
  of leaving them to a log: the image the container was created from equals `zenbpm.image` and is
  pinned by digest, and `git.commitId` of `/system/status` is a prefix of `zenbpm.commit`. Seen to
  fail: `-Dzenbpm.commit=0123...` ("The engine reports commit 'bdd795a399b6', the pin is
  '0123...'") and `-Dzenbpm.image=ghcr.io/pbinitiative/zenbpm:dev` ("is not pinned by its
  digest"); the replay of the failing class carried "Creating container for image:
  ghcr.io/pbinitiative/zenbpm@sha256:d7808e30..." and 110 lines of the engine's own log. No Java
  file holds a commit, a digest or a version.
- [x] `api.yaml` and `zenbpm.proto` are present with their provenance header, taken from
  `zenbpm.commit`. `api.yaml` below its header is byte for byte `git show <commit>:openapi/api.yaml`
  (`cmp`), the proto differs from `pkg/zenclient/proto/zenbpm.proto` by its header and the
  `java_package` line only (`diff`).
- [x] README section "Supported engine version" names the commit and the digest, says "tested, not
  newer", and says that the pin is a `main` build until the engine tags a release containing it.

**Implemented 2026-10-13.** The pin: engine `main` head `bdd795a399b6484d062d52ac90c0d0725cb0ad91`
(2026-10-13, after E13.3 was merged as `bc3384b3`), the `dev` image built from it
`sha256:d7808e30821a7349e0f42476d8aaa21ff92fc752c0ccc432771764d307b87ed0` (revision label =
that commit, created 2026-10-13T04:59:27Z). Booted by hand with the environment above, it answered
ready after six seconds and reported `"commitId": "bdd795a399b6"` (12 characters) and `"version":
"v1.8.0"`. Where the result differs from the instructions above:

- `PERSISTENCE_INSTANCE_HISTORY_TTL=0` is the engine's default and means NO time to live: the engine
  only sets a TTL where the value is not 0, so finished instances stay readable. It is kept, with a
  comment saying that, so that a new engine default does not change what a test finds.
- `EngineLog` hands every line of the container to the logger `zenbpm-engine` at DEBUG; the
  `logback-test.xml` template switches that logger on, so `SuppressOutputExtension` holds the lines
  back in a green run and replays them for a failing class.
- The template does NOT set `org.testcontainers`, `tc` and `com.github.dockerjava` to WARN. Root
  INFO does the quieting (docker-java's DEBUG wire log is the noise), as in `camunda8-adapter` and
  `adapter-platform-integration`, which both went from WARN to INFO: a record dropped by its level
  reaches no appender, so the replay of a failing class would lose which image was started. What a
  green build still shows is the dozen Testcontainers lines written before the first extension
  callback (Docker detection, Ryuk), which no extension reaches.
- `engine-test-support` is installed but not deployed (`maven.deploy.skip`): only this repository's
  tests use it. `bin/check-deploy-safety.sh` still finds exactly the 13 published files.
- Step 5: `publish-snapshots.yaml` stays without a ghcr.io login. The image is public; an anonymous
  pull of the pinned digest answered HTTP 200, and one run per push to `main` stays far below the
  anonymous limit, while a login there would leave a token which may write packages in
  `~/.docker/config.json`.

### S1.2.2 Generate the gRPC stubs from the pinned proto

- [ ] **Depends on:** S1.2.1.

**Instructions**

1. In `core/pom.xml` add `io.github.ascopes:protobuf-maven-plugin` (or `org.xolstice`'s, whichever
   the Quarkus BOM plays with; verify a Quarkus build of `quarkus/runtime` still resolves the
   generated classes) generating Java and gRPC stubs from `src/main/proto/zenbpm.proto` into
   `target/generated-sources/protobuf`, package `org.pbinitiative.zenbpmadapter.client.grpc` (set
   `option java_package` in the copied proto; the header names this as the one deliberate edit).
2. Spotless excludes generated sources; JaCoCo excludes
   `org/pbinitiative/zenbpmadapter/client/grpc/**` (the report would otherwise count unused stub
   methods as missed).
3. A unit test `ZenBpmGrpcStubsTest` asserting the stub class `ZenBpmGrpc` has the `JobStream`
   method descriptor (proves the generation ran, and fails when the proto is replaced by one which
   renamed it).

**Acceptance criteria**

- [x] `mvn install` compiles the stubs without a locally installed `protoc`. The plugin is
  `io.github.ascopes:protobuf-maven-plugin` 5.1.11, `protoc` 4.35.0 (= `protobuf.version`) and
  `protoc-gen-grpc-java` 1.81.0 (= `grpc.version`) come from Maven Central; the machine has no
  `protoc`. The generated code says "Protobuf Java Version: 4.35.0". Seen to fail: with `protoc`
  4.35.1 `ZenBpmGrpcStubsTest` fails with "gencode 4.35.1, runtime 4.35.0. Runtime version cannot be
  older than the linked gencode version"; with the RPC renamed in the proto the test no longer
  compiles.
- [x] `mvn spotless:check` ignores the generated code. Spotless reads `src/main/java` and
  `src/test/java` only and excludes `**/generated-sources/**`; the build ran it over the stubs'
  module green.
- [x] The coverage gate does not count stub classes. The full build's gate says "the report holds
  no instruction" for both platforms. Seen to fail: without the JaCoCo exclusion both reports count
  12,729 instructions, 0.55 % covered, and the gate breaks the build.

**Implemented 2026-10-13.** Beyond the instructions: the javadoc check of `core` excludes the stub
package (the generated comments broke its fail-on-warnings), and the plugin does not embed the
proto in the jar, which carries the 59 generated classes only. A Quarkus build of `quarkus/runtime`
resolves the generated classes: a throw-away class there calling `ZenBpmGrpc` and
`Zenbpm.JobStreamRequest` built green together with `quarkus/deployment` and
`quarkus/integration-tests`. Both platform modules resolve `protobuf-java` 4.35.0 and gRPC 1.81.0.

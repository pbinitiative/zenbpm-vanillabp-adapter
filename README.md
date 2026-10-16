# VanillaBP adapter for ZenBPM

[![Checks](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/actions/workflows/checks.yaml/badge.svg?branch=main)](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/actions/workflows/checks.yaml)
[![MIT License](https://img.shields.io/badge/License-MIT-blue.svg)](./LICENSE)
[![Coverage Spring Boot](https://img.shields.io/badge/dynamic/regex?url=https%3A%2F%2Fpbinitiative.github.io%2Fzenbpm-vanillabp-adapter%2Fspring-boot-report%2Findex.html&search=Total%3C%2Ftd%3E%3Ctd%20class%3D%22bar%22%3E%5B%5E%3C%5D%2A%3C%2Ftd%3E%3Ctd%20class%3D%22ctr2%22%3E%28%5B%5E%3C%5D%2B%29%3C&replace=%241&label=Coverage%20Spring%20Boot&color=green&cacheSeconds=60)](https://pbinitiative.github.io/zenbpm-vanillabp-adapter/spring-boot-report/index.html)
[![Coverage Quarkus](https://img.shields.io/badge/dynamic/regex?url=https%3A%2F%2Fpbinitiative.github.io%2Fzenbpm-vanillabp-adapter%2Fquarkus-report%2Findex.html&search=Total%3C%2Ftd%3E%3Ctd%20class%3D%22bar%22%3E%5B%5E%3C%5D%2A%3C%2Ftd%3E%3Ctd%20class%3D%22ctr2%22%3E%28%5B%5E%3C%5D%2B%29%3C&replace=%241&label=Coverage%20Quarkus&color=green&cacheSeconds=60)](https://pbinitiative.github.io/zenbpm-vanillabp-adapter/quarkus-report/index.html)

This is the [VanillaBP](https://www.vanillabp.io) adapter for the
[ZenBPM](https://github.com/pbinitiative/zenbpm) engine (VanillaBP Version 2). It lets a VanillaBP
business application run its workflows on a ZenBPM engine without the business code depending on
the ZenBPM API. It is owned by the ZenBPM maintainers at pbinitiative and follows the conventions
of the VanillaBP adapters.

This `README.md` is aimed at contributors. The user documentation is not written yet: it goes into
the [wiki](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/wiki) as the features land, and
until then the wiki holds a placeholder page only. The VanillaBP concepts the adapter builds on are
documented in the [VanillaBP wiki](https://github.com/vanillabp/adapter-platform-integration/wiki).

## Status

**Under construction.** On Spring Boot and on Quarkus the adapter registers one process service and
one deployment service per adapter id of type `zenbpm`, validates the configuration of each id
while the application starts and answers the platform's health check. The smoke tests in
`smoke-test` boot it on Spring Boot with no, one and two adapter ids, the extension tests in
`quarkus/deployment` on Quarkus with one and two, and with none configured at all, where the
platform derives the id `zenbpm`. An application on Quarkus which configures adapter ids of another
type only is refused by the platform, which asks for an id of every adapter type on the classpath.
Two adapter ids on one `rest-address` boot only where their `name-clash-avoidance` at the adapter
level differs, the setup of a migration from one mode to the other; with the same mode they end the
start, naming the keys which tell them apart. The address is compared as configured, so two
addresses of one engine (an alias, a proxy, two nodes of one cluster) are not recognised as one.
The adapter deploys nothing and runs no task yet: every step which needs the engine throws an
exception which says that it is not built yet. What is built next, and in which order, is the
implementation plan in [`docs/`](docs/README.md).

## Modules

|                Module                |                   Artifact                    |                                    Purpose                                    |
|--------------------------------------|-----------------------------------------------|-------------------------------------------------------------------------------|
| `engine-test-support`                | (not published)                               | the pinned ZenBPM engine as a Testcontainers container, for integration tests |
| `core`                               | `zenbpm-vanillabp-adapter`                    | the platform-neutral adapter: deployment, task delivery, REST and gRPC client |
| `spring-boot`                        | `zenbpm-vanillabp-adapter-spring-boot`        | Spring Boot auto-configuration                                                |
| `smoke-test`                         | (not published)                               | discovery, health and startup validation on Spring Boot without an engine     |
| `quarkus/runtime`                    | `zenbpm-vanillabp-adapter-quarkus`            | Quarkus extension, runtime part                                               |
| `quarkus/deployment`                 | `zenbpm-vanillabp-adapter-quarkus-deployment` | Quarkus extension, build-time part                                            |
| `quarkus/integration-tests`          | (not published)                               | end-to-end tests on a booted Quarkus application                              |
| `test-coverage-report/spring-boot`   | (not published)                               | aggregated coverage of the core and the Spring Boot modules                   |
| `test-coverage-report/quarkus`       | (not published)                               | aggregated coverage of the core and the Quarkus modules                       |
| `test-coverage-report/coverage-gate` | (not published)                               | breaks the build below the coverage threshold, last module of the reactor     |

All artifacts have the groupId `org.pbinitiative.zenbpm`, the Java packages start with
`org.pbinitiative.zenbpmadapter`.

## Supported engine version

The adapter is tested against exactly one ZenBPM engine build, and supports that one: tested, not
newer. Today it is a build of the engine's `main`, because no engine release carries the job lock the
adapter's task delivery is built on (decision 15 in [`DECISIONS.md`](./DECISIONS.md)). The pin
becomes a release tag once the engine tags a release containing that commit.

| Pinned |                                                 Value                                                 |
|--------|-------------------------------------------------------------------------------------------------------|
| commit | `bdd795a399b6484d062d52ac90c0d0725cb0ad91` (2026-10-13)                                               |
| image  | `ghcr.io/pbinitiative/zenbpm@sha256:d7808e30821a7349e0f42476d8aaa21ff92fc752c0ccc432771764d307b87ed0` |

The build reads both from `zenbpm.commit` and `zenbpm.image` in the root `pom.xml`. The integration
tests start that image, and `EngineUnderTestIT` fails when the running container is not that image
or the engine reports another commit.

The REST contract the adapter is written against is the copy `core/src/main/zenbpm/api.yaml`. The
job stream's contract is the copy `core/src/main/proto/zenbpm.proto`. Both come from the pinned
commit and name it, with the image, in their first lines. The table above repeats the pin as well.
`EnginePinTest` fails when one of these three names another pin than the POM.
`bin/check-engine-copies.sh` compares the two copies with the engine's files of the pinned commit,
which it reads from GitHub. Only the header and the proto's `java_package` line may differ. The
pull-request check runs it, and it fails on any other difference (decision 18 in
[`DECISIONS.md`](./DECISIONS.md)).

Moving the pin is one commit with these steps:

1. Pull `ghcr.io/pbinitiative/zenbpm:dev` and read its digest and its
   `org.opencontainers.image.revision` label.
2. Check that the revision is the head of the engine's `main`, or the newest commit which got an
   image.
3. Write the commit and the digest into `zenbpm.commit` and `zenbpm.image` of the root `pom.xml`.
4. Copy `openapi/api.yaml` and `pkg/zenclient/proto/zenbpm.proto` of exactly that commit over the
   two copies (`git show <commit>:<path>`). Keep the header of each, with the new commit, date and
   image, and keep the `java_package` line of the proto.
5. Update the table above.
6. Run `bin/check-engine-copies.sh` and `mvn install`.

## Which phase-two failures are repeated

A failed call of the engine is classified in one place, `ZenBpmErrors` in the core, so that the
phase-two outbox and the commands a job handler sends back cannot disagree about what a failure
means. It answers from the status, the method and the path of the call and never from the
engine's message, which any version of the engine may reword (decision 10 in
[`DECISIONS.md`](./DECISIONS.md)). The first exception of the REST client in the chain of causes
answers alone, so a failure wrapped by a caller is read like a plain one.

|                                Failure                                 |   Read as    |                                      Why                                      |
|------------------------------------------------------------------------|--------------|-------------------------------------------------------------------------------|
| status `400`, `413` or `415`                                           | permanent    | the request is malformed, too large or of the wrong content type              |
| `NumberFormatException`                                                | permanent    | a key which is not a number never becomes one                                 |
| status `404` from a call of a job                                      | gone job     | the engine holds no such job                                                  |
| status `409` from a completion or failure of a job                     | gone job     | the job no longer takes the command: it ended, or a newer delivery decides    |
| status `404` from `POST /v1/messages`                                  | nobody waits | the engine drops a message nothing waits for                                  |
| status `409` from `POST /v1/jobs/{key}/extend-lock`                    | lock lost    | the lock lapsed or was never taken, the job ended, or another client holds it |
| status `502`, `503` or `504`                                           | unavailable  | the engine or a proxy in front of it could not pass the request               |
| `ZenBpmUnavailableException`                                           | unavailable  | no answer, a broken connection, or an answer which cannot be read             |
| `SocketTimeoutException`, `HttpTimeoutException` or `ConnectException` | unavailable  | the engine could not be reached, and no REST client wrapped it                |
| any other status, `500` included                                       | repeatable   | a technical fault of the engine may pass                                      |

`ZenBpmErrorsTest` holds every row, plain and wrapped, and fails when the table names a row it does
not test, tests a row the table does not name, names a row twice or orders the rows differently.
`ZenBpmErrorsIT` makes the pinned engine answer each status of the job, message and lock rows,
`400`, `413` and `502`, and checks how each is read.

Only a permanent failure is not repeatable: `ZenBpmErrors.isRepeatable` is false for the first two
rows and true for every other. The three narrower answers, a gone job, nobody waits and a lost lock,
mean something only to the caller which sent that call, so it asks for them before it asks whether
to repeat.

Where the status says less than it seems:

- The engine answers `502` also for a key of a partition it does not have, such as a key another
  engine handed out. The status alone cannot tell this apart from a node which could not be
  reached, so such a key reads as unavailable. Telling the two apart needs the key's partition (bits
  12 to 21) compared with the partitions `GET /system/status` reports.
- A `ZenBpmUnavailableException` may name a `2xx` status: the engine did what was asked, and only
  the body of its answer was lost or could not be read. It is repeatable all the same, because a
  permanent failure would block an operation the engine carried out. Not every repetition is
  harmless, though. A read is answered again, and a completion of a completed job is answered as the
  first one was. A start is repeated only after the core asked whether its workflow is there already,
  which a wrong "unknown" turns into a second workflow. A message may be correlated twice, because
  the engine does not deduplicate messages, and a failure without a delivery token counts twice. The
  caller of such a call decides whether it accepts the duplicate.
- A `404` or `409` is read from its status, method and path alone. A proxy in front of the engine
  which answers `404` to a call of a job, for example because its routes leave out a path, is read
  as a gone job like the engine's own answer.

## Building

The build depends on two repositories of the VanillaBP project,
[`spi-for-java`](https://github.com/vanillabp/spi-for-java) and
[`adapter-platform-integration`](https://github.com/vanillabp/adapter-platform-integration). Both
are `2.0.0-SNAPSHOT`, and VanillaBP publishes their snapshots to the snapshot repository of Maven
Central, which the root POM names and which needs no login. So nothing has to be built first:

```bash
mvn install
```

`install` and not `install verify`: install runs every phase verify has, so naming both walks the
lifecycle twice. Spotless checks the formatting in every build; `mvn spotless:apply` fixes it.

The integration tests start the engine in Docker, with the image of the section above. Without a
Docker environment they are skipped, and the build stays green. The job stream's gRPC stubs are
generated from `core/src/main/proto/zenbpm.proto` in every build, into
`core/target/generated-sources/protobuf`; `protoc` and its gRPC plugin are downloaded from Maven
Central, so nothing has to be installed for it. A new module with integration tests copies
`engine-test-support/src/test/resources/logback-test.xml`, which says why it looks the way it does.

Working rules for this repository, for people and coding agents alike, are in
[`AGENTS.md`](./AGENTS.md). The decisions the code relies on are in
[`DECISIONS.md`](./DECISIONS.md), what the engine cannot do and how the adapter answers it in
[`GAPS.md`](./GAPS.md).

## Using the snapshots

Every push to `main` publishes the artifacts as `2.0.0-SNAPSHOT` to this repository's GitHub
Packages (`.github/workflows/publish-snapshots.yaml`), for example:

```xml
<dependency>
  <groupId>org.pbinitiative.zenbpm</groupId>
  <artifactId>zenbpm-vanillabp-adapter-spring-boot</artifactId>
  <version>2.0.0-SNAPSHOT</version>
</dependency>
```

The Quarkus extension is `zenbpm-vanillabp-adapter-quarkus`. GitHub Packages asks for credentials
even for a public package, and its Maven registry accepts only a classic personal access token: any
GitHub account's classic token with `read:packages` works. A consumer adds the registry and the
token to its Maven `settings.xml`:

```xml
<settings>
  <servers>
    <server>
      <id>zenbpm-vanillabp-adapter</id>
      <username>YOUR_GITHUB_USER</username>
      <password>YOUR_CLASSIC_TOKEN</password>
    </server>
  </servers>
  <profiles>
    <profile>
      <id>zenbpm-vanillabp-adapter</id>
      <repositories>
        <repository>
          <id>zenbpm-vanillabp-adapter</id>
          <url>https://maven.pkg.github.com/pbinitiative/zenbpm-vanillabp-adapter</url>
          <snapshots>
            <enabled>true</enabled>
          </snapshots>
          <releases>
            <enabled>false</enabled>
          </releases>
        </repository>
      </repositories>
    </profile>
  </profiles>
  <activeProfiles>
    <activeProfile>zenbpm-vanillabp-adapter</activeProfile>
  </activeProfiles>
</settings>
```

Releases stay switched off for this repository on purpose. A repository of the settings is asked
before Maven Central, so without it every release dependency is first looked up in GitHub Packages
and refused: measured on 2026-10-06, resolving this adapter from an empty local repository took six
minutes with 1,317 such refusals, and one minute without them.

The adapter builds on VanillaBP's own snapshots, which live in the snapshot repository of Maven
Central, `https://central.sonatype.com/repository/maven-snapshots/`. It asks for no token, and the
adapter's published POMs name it. A consumer whose build does not read the repositories of its
dependencies, one behind a mirror for example, adds it itself, with snapshots switched on and
releases switched off, the way the root `pom.xml` of this repository does.

## Contributing

Every pull request and every push to `main` runs `.github/workflows/checks.yaml`: one `mvn install`,
which is Spotless, compile, javadoc, the tests, both coverage reports and the gate. Its job `build`
is the check branch protection on `main` requires, so nothing reaches `main` without it. The same
build runs a second time on Java 21 (`build on Java 21`), the Java the published classes are
compiled for, because a dependency built for a newer Java would only fail at run time. When a red
run got as far as writing test or coverage reports, they are attached to the run as the artifact
`test-reports` (`test-reports-java-21` for the run on Java 21). A build which stops earlier, at
Spotless or the compiler, has none, and its log is the place to read.

The build reads the VanillaBP snapshots from the snapshot repository of Maven Central, which needs
no login. So a pull request from a fork builds exactly like one from this repository, and the
workflows need no secret of their own: `publish-snapshots.yaml` deploys with the workflow's
`GITHUB_TOKEN`.

## Test coverage

`mvn install` builds one aggregated JaCoCo report per platform:

1. **Spring Boot** (core + Spring Boot integration) - into `test-coverage-report/spring-boot/report`
2. **Quarkus** (core + Quarkus extension) - into `test-coverage-report/quarkus/report`

Every push to `main` publishes both to GitHub Pages, as
[`spring-boot-report`](https://pbinitiative.github.io/zenbpm-vanillabp-adapter/spring-boot-report/index.html)
and [`quarkus-report`](https://pbinitiative.github.io/zenbpm-vanillabp-adapter/quarkus-report/index.html).
The two coverage badges at the top read the instruction coverage from those pages, the number the
gate below compares. While the core compiles no code yet they show `n/a`, as the reports do.

Coverage is measured separately per platform, because a platform's tests never cover the other
platform's code.

The build breaks below the line: `test-coverage-report/coverage-gate` is the last module of the
reactor, reads both reports and fails whenever a platform is below its threshold in the root POM
(`coverage.threshold.spring-boot`, `coverage.threshold.quarkus`, in percent of covered
instructions). Both properties hold 85, the number every VanillaBP adapter gates on, and that is not
the target: the rule is 90 per platform, so a report between 85 and 90 passes the build and still
names a gap. Two more checks keep a report from being incomplete without anybody noticing. Every
module producing a `jacoco.exec` has to be read by a report. And every module with production
sources has to be read by the report of its platform, decided by where it lives (the core by both,
`spring-boot` and `smoke-test` by Spring Boot, `quarkus/` by Quarkus), because a module without
tests writes no `jacoco.exec`, and left out of its report it would empty that report instead of
failing it. All of these are `CoverageGateTest`; the failure paths of the last one are held by
`ProductionModulesTest`. The conventions every test class of this repository follows are checked by
`TestClassConventionsTest`, what the published POMs hand an application by `PublishedPomsTest`, and
that every published jar and sources jar carries `LICENSE`, `LICENSE-APACHE-2.0` and `NOTICE` by
`PublishedJarsTest`. `PublicationSafetyTest` keeps the snapshot publication honest: the deploy is
deferred until the gate, the last module, has passed, and each coverage badge's expression reads the
report this build wrote.

`bin/check-deploy-safety.sh` proves the deferred deploy itself, without GitHub: it deploys a copy of
the working tree into a local directory (exactly the jar, sources jar and POM of the core,
`spring-boot`, `quarkus` and `quarkus-deployment` and the parent's POM have to arrive, no file
missing and none more), then adds an untested method to the copy, raises both coverage thresholds
to 100 % so that this method alone turns the gate red, and checks that the red gate leaves the
directory empty. It installs nothing into the local Maven repository. Run it
after changing anything about deploying, the module order or the gate.

The gate reports what it measured on every run, green ones included. The angle brackets stand for
the numbers of the run:

```
coverage gate | Spring Boot: <percent> % instructions (<missed> of <total> missed) | at the rule of 90 %
coverage gate | Quarkus: <percent> % instructions (<missed> of <total> missed) | <gap> points below the rule of 90 %, build breaks below 85 %
```

Two kinds of run cannot be measured, and in both the gate prints a line per platform saying why and
reports its two threshold tests as skipped rather than passed. A build which stops before `verify`
never writes the reports. A report without a single instruction (what the skeleton produces today,
and what an aggregate which lost all its modules would produce) prints
`the report holds no instruction, so there is no coverage to check`.

## Licence

This repository is licensed under the [MIT License](./LICENSE).

Parts of it are copied and adapted from the VanillaBP adapters
[`camunda8-adapter`](https://github.com/vanillabp/camunda8-adapter) and
[`process-engine-api-adapter`](https://github.com/vanillabp/process-engine-api-adapter), which are
licensed under the Apache License, Version 2.0. Those files keep that licence: each carries a header
naming its origin and `SPDX-License-Identifier: Apache-2.0`, the licence text is in
[`LICENSE-APACHE-2.0`](./LICENSE-APACHE-2.0), and [`NOTICE`](./NOTICE) names both origins. Every
other file is MIT, with one exception that is still open. Decision 17 in
[`DECISIONS.md`](./DECISIONS.md) says why.

The exception: `core/src/main/zenbpm/api.yaml` and `core/src/main/proto/zenbpm.proto` are copies of
files of the ZenBPM engine, which is licensed under the AGPL-3.0-or-later and an enterprise licence.
The core jar ships classes generated from the proto, and its sources jar their Java sources. Under
which licence these copies and the generated code travel is not settled yet; [`NOTICE`](./NOTICE)
says so, and the ZenBPM maintainers decide it.

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

**Skeleton.** The repository builds, measures coverage per platform and gates on it, but the
adapter does nothing yet. What is built next, and in which order, is the implementation plan in
[`docs/`](docs/README.md).

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

Both are written once, as `zenbpm.commit` and `zenbpm.image` in the root `pom.xml`. The integration
tests start that image, and `EngineUnderTestIT` fails when the running container is not that image
or the engine reports another commit. The REST contract the adapter is written against is the copy
`core/src/main/zenbpm/api.yaml`, the job stream's the copy `core/src/main/proto/zenbpm.proto`, both
taken from that commit and naming it in their first line. Moving the pin changes all four in one
commit: pull `ghcr.io/pbinitiative/zenbpm:dev`, read its digest and its
`org.opencontainers.image.revision` label, check that the revision is the head of the engine's
`main` (or the newest commit which got an image), and copy the two files of exactly that commit.

## Building

The build depends on two repositories of the VanillaBP project, which have to be in the local Maven
repository first, in this order:

1. [`spi-for-java`](https://github.com/vanillabp/spi-for-java) (`mvn install`)
2. [`adapter-platform-integration`](https://github.com/vanillabp/adapter-platform-integration)
   (`./mvnw install`)

Both are `2.0.0-SNAPSHOT` and VanillaBP publishes their snapshots to its GitHub Packages, so instead
of building them they can be resolved from there with a GitHub token which may read packages. Then:

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

The adapter builds on VanillaBP's own snapshots, which live in VanillaBP's GitHub Packages in the
same way. Until VanillaBP 2.0 is released, a consumer needs those two registries as well, with the
same server credentials and releases switched off, see `.github/workflows/settings.xml` for their
addresses.

## Contributing

Every pull request and every push to `main` runs `.github/workflows/checks.yaml`: one `mvn install`,
which is Spotless, compile, javadoc, the tests, both coverage reports and the gate. Its job `build`
is the check branch protection on `main` requires, so nothing reaches `main` without it. The same
build runs a second time on Java 21 (`build on Java 21`), the Java the published classes are
compiled for, because a dependency built for a newer Java would only fail at run time. When a red
run got as far as writing test or coverage reports, they are attached to the run as the artifact
`test-reports` (`test-reports-java-21` for the run on Java 21). A build which stops earlier, at
Spotless or the compiler, has none, and its log is the place to read.

The build reads the VanillaBP snapshots from VanillaBP's GitHub Packages, which asks for
credentials even for public packages, and only accepts a classic personal access token. The two
repository secrets which carry them are `VANILLABP_PACKAGES_USER` (a GitHub user name) and
`VANILLABP_PACKAGES_TOKEN` (a classic token of that user with `read:packages` and nothing else);
`.github/workflows/settings.xml` reads them, and only the build step gets them. They are one
maintainer's token today, so they are replaced when that maintainer leaves or the token expires.
A classic token cannot be limited to one organisation: it reads every package its owner can read,
private ones of other organisations included. So it should come from an account which belongs to as
few organisations as possible, ideally to none. A pull request from a fork gets no
repository secrets; its run builds `spi-for-java` and `adapter-platform-integration` from the head
of their default branch instead, which takes a few minutes longer.

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
missing and none more), then adds an untested method to the copy and checks that the
red gate leaves the directory empty. It installs nothing into the local Maven repository. Run it
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
other file is MIT. Decision 17 in [`DECISIONS.md`](./DECISIONS.md) says why.

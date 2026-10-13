# Decision log

Decisions this repository's code points at. A number is handed out once and never reused or
renumbered, so a citation stays resolvable; a decision which gets overturned keeps its entry,
marked as superseded and naming the entry which replaced it.

A citation in code reads `see decision 3 in the repository's DECISIONS.md`, and it names an entry
of THIS repository only.

The numbers were handed out by the implementation plan in [`docs/`](docs/README.md), so that every
story could cite its decision before the code existed. An entry is added here by the story which
first relies on it, under the number the plan gave it; that is why numbers are missing below. The
drafts of the missing ones are in
[`docs/architecture/02-design-decisions.md`](docs/architecture/02-design-decisions.md).

### 1. The adapter is native, and the Camunda 8 adapter is its template

ZenBPM is a remote, at-least-once, polling BPMS with a query API, which is the shape the Camunda 8
adapter was written for. It is NOT built on the Process-Engine-API adapter: no Process-Engine-API
implementation for ZenBPM exists, the adapter would have had to write one against its own private
command classes, and every capability the PEA cannot express (finding a workflow, listeners,
versions, pushing variables, typed failures) is one ZenBPM's REST API offers and the PEA layer would
have hidden. What is borrowed from the PEA adapter is the raw-XML handling, because a model type of
a Camunda artifact would tie this adapter to Camunda for no reason.

### 3. The REST client is the adapter's own, the gRPC stubs are generated

The adapter uses about twenty REST endpoints. The official Java client trails the engine by two
minors, changed its deploy contract with 1.8, and knows nothing about Quarkus; a generated REST
client would bring a generator into every build and its own reflection into every native image. So
the REST side is a thin `java.net.http` + Jackson client written against the copy of
`openapi/api.yaml` this repository pins, with one method per endpoint and one exception carrying the
status code. The job stream is generated from the pinned `zenbpm.proto`, because that is what
protobuf is for. The pinned copies name the engine commit they came from, and a test diffs them
against the image the integration tests run.

*The last sentence is superseded by decision 18: the engine does not serve its OpenAPI document, so
the copies are checked against the engine's files of the pinned commit instead.*

### 13. A class opens its fields one by one, not as a whole

The deployment service, the process service and the engine client of this adapter hold many
fields, most of them collaborators nobody outside the class needs. Which of them a caller may read
belongs to the surface of the class, so an accessor is declared per field, and Lombok's `@Getter`
on the class is refused even where an IDE offers it: it would publish the current field list and
then keep publishing whatever field a later change adds.
`@SuppressWarnings("LombokGetterMayBeUsed")` on such a class keeps that offer from coming back.

### 15. One pinned engine version, no release lines

The adapter needs at least the engine's job lock per subscription with its extension (engine commit
`071460cc`: lock per subscription, lock extension, `lock_until`), because the adapter's task
delivery is built on it, and no release carried it when the adapter was started. So during
development the pin is a commit of the engine's `main` and the digest of the image built from it;
before the adapter's first release it becomes an engine release tag. At startup the adapter logs the
engine's version and commit from `GET /system/status` and warns where the commit is not the tested
one. It does not compare versions: the version an engine on `main` reports is the last release's,
the same for every build since. Beyond that the adapter compiles against no engine artifact, so no
pin decides anything else; the REST contract is the copy of `api.yaml` this repository ships, and
the integration tests run against the pinned image. Supported means tested: the pinned image and
nothing newer. Release lines are what Camunda 8 needs because its client is the minimum cluster;
here they would be branches for a contract which has no stability promise yet. If an engine minor
breaks the surface the adapter uses, the answer is a new adapter release against the new pin, and
this entry is revisited.

### 16. The adapter follows VanillaBP's adapter conventions although another organisation owns it

`zenbpm-vanillabp-adapter` belongs to the ZenBPM maintainers at pbinitiative, implements VanillaBP's
adapter SPI and is read by people who know the Camunda and Process-Engine-API adapters. It therefore
keeps their shape: the `core` / `spring-boot` / `quarkus` split with platform modules which only
construct and register, Spotless with the platform's formatting conventions, coverage measured per
platform with the same gate and rule, `test-utils` in every test, a `DECISIONS.md` as the only thing
code cites, a user-facing wiki and a contributor-facing README, and configuration under
`vanillabp.adapters.<id>.*` validated at startup with guiding messages. What differs is what
ownership decides: the licence, the coordinates (groupId `org.pbinitiative.zenbpm`, packages
`org.pbinitiative.zenbpmadapter`), the CI and where releases are published. The Go conventions of
the engine repository do not reach into this one.

*"Spotless with the platform's formatting conventions" is superseded in part by decisions 20 and
22: the formatter is the platform's, but it indents by four spaces instead of two, and a wrapped
parameter list closes on a line of its own.*

### 17. Code adapted from the Apache-2.0 adapters keeps its licence and its notice

The repository is MIT-licensed, and a good part of its first version is copied and adapted from
`camunda8-adapter` and `process-engine-api-adapter`, both Apache 2.0. Apache 2.0 allows that inside
an MIT project as long as the licence text and the attributions travel with the code, so every
adapted file keeps any header it had, `LICENSE-APACHE-2.0` holds the licence text, and `NOTICE`
names the two origins. The VanillaBP adapters write no header into their Java files, so an adapted
Java file gets one naming its origin, that it was adapted, and
`SPDX-License-Identifier: Apache-2.0`. A file written from scratch carries the MIT header with
`SPDX-License-Identifier: MIT`. Which is which is decided when the file is created and never
changed afterwards, because the origin of a file is a fact and not a preference.

### 18. The pinned copies are checked against the engine's files of the pinned commit

Supersedes the last sentence of decision 3. The copies of `api.yaml` and `zenbpm.proto` name the
engine commit and image they came from. `bin/check-engine-copies.sh`, run by the pull-request check,
compares them with the engine's files at that commit; the proto may differ by its header and its
`java_package` line only. `EngineUnderTestIT` proves that the pinned image reports that commit, so
the copies are the contract of the image the tests run. A diff against the running engine needs the
engine to serve its OpenAPI document, which it does not; the generated `GetSpec()` only feeds its
request validator. Once it serves the document, that diff replaces the check against the commit.

### 19. Tests come first, helpers last, private methods after the others

A reader opens a test class to learn what is tested, and any other class to learn what it offers.
So in a test class the lifecycle methods (`@BeforeEach` and the like) come before the first test,
and every other method follows the last test: argument sources of parameterized tests, assertion
helpers and private methods alike. In every class, main or test, the private methods follow the
non-private ones. Constructors are not methods for this rule; they stay next to the fields. The rule
covers the members of a top-level class. `MethodOrderTest` of the coverage gate checks it on every
build, because a rule that is only written down drifts. Nested classes are reviewed by eye.

### 20. Java is indented by four spaces

Supersedes in part decision 16, which kept the formatting conventions of the VanillaBP platform. The
owners of this repository chose four spaces per indentation level for every Java file, main and
test, because that is how most Java is written outside the VanillaBP repositories, and so how most
of this repository's readers read it. `formatting_conventions.xml` stays the platform's file with
`indentation.size` and `tabulation.size` raised from 2 to 4. A wrapped line still continues two
levels deeper than its start, now 8 spaces, so it stays apart from the body of a block. Spotless
applies and checks it on every build, like the rest of the file. Everything else of decision 16
stands.

### 21. Test classes and their test methods are package-private

JUnit 5 runs test classes and test and lifecycle methods which are not `public`, so `public` on them
says nothing, and IDEs and static analysis flag it as redundant. Test classes and their `@Test`,
`@ParameterizedTest`, `@BeforeEach`, `@AfterAll` and similar methods are therefore declared without
a visibility modifier. Classes in test sources which hold no test, such as shared helpers, keep the
visibility their callers need. `TestVisibilityTest` of the coverage gate checks it on every build.

### 22. A wrapped parameter list closes on a line of its own

Supersedes in part decision 16, like decision 20. Where the parameters of a method, a constructor
or a record are wrapped, each stands on a line of its own one level (four spaces) deeper than the
declaration, and the closing parenthesis returns to the declaration's indentation, followed by the
`throws` clause and the opening brace:

```java
private ProductionModules.Report report(
    final String platform,
    final String... aggregated
) throws IOException {
```

The parameters then read as a list of their own, the line with `) {` separates them from the body,
and adding a parameter changes one line instead of two. `formatting_conventions.xml` says so with
"indent by one" on the three parameter alignments and `separate_lines_if_wrapped` for the
parentheses of method and record declarations; Spotless applies and checks it on every build. A
declaration short enough for one line stays on one line.

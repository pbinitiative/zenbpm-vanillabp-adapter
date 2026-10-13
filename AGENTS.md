# Working on zenbpm-vanillabp-adapter

The VanillaBP adapter for a remote ZenBPM engine, on Spring Boot and on Quarkus.

This repository is owned by the ZenBPM maintainers at pbinitiative, not by the VanillaBP project.
It implements VanillaBP's adapter SPI and follows the conventions of the VanillaBP adapters, so
that somebody who knows the Camunda 8 or the Process-Engine-API adapter finds their way here; see
decision 16 in the repository's DECISIONS.md. The engine is written in Go, and the Go conventions
of the engine repository do not apply here.

Read [`README.md`](./README.md) first: it says what is built, how to build it and which engine it
is tested against. The analysis, the architecture and the implementation plan are in
[`docs/`](docs/README.md); a story of the plan says what to build and how it is accepted.

What an adapter implements is described once, for every adapter, in
[`ADAPTER-AUTHORS.md`](https://github.com/vanillabp/adapter-platform-integration/blob/main/migration-adapter/ADAPTER-AUTHORS.md)
of the platform repository: the two interfaces, the calls the core expects back, what an answer
promises and what a wrong one costs. Read it before changing anything on the SPI boundary. Where
this adapter cannot do what it asks, the gap belongs in [`GAPS.md`](./GAPS.md).

## Building

`spi-for-java` and `adapter-platform-integration` have to be in the local Maven repository first,
built from the VanillaBP repositories or resolved from VanillaBP's GitHub Packages. Then:

```bash
mvn install                   # install alone: it runs every phase verify has
mvn spotless:apply            # before every commit; Spotless fails the build on violations
bin/check-deploy-safety.sh    # after touching deploying, module order or the coverage gate
```

## Licence headers

The repository is MIT-licensed, and code copied from the Apache-2.0 VanillaBP adapters keeps its
licence (decision 17 in the repository's DECISIONS.md). So every Java file starts with one of two
headers, chosen when the file is created and never changed afterwards. A file written here:

```java
/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
```

A file copied and adapted from one of the VanillaBP adapters names the repository it actually came
from, so the second line reads either `from vanillabp/camunda8-adapter` or
`from vanillabp/process-engine-api-adapter`:

```java
/*
 * Copyright 2026 Phactum Softwareentwicklung GmbH
 * Adapted for zenbpm-vanillabp-adapter from vanillabp/process-engine-api-adapter, see NOTICE.
 * SPDX-License-Identifier: Apache-2.0
 */
```

A file adapted from a repository `NOTICE` does not name yet adds that repository to `NOTICE`.

## Indentation and visibility

Java is indented by four spaces, and a wrapped line continues eight spaces deeper than its start
(decision 20 in the repository's DECISIONS.md). A wrapped parameter list of a method, constructor
or record is the exception: its parameters stand four spaces deeper, and `)` returns to the
declaration's indentation on a line of its own, followed by `throws` and `{` (decision 22).
`mvn spotless:apply` does both; do not format by hand.
Test classes and their test and lifecycle methods have no `public`: JUnit 5 does not need it
(decision 21). `TestVisibilityTest` in `test-coverage-report/coverage-gate` fails the build on a
`public` test class or test method.

## Where methods go

In a test class the tests come first: lifecycle methods (`@BeforeEach`, `@AfterAll`, ...) above the
first test, every other method below the last one. That includes the argument sources of
parameterized tests. In every class the private methods come after the non-private ones.
Constructors stay next to the fields. `MethodOrderTest` in `test-coverage-report/coverage-gate`
fails the build on a method out of place and names it. The reason is decision 19 in the
repository's DECISIONS.md.

## What belongs in `UPGRADE.md`

[`UPGRADE.md`](./UPGRADE.md) holds what a released version of this adapter asks an upgrading
application to change, and nothing else. A change between two snapshots earns no entry. The end
state of a new feature belongs in the wiki, a reasoning several places rely on belongs in
`DECISIONS.md`, and what is neither is said in the commit message.

## The decision log is binding

[`DECISIONS.md`](./DECISIONS.md) holds the decisions several places in this repository rely on. It
is the ONLY thing the code is allowed to cite, in the plain greppable form
`see decision 7 in the repository's DECISIONS.md`, and only entries of THIS repository.

Read it before you change behaviour. An entry is the reason the code around it looks the way it
does, so a change which contradicts one is wrong until the entry says otherwise.

**A decision is changed or replaced only after asking.** Where your change would make an entry
untrue, stop and put the question to the maintainers before you write the change. If the answer is
yes, the same commit updates the log: the old entry STAYS, marked as superseded and naming the
entry which replaced it, and the new decision takes the next free number. Numbers are never reused
and never renumbered, because a citation in an older release still points at them.

The plan in `docs/` handed out the numbers 1 to 17 in advance, so a story adds the entry it relies
on under the plan's number. A decision the plan does not know takes the next number after the
highest one handed out anywhere, in the plan or here. [`GAPS.md`](./GAPS.md) is numbered the same
way.

Before opening a pull request, check that no other open pull request claimed the same new number:

```bash
git fetch origin
git show origin/main:DECISIONS.md | grep -E '^### [0-9]+\. '   # the numbers already taken
gh pr list --state open
gh pr diff <n> | grep -E '^\+### [0-9]+\. '                    # for each open pull request
```

If your number is taken, your entry gets the next free one, and you correct every citation of it in
the code and in the documentation of your branch. Read each citation before you change it: not
every `see decision <n>` in the branch is about your decision. A branch can cite a number somebody
else handed out long ago, and that citation stays as it is, so a search and replace over the branch
turns a right reference into a wrong one.

None of this breaks the rule that a number is never renumbered. That rule is about a merged number,
which a citation in a released artifact points at. Until the pull request is merged, nothing outside
the branch has seen the number, so correcting it costs no more than the branch.

## What code may point at

Nothing which a later change can invalidate without anything noticing: no story or prompt number,
no issue or pull-request number, no chat transcript, no person. Those record a conversation at a
point in time. A decision entry lives next to the code and is overhauled in the same commit, which
is what makes it citable.

Where a name can carry the reason, the name is the better fix. Where it cannot, a comment says why
in its own words, complete where it stands. Commit messages and pull-request descriptions may cite
whatever they like.

# VanillaBP adapter for ZenBPM - analysis, architecture and implementation plan

This folder holds everything needed to build `zenbpm-adapter`, the VanillaBP 2 adapter for the
ZenBPM engine (`github.com/pbinitiative/zenbpm`). The adapter is owned and built by the ZenBPM
maintainers at `github.com/pbinitiative/zenbpm-adapter` (MIT licence, packages
`org.pbinitiative.zenbpmadapter`), not by the VanillaBP project; it implements VanillaBP's adapter SPI
and follows the conventions of the VanillaBP adapters so that a reader of one recognises the other.
The plan was produced on 2026-09-07 and revised for that ownership on 2026-09-13, against

- `adapter-platform-integration` `2.0.0-SNAPSHOT` (adapter SPI as of decision 37),
- `camunda8-adapter` at decision 20 (the structural template),
- `process-engine-api-adapter` at decision 8 / gap 22 (donor of the raw-XML utilities),
- `zenbpm` working tree `b22f12c2` (`VERSION` 1.8.0, unreleased; last release v1.7.0).

Every statement about the engine names the file it was read from, so a later engine version can be
re-checked file by file rather than re-analysed.

## How to read this folder

| Order | File | What it answers |
|---|---|---|
| 1 | [`analysis/01-zenbpm-capabilities.md`](analysis/01-zenbpm-capabilities.md) | What the engine offers, verified against its source. |
| 2 | [`analysis/02-spi-requirements-mapping.md`](analysis/02-spi-requirements-mapping.md) | Every promise of the adapter SPI held against the engine: supported, partial, missing, and what the adapter does about it. |
| 3 | [`analysis/03-template-assessment.md`](analysis/03-template-assessment.md) | Why the adapter is built natively on the Camunda 8 template and not on the Process-Engine-API adapter, and what is borrowed from where. |
| 4 | [`architecture/01-architecture.md`](architecture/01-architecture.md) | Repository, modules, class map, runtime flows, configuration keys, threading, error classification, test infrastructure. |
| 5 | [`architecture/02-design-decisions.md`](architecture/02-design-decisions.md) | The draft `DECISIONS.md` of the new repository - numbered, ready to be cited by code. |
| 6 | [`architecture/03-deviations-and-gaps.md`](architecture/03-deviations-and-gaps.md) | The draft wiki `Deviations` page and the `GAPS.md` of the repository. |
| 7 | [`plan/00-roadmap.md`](plan/00-roadmap.md) | Epics, milestones, the dependency graph and the global story order. |
| 8 | `plan/epic-*.md` | One file per epic: features, stories, development instructions, acceptance criteria. |
| 9 | [`open-questions.md`](open-questions.md) | What could not be settled from the code, who has to answer it, and the recommendation for each. |

## Who owns what

| Owned by pbinitiative (this repository and the engine) | Owned by the VanillaBP project (asked, not changed here) |
|---|---|
| every decision of `architecture/02-design-decisions.md`, the gaps, the configuration keys | the adapter SPI (`adapter-platform-integration`) and its `ADAPTER-AUTHORS.md` |
| the engine changes of the engine-enablement epic (same maintainers, same organisation) | the skills in the workspace's `.claude/skills/` which still say the ZenBPM adapter is built on the PEA adapter |
| CI, releases, wiki at `pbinitiative/zenbpm-adapter.wiki`, coverage pages | the `blueprints` repository (a `-Pzenbpm` profile is a pull request there) and the wiki page `BPMS-adapters` which lists adapters |
| the code copied from `camunda8-adapter` and `process-engine-api-adapter` (Apache 2.0), which keeps its notices | the `2.0.0-SNAPSHOT` platform artifacts the build reads from VanillaBP's GitHub Packages |

The last row is why CI is part of the plan from the first story: a build in a foreign organisation
needs a token to read VanillaBP's snapshots, and that has to work before any code exists.

## Vocabulary used throughout

Terms follow the `vanillabp-concepts` skill: a *process* is the BPMN model, a *workflow* one running
instance; an *adapter type* is `zenbpm`, an *adapter id* one configured instance of it; a *workflow
module* is one deployable unit of BPMN files plus code; the *core* is `migration-adapter`. ZenBPM's own
vocabulary is used for its API: a *process definition* (deployed model, one `processDefinitionKey` per
version), a *process instance* (`key`), a *job* (`jobKey`, what a worker receives), a *business key*
(free text on an instance, filterable) and a *partition* (an internal Raft group, invisible to the
adapter except for paging).

## Status legend used in the plan files

- `[ ]` not started · `[~]` in progress · `[x]` done · `[!]` blocked on an open question (the
  question is named in the story).
- A story marked **engine-gated** has two shapes: the fallback the adapter ships today and the final
  shape it takes once the engine change of the `engine-enablement` epic lands. Both are specified.

## How the plan was improved beyond the request

Four things were added to what was asked for, because the analysis made them unavoidable:

1. **An engine-enablement epic.** Ten features of the VanillaBP contract hit a limit of the engine
   itself (a fixed 30-second job lock, no lock extension, no retries, no business key on message
   starts, no variable filter, no listeners, no user-task lifecycle events, output propagation only
   through mappings, no authentication). Since the engine lives in this workspace, each limit is
   written up as a small engine change with the adapter story which consumes it. The adapter ships a
   fallback for each so nothing waits on the engine.
2. **A default of `use-prefix` instead of `by-adapter`.** ZenBPM has no tenant, and there is no
   version-1 application whose running workflows a default has to keep finding. Refusing the SPI's
   default at every boot, as the Process-Engine-API adapter has to, would make every application
   configure the same line. This is a draft decision and an open question for the maintainer.
3. **Two lookups behind one probe.** The engine finds an instance by business key but never by
   variable, and an instance the engine starts itself (timer) or by message carries no business key.
   The workflow probe therefore asks by business key first and by instance key second, and the
   aggregate id of a BPMS-initiated workflow IS the instance key. The message-started workflow stays
   the one case which needs the engine (open question 1).
4. **CI from the first commit, shaped by every epic.** The repository lives in an organisation other
   than the platform it builds against, so the pull-request workflow, the snapshot publication and the
   coverage pages exist before the first line of Java (E1, feature F1.3) and grow with the plan: Docker
   integration tests from E4, a nightly run against the engine's `latest` image and a native-image job
   in E11, the release workflow in E12, and a cross-repository trigger from the engine's CI in E13.

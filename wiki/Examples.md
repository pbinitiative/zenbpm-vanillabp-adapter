The adapter's repository holds complete example applications in
[`examples/`](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/tree/main/examples). Each one
tells a realistic business story, and comes twice: as a Spring Boot application and as a Quarkus
application, with the same BPMN model and the same REST API. They run against a standalone ZenBPM
engine, the one the adapter is tested against, and you drive them with `curl` while
[zenbpm-ui](https://github.com/pbinitiative/zenbpm-ui) shows what the engine does.

Where the [Blueprints](Blueprints) show VanillaBP's concepts one topic at a time, on every BPMS, an
example shows a whole use case on ZenBPM: its model, its workflow aggregate, its configuration and
its tests, all of them written for this adapter.

## The examples

|                                                        Example                                                         |                                                The story                                                |                         What it shows                          |
|------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------|----------------------------------------------------------------|
| [Purchase approval](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/tree/main/examples/purchase-approval)     | A clerk checks a purchase request; above 50 000 a manager has to approve it too.                        | user tasks answered through the REST API, gateways on booleans |
| [Card payment](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/tree/main/examples/card-payment)               | A web shop charges a card: declined, retried, or stopped by an outage an operator resolves.             | a BPMN error, retries, an incident resolved                    |
| [Bank transfer order](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/tree/main/examples/bank-transfer-order) | An order paid by bank transfer: shipped when the money arrives, reminded, or cancelled at the deadline. | a message correlated by the application, timers                |
| [Insurance claim](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/tree/main/examples/insurance-claim)         | A decision table routes a motor claim to an immediate payout, an adjuster or a rejection.               | a DMN decision table, a declared number                        |
| [Employee onboarding](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/tree/main/examples/employee-onboarding) | A new employee gets accounts, a workplace prepared by a reusable process, and a welcome meeting.        | a call activity, parallel branches, `@WorkflowEnded`           |

## What every example is

- **An application of its own.** It has no parent of the adapter's build, so its directory can be
  copied out and still builds. The Spring Boot twin inherits `spring-boot-starter-parent`, the
  Quarkus twin imports `quarkus-bom`, both in the versions the adapter is built with
  (`ExamplesTest#everyTwinUsesTheVersionsOfThisBuild`).
- **The same process twice.** Both twins deploy the same model files and workflow module
  (`ExamplesTest#theTwinsRunTheSameModels`), and the README of an example walks through both.
- **Configured as the page [Home](Home#minimal-configuration) says**, with `rest-address`,
  `name-clash-avoidance: use-prefix`, and `user-task-poll-interval: PT1S` where the example has
  user tasks, so a new user task reaches the application within a second.
- **Tested against the real engine.** The integration test of each twin starts the pinned engine
  in Docker and walks through the README's story via the REST API: for the purchase approval, for
  example, `PurchaseApprovalIT#aPurchaseAboveTheLimitIsOrderedOnlyOnceTheManagerApprovesIt`. The
  adapter's CI runs every one of them on each pull request
  (`ExamplesTest#everyCitedTestExistsInBothTwins`,
  `EnginePinTest#theExamplesNameThePin`).

## Running one

The
[README of `examples/`](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/blob/main/examples/README.md)
says what you need, how to start the engine and how to build. In short: install the adapter, start
the engine with the `docker run` of that README, which is the one of the page
[Home](Home#an-engine-to-try-it-against) with due timers looked for every second, and start a twin
with `mvn spring-boot:run` or `mvn quarkus:dev`. Each example's README tells its story, draws
its process and lists the `curl` calls.

# Examples

Complete applications which run a business process on a standalone ZenBPM engine through this
adapter. Each example tells one realistic story, and comes twice: as a Spring Boot application in
`springboot/` and as a Quarkus application in `quarkus/`. The two twins share the same BPMN model
and the same REST API, so the difference between them is only what the platform asks for.

|                   Example                    |                                                The story                                                |                                   What it shows                                    |
|----------------------------------------------|---------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------|
| [`purchase-approval`](purchase-approval)     | A clerk checks a purchase request; above 50 000 a manager has to approve it as well.                    | User tasks completed through the REST API, gateways deciding on the aggregate      |
| [`card-payment`](card-payment)               | A web shop charges a card: declined, retried, or stopped by an outage an operator resolves.             | A BPMN error on a boundary event, retries with a backoff, an incident              |
| [`bank-transfer-order`](bank-transfer-order) | An order paid by bank transfer: shipped when the money arrives, reminded, or cancelled at the deadline. | A message the application correlates, an interrupting and a non-interrupting timer |
| [`insurance-claim`](insurance-claim)         | A decision table routes a motor claim to an immediate payout, an adjuster or a rejection.               | A business rule task with a DMN table, a declared number, a user task              |

Each example directory is an application of its own, with no parent of the adapter's build: copy
it out and it still builds. Its README tells the story, draws the process, and walks through it
with `curl`.

## What you need

- Java 21 or newer and Maven
- Docker, for the engine
- the adapter in your local Maven repository. Nothing is released yet, so either build it once in
  the root of this repository:

  ```bash
  mvn -Dmaven.test.skip=true --projects spring-boot,quarkus/deployment --also-make install
  ```

  or read the snapshots from the adapter's GitHub Packages, as the README section
  [Using the snapshots](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/blob/main/README.md#using-the-snapshots)
  says. VanillaBP itself comes from the snapshot repository of Maven Central, which every example
  names.

## The engine

Every example talks to the engine the adapter is tested against, on the ports the examples are
configured for. Start it in a terminal of its own:

```bash
docker run --rm -p 18080:8080 -p 9090:9090 -e POLL_TIMER_DELAY_SECONDS=1 ghcr.io/pbinitiative/zenbpm@sha256:d7808e30821a7349e0f42476d8aaa21ff92fc752c0ccc432771764d307b87ed0
```

`POLL_TIMER_DELAY_SECONDS=1` makes the engine look for due timers every second instead of every
ten. Point an application at another engine with the environment variable `ZENBPM_REST_ADDRESS`,
for example `ZENBPM_REST_ADDRESS=http://zenbpm.example.com:8080`; the gRPC address follows on port
9090 of the same host.

To watch what happens, run [zenbpm-ui](https://github.com/pbinitiative/zenbpm-ui) against the
engine's REST address `http://localhost:18080`. The examples run their processes under the
workflow module's id as a prefix, so the process `purchase_approval` of the example
`purchase-approval` is `purchase-approval__purchase_approval` in the engine.

## Building and testing

```bash
mvn -f examples/pom.xml verify     # every example, both twins
cd examples/purchase-approval/springboot && mvn verify    # one twin
```

The integration test of a twin starts an engine of its own in Docker, from the same image, and
walks through the story of the example's README via its REST API. The adapter's CI runs every one
of them on each pull request.

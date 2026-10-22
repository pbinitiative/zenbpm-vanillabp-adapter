## VanillaBP adapter for ZenBPM

This adapter connects a [VanillaBP](https://www.vanillabp.io) business application to a
[ZenBPM](https://github.com/pbinitiative/zenbpm) engine without the business code depending on the
ZenBPM API. ZenBPM is a **remote** engine: the adapter talks to it over REST and over a gRPC job
stream, and the engine cannot join your database transaction. So VanillaBP's two-phase start and
its transaction outbox apply, as they do for Camunda 8.

Everything not specific to ZenBPM (workflow modules, workflow aggregates, the `@WorkflowTask`
contract, BPMS migration, the Spring Boot and Quarkus setup) is documented in the
**[VanillaBP wiki](https://github.com/vanillabp/adapter-platform-integration/wiki)**. Read that
first. This wiki only adds what a user of THIS adapter has to know:

1. [Getting started](#getting-started)<br>
   The dependencies per platform, an engine to try it against, and the smallest configuration
   which runs.
2. [Configuration](Configuration)<br>
   Every key below `vanillabp.adapters.<id>.*`, what your BPMN model has to look like, and what
   the start says about it.
3. [Deviations](Deviations)<br>
   Where this adapter does not deliver what VanillaBP promises, one sentence per gap of the engine.
4. [Engine APIs](Engine-APIs)<br>
   Every call this adapter makes against the engine and what it is for. Hand it to whoever puts a
   proxy in front of the engine.
5. [Native images](Native-images)<br>
   What it takes to build a Quarkus application with this adapter as a native image.
6. [What a handler may assume about its thread](#what-a-handler-may-assume-about-its-thread)<br>
   How many handlers run at once, the lock the engine holds for each, and the one case in which a
   handler runs twice.
7. [The engine this adapter is tested against](#the-engine-this-adapter-is-tested-against)<br>
   Tested, not newer.

## Getting started

Nothing is released yet. The snapshots of `main` are published as `2.0.0-SNAPSHOT` to the GitHub
Packages of the adapter's repository, which asks for a token even to read: the README's section
[Using the snapshots](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/blob/main/README.md#using-the-snapshots)
says how to add it to your Maven settings. The adapter needs Java 21 or newer.

### Spring Boot

One dependency. It brings the platform-neutral adapter core and VanillaBP's Spring Boot
integration with it (`DocumentationTest#theSpringBootDependencyBringsThePlatform`):

```xml
<dependency>
  <groupId>org.pbinitiative.zenbpm</groupId>
  <artifactId>zenbpm-vanillabp-adapter-spring-boot</artifactId>
  <version>2.0.0-SNAPSHOT</version>
</dependency>
```

### Quarkus

Both VanillaBP and the adapter are Quarkus extensions, and VanillaBP asks for its own extension in
the module which builds the application
([Quarkus integration](https://github.com/vanillabp/adapter-platform-integration/wiki/Quarkus-integration#add-the-required-dependencies-to-the-correct-mavengradle-modules)):

```xml
<dependency>
  <groupId>io.vanillabp</groupId>
  <artifactId>vanillabp-quarkus-integration</artifactId>
  <version>2.0.0-SNAPSHOT</version>
</dependency>
<dependency>
  <groupId>org.pbinitiative.zenbpm</groupId>
  <artifactId>zenbpm-vanillabp-adapter-quarkus</artifactId>
  <version>2.0.0-SNAPSHOT</version>
</dependency>
```

### An engine to try it against

One node of the engine, with its REST API on port 8080 and its job stream on port 9090, which are
the engine's defaults. The image is the one this adapter is tested against, see
[below](#the-engine-this-adapter-is-tested-against):

```bash
docker run --rm -p 8080:8080 -p 9090:9090 ghcr.io/pbinitiative/zenbpm@sha256:d7808e30821a7349e0f42476d8aaa21ff92fc752c0ccc432771764d307b87ed0
```

The engine keeps its data inside the container, so `--rm` gives you a fresh engine on every run.

### Minimal configuration

The adapter provides the VanillaBP adapter *type* `zenbpm`. If ZenBPM is the only BPMS of your
application, the adapter is derived from the classpath: its id is the type, `zenbpm`, and it is
the prioritized adapter. Your workflow modules are found by their descriptors, and their BPMN is
read from the
[conventional location](https://github.com/vanillabp/adapter-platform-integration/wiki/Workflow-modules#where-vanillabp-looks-for-bpmn-files)
`<workflow-module-id>/processes/zenbpm`. Two lines remain:

```yaml
vanillabp:
  adapters:
    zenbpm:
      rest-address: http://localhost:8080
      name-clash-avoidance: use-prefix
```

`rest-address` is where the engine's REST API answers; the job stream is found on the same host,
port 9090, unless `grpc-address` says otherwise
(`ZenBpmAdapterConfigurationTest#theGrpcAddressIsDerivedFromTheRestHost`). Without `rest-address`
an application with no BPMN file to deploy still boots, and the start warns naming the key; one
whose workflow modules bring BPMN files ends its start, naming the key, see
[Boot behavior](Configuration#boot-behavior).

**`name-clash-avoidance` has to be written.** VanillaBP keeps the workflow modules of an
application apart, and its default, `by-adapter`, asks the BPMS for an isolation of its own, such
as a tenant. ZenBPM has none, so the adapter refuses `by-adapter` while it deploys, and the start
of a workflow module with BPMN files ends with a message which names this line
(`ZenBpmStartupValidationBootTest#nothingConfiguredEndsTheStartNamingBothWaysOut`). Write
`use-prefix`, which puts the workflow module's id in front of every identifier the engine sees, or
`none`, which deploys the identifiers as they are, see
[Keeping workflow modules apart](Configuration#keeping-workflow-modules-apart). The default stays
`by-adapter` because every VanillaBP adapter has it, so an application moved here from another
BPMS chooses what its identifiers become instead of getting prefixes nobody chose
([decision 24](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/blob/main/DECISIONS.md#24-by-adapter-stays-the-default-and-is-refused-while-deploying)).

Give the adapter instance a **custom id** whenever the id is not the type, for example to run two
engines side by side. Then the type and the priority list are required
(`ZenBpmDistinctInstancesBootTest#oneEngineWithTwoModesBoots` boots two such ids):

```yaml
vanillabp:
  prioritized-adapters:
    - orders-engine
  adapters:
    orders-engine:
      type: zenbpm
      rest-address: http://zenbpm:8080
      name-clash-avoidance: use-prefix
```

Because ZenBPM is remote, the application also needs VanillaBP's **phase-two outbox**: a workflow
is started, a task completed and a message correlated only after your transaction committed. The
outbox works out of the box on JPA and JDBC and on MongoDB, see
[Configure the transaction outbox](https://github.com/vanillabp/adapter-platform-integration/wiki/Spring-Boot-integration#configure-the-transaction-outbox)
(on Quarkus,
[the same section](https://github.com/vanillabp/adapter-platform-integration/wiki/Quarkus-integration#configure-the-transaction-outbox)).
A start inside a transaction which rolls back starts nothing
(`ZenBpmDeploymentAndStartIT#noInstanceAfterRollback`).

What this section promises is booted by two tests against a fresh engine of the image above: they
read the configuration of this page from the page itself, start an application with it and run a
workflow through it, `ZenBpmHomePageIT` on Spring Boot and `ZenBpmHomePageTest` on Quarkus. The
tests set one key more, `grpc-address`, because the test containers publish the engine's port
9090 on a port of their own.

## What a handler may assume about its thread

ZenBPM hands work out over a gRPC job stream, and the adapter subscribes the job types of each
workflow module it starts. Every handler of one adapter id runs on the handler slots of that
adapter id. Your handlers live with what follows from that:

- a `@WorkflowTask` method owns one slot for as long as it runs, from the transaction VanillaBP
  opens to the commit;
- every job type of one adapter id draws from the same slots, across every workflow module the
  adapter id serves, so blocking inside a handler slows down work which has nothing to do with it
  (`ZenBpmJobDispatcherTest#moreJobsThanSlotsAllRun`);
- the engine hands an adapter id at most as many jobs of one type at once as it has slots, beside
  the tasks it keeps open for their asynchronous completion
  (`ZenBpmJobStreamTest#everySubscriptionCarriesItsSettings`), and a job which arrives while every
  slot is busy waits for one; the adapter's own timing, which renews the locks, never waits for a
  slot (`ZenBpmExecutorTest#aTimingThreadCannotWaitForASlot`,
  `ZenBpmExecutorTest#theTimingIsNotStarvedByBusyHandlers`);
- the number to size the slots against is the database connection pool, not the CPU: each running
  handler holds a connection for the length of its transaction.

The number is `vanillabp.adapters.<id>.worker-threads`, four platform threads by default, or
`virtual` with `worker-threads-bound` handlers at once
(`ZenBpmAdapterConfigurationTest#virtualWorkerThreadsAreAccepted`), see
[Timeouts and worker settings](Configuration#timeouts-and-worker-settings).

**The engine locks each job it hands out**, for the `job-timeout` of its task (`PT5M` by default):
while the lock lives, no other node of your application gets the job. The adapter renews the lock
for as long as your method runs, once half of it is left, so a handler may run as long as it needs
(`ZenBpmLockRenewalTest#aFullLockIsExtendedOnceHalfOfItIsLeft`,
`ZenBpmInboundIdempotencyIT#longHandlersKeepTheirLock`). A lock the adapter could not keep, because
the engine refused the renewal, is named in a WARN (`ZenBpmInboundIdempotencyIT#aLostLockIsNamed`).

**One case remains in which a handler runs twice.** The engine keeps its locks in the memory of
the node which leads the job's partition. When the leader changes, because that node restarted or
the cluster elected another one, the new leader knows no lock and hands every waiting job out again
at once ([GAPS 2](Deviations#gaps-2-a-new-leader-hands-every-job-out-again)). Where the first delivery
already committed, your method does not run again: VanillaBP answers the second delivery from its
record of deliveries, and the adapter sends the recorded outcome to the engine
(`ZenBpmRestartDeliveryIT#aTaskDeliveredBeforeARestartAndCompletedAfterItRunsOnce`). Where the
first delivery still runs, both run. A job is delivered at least once anyway, so a handler has to
cope with being called again, see
[a repeated delivery](https://github.com/vanillabp/adapter-platform-integration/wiki/Workflow-tasks#a-repeated-delivery-does-not-run-your-handler-again).
A single node of the engine has one leader, and it changes only when the node restarts; this case
cannot be reproduced by the adapter's tests, which run one node.

## The engine this adapter is tested against

The adapter is tested against exactly one build of the engine, and supports that one: tested, not
newer. No released engine version is supported yet, because no engine release carries the job lock
the task delivery is built on. The build is one of the engine's `main`:

- commit `bdd795a399b6484d062d52ac90c0d0725cb0ad91`,
- image `ghcr.io/pbinitiative/zenbpm@sha256:d7808e30821a7349e0f42476d8aaa21ff92fc752c0ccc432771764d307b87ed0`.

At startup the adapter asks the engine which commit it runs, and warns where it is another one
(`ZenBpmEnvironmentInfoTest#anotherCommitIsOneWarning`). A newer engine may work; nobody tested
it. The adapter's nightly build runs its tests against the engine's newest build as well, as an
early warning (`NightlyWorkflowTest`), and that is no promise either.

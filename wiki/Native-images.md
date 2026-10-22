A Quarkus application with this adapter can be built as a native image, and it needs nothing of its
own for that: no reflection configuration, no resource list, no build argument. What VanillaBP's
own extension does for a native image is on the platform's
[Native images](https://github.com/vanillabp/adapter-platform-integration/wiki/Native-images) page;
this page adds what the adapter brings.

## What is tested

A small application with one workflow is built as a native executable and started against the
engine this adapter is tested against. The binary deploys its workflow module, starts a workflow
through VanillaBP's two-phase start, serves the job of a first task, correlates the message the
workflow then waits for, serves the job of a second task and reports the end of the workflow to the
application's `@WorkflowEnded` method. The test accepts the run only when the binary exits with 0
and the engine shows the workflow completed (`ZenBpmNativeImageIT`). The adapter's nightly build
runs it every night.

That application has no Micrometer: the adapter's meters are tested in the JVM only, see
[What an operator gets to see](Configuration#what-an-operator-gets-to-see).

## The job stream on Quarkus

On Quarkus the job stream runs on `grpc-netty` over Quarkus' Netty extension, which the adapter's
extension brings, and not on the `grpc-netty-shaded` the adapter uses on Spring Boot: the shaded
Netty does not build into a native image
([decision 61](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/blob/main/DECISIONS.md#61-on-quarkus-the-job-stream-runs-on-grpc-netty-over-quarkus-netty-not-on-grpc-netty-shaded)).
So the job stream runs on the Netty of the Quarkus platform your application uses
(`QuarkusNettyTest`).

## What the extension registers

Each item was named by a native build which failed without it, and `ZenBpmNativeImageProcessorTest`
holds every one:

- the gRPC providers the job stream's channel finds through the service loader: the transport, the
  name resolver and the load balancer;
- the run-time initialization of the two classes of `grpc-netty` which hold a Netty buffer
  allocator;
- the records the REST client reads the engine's JSON into, and every class generated from the
  engine's proto, for reflection;
- VanillaBP's version descriptor, so that the check whether the adapter fits the platform can read
  the platform's version.

The adapter's own version descriptor and your workflow modules' BPMN files are registered by
VanillaBP's extension.

## What a build costs

The build of the tested application took about two minutes and 6 GB of memory at its peak, on a
laptop with 14 cores, in the builder container
`quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25` Quarkus picks where no GraalVM is
installed. The adapter's nightly build uses Mandrel for Java 25 installed on the runner, with
`-Dquarkus.native.container-build=false`. How the repository's own native build is run is in the
README's section
[Native image](https://github.com/pbinitiative/zenbpm-vanillabp-adapter/blob/main/README.md#native-image).

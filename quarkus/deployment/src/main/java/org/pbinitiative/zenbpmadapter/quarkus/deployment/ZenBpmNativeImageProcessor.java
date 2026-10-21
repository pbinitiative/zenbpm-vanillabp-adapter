/*
 * Copyright 2026 Phactum Softwareentwicklung GmbH
 * Adapted for zenbpm-vanillabp-adapter from vanillabp/camunda8-adapter, see NOTICE.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.pbinitiative.zenbpmadapter.quarkus.deployment;

import java.util.List;
import java.util.ServiceLoader;

import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;

import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.IndexDependencyBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageResourceBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.deployment.builditem.nativeimage.RuntimeInitializedClassBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ServiceProviderBuildItem;
import io.vanillabp.integration.spi.parts.VanillaBpParts;

/**
 * What the ZenBPM adapter needs in a native image, registered here so that no application has to
 * find it out from a stack trace.
 * <p>
 * On the JVM a library finds what it needs by itself: a class by its name, an implementation named
 * in a service file, a resource of another jar. A native image holds only what was named while it
 * was built. Every registration below was named by a native build of
 * <code>quarkus/native-image-tests</code> which failed, or whose binary failed, without it; the
 * comment on each says how. Only a native build reads these registrations; a build in JVM mode
 * ignores them.
 * <p>
 * The job stream runs on <code>grpc-netty</code> over Quarkus' own Netty extension here, not on
 * <code>grpc-netty-shaded</code> as on Spring Boot, see decision 61 in the repository's
 * DECISIONS.md: the Netty extension answers what Netty itself needs in a native image, and what is
 * left is gRPC's.
 */
class ZenBpmNativeImageProcessor {

    /**
     * The package of the classes generated from the engine's proto, which holds nothing else.
     */
    static final String GENERATED_PACKAGE = "org.pbinitiative.zenbpmadapter.client.grpc";

    /**
     * The class whose nested records are the JSON of the engine's REST API: the only types the REST
     * client hands to Jackson besides maps and lists.
     */
    static final String REST_WIRE = "org.pbinitiative.zenbpmadapter.client.ZenBpmWire";

    /**
     * The gRPC extension points the job stream's channel finds through the {@link ServiceLoader}:
     * the transport, the resolver of the engine's address and the load balancer. Quarkus switches
     * off GraalVM's own registration of service providers, so without these the boot ended with
     * <code>No functional channel service provider found</code> when the adapter built the channel
     * of its first adapter id.
     */
    static final List<String> GRPC_PROVIDERS = List
            .of("io.grpc.ManagedChannelProvider", "io.grpc.NameResolverProvider", "io.grpc.LoadBalancerProvider");

    /**
     * Classes of <code>grpc-netty</code> which must not be initialized while the image is built.
     * Each holds one of Netty's buffer allocators in a static field, and Quarkus' Netty extension
     * initializes the allocators at run time, so the image build refused them: <code>An object of
     * type 'io.netty.buffer.PooledByteBufAllocator' was found in the image heap</code>, reached
     * through these two holders.
     */
    static final List<String> INITIALIZED_AT_RUN_TIME = List
            .of("io.grpc.netty.Utils$ByteBufAllocatorPreferDirectHolder",
                    "io.grpc.netty.Utils$ByteBufAllocatorPreferHeapHolder");

    /**
     * The artifact which holds the generated classes and the records of the REST client. A build
     * step sees its classes only once they are in the Jandex index, which
     * {@link IndexDependencyBuildItem} asks for.
     */
    private static final String CORE_GROUP_ID = "org.pbinitiative.zenbpm";

    private static final String CORE_ARTIFACT_ID = "zenbpm-vanillabp-adapter";

    /**
     * Quarkus builds the processor while it builds the application, to run the steps below.
     */
    ZenBpmNativeImageProcessor() {
    }

    /**
     * Indexes the adapter's core, so the steps below find the generated classes and the records of
     * the REST client.
     *
     * @return The index request for the core
     */
    @BuildStep
    IndexDependencyBuildItem theCoreIsIndexed() {
        return new IndexDependencyBuildItem(CORE_GROUP_ID, CORE_ARTIFACT_ID);
    }

    /**
     * Registers every class generated from the engine's proto for reflection: the messages, their
     * builders and the enums, taken from the index rather than written down, because which classes
     * <code>protoc</code> writes follows the proto.
     * <p>
     * The job stream itself does not need this: the native binary runs its workflow without it,
     * because gRPC reads and writes a message through its generated parser. Protobuf reads the
     * accessors of a message's fields by name wherever it treats the message generically: its
     * <code>toString()</code>, <code>equals</code> and <code>hashCode</code> go through the
     * message's descriptor. A log line or a collection holding a message reaches that, and in a
     * native image it would fail where the JVM never does.
     *
     * @param index The combined Jandex index, which holds the core because of the step above
     * @param reflectiveClasses Where the registrations go
     */
    @BuildStep
    void theGeneratedJobStreamClassesAreReadByReflection(
        final CombinedIndexBuildItem index,
        final BuildProducer<ReflectiveClassBuildItem> reflectiveClasses
    ) {
        final var generatedClasses = index
                .getIndex()
                .getKnownClasses()
                .stream()
                .map(ClassInfo::name)
                .filter(name -> GENERATED_PACKAGE.equals(name.packagePrefix()))
                .map(DotName::toString)
                .sorted()
                .toArray(String[]::new);
        reflectiveClasses.produce(ReflectiveClassBuildItem
                .builder(generatedClasses)
                .constructors()
                .methods()
                .fields()
                .reason("protobuf reads the generated classes of the ZenBPM job stream by reflection")
                .build());
    }

    /**
     * Registers the records Jackson reads the engine's REST answers into and writes its requests
     * from, taken from the index rather than written down, so that a record added for the next call
     * of the API is registered with it. Without them the binary waited for the engine to report
     * ready until it gave up, because the first answer, the one of
     * <code>GET /system/health/ready</code>, "cannot be read".
     *
     * @param index The combined Jandex index, which holds the core because of the step above
     * @param reflectiveClasses Where the registrations go
     */
    @BuildStep
    void theRestClientsJsonIsReadByReflection(
        final CombinedIndexBuildItem index,
        final BuildProducer<ReflectiveClassBuildItem> reflectiveClasses
    ) {
        final var wireRecords = index
                .getIndex()
                .getKnownClasses()
                .stream()
                .map(ClassInfo::name)
                .map(DotName::toString)
                .filter(name -> name.startsWith(REST_WIRE
                        + "$"))
                .sorted()
                .toArray(String[]::new);
        reflectiveClasses.produce(ReflectiveClassBuildItem
                .builder(wireRecords)
                .constructors()
                .methods()
                .fields()
                .reason("Jackson reads and writes the JSON of the ZenBPM REST API through these records")
                .build());
    }

    /**
     * Registers the gRPC providers the job stream's channel looks up through the service loader,
     * taken from the classpath rather than written down: which implementation serves an extension
     * point is up to the gRPC release the application runs.
     *
     * @param services Where the registrations go
     */
    @BuildStep
    void theJobStreamFindsItsGrpcProviders(final BuildProducer<ServiceProviderBuildItem> services) {
        GRPC_PROVIDERS
                .forEach(provider -> services.produce(ServiceProviderBuildItem.allProvidersFromClassPath(provider)));
    }

    /**
     * Moves the initialization of the classes named in {@link #INITIALIZED_AT_RUN_TIME} out of the
     * image build.
     *
     * @param classes Where the registrations go
     */
    @BuildStep
    void theAllocatorHoldersAreInitializedAtRunTime(final BuildProducer<RuntimeInitializedClassBuildItem> classes) {
        INITIALIZED_AT_RUN_TIME.forEach(name -> classes.produce(new RuntimeInitializedClassBuildItem(name)));
    }

    /**
     * Puts the platform's own version descriptor into the image. The adapter checks at every start
     * that it and the platform integration belong together, and reads the platform's version from
     * this file. The platform registers the descriptors it finds in the application's archives, and
     * the jar which carries its own is none of them, so without this the binary warned that
     * "unknown is on the classpath" and could not tell a platform the adapter does not fit.
     *
     * @return The resource registration
     */
    @BuildStep
    NativeImageResourceBuildItem thePlatformsVersionIsInTheImage() {
        return new NativeImageResourceBuildItem(VanillaBpParts.PLATFORM_DESCRIPTOR);
    }

}

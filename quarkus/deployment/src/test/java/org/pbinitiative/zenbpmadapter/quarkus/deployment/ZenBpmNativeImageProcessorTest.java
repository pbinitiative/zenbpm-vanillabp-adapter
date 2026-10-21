/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.deployment;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.jboss.jandex.Index;
import org.jboss.jandex.Indexer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pbinitiative.zenbpmadapter.ZenBpmAdapter;
import org.pbinitiative.zenbpmadapter.client.grpc.ZenBpmGrpc;

import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.deployment.builditem.nativeimage.RuntimeInitializedClassBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ServiceProviderBuildItem;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What the native image of an application is told about the adapter: every class generated from the
 * engine's proto and every record of the REST client is open to reflection, and nothing else of the
 * core is; the gRPC providers of the classpath are registered; the holders of gRPC's buffer
 * allocators are initialized at run time; the platform's version descriptor is in the image. A
 * native build itself is not run here, <code>quarkus/native-image-tests</code> does that.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmNativeImageProcessorTest {

    private static final String CORE_GROUP_ID = "org.pbinitiative.zenbpm";

    private static final String CORE_ARTIFACT_ID = "zenbpm-vanillabp-adapter";

    @Test
    @DisplayName("The core is indexed by the coordinates it is published under")
    void theCoreIsIndexed() {
        final var request = new ZenBpmNativeImageProcessor().theCoreIsIndexed();

        assertAll(
                () -> assertEquals(CORE_GROUP_ID, request.getGroupId()),
                () -> assertEquals(CORE_ARTIFACT_ID, request.getArtifactId()));
    }

    @Test
    @DisplayName("Every generated class of the job stream is open to reflection, with its constructors, methods and fields")
    void everyGeneratedClassIsOpenToReflection() throws IOException, URISyntaxException {
        final var core = indexOfTheCore();
        final var generated = core
                .getKnownClasses()
                .stream()
                .map(type -> type
                        .name()
                        .toString())
                .filter(name -> name.startsWith(ZenBpmNativeImageProcessor.GENERATED_PACKAGE
                        + "."))
                .sorted()
                .toList();
        final var registrations = new ArrayList<ReflectiveClassBuildItem>();
        final BuildProducer<ReflectiveClassBuildItem> producer = registrations::add;

        new ZenBpmNativeImageProcessor().theGeneratedJobStreamClassesAreReadByReflection(new CombinedIndexBuildItem(
                core, core), producer);

        final var registration = registrations.getFirst();
        assertAll(
                () -> assertEquals(1, registrations.size()),
                () -> assertTrue(generated.contains(ZenBpmGrpc.class.getName()), generated::toString),
                () -> assertTrue(generated.size() > 1, generated::toString),
                () -> assertEquals(generated, List.copyOf(registration.getClassNames())),
                () -> assertTrue(registration.isConstructors()),
                () -> assertTrue(registration.isMethods()),
                () -> assertTrue(registration.isFields()));
    }

    @Test
    @DisplayName("Every record of the REST client is open to reflection, with its constructors, methods and fields")
    void everyRecordOfTheRestClientIsOpenToReflection() throws IOException, URISyntaxException {
        final var core = indexOfTheCore();
        final var records = core
                .getKnownClasses()
                .stream()
                .map(type -> type
                        .name()
                        .toString())
                .filter(name -> name.startsWith(ZenBpmNativeImageProcessor.REST_WIRE
                        + "$"))
                .sorted()
                .toList();
        final var registrations = new ArrayList<ReflectiveClassBuildItem>();
        final BuildProducer<ReflectiveClassBuildItem> producer = registrations::add;

        new ZenBpmNativeImageProcessor().theRestClientsJsonIsReadByReflection(new CombinedIndexBuildItem(core, core),
                producer);

        final var registration = registrations.getFirst();
        assertAll(
                () -> assertEquals(1, registrations.size()),
                () -> assertTrue(records.contains("%s$Health".formatted(ZenBpmNativeImageProcessor.REST_WIRE)),
                        records::toString),
                () -> assertTrue(
                        records.contains("%s$CreateProcessInstance".formatted(ZenBpmNativeImageProcessor.REST_WIRE)),
                        records::toString),
                () -> assertEquals(records, List.copyOf(registration.getClassNames())),
                () -> assertTrue(registration.isConstructors()),
                () -> assertTrue(registration.isMethods()),
                () -> assertTrue(registration.isFields()));
    }

    @Test
    @DisplayName("The gRPC providers come from grpc-netty, and none from the shaded transport")
    void theGrpcProvidersComeFromGrpcNetty() {
        final var registrations = new ArrayList<ServiceProviderBuildItem>();
        final BuildProducer<ServiceProviderBuildItem> producer = registrations::add;

        new ZenBpmNativeImageProcessor().theJobStreamFindsItsGrpcProviders(producer);

        final var providers = registrations
                .stream()
                .collect(Collectors.toMap(ZenBpmNativeImageProcessorTest::serviceOf,
                        ServiceProviderBuildItem::providers));
        assertAll(
                () -> assertEquals(ZenBpmNativeImageProcessor.GRPC_PROVIDERS, registrations
                        .stream()
                        .map(ZenBpmNativeImageProcessorTest::serviceOf)
                        .toList()),
                () -> assertTrue(providers
                        .get("io.grpc.ManagedChannelProvider")
                        .contains("io.grpc.netty.NettyChannelProvider"), providers::toString),
                () -> assertTrue(providers
                        .get("io.grpc.NameResolverProvider")
                        .contains("io.grpc.internal.DnsNameResolverProvider"), providers::toString),
                () -> assertTrue(providers
                        .get("io.grpc.LoadBalancerProvider")
                        .contains("io.grpc.internal.PickFirstLoadBalancerProvider"), providers::toString),
                () -> assertTrue(providers
                        .values()
                        .stream()
                        .flatMap(List::stream)
                        .noneMatch(provider -> provider.startsWith("io.grpc.netty.shaded.")), providers::toString));
    }

    @Test
    @DisplayName("The two holders of gRPC's buffer allocators are initialized at run time")
    void theAllocatorHoldersAreInitializedAtRunTime() {
        final var registrations = new ArrayList<RuntimeInitializedClassBuildItem>();
        final BuildProducer<RuntimeInitializedClassBuildItem> producer = registrations::add;

        new ZenBpmNativeImageProcessor().theAllocatorHoldersAreInitializedAtRunTime(producer);

        assertEquals(
                List.of("io.grpc.netty.Utils$ByteBufAllocatorPreferDirectHolder",
                        "io.grpc.netty.Utils$ByteBufAllocatorPreferHeapHolder"),
                registrations
                        .stream()
                        .map(RuntimeInitializedClassBuildItem::getClassName)
                        .toList());
    }

    @Test
    @DisplayName("The platform's version descriptor is put into the image")
    void thePlatformsVersionIsInTheImage() {
        final var registration = new ZenBpmNativeImageProcessor().thePlatformsVersionIsInTheImage();

        assertEquals(List.of("META-INF/vanillabp/platform-version.properties"), registration.getResources());
    }

    // the classes of the core, generated or not, as the index of an application holds them
    private static Index indexOfTheCore() throws IOException, URISyntaxException {
        final var indexer = new Indexer();
        final var location = Path.of(ZenBpmAdapter.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());
        if (Files.isDirectory(location)) {
            indexClasses(indexer, location);
        } else {
            try (var jar = FileSystems.newFileSystem(location, Map.of())) {
                indexClasses(indexer, jar.getPath("/"));
            }
        }
        return indexer.complete();
    }

    private static void indexClasses(
        final Indexer indexer,
        final Path root
    ) throws IOException {
        try (var files = Files.walk(root)) {
            for (final var file : files
                    .filter(path -> path
                            .toString()
                            .endsWith(".class"))
                    .toList()) {
                try (var bytes = Files.newInputStream(file)) {
                    indexer.index(bytes);
                }
            }
        }
    }

    private static String serviceOf(final ServiceProviderBuildItem registration) {
        return registration
                .serviceDescriptorFile()
                .substring(ServiceProviderBuildItem.SPI_ROOT.length());
    }

}

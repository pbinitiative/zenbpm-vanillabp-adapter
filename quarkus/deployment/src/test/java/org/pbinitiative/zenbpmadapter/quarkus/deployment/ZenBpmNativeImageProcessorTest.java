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
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What the native image of an application is told about the job stream: every class generated from
 * the engine's proto is open to reflection, and nothing else of the core is. A native build itself
 * is not run here.
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

}

/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.coverage;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.CoverageGate;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The README promises that the tests which start the engine are skipped without a Docker
 * environment and the build stays green. A class which starts the engine on its own, outside the
 * Testcontainers extension, fails instead, and nobody who has Docker sees it, so the promise is
 * checked here and not by a build without Docker.
 */
@ExtendWith(SuppressOutputExtension.class)
class EngineTestsWithoutDockerTest {

    private static final Path ROOT = CoverageGate.repositoryRoot("coverage.repository.root");

    private static final String SKIPPED_WITHOUT_DOCKER = "@Testcontainers(disabledWithoutDocker = true)";

    @Test
    @DisplayName("Every test class which starts the engine is skipped without Docker")
    void everyTestClassStartingTheEngineIsSkippedWithoutDocker() {
        final var offenders = testClassesStartingTheEngine()
                .stream()
                .filter(file -> !read(file).contains(SKIPPED_WITHOUT_DOCKER))
                .map(EngineTestsWithoutDockerTest::nameOf)
                .toList();

        assertTrue(offenders.isEmpty(), () -> """
                Without a Docker environment the tests which start the engine are skipped, and the \
                build stays green, as the README promises. Annotate each class below with %s, after \
                '@ExtendWith(SuppressOutputExtension.class)', and start nothing which needs the \
                container where no Docker environment is there:
                %s""".formatted(SKIPPED_WITHOUT_DOCKER, String.join("\n", offenders)));
    }

    @Test
    @DisplayName("The check reads the test classes of this repository which start the engine, so it cannot pass by reading none")
    void theCheckReadsTestClasses() {
        final var names = testClassesStartingTheEngine()
                .stream()
                .map(EngineTestsWithoutDockerTest::nameOf)
                .toList();

        assertTrue(names.size() > 10, () -> "only %d test classes starting the engine found below %s: %s"
                .formatted(names.size(), ROOT, names));
        assertTrue(names.contains(
                "quarkus/integration-tests/src/test/java/org/pbinitiative/zenbpmadapter/quarkus/it/ZenBpmWorkflowLifecycleTest.java"),
                names::toString);
    }

    private static List<Path> testClassesStartingTheEngine() {
        try (var paths = Files.walk(ROOT)) {
            return paths
                    .filter(path -> path
                            .toString()
                            .endsWith(".java"))
                    .filter(path -> nameOf(path).contains("/src/test/java/"))
                    .filter(path -> !nameOf(path).contains("/target/"))
                    .filter(path -> read(path).contains("new EngineUnderTest("))
                    .sorted()
                    .toList();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String nameOf(final Path file) {
        return ROOT
                .relativize(file)
                .toString()
                .replace('\\', '/');
    }

    private static String read(final Path file) {
        try {
            return Files.readString(file);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

}

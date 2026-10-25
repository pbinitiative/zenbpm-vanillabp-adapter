/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.coverage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import io.vanillabp.integration.test.utils.CoverageGate;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The gate's own failure paths, held against small repositories written into a temporary
 * directory. The real gate only ever sees this repository in its current, correct state, so
 * without these the cases it exists for would never run.
 */
@ExtendWith(SuppressOutputExtension.class)
class ProductionModulesTest {

    private static final String CSV_HEADER = """
            GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,INSTRUCTION_COVERED,BRANCH_MISSED,BRANCH_COVERED,\
            LINE_MISSED,LINE_COVERED,COMPLEXITY_MISSED,COMPLEXITY_COVERED,METHOD_MISSED,METHOD_COVERED
            """;

    @TempDir
    Path root;

    @Test
    @DisplayName("A repository whose reports read every production module passes")
    void aCompleteRepositoryPasses() throws IOException {
        module("core", "zenbpm-vanillabp-adapter", true);
        module("spring-boot", "zenbpm-vanillabp-adapter-spring-boot", true);
        module("quarkus/runtime", "zenbpm-vanillabp-adapter-quarkus", true);
        final var springBoot = report(
                "spring-boot",
                "zenbpm-vanillabp-adapter",
                "zenbpm-vanillabp-adapter-spring-boot");
        final var quarkus = report("quarkus", "zenbpm-vanillabp-adapter", "zenbpm-vanillabp-adapter-quarkus");

        assertEquals(
                List.of(),
                ProductionModules.missingFromTheReportOfTheirPlatform(root, springBoot, quarkus, Set.of()));
    }

    @Test
    @DisplayName("A core left out of one report is named with that report, whether it has tests or not")
    void aCoreLeftOutOfOneReportIsNamed() throws IOException {
        module("core", "zenbpm-vanillabp-adapter", true);
        final var springBoot = report("spring-boot");
        final var quarkus = report("quarkus", "zenbpm-vanillabp-adapter");

        assertEquals(
                List.of("zenbpm-vanillabp-adapter (in 'core'): not read by the Spring Boot report"),
                ProductionModules.missingFromTheReportOfTheirPlatform(root, springBoot, quarkus, Set.of()));
    }

    @Test
    @DisplayName("A module the layout gives no platform fails until it is a named exception")
    void aModuleWithoutAPlatformNeedsADecision() throws IOException {
        module("engine-test-support", "zenbpm-vanillabp-adapter-engine-test-support", true);
        final var springBoot = report("spring-boot");
        final var quarkus = report("quarkus");

        assertEquals(
                List
                        .of(
                                "zenbpm-vanillabp-adapter-engine-test-support (in 'engine-test-support'): no rule "
                                        + "says which platform runs it"),
                ProductionModules.missingFromTheReportOfTheirPlatform(root, springBoot, quarkus, Set.of()));
        assertEquals(
                List.of(),
                ProductionModules
                        .missingFromTheReportOfTheirPlatform(
                                root,
                                springBoot,
                                quarkus,
                                Set.of("zenbpm-vanillabp-adapter-engine-test-support")));
    }

    @Test
    @DisplayName("A module under quarkus/ which no report reads fails until it is a named exception")
    void aQuarkusModuleOutsideTheReportNeedsADecision() throws IOException {
        module("quarkus/native-image-tests", "zenbpm-vanillabp-adapter-quarkus-native-image-tests", true);
        final var springBoot = report("spring-boot");
        final var quarkus = report("quarkus");

        assertEquals(
                List
                        .of(
                                "zenbpm-vanillabp-adapter-quarkus-native-image-tests (in 'quarkus/native-image-tests'): "
                                        + "not read by the Quarkus report"),
                ProductionModules.missingFromTheReportOfTheirPlatform(root, springBoot, quarkus, Set.of()));
        assertEquals(
                List.of(),
                ProductionModules
                        .missingFromTheReportOfTheirPlatform(
                                root,
                                springBoot,
                                quarkus,
                                Set.of("zenbpm-vanillabp-adapter-quarkus-native-image-tests")));
    }

    @Test
    @DisplayName("The modules with production sources are named by their artifact ids, wherever they live")
    void theModulesWithProductionSourcesAreNamed() throws IOException {
        module("engine-test-support", "zenbpm-vanillabp-adapter-engine-test-support", true);
        module("quarkus/native-image-tests", "zenbpm-vanillabp-adapter-quarkus-native-image-tests", true);
        module("smoke-test", "zenbpm-vanillabp-adapter-smoke-test", false);

        assertEquals(
                List
                        .of(
                                "zenbpm-vanillabp-adapter-engine-test-support",
                                "zenbpm-vanillabp-adapter-quarkus-native-image-tests"),
                ProductionModules.artifactIdsWithProductionSources(root));
    }

    @Test
    @DisplayName("An example application below examples/ is no module of the adapter and is not asked for")
    void theExamplesAreNotAskedFor() throws IOException {
        module("examples/purchase-approval/springboot", "purchase-approval-springboot", true);
        module("spring-boot/examples/a-module", "a-module-named-examples-elsewhere", true);
        final var springBoot = report("spring-boot", "a-module-named-examples-elsewhere");
        final var quarkus = report("quarkus");

        assertEquals(
                List.of(),
                ProductionModules.missingFromTheReportOfTheirPlatform(root, springBoot, quarkus, Set.of()));
        assertEquals(List.of("a-module-named-examples-elsewhere"), ProductionModules.artifactIdsWithProductionSources(
                root));
    }

    @Test
    @DisplayName("Modules without production sources and anything under target are not asked for")
    void testOnlyModulesAndBuildOutputAreNotAskedFor() throws IOException {
        module("smoke-test", "zenbpm-vanillabp-adapter-smoke-test", false);
        module("core/target/copy", "a-copy-in-the-build-output", true);
        final var springBoot = report("spring-boot");
        final var quarkus = report("quarkus");

        assertEquals(
                List.of(),
                ProductionModules.missingFromTheReportOfTheirPlatform(root, springBoot, quarkus, Set.of()));
    }

    @Test
    @DisplayName("A report holding only its header has no instruction to measure")
    void aHeaderOnlyReportHoldsNoInstruction() throws IOException {
        final var coverage = CoverageGate.read(csv(CSV_HEADER), "Spring Boot", CoverageGate.Metric.INSTRUCTIONS);

        assertEquals(0, coverage.missed() + coverage.covered());
    }

    @Test
    @DisplayName("Untested code is measured as missed and lands below any threshold")
    void untestedCodeIsMissed() throws IOException {
        final var coverage = CoverageGate
                .read(
                        csv(CSV_HEADER
                                + "core,org.pbinitiative.zenbpmadapter,Probe,2,0,0,0,1,0,1,0,1,0\n"),
                        "Spring Boot",
                        CoverageGate.Metric.INSTRUCTIONS);

        assertEquals(2, coverage.missed());
        assertTrue(coverage.percentage() < 85);
    }

    @Test
    @DisplayName("A report which was never written fails the gate instead of passing it")
    void aMissingReportFails() {
        final var thrown = assertThrows(
                IllegalStateException.class,
                () -> CoverageGate.read(root.resolve("jacoco.csv"), "Quarkus", CoverageGate.Metric.INSTRUCTIONS));

        assertTrue(thrown
                .getMessage()
                .contains("mvn install"));
    }

    private void module(
        final String location,
        final String artifactId,
        final boolean withProductionSources
    ) throws IOException {
        final var module = root.resolve(location);
        Files.createDirectories(module);
        Files.writeString(module.resolve("pom.xml"), """
                <project>
                  <parent>
                    <artifactId>zenbpm-vanillabp-adapter-parent</artifactId>
                  </parent>
                  <!-- <artifactId>not-this-one</artifactId> -->
                  <artifactId>%s</artifactId>
                </project>
                """.formatted(artifactId));
        if (withProductionSources) {
            Files.createDirectories(module.resolve("src/main/java"));
        }
    }

    private ProductionModules.Report report(
        final String platform,
        final String... aggregated
    ) throws IOException {
        final var dependencies = new StringBuilder();
        for (final var artifactId : aggregated) {
            dependencies.append("<dependency><artifactId>%s</artifactId></dependency>".formatted(artifactId));
        }
        final var pom = root.resolve("test-coverage-report/%s/pom.xml".formatted(platform));
        Files.createDirectories(pom.getParent());
        Files.writeString(pom, """
                <project>
                  <artifactId>%s-report</artifactId>
                  <dependencies>%s</dependencies>
                  <build><plugins><plugin><artifactId>jacoco-maven-plugin</artifactId></plugin></plugins></build>
                </project>
                """.formatted(platform, dependencies));
        return new ProductionModules.Report(platform.equals("quarkus") ? "Quarkus" : "Spring Boot", pom);
    }

    private Path csv(final String content) throws IOException {
        return Files.writeString(root.resolve("jacoco.csv"), content);
    }

}

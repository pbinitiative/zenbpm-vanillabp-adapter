/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.coverage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.CoverageGate;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The examples of <code>examples/</code> are applications of their own, built by
 * <code>examples/pom.xml</code> and not by this build, see decision 67 in the repository's
 * DECISIONS.md. Nothing in this build compiles them, so this test holds them to what they promise.
 * Every example comes for both platforms. The two twins run the same models with the same VanillaBP
 * configuration on the same port. Each twin uses the platform versions and the adapter version of
 * this build. And every test an example's README or its <code>requests.http</code> names is a test
 * both twins have.
 */
@ExtendWith(SuppressOutputExtension.class)
class ExamplesTest {

    private static final Path ROOT = CoverageGate.repositoryRoot("coverage.repository.root");

    private static final Path EXAMPLES = ROOT.resolve("examples");

    private static final List<String> PLATFORMS = List.of("springboot", "quarkus");

    private static final Pattern MODULE = Pattern.compile("<module>([^<]+)</module>");

    /** The top-level key <code>vanillabp:</code> of a YAML file, up to the next top-level key. */
    private static final Pattern VANILLABP_CONFIGURATION = Pattern.compile("(?ms)^vanillabp:$.*?(?=^[A-Za-z]|\\z)");

    /** Where the requests of <code>requests.http</code> go: <code>@app = http://localhost:8101</code>. */
    private static final Pattern PORT_OF_THE_REQUESTS = Pattern.compile("(?m)^@app = http://localhost:(\\d+)$");

    /** The port of a Spring Boot twin, the key <code>server.port</code>. */
    private static final Pattern PORT_OF_SPRING_BOOT = Pattern.compile("(?m)^server:\\n  port: (\\d+)$");

    /** The port of a Quarkus twin, the key <code>quarkus.http.port</code>. */
    private static final Pattern PORT_OF_QUARKUS = Pattern.compile("(?m)^  http:\\n    port: (\\d+)$");

    /** The files of an example which cite the tests holding what they say. */
    private static final List<String> CITING_FILES = List.of("README.md", "requests.http");

    /** A test the README cites, as <code>PurchaseApprovalIT#aSmallPurchaseIsOrdered</code>. */
    private static final Pattern CITED_TEST = Pattern.compile("\\b([A-Z]\\w*IT)#(\\w+)");

    @Test
    @DisplayName("Every example comes for Spring Boot and for Quarkus, with a README and a requests.http, and the build of the examples builds every twin")
    void everyExampleComesForBothPlatforms() throws IOException {
        final var examples = examples();
        assertFalse(examples.isEmpty(), "examples/ holds no example");
        final var twins = new TreeSet<String>();
        for (final var example : examples) {
            for (final var file : CITING_FILES) {
                assertTrue(Files.isRegularFile(example.resolve(file)), () -> "%s has no %s".formatted(ROOT
                        .relativize(example), file));
            }
            for (final var platform : PLATFORMS) {
                assertTrue(Files.isRegularFile(example.resolve(platform).resolve("pom.xml")), () -> """
                        %s has no twin for %s: every example is an application on Spring Boot and one \
                        on Quarkus.""".formatted(ROOT.relativize(example), platform));
                twins.add("%s/%s".formatted(example.getFileName(), platform));
            }
        }

        assertEquals(twins, modulesOf(EXAMPLES.resolve("pom.xml")),
                "examples/pom.xml has to name every twin of every example, and nothing else");
    }

    @Test
    @DisplayName("The two twins of an example deploy the same models and the same workflow module")
    void theTwinsRunTheSameModels() throws IOException {
        for (final var example : examples()) {
            final var springBoot = example.resolve("springboot/src/main/resources");
            final var quarkus = example.resolve("quarkus/src/main/resources");
            final var models = models(springBoot);
            assertFalse(models.isEmpty(), () -> "%s has no model".formatted(ROOT.relativize(springBoot)));

            assertEquals(models, models(quarkus), () -> "the twins of %s deploy different files"
                    .formatted(ROOT.relativize(example)));
            for (final var model : models) {
                assertArrayEquals(Files.readAllBytes(springBoot.resolve(model)), Files.readAllBytes(quarkus.resolve(
                        model)), () -> "the twins of %s differ in %s".formatted(ROOT.relativize(example), model));
            }
        }
    }

    @Test
    @DisplayName("The two twins of an example configure VanillaBP and the adapter alike")
    void theTwinsConfigureVanillaBpAlike() throws IOException {
        for (final var example : examples()) {
            final var place = ROOT.relativize(example);

            assertEquals(vanillaBpConfiguration(example.resolve("springboot")), vanillaBpConfiguration(example
                    .resolve("quarkus")),
                    () -> "the twins of %s differ under vanillabp: in application.yaml"
                            .formatted(place));
        }
    }

    @Test
    @DisplayName("Both twins of an example answer on the port its requests.http sends to")
    void theRequestsReachTheExamplesPort() throws IOException {
        for (final var example : examples()) {
            final var place = ROOT.relativize(example);
            final var port = port(example.resolve("requests.http"), PORT_OF_THE_REQUESTS);

            assertEquals(port, port(example.resolve("springboot/src/main/resources/application.yaml"),
                    PORT_OF_SPRING_BOOT),
                    () -> "the Spring Boot twin of %s answers on another port than %s"
                            .formatted(place, port));
            assertEquals(port, port(example.resolve("quarkus/src/main/resources/application.yaml"),
                    PORT_OF_QUARKUS),
                    () -> "the Quarkus twin of %s answers on another port than %s".formatted(
                            place, port));
        }
    }

    @Test
    @DisplayName("Every twin uses the platform versions and the adapter version of this build")
    void everyTwinUsesTheVersionsOfThisBuild() throws IOException {
        final var root = Files.readString(ROOT.resolve("pom.xml"));
        final var revision = property(root, "revision");
        final var springBootVersion = property(root, "springboot.version");
        final var quarkusVersion = property(root, "quarkus.version");
        final var vanillaBpVersion = property(root, "adapter-platform.version");
        for (final var example : examples()) {
            final var springBoot = Files.readString(example.resolve("springboot/pom.xml"));
            final var quarkus = Files.readString(example.resolve("quarkus/pom.xml"));
            final var place = ROOT.relativize(example);

            assertEquals(revision, property(springBoot, "zenbpm-vanillabp-adapter.version"),
                    () -> "the Spring Boot twin of %s uses another adapter".formatted(place));
            assertEquals(revision, property(quarkus, "zenbpm-vanillabp-adapter.version"),
                    () -> "the Quarkus twin of %s uses another adapter".formatted(place));
            assertTrue(Pattern
                    .compile("<artifactId>spring-boot-starter-parent</artifactId>\\s*<version>%s</version>"
                            .formatted(Pattern.quote(springBootVersion)))
                    .matcher(springBoot)
                    .find(),
                    () -> "the Spring Boot twin of %s has to inherit spring-boot-starter-parent %s"
                            .formatted(place, springBootVersion));
            assertEquals(quarkusVersion, property(quarkus, "quarkus.version"),
                    () -> "the Quarkus twin of %s uses another Quarkus".formatted(place));
            // the twin names VanillaBP's Quarkus extension itself, and the version nearest to it wins
            // over the one the adapter brings
            assertEquals(vanillaBpVersion, property(quarkus, "vanillabp.version"),
                    () -> "the Quarkus twin of %s uses another VanillaBP than the adapter".formatted(place));
        }
    }

    @Test
    @DisplayName("Every test an example's README and its requests.http cite is a test of both twins")
    void everyCitedTestExistsInBothTwins() throws IOException {
        for (final var example : examples()) {
            for (final var file : CITING_FILES) {
                final var place = ROOT.relativize(example.resolve(file));
                final var cited = CITED_TEST
                        .matcher(Files.readString(example.resolve(file)))
                        .results()
                        .toList();
                assertFalse(cited.isEmpty(), () -> "%s names no test which holds what it says".formatted(place));
                final var missing = new ArrayList<String>();
                for (final var test : cited) {
                    for (final var platform : PLATFORMS) {
                        if (!hasTest(example.resolve(platform), test.group(1), test.group(2))) {
                            missing.add("%s#%s on %s".formatted(test.group(1), test.group(2), platform));
                        }
                    }
                }

                assertEquals(List.of(), missing, () -> "%s cites tests which do not exist".formatted(place));
            }
        }
    }

    private static List<Path> examples() throws IOException {
        try (var children = Files.list(EXAMPLES)) {
            return children
                    .filter(Files::isDirectory)
                    .filter(directory -> !directory
                            .getFileName()
                            .toString()
                            .equals("target"))
                    .sorted()
                    .toList();
        }
    }

    private static String port(
        final Path file,
        final Pattern key
    ) throws IOException {
        final var port = key.matcher(Files.readString(file));
        assertTrue(port.find(), () -> "%s names no port as %s".formatted(ROOT.relativize(file), key.pattern()));
        return port.group(1);
    }

    private static String vanillaBpConfiguration(final Path twin) throws IOException {
        final var yaml = twin.resolve("src/main/resources/application.yaml");
        final var configuration = VANILLABP_CONFIGURATION.matcher(Files.readString(yaml));
        assertTrue(configuration.find(), () -> "%s has no key vanillabp:".formatted(ROOT.relativize(yaml)));
        return configuration.group();
    }

    private static Set<String> modulesOf(final Path pom) throws IOException {
        return MODULE
                .matcher(Files.readString(pom))
                .results()
                .map(module -> module.group(1))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /**
     * The files a twin deploys: everything below a directory <code>processes</code> of its
     * resources, and its workflow module's descriptor.
     */
    private static Set<String> models(final Path resources) throws IOException {
        try (var walk = Files.walk(resources)) {
            return walk
                    .filter(Files::isRegularFile)
                    .map(file -> resources
                            .relativize(file)
                            .toString()
                            .replace('\\', '/'))
                    .filter(file -> file.contains("/processes/") || file.equals("META-INF/workflow-module"))
                    .collect(Collectors.toCollection(TreeSet::new));
        }
    }

    private static String property(
        final String pom,
        final String name
    ) {
        final var declaration = Pattern
                .compile("<%1$s>([^<]+)</%1$s>".formatted(Pattern.quote(name)))
                .matcher(pom);
        assertTrue(declaration.find(), () -> "no property %s".formatted(name));
        return declaration
                .group(1)
                .trim();
    }

    private static boolean hasTest(
        final Path twin,
        final String testClass,
        final String method
    ) throws IOException {
        try (var walk = Files.walk(twin.resolve("src/test/java"))) {
            for (final var source : walk
                    .filter(file -> file.endsWith("%s.java".formatted(testClass)))
                    .toList()) {
                if (Files
                        .readString(source)
                        .contains("void %s(".formatted(method))) {
                    return true;
                }
            }
        }
        return false;
    }

}

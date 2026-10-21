/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.coverage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.CoverageGate;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Every Quarkus module of the repository runs the Netty the Quarkus platform manages, see decision 61
 * in the repository's DECISIONS.md. The pin is an import of the Netty BOM in each module's own
 * dependency management, because the Spring Boot BOM the root POM imports first would lift Netty in
 * every module. A test on a module's classpath sees that module only, and the native module shows a
 * wrong Netty in its native build alone, so this test reads the POMs.
 */
@ExtendWith(SuppressOutputExtension.class)
class QuarkusNettyPinTest {

    private static final Path ROOT = CoverageGate.repositoryRoot("coverage.repository.root");

    /** The import of the Netty BOM in the version the root POM pins, inside dependency management. */
    private static final Pattern NETTY_PIN = Pattern.compile("""
            (?s)<dependencyManagement>(?:(?!</dependencyManagement>).)*\
            <artifactId>netty-bom</artifactId>\\s*<version>\\$\\{netty\\.version\\.quarkus}</version>\\s*\
            <type>pom</type>\\s*<scope>import</scope>""");

    @Test
    @DisplayName("Every module under quarkus/ imports the Netty BOM in the version the root POM pins")
    void everyQuarkusModulePinsNetty() throws IOException {
        final var modules = quarkusModules();

        assertFalse(modules.isEmpty(), "no module under '%s'".formatted(ROOT.resolve("quarkus")));
        assertEquals(List.of(), modules
                .stream()
                .filter(pom -> !NETTY_PIN
                        .matcher(read(pom))
                        .find())
                .map(pom -> ROOT
                        .relativize(pom)
                        .toString())
                .toList(), """
                        These POMs do not import 'io.netty:netty-bom' in '${netty.version.quarkus}' with \
                        scope 'import' in their 'dependencyManagement'. Add the import, as the other \
                        modules under 'quarkus/' have it: a Quarkus application runs the Netty the \
                        Quarkus platform manages, and Quarkus' native-image substitutions are written \
                        for that one.""");
    }

    private static List<Path> quarkusModules() throws IOException {
        try (Stream<Path> modules = Files.list(ROOT.resolve("quarkus"))) {
            return modules
                    .map(module -> module.resolve("pom.xml"))
                    .filter(Files::isRegularFile)
                    .sorted()
                    .toList();
        }
    }

    private static String read(final Path pom) {
        try {
            return Files.readString(pom, StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new IllegalStateException("cannot read '%s'".formatted(pom), e);
        }
    }

}

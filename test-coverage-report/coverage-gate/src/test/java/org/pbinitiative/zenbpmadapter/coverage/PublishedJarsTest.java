/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.coverage;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.CoverageGate;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Every jar this repository publishes carries both licence texts and the NOTICE, the sources
 * jars included (decision 17 in the repository's DECISIONS.md). Apache 2.0 asks for them
 * wherever adapted code is redistributed, and an artifact travels without the repository
 * around it. The root POM puts them there; this reads the archives the build wrote, so a
 * module which replaces the root POM's resources and forgets the entry is found here and not
 * by a user.
 * <p>
 * A module is published unless its POM sets <code>maven.deploy.skip</code>; a module of
 * packaging <code>pom</code> has no jar. The gate is the last module of the reactor, so every
 * other module has written its jars when this runs.
 */
@ExtendWith(SuppressOutputExtension.class)
class PublishedJarsTest {

    private static final List<String> LICENCE_FILES = List
            .of("META-INF/LICENSE", "META-INF/LICENSE-APACHE-2.0", "META-INF/NOTICE");

    @Test
    @DisplayName("Every published jar carries both licence texts and the NOTICE")
    void everyPublishedJarCarriesTheLicences() throws IOException {

        final var commandLine = System.getProperty("coverage.maven.command", "");
        if (CoverageGate.stopsBeforeTheReportsAreWritten(commandLine)) {
            final var reason = CoverageGate.describeRunWithoutReports(commandLine);
            Assumptions.abort(reason);
        }

        final var root = CoverageGate.repositoryRoot("coverage.repository.root");
        final var offenders = new ArrayList<String>();
        var jarsRead = 0;
        for (final var module : publishedModules(root)) {
            final var jars = jarsOf(module);
            if (jars.isEmpty()) {
                offenders.add("%s: no jar in target/".formatted(root.relativize(module)));
            }
            for (final var jar : jars) {
                jarsRead++;
                try (var archive = new JarFile(jar.toFile())) {
                    for (final var entry : LICENCE_FILES) {
                        if (archive.getEntry(entry) == null) {
                            offenders.add("%s lacks %s".formatted(root.relativize(jar), entry));
                        }
                    }
                }
            }
        }

        assertTrue(jarsRead > 0, "no published jar was found below %s".formatted(root));
        assertTrue(offenders.isEmpty(), () -> """
                Published archives without the licence texts or the NOTICE: %s. The root POM declares \
                LICENSE, LICENSE-APACHE-2.0 and NOTICE as a resource with targetPath META-INF; a module \
                which declares <resources> of its own replaces that list and has to repeat the entry, as \
                core/pom.xml does."""
                .formatted(String.join("; ", offenders)));

    }

    private static List<Path> publishedModules(
            final Path root) {

        final var modules = new ArrayList<Path>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {

                @Override
                public FileVisitResult preVisitDirectory(
                        final Path directory,
                        final BasicFileAttributes attributes) throws IOException {

                    final var name = directory.getFileName() == null
                            ? ""
                            : directory
                                    .getFileName()
                                    .toString();
                    if (name.equals("target") || name.startsWith(".")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    final var pom = directory.resolve("pom.xml");
                    if (Files.isRegularFile(pom)) {
                        final var content = Files.readString(pom, StandardCharsets.UTF_8);
                        final var noJar = content.contains("<packaging>pom</packaging>");
                        final var notPublished = content.contains("<maven.deploy.skip>true</maven.deploy.skip>");
                        if (!noJar && !notPublished) {
                            modules.add(directory);
                        }
                    }
                    return FileVisitResult.CONTINUE;

                }

            });
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        return modules;

    }

    private static List<Path> jarsOf(
            final Path module) throws IOException {

        final var target = module.resolve("target");
        if (!Files.isDirectory(target)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(target)) {
            return files
                    .filter(file -> file
                            .getFileName()
                            .toString()
                            .endsWith(".jar"))
                    .sorted()
                    .toList();
        }

    }

}

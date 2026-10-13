/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.coverage;

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
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Which report has to read the production code of which module, decided by where the module
 * lives and not by whether it produced execution data.
 * <p>
 * The platform's completeness check finds a module by its <code>target/jacoco.exec</code>, so it
 * never sees a production module without tests. Left out of its report, such a module turns
 * that report into one without a single instruction, and the gate cannot tell that from a
 * repository which compiles no code yet. Read by its report, the same module counts as missed.
 * So this check asks the directory layout instead: a module with <code>src/main/java</code> is
 * production code, and its place in the repository says which platform runs it.
 */
final class ProductionModules {

    /** The report of a platform, by name and by the POM listing what it aggregates. */
    record Report(
        String platform,
        Path pom
    ) {
    }

    private static final Pattern COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);

    private static final Pattern PARENT = Pattern.compile("<parent>.*?</parent>", Pattern.DOTALL);

    private static final Pattern DEPENDENCIES = Pattern
            .compile("<dependencies>(.*?)</dependencies>", Pattern.DOTALL);

    private static final Pattern ARTIFACT_ID = Pattern.compile("<artifactId>([^<]+)</artifactId>");

    private ProductionModules() {
        // static helpers only
    }

    /**
     * The production modules a report does not read although its platform runs them.
     *
     * @param repositoryRoot The repository's root directory
     * @param springBoot The Spring Boot report
     * @param quarkus The Quarkus report
     * @param deliberatelyNotAggregated Artifact IDs of modules with production sources which
     *          belong to no report, each of them a decision
     * @return One line per module and report it is missing from, empty if there is none
     */
    static List<String> missingFromTheReportOfTheirPlatform(
        final Path repositoryRoot,
        final Report springBoot,
        final Report quarkus,
        final Set<String> deliberatelyNotAggregated
    ) {

        final var readBySpringBoot = aggregatedArtifactIds(springBoot.pom());
        final var readByQuarkus = aggregatedArtifactIds(quarkus.pom());

        final var missing = new ArrayList<String>();
        for (final var module : modulesWithProductionSources(repositoryRoot)) {
            final var artifactId = artifactIdOf(module.resolve("pom.xml"));
            if (deliberatelyNotAggregated.contains(artifactId)) {
                continue;
            }
            final var location = repositoryRoot
                    .relativize(module)
                    .toString()
                    .replace('\\', '/');
            final var readBy = new ArrayList<Report>();
            if (location.equals("core")) {
                readBy.add(springBoot);
                readBy.add(quarkus);
            } else if (location.startsWith("spring-boot") || location.startsWith("smoke-test")) {
                readBy.add(springBoot);
            } else if (location.startsWith("quarkus/")) {
                readBy.add(quarkus);
            } else {
                missing
                        .add(
                                "%s (in '%s'): no rule says which platform runs it"
                                        .formatted(artifactId, location));
                continue;
            }
            for (final var report : readBy) {
                final var aggregated = report == springBoot ? readBySpringBoot : readByQuarkus;
                if (!aggregated.contains(artifactId)) {
                    missing
                            .add(
                                    "%s (in '%s'): not read by the %s report"
                                            .formatted(artifactId, location, report.platform()));
                }
            }
        }
        return missing;

    }

    /**
     * The message the gate fails with: what is missing, and the two ways to fix it.
     *
     * @param missing The lines {@link #missingFromTheReportOfTheirPlatform} returned
     * @param springBoot The Spring Boot report
     * @param quarkus The Quarkus report
     * @return The message
     */
    static String describe(
        final List<String> missing,
        final Report springBoot,
        final Report quarkus
    ) {

        return """
                %d production module(s) are not read by the report of their platform: %s. Their code \
                counts in no report, so a report can look complete, or hold no instruction at all, while \
                untested code ships. Add each as a <dependency> to the report of its platform (%s, %s); \
                the core belongs to both, 'spring-boot' and 'smoke-test' to Spring Boot, 'quarkus/' to \
                Quarkus. A module whose production code belongs to no report is named in the gate's list \
                of deliberate exceptions, with the reason."""
                .formatted(
                        missing.size(),
                        String.join("; ", missing),
                        springBoot.pom(),
                        quarkus.pom());

    }

    private static List<Path> modulesWithProductionSources(
        final Path repositoryRoot
    ) {

        final var productionSources = Path.of("src", "main", "java");
        final var modules = new ArrayList<Path>();
        try {
            Files.walkFileTree(repositoryRoot, new SimpleFileVisitor<>() {

                @Override
                public FileVisitResult preVisitDirectory(
                    final Path directory,
                    final BasicFileAttributes attributes
                ) {

                    final var name = directory.getFileName() == null
                            ? ""
                            : directory
                                    .getFileName()
                                    .toString();
                    // build output and the repository's own metadata hold no module
                    if (name.equals("target") || name.startsWith(".")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (directory.endsWith(productionSources)) {
                        final var module = directory
                                .getParent()
                                .getParent()
                                .getParent();
                        if (Files.isRegularFile(module.resolve("pom.xml"))) {
                            modules.add(module);
                        }
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;

                }

            });
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        modules.sort(null);
        return modules;

    }

    private static List<String> aggregatedArtifactIds(
        final Path pom
    ) {

        final var dependencies = DEPENDENCIES.matcher(withoutComments(pom));
        final var artifactIds = new ArrayList<String>();
        while (dependencies.find()) {
            final var artifactId = ARTIFACT_ID.matcher(dependencies.group(1));
            while (artifactId.find()) {
                artifactIds.add(artifactId.group(1).trim());
            }
        }
        return artifactIds;

    }

    private static String artifactIdOf(
        final Path pom
    ) {

        final var ownPart = PARENT.matcher(withoutComments(pom)).replaceFirst("");
        final var artifactId = ARTIFACT_ID.matcher(ownPart);
        if (!artifactId.find()) {
            throw new IllegalStateException("'%s' names no artifactId".formatted(pom));
        }
        return artifactId.group(1).trim();

    }

    private static String withoutComments(
        final Path pom
    ) {

        try {
            return COMMENT
                    .matcher(Files.readString(pom, StandardCharsets.UTF_8))
                    .replaceAll("");
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }

    }

}

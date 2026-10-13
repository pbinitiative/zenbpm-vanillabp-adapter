/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.coverage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.CoverageGate;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Two promises of the snapshot publication which a green build would otherwise never look at.
 * <p>
 * Nothing is uploaded before the coverage gate passed. That is the deploy plugin's
 * <code>deployAtEnd</code> together with the gate being the last module of the reactor. Without
 * either, a green build still passes, and only a later red one would publish the modules built
 * before the gate. <code>bin/check-deploy-safety.sh</code> proves the behaviour itself, with a
 * deploy into a local directory; this test keeps the two settings it rests on from going away.
 * <p>
 * The coverage badges of the README read the instruction coverage out of the published report
 * pages with a regular expression. Its URL is not built by Maven, so a broken edit stays green
 * here and shows on GitHub as a badge without a number. This test runs each badge's expression
 * against the report page this build just wrote, and against the three forms the page takes.
 */
@ExtendWith(SuppressOutputExtension.class)
class PublicationSafetyTest {

    private static final Path ROOT = CoverageGate.repositoryRoot("coverage.repository.root");

    private static final Pattern COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);

    private static final Pattern DEPLOY_PLUGIN = Pattern
            .compile(
                    "<plugin>\\s*<groupId>org\\.apache\\.maven\\.plugins</groupId>\\s*"
                            + "<artifactId>maven-deploy-plugin</artifactId>.*?</plugin>",
                    Pattern.DOTALL);

    private static final Pattern MODULE = Pattern.compile("<module>([^<]+)</module>");

    private static final Pattern BADGE = Pattern
            .compile(
                    "\\[!\\[Coverage ([^\\]]+)]\\(https://img\\.shields\\.io/badge/dynamic/regex\\?([^)]+)\\)]\\(([^)]+)\\)");

    @Test
    @DisplayName("Nothing is uploaded before the coverage gate, the last module, passed")
    void nothingIsUploadedBeforeTheGatePassed() {

        final var rootPom = withoutComments(ROOT.resolve("pom.xml"));
        final var deployPlugin = DEPLOY_PLUGIN.matcher(rootPom);
        assertTrue(
                deployPlugin.find() && deployPlugin
                        .group()
                        .contains("<deployAtEnd>true</deployAtEnd>"),
                """
                        The root POM has to configure maven-deploy-plugin with <deployAtEnd>true</deployAtEnd>. \
                        Without it every module uploads right after its own build, and a red coverage gate at \
                        the end of the reactor leaves the modules before it published.""");

        final var overriding = new ArrayList<String>();
        for (final var pom : poms()) {
            if (!pom.equals(ROOT.resolve("pom.xml")) && withoutComments(pom).contains("<deployAtEnd>")) {
                overriding.add(ROOT.relativize(pom).toString());
            }
        }
        assertEquals(
                List.of(),
                overriding,
                "No module may set deployAtEnd itself; the root POM's value is the one which keeps the "
                        + "upload behind the gate.");

        assertEquals(
                "test-coverage-report",
                lastModule(rootPom),
                "The coverage reports have to be the last module of the root POM, because the deferred "
                        + "upload runs when the last module of the reactor is through.");
        assertEquals(
                "coverage-gate",
                lastModule(withoutComments(ROOT.resolve("test-coverage-report/pom.xml"))),
                "The gate has to be the last module of test-coverage-report, so it is the last of the "
                        + "whole reactor and nothing is uploaded before it passed.");

    }

    @Test
    @DisplayName("Each coverage badge reads the instruction coverage of its own report")
    void eachCoverageBadgeReadsTheInstructionCoverageOfItsReport() throws IOException {

        final var badges = badges();
        assertEquals(
                List.of("Spring Boot", "Quarkus"),
                List.copyOf(badges.keySet()),
                "The README has to carry one coverage badge per platform.");

        for (final var badge : badges.entrySet()) {
            final var platform = badge.getKey();
            final var directory = platform.equals("Spring Boot") ? "spring-boot" : "quarkus";
            final var parameters = badge.getValue();
            assertEquals(
                    "https://pbinitiative.github.io/zenbpm-vanillabp-adapter/%s-report/index.html".formatted(directory),
                    parameters.get("url"),
                    "The %s badge has to read the %s report page.".formatted(platform, platform));
            assertEquals("$1", parameters.get("replace"),
                    "The %s badge has to show its first group.".formatted(platform));

            final var search = Pattern.compile(parameters.get("search"));
            assertEquals("94%", firstGroup(search, footer("1,821 of 31,295", "94%")), platform);
            assertEquals("0%", firstGroup(search, footer("2 of 2", "0%")), platform);
            assertEquals("n/a", firstGroup(search, footer("0 of 0", "n/a")), platform);

            final var commandLine = System.getProperty("coverage.maven.command", "");
            if (CoverageGate.stopsBeforeTheReportsAreWritten(commandLine)) {
                Assumptions.abort(CoverageGate.describeRunWithoutReports(commandLine));
            }
            final var page = Files
                    .readString(ROOT.resolve("test-coverage-report/%s/report/index.html".formatted(directory)));
            final var shown = firstGroup(search, page);
            assertTrue(
                    shown != null && shown.matches("\\d+%|n/a"),
                    "The %s badge reads '%s' from the report this build wrote, not a percentage or 'n/a'."
                            .formatted(platform, shown));
        }

    }

    /** The table footer of a JaCoCo report page: instructions first, branches second. */
    private static String footer(
        final String instructions,
        final String instructionCoverage
    ) {

        return """
                <tfoot><tr><td>Total</td><td class="bar">%s</td><td class="ctr2">%s</td>\
                <td class="bar">0 of 0</td><td class="ctr2">n/a</td></tr></tfoot>"""
                .formatted(instructions, instructionCoverage);

    }

    private static String firstGroup(
        final Pattern pattern,
        final String text
    ) {

        final var matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : null;

    }

    /** The badge parameters per platform, in the order the README shows them. */
    private static Map<String, Map<String, String>> badges() throws IOException {

        final var badges = new LinkedHashMap<String, Map<String, String>>();
        final var matcher = BADGE.matcher(Files.readString(ROOT.resolve("README.md")));
        while (matcher.find()) {
            final var parameters = new LinkedHashMap<String, String>();
            for (final var parameter : matcher
                    .group(2)
                    .split("&")) {
                final var separator = parameter.indexOf('=');
                parameters
                        .put(
                                parameter.substring(0, separator),
                                URLDecoder.decode(parameter.substring(separator + 1), StandardCharsets.UTF_8));
            }
            badges.put(matcher.group(1), parameters);
        }
        return badges;

    }

    private static String lastModule(
        final String pom
    ) {

        final var matcher = MODULE.matcher(pom);
        String last = null;
        while (matcher.find()) {
            last = matcher.group(1);
        }
        return last;

    }

    private static List<Path> poms() {

        final var poms = new ArrayList<Path>();
        try {
            Files.walkFileTree(ROOT, new SimpleFileVisitor<>() {

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
                    if (name.equals("target") || name.startsWith(".")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    final var pom = directory.resolve("pom.xml");
                    if (Files.isRegularFile(pom)) {
                        poms.add(pom);
                    }
                    return FileVisitResult.CONTINUE;

                }

            });
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        return poms;

    }

    private static String withoutComments(
        final Path file
    ) {

        try {
            return COMMENT
                    .matcher(Files.readString(file, StandardCharsets.UTF_8))
                    .replaceAll("");
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }

    }

}

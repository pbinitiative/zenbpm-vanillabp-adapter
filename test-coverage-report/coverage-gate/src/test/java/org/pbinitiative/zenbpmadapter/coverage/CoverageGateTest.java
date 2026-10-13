/*
 * Copyright 2026 Phactum Softwareentwicklung GmbH
 * Adapted for zenbpm-vanillabp-adapter from vanillabp/camunda8-adapter, see NOTICE.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.pbinitiative.zenbpmadapter.coverage;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.vanillabp.integration.test.utils.CoverageGate;
import io.vanillabp.integration.test.utils.PrintsWhenPassing;

/**
 * The coverage gate: it breaks the build when a platform's aggregated coverage
 * drops below the threshold, so a drop is noticed while it happens instead of a year
 * later. The threshold is 85 in every VanillaBP repository while the rule is 90, which
 * keeps one repository's bad week from being answered by editing the number.
 * <p>
 * JaCoCo's own <code>check</code> goal cannot do this: it judges ONE module's classes
 * against ONE execution-data file, while both numbers of this repository come from
 * aggregated reports spanning many modules. So the gate reads exactly the report which
 * is published, and report and gate can never disagree.
 * <p>
 * The completeness test comes first for a reason: a threshold
 * checked against an incomplete aggregate fails builds for coverage which exists and
 * is only not counted, and nobody can fix that by writing a test.
 * <p>
 * This is the one test class in VanillaBP which prints while it passes, and therefore the
 * one without {@code SuppressOutputExtension}. Everywhere else a passing test says nothing,
 * because output nobody reads hides the output somebody has to. Here the passing run IS the
 * measurement: it is the only place the build states where each platform stands against the
 * rule, and a repository sitting between the threshold and the rule has a gap which would
 * otherwise be visible only to whoever opens the report.
 */
@PrintsWhenPassing(
    "the measurement is the result - what each platform reached belongs in the log of every "
            + "build and not only of a red one, so whoever just wrote tests reads there whether the "
            + "gap to the rule got smaller")
class CoverageGateTest {

    private static final Path ROOT = CoverageGate.repositoryRoot("coverage.repository.root");

    /**
     * What a report is expected to show, as opposed to the threshold, which is where the
     * build stops. Reported on every run, never asserted: a build breaking at the rule
     * would leave a repository nothing to do but edit the rule.
     */
    private static final double RULE = Double.parseDouble(System.getProperty("coverage.rule"));

    /**
     * The command line this build was started with, handed over by the module's Surefire
     * configuration. It answers the one question the gate cannot read off the disk:
     * whether this run writes the reports at all.
     */
    private static final String MAVEN_COMMAND_LINE = System.getProperty("coverage.maven.command", "");

    private static final List<Path> AGGREGATE_POMS = List
            .of(
                    ROOT.resolve("test-coverage-report/spring-boot/pom.xml"),
                    ROOT.resolve("test-coverage-report/quarkus/pom.xml"));

    /**
     * Modules whose execution data or production code belongs to no coverage report. Each
     * entry is a decision - a module missing here and missing from the aggregate of its
     * platform is the defect this test exists for.
     * <p>
     * {@code engine-test-support} starts the engine for the integration tests. Its classes
     * live in {@code src/main/java} only because a test classpath cannot read another
     * module's test classes; no application runs them, on either platform.
     */
    private static final Set<String> DELIBERATELY_NOT_AGGREGATED = Set
            .of("zenbpm-vanillabp-adapter-engine-test-support");

    @Test
    @DisplayName("Every module producing coverage data is read by an aggregated report")
    void everyModuleProducingCoverageDataIsAggregated() {

        final var missing = CoverageGate
                .modulesMissingFromAggregates(ROOT, AGGREGATE_POMS, DELIBERATELY_NOT_AGGREGATED);

        assertTrue(missing.isEmpty(), () -> CoverageGate.describeMissingModules(missing, AGGREGATE_POMS));

    }

    /**
     * The check above finds a module by its execution data, so it never sees a production
     * module without tests. Left out of its report, such a module empties the report instead of
     * failing it. This one decides by the directory layout, see {@link ProductionModules}.
     */
    @Test
    @DisplayName("Every production module is read by the report of its platform")
    void everyProductionModuleIsReadByTheReportOfItsPlatform() {

        final var springBoot = new ProductionModules.Report("Spring Boot", AGGREGATE_POMS.get(0));
        final var quarkus = new ProductionModules.Report("Quarkus", AGGREGATE_POMS.get(1));

        final var missing = ProductionModules
                .missingFromTheReportOfTheirPlatform(ROOT, springBoot, quarkus, DELIBERATELY_NOT_AGGREGATED);

        assertTrue(missing.isEmpty(), () -> ProductionModules.describe(missing, springBoot, quarkus));

    }

    @Test
    @DisplayName("The Spring Boot report is above the coverage threshold")
    void theSpringBootReportIsAboveTheThreshold() {

        assertAboveThreshold("Spring Boot", 0, "coverage.threshold.spring-boot");

    }

    @Test
    @DisplayName("The Quarkus report is above the coverage threshold")
    void theQuarkusReportIsAboveTheThreshold() {

        assertAboveThreshold("Quarkus", 1, "coverage.threshold.quarkus");

    }

    /**
     * A run which stops before <code>verify</code> writes no report, and the gate says so
     * and skips instead of failing over a file this run could not have written. It stays
     * loud about it: the line is printed and the test is reported as skipped, so nobody
     * reads a green run as a checked one.
     * <p>
     * A report which holds no instruction at all is skipped the same way. A percentage of
     * nothing is no measurement: read as 0 % it would fail a build which has no test to write,
     * and passed it would read as checked. That is a repository whose modules compile no code
     * yet, or later an aggregate which lost all its modules; either way the skipped test shows
     * that nothing was measured.
     * <p>
     * The threshold is read per platform, because a report exists per platform. Both
     * properties hold the same 85 in every VanillaBP repository, and that number is the
     * floor against regression rather than the goal: the rule is the {@code coverage.rule}
     * property, and a repository between the two has a gap somebody still owes a test for.
     */
    private void assertAboveThreshold(
            final String platform,
            final int report,
            final String thresholdProperty) {

        if (CoverageGate.stopsBeforeTheReportsAreWritten(MAVEN_COMMAND_LINE)) {
            final var reason = CoverageGate.describeRunWithoutReports(MAVEN_COMMAND_LINE);
            System.out.println("coverage gate | %s | %s".formatted(platform, reason));
            Assumptions.abort(reason);
        }

        final var threshold = Double.parseDouble(System.getProperty(thresholdProperty));

        final var coverage = CoverageGate
                .read(
                        CoverageGate
                                .reportsOfBothPlatforms(ROOT)
                                .get(report),
                        platform,
                        CoverageGate.Metric.INSTRUCTIONS);

        if ((coverage.missed() + coverage.covered()) == 0) {
            final var reason = "the report holds no instruction, so there is no coverage to check";
            System.out.println("coverage gate | %s | %s".formatted(platform, reason));
            Assumptions.abort(reason);
        }

        report(coverage, threshold);

        assertTrue(
                coverage.percentage() >= threshold,
                () -> """
                        %s - below the %s %% at which every VanillaBP build stops. The rule is %s per \
                        platform, so anything under that is already a gap, and this number is where the gap \
                        grew too big to carry. Coverage is measured separately per platform, so this platform's \
                        own tests have to close it: sort the per-package numbers of the report's jacoco.csv by \
                        MISSED instructions, not by percentage, and put the test where the uncovered code \
                        belongs. Code nobody can reach is dead and gets deleted rather than covered."""
                        .formatted(coverage, plain(threshold), plain(RULE)));

    }

    /**
     * The line a passing run leaves behind. It names both numbers, because the one which
     * breaks the build is not the one to aim at, and it says how far a platform is from the
     * rule while that distance is still small enough to close.
     */
    private void report(
            final CoverageGate.Coverage coverage,
            final double threshold) {

        final var verdict = coverage.percentage() >= RULE
                ? "at the rule of %s %%".formatted(plain(RULE))
                : "%.2f points below the rule of %s %%, build breaks below %s %%"
                        .formatted(RULE - coverage.percentage(), plain(RULE), plain(threshold));

        System.out.println("coverage gate | %s | %s".formatted(coverage, verdict));

    }

    /** A whole percentage without the '.0' a double would print. */
    private static String plain(
            final double percentage) {

        return percentage == Math.rint(percentage)
                ? String.valueOf((long) percentage)
                : String.valueOf(percentage);

    }

}

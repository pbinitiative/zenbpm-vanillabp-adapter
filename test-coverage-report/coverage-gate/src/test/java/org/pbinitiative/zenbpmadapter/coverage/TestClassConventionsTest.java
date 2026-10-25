/*
 * Copyright 2026 Phactum Softwareentwicklung GmbH
 * Adapted for zenbpm-vanillabp-adapter from vanillabp/camunda8-adapter, see NOTICE.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.pbinitiative.zenbpmadapter.coverage;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.CoverageGate;
import io.vanillabp.integration.test.utils.MessageConventions;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.TestClassConventions;

/**
 * A gate in the module which already gates this repository as a whole:
 * every test class registers {@link SuppressOutputExtension}, so a build log carries
 * what a FAILING test printed and nothing else.
 * <p>
 * In the VanillaBP repositories where the check was introduced, four respectively nine
 * test classes did not follow the rule. It is checked here rather than reviewed because the
 * rule had been written down twice before it drifted.
 * <p>
 * It also checks the guiding messages of this repository. A message is what a developer
 * reads in the moment something goes wrong, so a sentence which fell apart in the source
 * takes away the one explanation they get. The check reads the main sources, and one run
 * of it covers every module.
 * <p>
 * The test classes of <code>examples/</code> are left out of the suppression: an example is an
 * application somebody copies, and its tests use what the platform brings, not this repository's
 * test utilities, see decision 67 in the repository's DECISIONS.md.
 */
@ExtendWith(SuppressOutputExtension.class)
class TestClassConventionsTest {

    @Test
    @DisplayName("Every test class of this repository suppresses its output")
    void everyTestClassSuppressesItsOutput() {
        final var root = CoverageGate.repositoryRoot("coverage.repository.root");

        final var offenders = outsideTheExamples(root, TestClassConventions.testClassesWithoutOutputSuppression(root));

        assertTrue(
                offenders.isEmpty(),
                () -> TestClassConventions.describeTestClassesWithoutOutputSuppression(offenders));
    }

    @Test
    @DisplayName("No test class registers the suppression after '@Testcontainers'")
    void noTestClassSuppressesTooLate() {
        final var root = CoverageGate.repositoryRoot("coverage.repository.root");

        final var offenders = outsideTheExamples(root, TestClassConventions.testClassesSuppressingTooLate(root));

        assertTrue(
                offenders.isEmpty(),
                () -> TestClassConventions.describeTestClassesSuppressingTooLate(offenders));
    }

    @Test
    @DisplayName("No message of this repository carries a run of spaces between two words")
    void noMessageFellApart() {
        final var root = CoverageGate.repositoryRoot("coverage.repository.root");

        final var offenders = MessageConventions.messagesPulledApart(root);

        assertTrue(
                offenders.isEmpty(),
                () -> MessageConventions.describeMessagesPulledApart(offenders));
    }

    @Test
    @DisplayName("No message of this repository glues two words into one")
    void noMessageIsGluedTogether() {
        final var root = CoverageGate.repositoryRoot("coverage.repository.root");

        final var offenders = MessageConventions.messagesGluedTogether(root);

        assertTrue(
                offenders.isEmpty(),
                () -> MessageConventions.describeMessagesGluedTogether(offenders));
    }

    private static List<Path> outsideTheExamples(
        final Path root,
        final List<Path> testClasses
    ) {
        final var examples = root
                .toAbsolutePath()
                .normalize()
                .resolve("examples");
        return testClasses
                .stream()
                .filter(testClass -> !root
                        .toAbsolutePath()
                        .normalize()
                        .resolve(testClass)
                        .normalize()
                        .startsWith(examples))
                .toList();
    }

}

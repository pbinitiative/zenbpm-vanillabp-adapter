/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.coverage;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pbinitiative.zenbpmadapter.coverage.JavaMembers.Kind;

import io.vanillabp.integration.test.utils.CoverageGate;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * JUnit 5 runs package-private test classes and methods, so {@code public} on them says nothing
 * and is left out, see decision 21 in the repository's DECISIONS.md.
 */
@ExtendWith(SuppressOutputExtension.class)
class TestVisibilityTest {

    private static final Path ROOT = CoverageGate.repositoryRoot("coverage.repository.root");

    @Test
    @DisplayName("No test class is declared public")
    void noTestClassIsPublic() {
        final var offenders = JavaMembers
                .javaSources(ROOT, "test")
                .filter(TestVisibilityTest::holdsATest)
                .filter(JavaMembers::declaresAPublicClass)
                .map(source -> "  %s".formatted(ROOT.relativize(source)))
                .toList();

        assertTrue(
                offenders.isEmpty(),
                () -> """
                        JUnit 5 runs a package-private test class, so 'public' on it is noise (see decision 21 \
                        in the repository's DECISIONS.md). Remove it from:
                        %s"""
                        .formatted(String.join("\n", offenders)));
    }

    @Test
    @DisplayName("No test or lifecycle method is declared public")
    void noTestMethodIsPublic() {
        final var offenders = JavaMembers
                .javaSources(ROOT, "test")
                .flatMap(source -> JavaMembers
                        .methodsOf(source)
                        .stream())
                .filter(method -> (method.kind() == Kind.TEST) || (method.kind() == Kind.LIFECYCLE))
                .filter(JavaMembers.Member::isPublic)
                .map(method -> method.describe(ROOT))
                .toList();

        assertTrue(
                offenders.isEmpty(),
                () -> """
                        JUnit 5 runs package-private test and lifecycle methods, so 'public' on them is noise \
                        (see decision 21 in the repository's DECISIONS.md). Remove it from:
                        %s"""
                        .formatted(String.join("\n", offenders)));
    }

    private static boolean holdsATest(final Path source) {
        return JavaMembers
                .methodsOf(source)
                .stream()
                .anyMatch(method -> method.kind() == Kind.TEST);
    }

}

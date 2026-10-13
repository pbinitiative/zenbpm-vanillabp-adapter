/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.coverage;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pbinitiative.zenbpmadapter.coverage.JavaMembers.Kind;

import io.vanillabp.integration.test.utils.CoverageGate;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The order of the methods in every Java class of this repository, see decision 19 in the
 * repository's DECISIONS.md: in a test class the lifecycle methods, then the tests, then every
 * other method; in every class the private methods after the others.
 */
@ExtendWith(SuppressOutputExtension.class)
class MethodOrderTest {

    private static final Path ROOT = CoverageGate.repositoryRoot("coverage.repository.root");

    @Test
    @DisplayName("In every test class the lifecycle methods come first, then the tests, then every other method")
    void testsComeBeforeTheirHelpers() {

        final var offenders = JavaMembers
                .javaSources(ROOT, "test")
                .flatMap(source -> outOfOrderInATestClass(source).stream())
                .toList();

        assertTrue(
                offenders.isEmpty(),
                () -> """
                        A reader opens a test class to learn what is tested, so the tests come first: lifecycle \
                        methods before the first test, argument sources and helpers after the last one (see \
                        decision 19 in the repository's DECISIONS.md). Move these methods:
                        %s"""
                        .formatted(String.join("\n", offenders)));

    }

    @Test
    @DisplayName("In every class the private methods follow the others")
    void privateMethodsComeLast() {

        final var offenders = Stream
                .concat(JavaMembers.javaSources(ROOT, "main"), JavaMembers.javaSources(ROOT, "test"))
                .flatMap(source -> outOfOrderAfterAPrivateMethod(source).stream())
                .toList();

        assertTrue(
                offenders.isEmpty(),
                () -> """
                        A reader opens a class to learn what it offers, so its private methods come after the \
                        others (see decision 19 in the repository's DECISIONS.md). Move these methods above \
                        the first private method:
                        %s"""
                        .formatted(String.join("\n", offenders)));

    }

    @Test
    @DisplayName("The check reads the methods of this repository's classes, so it cannot pass by reading none")
    void theCheckReadsMethods() {

        final var tests = JavaMembers
                .javaSources(ROOT, "test")
                .flatMap(source -> JavaMembers
                        .methodsOf(source)
                        .stream())
                .filter(method -> method.kind() == Kind.TEST)
                .count();
        final var privateMethods = JavaMembers
                .javaSources(ROOT, "main")
                .flatMap(source -> JavaMembers
                        .methodsOf(source)
                        .stream())
                .filter(method -> method.kind() == Kind.PRIVATE)
                .count();

        assertTrue(tests > 0, "no test method found; does the member indentation still match the formatter?");
        assertTrue(privateMethods > 0,
                "no private method found; does the member indentation still match the formatter?");

    }

    private static List<String> outOfOrderInATestClass(
            final Path source) {

        final var methods = JavaMembers.methodsOf(source);
        final var tests = methods
                .stream()
                .filter(method -> method.kind() == Kind.TEST)
                .toList();
        if (tests.isEmpty()) {
            return List.of();
        }
        final var firstTest = tests
                .getFirst()
                .line();
        final var lastTest = tests
                .getLast()
                .line();
        return methods
                .stream()
                .filter(method -> ((method.kind() == Kind.LIFECYCLE) && (method.line() > firstTest)) || (((method
                        .kind() == Kind.PRIVATE) || (method.kind() == Kind.OTHER)) && (method.line() < lastTest)))
                .map(method -> "%s, %s".formatted(
                        method.describe(ROOT),
                        method.kind() == Kind.LIFECYCLE ? "a lifecycle method after a test" : "a helper before a test"))
                .toList();

    }

    private static List<String> outOfOrderAfterAPrivateMethod(
            final Path source) {

        final var methods = JavaMembers.methodsOf(source);
        final var firstPrivate = methods
                .stream()
                .filter(method -> method.kind() == Kind.PRIVATE)
                .mapToInt(JavaMembers.Member::line)
                .findFirst();
        if (firstPrivate.isEmpty()) {
            return List.of();
        }
        return methods
                .stream()
                .filter(method -> (method.line() > firstPrivate.getAsInt()) && (method
                        .kind() != Kind.PRIVATE) && (method.kind() != Kind.CONSTRUCTOR))
                .map(method -> "%s, after the private method in line %d"
                        .formatted(method.describe(ROOT), firstPrivate.getAsInt()))
                .toList();

    }

}

/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.coverage;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.CoverageGate;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The order of the methods in every Java class of this repository, see decision 19 in the
 * repository's DECISIONS.md: in a test class the lifecycle methods, then the tests, then every
 * other method; in every class the private methods after the others.
 * <p>
 * The sources are read as text, which works because Spotless indents every member of a top-level
 * class by exactly two spaces. A member of a nested class is indented deeper and is not checked.
 */
@ExtendWith(SuppressOutputExtension.class)
public class MethodOrderTest {

  private static final Path ROOT = CoverageGate.repositoryRoot("coverage.repository.root");

  private static final Pattern TEST_ANNOTATION = Pattern
      .compile("(?m)^\\s*@(Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\\b");

  private static final Pattern LIFECYCLE_ANNOTATION = Pattern
      .compile("(?m)^\\s*@(BeforeEach|AfterEach|BeforeAll|AfterAll)\\b");

  // a method or constructor of the top-level class: two spaces, then neither an annotation, a
  // comment nor a closing brace, no type declaration, and the name followed by '(' before any '='
  private static final Pattern DECLARATION = Pattern
      .compile("^  (?![ @/*}])(?!.*\\b(?:class|record|enum|interface)\\b)[^=(]*?\\b(\\w+)\\s*\\(");

  // where one member ends and the annotations and javadoc of the next one begin
  private static final Pattern MEMBER_END = Pattern.compile("^  }\\s*$|^  [^ @/*].*;\\s*$");

  @Test
  @DisplayName("In every test class the lifecycle methods come first, then the tests, then every other method")
  public void testsComeBeforeTheirHelpers() {

    final var offenders = javaSources("test")
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
  public void privateMethodsComeLast() {

    final var offenders = Stream
        .concat(javaSources("main"), javaSources("test"))
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

  private enum Kind {
    TEST,
    LIFECYCLE,
    CONSTRUCTOR,
    PRIVATE,
    OTHER
  }

  private record Method(
                        Path source,
                        int line,
                        String name,
                        Kind kind) {

    String describe() {

      return "  %s:%d %s()".formatted(ROOT.relativize(source), line, name);

    }

  }

  private static List<String> outOfOrderInATestClass(
      final Path source) {

    final var methods = methodsOf(source);
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
        .filter(method -> ((method.kind() == Kind.LIFECYCLE) && (method
            .line() > firstTest)) || (((method.kind() == Kind.PRIVATE) || (method.kind() == Kind.OTHER)) && (method
                .line() < lastTest)))
        .map(method -> "%s, %s".formatted(
            method.describe(),
            method.kind() == Kind.LIFECYCLE ? "a lifecycle method after a test" : "a helper before a test"))
        .toList();

  }

  private static List<String> outOfOrderAfterAPrivateMethod(
      final Path source) {

    final var methods = methodsOf(source);
    final var firstPrivate = methods
        .stream()
        .filter(method -> method.kind() == Kind.PRIVATE)
        .mapToInt(Method::line)
        .findFirst();
    if (firstPrivate.isEmpty()) {
      return List.of();
    }
    return methods
        .stream()
        .filter(method -> (method.line() > firstPrivate.getAsInt()) && (method.kind() != Kind.PRIVATE) && (method
            .kind() != Kind.CONSTRUCTOR))
        .map(method -> "%s, after the private method in line %d".formatted(method.describe(), firstPrivate.getAsInt()))
        .toList();

  }

  private static List<Method> methodsOf(
      final Path source) {

    final List<String> lines;
    try {
      lines = Files.readAllLines(source);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    final var className = source
        .getFileName()
        .toString()
        .replaceFirst("\\.java$", "");
    final var methods = new ArrayList<Method>();
    final var preamble = new ArrayList<String>();
    for (var index = 0; index < lines.size(); ++index) {
      final var line = lines.get(index);
      final var declaration = DECLARATION.matcher(line);
      if (declaration.find()) {
        final var annotations = String.join("\n", preamble);
        final Kind kind;
        if (TEST_ANNOTATION
            .matcher(annotations)
            .find()) {
          kind = Kind.TEST;
        } else if (LIFECYCLE_ANNOTATION
            .matcher(annotations)
            .find()) {
              kind = Kind.LIFECYCLE;
            } else
          if (declaration
              .group(1)
              .equals(className)) {
                kind = Kind.CONSTRUCTOR;
              } else
            if (line.startsWith("  private ")) {
              kind = Kind.PRIVATE;
            } else {
              kind = Kind.OTHER;
            }
        methods.add(new Method(source, index + 1, declaration.group(1), kind));
        preamble.clear();
      } else if (MEMBER_END
          .matcher(line)
          .find()) {
            preamble.clear();
          } else {
            preamble.add(line);
          }
    }
    return methods;

  }

  private static Stream<Path> javaSources(
      final String sourceSet) {

    try (var paths = Files.walk(ROOT)) {
      return paths
          .filter(path -> path
              .toString()
              .endsWith(".java"))
          .filter(path -> isInSourceSet(ROOT.relativize(path), sourceSet))
          .sorted()
          .collect(Collectors.toList())
          .stream();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }

  }

  /**
   * Compares the names of the directories, not the path as text, so the check reads the same
   * whichever separator the operating system writes. Generated sources live in {@code target}
   * and are left out.
   */
  private static boolean isInSourceSet(
      final Path path,
      final String sourceSet) {

    final var names = new ArrayList<String>();
    path.forEach(name -> names.add(name.toString()));
    final var src = names.indexOf("src");
    return !names.contains("target") && (src >= 0) && (src + 2 < names.size()) && sourceSet
        .equals(names.get(src + 1)) && "java".equals(names.get(src + 2));

  }

}

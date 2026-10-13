/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.coverage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The methods of the top-level class of a Java source, read as text for the checks of decisions
 * 19 and 20 in the repository's DECISIONS.md.
 * <p>
 * Reading text works because Spotless indents every member of a top-level class by exactly
 * {@value #MEMBER_INDENT_WIDTH} spaces (decision 20). A member of a nested class is indented
 * deeper and is not read.
 */
final class JavaMembers {

    /**
     * The indentation of a member of a top-level class.
     */
    static final int MEMBER_INDENT_WIDTH = 4;

    private static final String MEMBER_INDENT = " ".repeat(MEMBER_INDENT_WIDTH);

    private static final Pattern TEST_ANNOTATION = Pattern
            .compile("(?m)^\\s*@(Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\\b");

    private static final Pattern LIFECYCLE_ANNOTATION = Pattern
            .compile("(?m)^\\s*@(BeforeEach|AfterEach|BeforeAll|AfterAll)\\b");

    // a method or constructor: the member indentation, then neither an annotation, a comment nor
    // a closing brace, no type declaration, and the name followed by '(' before any '='
    private static final Pattern DECLARATION = Pattern
            .compile("^%s(?![ @/*}])(?!.*\\b(?:class|record|enum|interface)\\b)[^=(]*?\\b(\\w+)\\s*\\("
                    .formatted(MEMBER_INDENT));

    // where one member ends and the annotations and javadoc of the next one begin
    private static final Pattern MEMBER_END = Pattern
            .compile("^%1$s}\\s*$|^%1$s[^ @/*].*;\\s*$".formatted(MEMBER_INDENT));

    private static final Pattern PUBLIC_CLASS = Pattern
            .compile("(?m)^public (?:final |abstract )*class ");

    /**
     * What a method is for the checks.
     */
    enum Kind {
        TEST,
        LIFECYCLE,
        CONSTRUCTOR,
        PRIVATE,
        OTHER
    }

    /**
     * One method or constructor of a top-level class.
     *
     * @param source The file
     * @param line The line of its declaration, counted from 1
     * @param name The name
     * @param kind What it is for the checks
     * @param isPublic Whether it is declared {@code public}
     */
    record Member(
                  Path source,
                  int line,
                  String name,
                  Kind kind,
                  boolean isPublic) {

        String describe(
                final Path root) {

            return "  %s:%d %s()".formatted(root.relativize(source), line, name);

        }

    }

    private JavaMembers() {
        // static helpers only
    }

    /**
     * The Java sources of one source set of every module, generated ones excluded.
     *
     * @param root The repository's root directory
     * @param sourceSet {@code main} or {@code test}
     * @return The files, sorted
     */
    static Stream<Path> javaSources(
            final Path root,
            final String sourceSet) {

        try (var paths = Files.walk(root)) {
            return paths
                    .filter(path -> path
                            .toString()
                            .endsWith(".java"))
                    .filter(path -> isInSourceSet(root.relativize(path), sourceSet))
                    .sorted()
                    .toList()
                    .stream();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

    }

    /**
     * Whether the top-level class of a source is declared {@code public}.
     *
     * @param source The file
     * @return {@code true} for {@code public class}, {@code public final class} and the like
     */
    static boolean declaresAPublicClass(
            final Path source) {

        return PUBLIC_CLASS
                .matcher(read(source))
                .find();

    }

    /**
     * The methods and constructors of the top-level class of a source, in the order they are
     * written.
     *
     * @param source The file
     * @return The members
     */
    static List<Member> methodsOf(
            final Path source) {

        final var className = source
                .getFileName()
                .toString()
                .replaceFirst("\\.java$", "");
        final var methods = new ArrayList<Member>();
        final var preamble = new ArrayList<String>();
        final var lines = read(source).split("\n", -1);
        for (var index = 0; index < lines.length; ++index) {
            final var line = lines[index];
            final var declaration = DECLARATION.matcher(line);
            if (declaration.find()) {
                final var name = declaration.group(1);
                methods.add(new Member(
                        source, index + 1, name, kindOf(String.join("\n", preamble), line, name, className), line
                                .startsWith(MEMBER_INDENT
                                        + "public ")));
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

    private static Kind kindOf(
            final String annotations,
            final String declaration,
            final String name,
            final String className) {

        if (TEST_ANNOTATION
                .matcher(annotations)
                .find()) {
            return Kind.TEST;
        }
        if (LIFECYCLE_ANNOTATION
                .matcher(annotations)
                .find()) {
            return Kind.LIFECYCLE;
        }
        if (name.equals(className)) {
            return Kind.CONSTRUCTOR;
        }
        if (declaration.startsWith(MEMBER_INDENT
                + "private ")) {
            return Kind.PRIVATE;
        }
        return Kind.OTHER;

    }

    private static String read(
            final Path source) {

        try {
            return Files.readString(source);
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

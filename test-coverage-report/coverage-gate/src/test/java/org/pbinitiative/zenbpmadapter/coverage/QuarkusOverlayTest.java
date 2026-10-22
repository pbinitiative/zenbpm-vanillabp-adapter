/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.coverage;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.CoverageGate;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The configuration overlay of the Quarkus extension, held against two rules nothing else checks.
 * <p>
 * It is never injected. An injected <code>@ConfigMapping</code> makes Quarkus check the
 * <code>vanillabp.*</code> tree while it initializes its static configuration, before the
 * platform's own mappings are registered, so every start fails with the platform's keys named as
 * unknown, and the message points at those keys and not at the injection. The test reads the main
 * sources of the Quarkus modules, because a source shows every CDI injection point: a field marked
 * <code>@Inject</code>, and a parameter of a constructor or of a method CDI calls, a producer, a
 * disposer, an observer or an initializer. A parameter of a method the adapter calls itself is no
 * injection point, so a helper may take the overlay a producer read.
 * <p>
 * It knows every key the Spring Boot overlay knows, at the same place in the tree: an application
 * moved from one platform to the other keeps its configuration. The test walks both overlays by
 * reflection and compares every key path, with <code>*</code> for an adapter id, a workflow
 * module, a workflow or a task.
 */
@ExtendWith(SuppressOutputExtension.class)
class QuarkusOverlayTest {

    private static final Path ROOT = CoverageGate.repositoryRoot("coverage.repository.root");

    private static final String MAPPING = "VanillaBpZenBpmProperties";

    /** A block or line comment, which may name an annotation without being one. */
    private static final Pattern COMMENT = Pattern.compile("(?s)/\\*.*?\\*/|//[^\\n]*");

    /** An array in the parentheses of an annotation, whose braces would cut a declaration apart. */
    private static final Pattern ANNOTATION_ARRAY = Pattern.compile("\\(\\s*\\{[^{}]*}\\s*\\)");

    /** A declaration: what stands before the brace which opens a body or the semicolon which ends it. */
    private static final Pattern DECLARATION = Pattern.compile("[^;{}]+[;{]");

    /**
     * A field marked <code>@Inject</code>, imported or written out, whose declaration names the
     * mapping.
     */
    private static final Pattern INJECTED = Pattern
            .compile("@(?:jakarta\\.inject\\.)?Inject\\b[^;{)]*\\b%s\\b".formatted(MAPPING));

    /**
     * A parameter of the mapping's type, plain or inside <code>Instance&lt;&gt;</code> or
     * <code>Provider&lt;&gt;</code>.
     */
    private static final Pattern PARAMETER = Pattern.compile(
            "[(,]\\s*(?:@\\w+(?:\\([^)]*\\))?\\s+)*(?:final\\s+)?(?:(?:[\\w.]+\\.)?(?:Instance|Provider)\\s*<\\s*(?:\\?\\s+extends\\s+)?)?(?:[\\w.]+\\.)?%s\\s*>?\\s+\\w+\\s*[,)]"
                    .formatted(MAPPING));

    /**
     * The annotations of a method whose parameters CDI fills where it calls it: a producer, a
     * disposer, an observer, an initializer.
     */
    private static final Pattern CALLED_BY_CDI = Pattern.compile(
            "@(?:[\\w.]+\\.)?(?:Produces|Disposes|Observes|ObservesAsync|Inject)\\b");

    @Test
    @DisplayName("No class of the Quarkus extension injects the configuration overlay")
    void theOverlayIsNeverInjected() throws IOException {
        final var offenders = new ArrayList<String>();
        for (final var source : quarkusMainSources()) {
            if (injectsTheMapping(source
                    .getFileName()
                    .toString()
                    .replace(".java", ""), Files.readString(source))) {
                offenders.add(ROOT
                        .relativize(source)
                        .toString());
            }
        }

        assertEquals(List.of(), offenders, () -> """
                These classes inject %s, which makes Quarkus check the configuration before the \
                platform's mappings are registered, so the start fails with the platform's keys \
                named as unknown. Read it with \
                ConfigProvider.getConfig().unwrap(SmallRyeConfig.class).getConfigMapping(%s.class) \
                instead: %s""".formatted(MAPPING, MAPPING, offenders));
    }

    @Test
    @DisplayName("The rule against injection finds what CDI fills, and leaves alone what the adapter's own code passes on")
    void theRuleTellsAnInjectionFromAHelper() {
        assertAll(
                () -> assertTrue(injectsTheMapping("Producer", """
                        @Inject
                        VanillaBpZenBpmProperties overlay;""")),
                () -> assertTrue(injectsTheMapping("Producer", """
                        @jakarta.inject.Inject Map<String, VanillaBpZenBpmProperties> overlays;""")),
                () -> assertTrue(injectsTheMapping("Producer", """
                        @Produces @Singleton @SuppressWarnings({"unused"})
                        Registry registry(final StartupReport report, final VanillaBpZenBpmProperties overlay) {""")),
                () -> assertTrue(injectsTheMapping("Producer",
                        """
                                void close(@Disposes final Registry registry, Instance<? extends VanillaBpZenBpmProperties> overlay) {""")),
                () -> assertTrue(injectsTheMapping("Observer",
                        """
                                void onStart(@Observes StartupEvent event, jakarta.inject.Provider<VanillaBpZenBpmProperties> overlay) {""")),
                () -> assertTrue(injectsTheMapping("Producer", """
                        Producer(final VanillaBpZenBpmProperties overlay) {""")),
                () -> assertFalse(injectsTheMapping("Producer", """
                        private static Duration retriesOf(final VanillaBpZenBpmProperties overlay) {""")),
                () -> assertFalse(injectsTheMapping("Producer", """
                        @Produces
                        Registry registry() {
                            return registryOf(overlay());
                        }

                        private static Registry registryOf(final VanillaBpZenBpmProperties overlay) {""")),
                () -> assertFalse(injectsTheMapping("Producer", """
                        /** Never write {@code @Inject VanillaBpZenBpmProperties overlay;} here. */
                        // nor @Produces Registry registry(VanillaBpZenBpmProperties overlay)
                        private final Map<String, String> names = new HashMap<>();""")),
                () -> assertFalse(injectsTheMapping("Producer",
                        """
                                final var producer = new Producer((VanillaBpZenBpmProperties overlay) -> overlay.adapters());""")));
    }

    @Test
    @DisplayName("The rule against injection is checked on the sources which read the overlay")
    void theSourcesAreFound() throws IOException {
        final var readers = quarkusMainSources()
                .stream()
                .filter(source -> {
                    try {
                        return Files
                                .readString(source)
                                .contains("getConfigMapping(%s.class)".formatted(MAPPING));
                    } catch (final IOException e) {
                        throw new IllegalStateException(e);
                    }
                })
                .count();

        assertTrue(readers > 0, "no source of the Quarkus modules reads the overlay, so the check above reads nothing");
    }

    @Test
    @DisplayName("The Quarkus overlay knows every key of the Spring Boot overlay, at the same place in the tree")
    void theQuarkusOverlayKnowsEveryKeyOfTheSpringBootOverlay() throws ClassNotFoundException {
        final var springBoot = OverlayKeys.ofSpringBoot();
        final var quarkus = OverlayKeys.ofQuarkus();

        assertTrue(springBoot.contains("vanillabp.adapters.*.rest-address"), springBoot::toString);
        assertTrue(springBoot.contains(
                "vanillabp.workflow-modules.*.workflows.*.tasks.*.adapters.*.retry-backoff"), springBoot::toString);
        assertEquals(springBoot, quarkus, () -> "only Spring Boot knows %s, only Quarkus knows %s".formatted(
                difference(springBoot, quarkus), difference(quarkus, springBoot)));
    }

    // a field marked to be injected, or a parameter of a method CDI calls or of a constructor:
    // Quarkus injects into the one constructor of a bean even without @Inject
    private static boolean injectsTheMapping(
        final String className,
        final String source
    ) {
        final var code = ANNOTATION_ARRAY
                .matcher(COMMENT
                        .matcher(source)
                        .replaceAll(" "))
                .replaceAll("()");
        if (INJECTED
                .matcher(code)
                .find()) {
            return true;
        }
        final var constructor = Pattern.compile("(?<!new\\s)\\b%s\\s*\\(".formatted(Pattern.quote(className)));
        final var declarations = DECLARATION.matcher(code);
        while (declarations.find()) {
            final var declaration = declarations.group();
            if (PARAMETER
                    .matcher(declaration)
                    .find() && (CALLED_BY_CDI
                            .matcher(declaration)
                            .find() || constructor
                                    .matcher(declaration)
                                    .find())) {
                return true;
            }
        }
        return false;
    }

    private static List<Path> quarkusMainSources() throws IOException {
        try (var files = Files.walk(ROOT.resolve("quarkus"))) {
            return files
                    .filter(path -> path
                            .toString()
                            .endsWith(".java"))
                    .filter(path -> ROOT
                            .relativize(path)
                            .toString()
                            .replace('\\', '/')
                            .matches("quarkus/[^/]+/src/main/java/.*"))
                    .sorted()
                    .toList();
        }
    }

    private static String difference(
        final SortedSet<String> keys,
        final SortedSet<String> others
    ) {
        return keys
                .stream()
                .filter(key -> !others.contains(key))
                .collect(Collectors.joining(", ", "[", "]"));
    }

}

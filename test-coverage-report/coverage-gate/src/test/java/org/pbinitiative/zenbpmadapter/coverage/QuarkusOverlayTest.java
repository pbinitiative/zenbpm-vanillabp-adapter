/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.coverage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;
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
 * It is never injected. An injected <code>@ConfigMapping</code> is validated while Quarkus
 * initializes its static configuration, before the mappings of the adapters are registered, so
 * every adapter key fails the start as unknown, and the message points at the key and not at the
 * injection. The test reads the main sources of the Quarkus modules, because a CDI injection point
 * is a field marked <code>@Inject</code> or a parameter of a method CDI calls, and a source shows
 * both.
 * <p>
 * It knows every key the Spring Boot overlay knows, at the same place in the tree: an application
 * moved from one platform to the other keeps its configuration. The test walks both overlays by
 * reflection and compares every key path, with <code>*</code> for an adapter id, a workflow
 * module, a workflow or a task.
 */
@ExtendWith(SuppressOutputExtension.class)
class QuarkusOverlayTest {

    private static final Path ROOT = CoverageGate.repositoryRoot("coverage.repository.root");

    private static final String QUARKUS_OVERLAY = "org.pbinitiative.zenbpmadapter.quarkus.runtime.VanillaBpZenBpmProperties";

    private static final String SPRING_BOOT_OVERLAY = "org.pbinitiative.zenbpmadapter.springboot.client.VanillaBpZenBpmProperties";

    private static final String OWN_PACKAGES = "org.pbinitiative.zenbpmadapter.";

    private static final String MAPPING = "VanillaBpZenBpmProperties";

    /**
     * A field or parameter marked <code>@Inject</code>, imported or written out, whose declaration
     * names the mapping.
     */
    private static final Pattern INJECTED = Pattern
            .compile("@(?:jakarta\\.inject\\.)?Inject\\b[^;{)]*\\b%s\\b".formatted(MAPPING));

    /**
     * A parameter of the mapping's type, plain or inside <code>Instance&lt;&gt;</code> or
     * <code>Provider&lt;&gt;</code>, which CDI fills where it calls the method: a producer, an
     * observer, a constructor.
     */
    private static final Pattern PARAMETER = Pattern.compile(
            "[(,]\\s*(?:@\\w+(?:\\([^)]*\\))?\\s+)*(?:final\\s+)?(?:(?:[\\w.]+\\.)?(?:Instance|Provider)\\s*<\\s*)?(?:[\\w.]+\\.)?%s\\s*>?\\s+\\w+\\s*[,)]"
                    .formatted(MAPPING));

    @Test
    @DisplayName("No class of the Quarkus extension injects the configuration overlay")
    void theOverlayIsNeverInjected() throws IOException {
        final var offenders = new ArrayList<String>();
        for (final var source : quarkusMainSources()) {
            final var text = Files.readString(source);
            if (INJECTED
                    .matcher(text)
                    .find() || PARAMETER
                            .matcher(text)
                            .find()) {
                offenders.add(ROOT
                        .relativize(source)
                        .toString());
            }
        }

        assertEquals(List.of(), offenders, () -> """
                These classes inject %s, which Quarkus then validates before the adapters' mappings \
                are registered, so every key of the adapter fails the start. Read it with \
                ConfigProvider.getConfig().unwrap(SmallRyeConfig.class).getConfigMapping(%s.class) \
                instead: %s""".formatted(MAPPING, MAPPING, offenders));
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
        final var springBoot = new TreeSet<String>();
        springBootKeys(Class.forName(SPRING_BOOT_OVERLAY), "vanillabp", springBoot);
        final var quarkus = new TreeSet<String>();
        quarkusKeys(Class.forName(QUARKUS_OVERLAY), "vanillabp", quarkus);

        assertTrue(springBoot.contains("vanillabp.adapters.*.rest-address"), springBoot::toString);
        assertTrue(springBoot.contains(
                "vanillabp.workflow-modules.*.workflows.*.tasks.*.adapters.*.retry-backoff"), springBoot::toString);
        assertEquals(springBoot, quarkus, () -> "only Spring Boot knows %s, only Quarkus knows %s".formatted(
                difference(springBoot, quarkus), difference(quarkus, springBoot)));
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

    // a property is a getter with a setter, as Spring Boot binds it
    private static void springBootKeys(
        final Class<?> type,
        final String path,
        final SortedSet<String> keys
    ) {
        for (final var getter : type.getMethods()) {
            final var name = getter.getName();
            if (getter.getParameterCount() != 0 || getter.getDeclaringClass() == Object.class || !name.startsWith(
                    "get") || !hasSetter(type, name.substring(3))) {
                continue;
            }
            walk(getter.getGenericReturnType(), "%s.%s".formatted(path, kebab(name.substring(3))), keys,
                    QuarkusOverlayTest::springBootKeys);
        }
    }

    // a property is an abstract method without parameters, as SmallRye maps it
    private static void quarkusKeys(
        final Class<?> type,
        final String path,
        final SortedSet<String> keys
    ) {
        for (final var method : type.getMethods()) {
            if (method.getParameterCount() != 0 || method.isDefault() || Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            walk(method.getGenericReturnType(), "%s.%s".formatted(path, kebab(method.getName())), keys,
                    QuarkusOverlayTest::quarkusKeys);
        }
    }

    private static void walk(
        final Type type,
        final String path,
        final SortedSet<String> keys,
        final Walker walker
    ) {
        if (type instanceof ParameterizedType parameterized && parameterized.getRawType() == Map.class) {
            walk(parameterized.getActualTypeArguments()[1], "%s.*".formatted(path), keys, walker);
        } else if (type instanceof ParameterizedType parameterized && parameterized.getRawType() == Optional.class) {
            keys.add(path);
        } else if (type instanceof Class<?> section && !section.isEnum() && section
                .getName()
                .startsWith(OWN_PACKAGES)) {
                    walker.keysOf(section, path, keys);
                } else {
                    keys.add(path);
                }
    }

    private static boolean hasSetter(
        final Class<?> type,
        final String property
    ) {
        return Arrays
                .stream(type.getMethods())
                .map(Method::getName)
                .anyMatch("set%s".formatted(property)::equals);
    }

    private static String kebab(final String camelCase) {
        return camelCase
                .replaceAll("([a-z0-9])([A-Z])", "$1-$2")
                .toLowerCase(Locale.ROOT);
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

    @FunctionalInterface
    private interface Walker {

        void keysOf(
            Class<?> section,
            String path,
            SortedSet<String> keys
        );

    }

}

/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.coverage;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Every key the configuration overlays of the two platforms read, as a path below
 * <code>vanillabp</code> with <code>*</code> for an adapter id, a workflow module, a workflow or a
 * task, e.g. <code>vanillabp.adapters.*.rest-address</code>. The overlays are walked by reflection,
 * so a key added to the code shows here without anybody listing it.
 */
final class OverlayKeys {

    private static final String QUARKUS_OVERLAY = "org.pbinitiative.zenbpmadapter.quarkus.runtime.VanillaBpZenBpmProperties";

    private static final String SPRING_BOOT_OVERLAY = "org.pbinitiative.zenbpmadapter.springboot.client.VanillaBpZenBpmProperties";

    private static final String OWN_PACKAGES = "org.pbinitiative.zenbpmadapter.";

    private OverlayKeys() {
    }

    /**
     * The keys of the Spring Boot overlay.
     *
     * @return The key paths, sorted
     * @throws ClassNotFoundException Where the overlay is not on the classpath of the test
     */
    static SortedSet<String> ofSpringBoot() throws ClassNotFoundException {
        final var keys = new TreeSet<String>();
        springBootKeys(Class.forName(SPRING_BOOT_OVERLAY), "vanillabp", keys);
        return keys;
    }

    /**
     * The keys of the Quarkus overlay.
     *
     * @return The key paths, sorted
     * @throws ClassNotFoundException Where the overlay is not on the classpath of the test
     */
    static SortedSet<String> ofQuarkus() throws ClassNotFoundException {
        final var keys = new TreeSet<String>();
        quarkusKeys(Class.forName(QUARKUS_OVERLAY), "vanillabp", keys);
        return keys;
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
                    OverlayKeys::springBootKeys);
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
                    OverlayKeys::quarkusKeys);
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

    @FunctionalInterface
    private interface Walker {

        void keysOf(
            Class<?> section,
            String path,
            SortedSet<String> keys
        );

    }

}

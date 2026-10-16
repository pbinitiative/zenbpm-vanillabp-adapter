/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.smoke;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientFactory;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientRegistry;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Two adapter ids of type <code>zenbpm</code> on one engine on Spring Boot. With the same
 * name-clash-avoidance mode they are one instance, and the start ends naming both ids and the keys
 * which tell them apart. With two modes they are the migration from one mode to the other, and the
 * application boots. The applications start inside the tests, so each test reads its own start.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmDistinctInstancesBootTest {

    @Test
    @DisplayName("Two adapter ids on one engine with the same mode end the start, naming both ids and the keys which tell them apart")
    void oneEngineWithOneModeEndsTheStart() {
        final var failure = rootOf(assertThrows(Exception.class, () -> run(oneEngine("use-prefix", "use-prefix"))
                .close()));
        final var message = failure.getMessage();

        assertAll(
                () -> assertTrue(message.startsWith(
                        "ZenBPM adapter ids 'zenbpm-new' and 'zenbpm-old' address the same engine, 'http://localhost:8080/v1', with the same name-clash-avoidance 'use-prefix',"),
                        message),
                () -> assertTrue(message.contains(
                        "'vanillabp.adapters.zenbpm-new.rest-address', 'vanillabp.adapters.zenbpm-old.rest-address'"),
                        message),
                () -> assertTrue(message.contains(
                        "'vanillabp.adapters.zenbpm-new.name-clash-avoidance', 'vanillabp.adapters.zenbpm-old.name-clash-avoidance'"),
                        message));
    }

    @Test
    @DisplayName("Two adapter ids on one engine with two modes boot, as the migration from one mode to the other")
    void oneEngineWithTwoModesBoots(final CapturedOutput output) {
        try (var context = run(oneEngine("use-prefix", "none"))) {
            final var log = output.getAllOfThisTest();

            assertAll(
                    () -> assertEquals(List.of("zenbpm-new", "zenbpm-old"), context
                            .getBean(ZenBpmClientRegistry.class)
                            .factories()
                            .stream()
                            .map(ZenBpmClientFactory::getAdapterId)
                            .toList()),
                    () -> assertEquals(List.of("zenbpm-old"), context
                            .getBean(ZenBpmClientRegistry.class)
                            .sharesEngineWith("zenbpm-new")),
                    () -> assertTrue(StartupBlock.isWritten(log), log),
                    () -> assertEquals("", StartupBlock.configuration(log), log));
        }
    }

    // the new engine first, as in a migration, both on one address
    private static String[] oneEngine(
        final String newMode,
        final String oldMode
    ) {
        return new String[]{
                "vanillabp.prioritized-adapters=zenbpm-new,zenbpm-old", "vanillabp.adapters.zenbpm-new.type=zenbpm", "vanillabp.adapters.zenbpm-new.rest-address=http://localhost:8080", "vanillabp.adapters.zenbpm-new.name-clash-avoidance=%s"
                        .formatted(
                                newMode), "vanillabp.adapters.zenbpm-old.type=zenbpm", "vanillabp.adapters.zenbpm-old.rest-address=http://localhost:8080", "vanillabp.adapters.zenbpm-old.name-clash-avoidance=%s"
                                        .formatted(oldMode)
        };
    }

    private static ConfigurableApplicationContext run(final String... properties) {
        return new SpringApplicationBuilder(SmokeTestApplication.class)
                .web(WebApplicationType.NONE)
                .run(Stream
                        .of(properties)
                        .map("--%s"::formatted)
                        .toArray(String[]::new));
    }

    private static Throwable rootOf(final Throwable throwable) {
        var cause = throwable;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

}

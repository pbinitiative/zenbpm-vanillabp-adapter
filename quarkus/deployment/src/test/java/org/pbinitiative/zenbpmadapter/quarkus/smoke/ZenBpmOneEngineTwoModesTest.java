/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.smoke;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientFactory;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientRegistry;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;

/**
 * Two adapter ids of type <code>zenbpm</code> on one engine with two name-clash-avoidance modes
 * boot on Quarkus: they are the migration from one mode to the other, and they reach the engine
 * with different identifiers.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmOneEngineTwoModesTest {

    @RegisterExtension
    static final QuarkusExtensionTest extensionTest = SmokeApplication
            .oneEngine("use-prefix", "none")
            .assertLogRecords(records -> {
                final var log = SmokeApplication.logOf(records);
                assertAll(
                        () -> assertTrue(StartupBlock.isWritten(log), log),
                        () -> assertEquals("", StartupBlock.configuration(log), log));
            });

    @Inject
    ZenBpmClientRegistry registry;

    @Test
    @DisplayName("Two adapter ids on one engine with two modes boot, and the registry knows that they share the engine")
    void oneEngineWithTwoModesBoots() {
        assertAll(
                () -> assertEquals(List.of("zenbpm-new", "zenbpm-old"), registry
                        .factories()
                        .stream()
                        .map(ZenBpmClientFactory::getAdapterId)
                        .toList()),
                () -> assertEquals(List.of("zenbpm-old"), registry.sharesEngineWith("zenbpm-new")));
    }

}

/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.smoke;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Two adapter ids of type <code>zenbpm</code> on one engine with the same name-clash-avoidance
 * mode are one instance on Quarkus: the start ends before anything is deployed, naming both ids and
 * the keys which tell them apart.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmOneEngineOneModeTest {

    @RegisterExtension
    static final QuarkusExtensionTest extensionTest = SmokeApplication
            .oneEngine("use-prefix", "use-prefix")
            .assertException(failure -> {
                final var message = SmokeApplication.rootMessageOf(failure);
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
            });

    @Test
    @DisplayName("Two adapter ids on one engine with the same mode end the start, naming both ids and the keys which tell them apart")
    void oneEngineWithOneModeEndsTheStart() {
        // never runs: the start fails, and the extension checks how
    }

}

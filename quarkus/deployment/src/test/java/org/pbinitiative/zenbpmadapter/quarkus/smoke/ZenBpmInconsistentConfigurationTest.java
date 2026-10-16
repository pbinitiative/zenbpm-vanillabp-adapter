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
 * An inconsistent adapter id which is first priority ends the start on Quarkus, and the message
 * names the key and says why the id cannot boot degraded.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmInconsistentConfigurationTest {

    @RegisterExtension
    static final QuarkusExtensionTest extensionTest = SmokeApplication
            .extensionTest()
            .overrideConfigKey("vanillabp.prioritized-adapters", "zenbpm")
            .overrideConfigKey("vanillabp.adapters.zenbpm.rest-address", "ftp://engine:8080")
            .assertException(failure -> {
                final var message = SmokeApplication.rootMessageOf(failure);
                assertAll(
                        () -> assertTrue(message.startsWith("ZenBPM adapter 'zenbpm' is configured inconsistently:\n"
                                + "  - vanillabp.adapters.zenbpm.rest-address "), message),
                        () -> assertTrue(message.contains(
                                "The adapter is first priority somewhere, so it cannot boot degraded."), message));
            });

    @Test
    @DisplayName("An inconsistent adapter id which is first priority ends the start, naming the key")
    void anInconsistentFirstPriorityAdapterIdEndsTheStart() {
        // never runs: the start fails, and the extension checks how
    }

}

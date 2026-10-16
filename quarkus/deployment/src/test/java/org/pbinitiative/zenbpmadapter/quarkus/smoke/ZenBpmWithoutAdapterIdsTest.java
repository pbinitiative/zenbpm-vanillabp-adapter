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
 * An application with the ZenBPM adapter on its classpath which configures no adapter id of type
 * <code>zenbpm</code>, only one of the platform's BPMS double. Unlike Spring Boot, where it boots and
 * the adapter has nothing, the platform on Quarkus refuses to start it: every adapter extension on
 * the classpath has to be configured, and the message names the key which configures one of this
 * type. An application which configures nothing at all boots, with the adapter id the platform
 * derives, see {@code ZenBpmStartupValidationTest}.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmWithoutAdapterIdsTest {

    @RegisterExtension
    static final QuarkusExtensionTest extensionTest = SmokeApplication
            .withTheBpmsDouble()
            .overrideConfigKey("vanillabp.prioritized-adapters", "dummy")
            .overrideConfigKey("vanillabp.adapters.dummy.type", "dummy")
            .assertException(failure -> {
                final var message = SmokeApplication.rootMessageOf(failure);
                assertAll(
                        () -> assertTrue(message.startsWith(
                                "VanillaBP Quarkus adapter extensions found but not configured."), message),
                        () -> assertTrue(message.contains("vanillabp.adapters.*.type=zenbpm"), message));
            });

    @Test
    @DisplayName("Without an adapter id of type zenbpm the platform refuses the start and names the key")
    void noAdapterIdOfThisTypeIsRefused() {
        // never runs: the start fails, and the extension checks how
    }

}

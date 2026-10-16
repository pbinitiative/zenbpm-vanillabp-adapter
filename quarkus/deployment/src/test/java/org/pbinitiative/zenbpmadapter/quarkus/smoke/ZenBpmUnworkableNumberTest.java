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
 * A number which cannot work is a typo, so it ends the start on Quarkus even of an adapter id which
 * may boot degraded.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmUnworkableNumberTest {

    @RegisterExtension
    static final QuarkusExtensionTest extensionTest = SmokeApplication
            .degradableOldEngine()
            .overrideConfigKey("vanillabp.adapters.old.worker-threads", "0")
            .assertException(failure -> {
                final var message = SmokeApplication.rootMessageOf(failure);
                assertAll(
                        () -> assertTrue(message.startsWith("ZenBPM adapter 'old' is configured inconsistently:"),
                                message),
                        () -> assertTrue(message.contains("vanillabp.adapters.old.worker-threads"), message),
                        () -> assertTrue(message.contains(
                                "A number or a duration which cannot work ends the boot even where"), message));
            });

    @Test
    @DisplayName("A number which cannot work ends the start even of an adapter id which may boot degraded")
    void aNumberWhichCannotWorkEndsTheStartOfADegradableAdapterId() {
        // never runs: the start fails, and the extension checks how
    }

}

/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.smoke;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * <code>max-redeliveries</code> is no key of the adapter: the engine counts the attempts of a job
 * itself, from the <code>retries</code> of the model, so the adapter keeps no retry of its own and
 * no number for it, see decision 27 in the repository's DECISIONS.md. On Quarkus the key ends the
 * start like any key the overlay does not model.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmRemovedKeyTest {

    @RegisterExtension
    static final QuarkusExtensionTest extensionTest = SmokeApplication
            .extensionTest()
            .overrideConfigKey("vanillabp.prioritized-adapters", "zenbpm")
            .overrideConfigKey("vanillabp.adapters.zenbpm.rest-address", "http://localhost:8080")
            .overrideConfigKey("vanillabp.adapters.zenbpm.max-redeliveries", "3")
            .assertException(failure -> {
                final var messages = SmokeApplication.messagesOf(failure);
                assertTrue(messages.contains("SRCFG00050: vanillabp.adapters.zenbpm.max-redeliveries"), messages);
            });

    @Test
    @DisplayName("max-redeliveries, which the adapter does not have, fails the start and names the key")
    void maxRedeliveriesFailsTheStart() {
        // never runs: the start fails, and the extension checks how
    }

}

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
 * A misspelt key under <code>vanillabp.adapters.&lt;id&gt;.</code> fails the start on Quarkus and
 * names the key, because the adapter's overlay models every key it reads and SmallRye Config
 * refuses a key no mapping knows. That is the difference to Spring Boot, which ignores the key.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmUnknownKeyTest {

    @RegisterExtension
    static final QuarkusExtensionTest extensionTest = SmokeApplication
            .extensionTest()
            .overrideConfigKey("vanillabp.prioritized-adapters", "zenbpm")
            .overrideConfigKey("vanillabp.adapters.zenbpm.rest-adress", "http://localhost:8080")
            .assertException(failure -> {
                final var messages = SmokeApplication.messagesOf(failure);
                assertTrue(messages.contains("SRCFG00050: vanillabp.adapters.zenbpm.rest-adress"), messages);
            });

    @Test
    @DisplayName("A misspelt key fails the start and names the key")
    void aMisspeltKeyFailsTheStart() {
        // never runs: the start fails, and the extension checks how
    }

}

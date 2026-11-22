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
 * <code>rest-address</code> is read for an adapter id and nowhere below it, so the overlay models
 * it at that level only. Written for a workflow module, it is a key no mapping knows, and it ends
 * the start on Quarkus like a misspelt one. Spring Boot ends the start for it as well, with a
 * message of the platform which names the level where the key is read.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmMisplacedKeyTest {

    private static final String MISPLACED = "vanillabp.workflow-modules.smoke-app.adapters.zenbpm.rest-address";

    @RegisterExtension
    static final QuarkusExtensionTest extensionTest = SmokeApplication
            .extensionTest()
            .overrideConfigKey("vanillabp.prioritized-adapters", "zenbpm")
            .overrideConfigKey("vanillabp.adapters.zenbpm.rest-address", "http://localhost:8080")
            .overrideConfigKey(MISPLACED, "http://localhost:8081")
            .assertException(failure -> {
                final var messages = SmokeApplication.messagesOf(failure);
                assertTrue(messages.contains("SRCFG00050: %s".formatted(MISPLACED)), messages);
            });

    @Test
    @DisplayName("A key written at a level where the adapter does not read it fails the start and names the key")
    void aKeyAtALevelWhereItIsNotReadFailsTheStart() {
        // never runs: the start fails, and the extension checks how
    }

}

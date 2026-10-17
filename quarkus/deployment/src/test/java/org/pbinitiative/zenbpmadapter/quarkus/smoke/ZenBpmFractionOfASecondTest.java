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
 * The engine keeps a <code>history-time-to-live</code> in whole seconds and would cut a fraction
 * without a word, so a value with one ends the start on Quarkus, naming the full key the application
 * wrote and what the engine would keep instead.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmFractionOfASecondTest {

    private static final String WORKFLOW_KEY = "vanillabp.workflow-modules.smoke-app.workflows.SampleWorkflowService.adapters.zenbpm.history-time-to-live";

    @RegisterExtension
    static final QuarkusExtensionTest extensionTest = SmokeApplication
            .extensionTest()
            .overrideConfigKey("vanillabp.prioritized-adapters", "zenbpm")
            .overrideConfigKey("vanillabp.adapters.zenbpm.rest-address", "http://localhost:8080")
            .overrideConfigKey(WORKFLOW_KEY, "PT0.5S")
            .assertException(failure -> {
                final var message = SmokeApplication.rootMessageOf(failure);
                assertTrue(message.contains(
                        "%s is PT0.5S, but the engine keeps whole seconds and would keep PT0S; write whole seconds, or remove the key, so the level above applies"
                                .formatted(WORKFLOW_KEY)),
                        message);
            });

    @Test
    @DisplayName("A history-time-to-live with a fraction of a second ends the start, naming the key where it was written")
    void aFractionOfASecondEndsTheStart() {
        // never runs: the start fails, and the extension checks how
    }

}

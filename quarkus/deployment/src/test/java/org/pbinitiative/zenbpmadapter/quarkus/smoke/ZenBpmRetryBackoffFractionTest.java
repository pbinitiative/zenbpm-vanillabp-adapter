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
 * The engine takes the backoff of a failure in whole seconds and refuses a fraction of one, so a
 * <code>retry-backoff</code> with one ends the start on Quarkus, naming the full key the application
 * wrote.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmRetryBackoffFractionTest {

    private static final String TASK_KEY = "vanillabp.workflow-modules.smoke-app.workflows.SampleWorkflowService.tasks.ship.adapters.zenbpm.retry-backoff";

    @RegisterExtension
    static final QuarkusExtensionTest extensionTest = SmokeApplication
            .extensionTest()
            .overrideConfigKey("vanillabp.prioritized-adapters", "zenbpm")
            .overrideConfigKey("vanillabp.adapters.zenbpm.rest-address", "http://localhost:8080")
            .overrideConfigKey(TASK_KEY, "PT1.5S")
            .assertException(failure -> {
                final var message = SmokeApplication.rootMessageOf(failure);
                assertTrue(message.contains(
                        "%s is PT1.5S, but the engine takes a backoff in whole seconds; write whole seconds, or remove the key, so the level above applies"
                                .formatted(TASK_KEY)),
                        message);
            });

    @Test
    @DisplayName("A retry-backoff with a fraction of a second ends the start, naming the key where it was written")
    void aFractionOfASecondEndsTheStart() {
        // never runs: the start fails, and the extension checks how
    }

}

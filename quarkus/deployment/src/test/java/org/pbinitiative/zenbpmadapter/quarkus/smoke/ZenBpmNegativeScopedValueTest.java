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
 * A value below the adapter level goes through the validation like the adapter's own, so a negative
 * one ends the start on Quarkus and names the full key the application wrote.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmNegativeScopedValueTest {

    private static final String TASK_KEY = "vanillabp.workflow-modules.smoke-app.workflows.SampleWorkflowService.tasks.ship.adapters.zenbpm.retry-backoff";

    @RegisterExtension
    static final QuarkusExtensionTest extensionTest = SmokeApplication
            .extensionTest()
            .overrideConfigKey("vanillabp.prioritized-adapters", "zenbpm")
            .overrideConfigKey("vanillabp.adapters.zenbpm.rest-address", "http://localhost:8080")
            .overrideConfigKey(TASK_KEY, "-PT1S")
            .assertException(failure -> {
                final var message = SmokeApplication.rootMessageOf(failure);
                assertTrue(message.contains("%s is PT-1S and must not be negative".formatted(TASK_KEY)), message);
            });

    @Test
    @DisplayName("A negative value below the adapter level ends the start, naming the key where it was written")
    void aNegativeScopedValueEndsTheStart() {
        // never runs: the start fails, and the extension checks how
    }

}

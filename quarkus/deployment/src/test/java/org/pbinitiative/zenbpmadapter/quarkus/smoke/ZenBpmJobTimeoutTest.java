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
 * Two tasks of one job type, whose task-level <code>job-timeout</code>s differ, end the start of a
 * Quarkus application, naming both values and the keys to set, before the engine is asked anything:
 * the engine locks the jobs of a type for one duration. The processes <code>Shipping</code> and
 * <code>Returns</code> each have a task <code>ship</code>, which under
 * <code>name-clash-avoidance: none</code> is one job type.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmJobTimeoutTest {

    private static final String WORKFLOWS = "vanillabp.workflow-modules.smoke-app.workflows.";

    @RegisterExtension
    static final QuarkusExtensionTest extensionTest = SmokeApplication
            .withTwoProcessesSharingATaskDefinition()
            .overrideConfigKey("vanillabp.prioritized-adapters", "zenbpm")
            .overrideConfigKey("vanillabp.adapters.zenbpm.rest-address", "http://localhost:1")
            .overrideConfigKey("vanillabp.adapters.zenbpm.name-clash-avoidance", "none")
            .overrideConfigKey("vanillabp.adapters.zenbpm.accept-unscoped-identifiers", "true")
            .overrideConfigKey("vanillabp.adapters.zenbpm.resources-location", SmokeApplication.WITH_TWO_SHIPPERS)
            .overrideConfigKey(WORKFLOWS
                    + "Shipping.tasks.ship.adapters.zenbpm.job-timeout", "PT1M")
            .overrideConfigKey(WORKFLOWS
                    + "Returns.tasks.ship.adapters.zenbpm.job-timeout", "PT2M")
            .assertException(failure -> {
                final var message = SmokeApplication.rootMessageOf(failure);
                assertAll(
                        () -> assertTrue(message.startsWith(
                                "Job type 'ship' of workflow module 'smoke-app' is served by two tasks with different job-timeouts:"),
                                message),
                        () -> assertTrue(message.contains("PT1M for task 'ship' of process 'Shipping'"), message),
                        () -> assertTrue(message.contains("PT2M for task 'ship' of process 'Returns'"), message),
                        () -> assertTrue(message.contains(WORKFLOWS
                                + "Shipping.tasks.ship.adapters.zenbpm.job-timeout"), message),
                        () -> assertTrue(message.contains(WORKFLOWS
                                + "Returns.tasks.ship.adapters.zenbpm.job-timeout"), message));
            });

    @Test
    @DisplayName("Two task-level job-timeouts of one job type end the start, naming both and the keys to set")
    void twoJobTimeoutsOfOneJobTypeEndTheStart() {
        // never runs: the start fails, and the extension checks how
    }

}

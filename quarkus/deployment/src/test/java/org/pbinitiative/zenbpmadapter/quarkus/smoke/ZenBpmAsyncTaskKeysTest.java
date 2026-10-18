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
 * The two keys of a task left open for its asynchronous completion are read at the adapter level
 * of a Quarkus application and validated while it starts: an <code>async-task-lock-renewal</code>
 * which is not shorter than the platform's <code>vanillabp.delivery.retention</code>, and an
 * <code>async-task-max-age-action</code> which names no action, end the start, naming the keys and
 * the fix.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmAsyncTaskKeysTest {

    @RegisterExtension
    static final QuarkusExtensionTest extensionTest = SmokeApplication
            .extensionTest()
            .overrideConfigKey("vanillabp.prioritized-adapters", "zenbpm")
            .overrideConfigKey("vanillabp.adapters.zenbpm.rest-address", "http://localhost:1")
            .overrideConfigKey("vanillabp.delivery.retention", "PT1H")
            .overrideConfigKey("vanillabp.adapters.zenbpm.async-task-lock-renewal", "PT1H")
            .overrideConfigKey("vanillabp.adapters.zenbpm.async-task-max-age-action", "fail")
            .assertException(failure -> {
                final var message = SmokeApplication.rootMessageOf(failure);
                assertAll(
                        () -> assertTrue(message.startsWith("ZenBPM adapter 'zenbpm' is configured inconsistently:"),
                                message),
                        () -> assertTrue(message.contains(
                                "  - vanillabp.adapters.zenbpm.async-task-lock-renewal is PT1H, which is not shorter than vanillabp.delivery.retention (PT1H)."),
                                message),
                        () -> assertTrue(message.contains(
                                "  - vanillabp.adapters.zenbpm.async-task-max-age-action is 'fail'; it is 'report', the default, or 'incident'"),
                                message));
            });

    @Test
    @DisplayName("A renewal not below the delivery retention and an action which names none end the start, naming the keys")
    void theKeysOfTasksLeftOpenAreValidatedAgainstThePlatform() {
        // never runs: the start fails, and the extension checks how
    }

}

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
 * A key of the adapter below a workflow module which does not exist passes the check for unknown
 * keys, and no task takes its value. The validation still sees it, because the overlay hands it the
 * values of every workflow module it holds. So a negative one ends the start on Quarkus and names
 * the full key the application wrote.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmNegativeValueBelowAnUnknownModuleTest {

    private static final String MODULE_KEY = "vanillabp.workflow-modules.no-such-module.adapters.zenbpm.retry-backoff";

    @RegisterExtension
    static final QuarkusExtensionTest extensionTest = SmokeApplication
            .extensionTest()
            .overrideConfigKey("vanillabp.prioritized-adapters", "zenbpm")
            .overrideConfigKey("vanillabp.adapters.zenbpm.rest-address", "http://localhost:8080")
            .overrideConfigKey(MODULE_KEY, "-PT1S")
            .assertException(failure -> {
                final var message = SmokeApplication.rootMessageOf(failure);
                assertTrue(message.contains("%s is PT-1S and must not be negative".formatted(MODULE_KEY)), message);
            });

    @Test
    @DisplayName("A negative value below a workflow module which does not exist ends the start, naming the key where it was written")
    void aNegativeValueBelowAnUnknownModuleEndsTheStart() {
        // never runs: the start fails, and the extension checks how
    }

}

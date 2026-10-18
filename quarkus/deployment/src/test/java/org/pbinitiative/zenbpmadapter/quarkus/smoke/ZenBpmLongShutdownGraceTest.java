/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.smoke;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientRegistry;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;

/**
 * A <code>shutdown-grace</code> above thirty seconds boots on Quarkus with a warning in the block at
 * the end of the start, which names the shutdown budgets to raise with it.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmLongShutdownGraceTest {

    private static final String WARNING = "vanillabp.adapters.zenbpm.shutdown-grace is PT45S, above the PT30S which the shutdown budgets of the runtimes grant by default";

    @RegisterExtension
    static final QuarkusExtensionTest extensionTest = SmokeApplication
            .extensionTest()
            .overrideConfigKey("vanillabp.prioritized-adapters", "zenbpm")
            .overrideConfigKey("vanillabp.adapters.zenbpm.rest-address", "http://localhost:8080")
            .overrideConfigKey("vanillabp.adapters.zenbpm.shutdown-grace", "PT45S")
            .assertLogRecords(records -> {
                final var block = StartupBlock.configuration(SmokeApplication.logOf(records));
                assertEquals(1, block.split(WARNING, -1).length - 1, block);
            });

    @Inject
    ZenBpmClientRegistry registry;

    @Test
    @DisplayName("A shutdown-grace above thirty seconds boots complete, with a warning")
    void aLongShutdownGraceBootsComplete() {
        assertTrue(registry
                .getFactory("zenbpm")
                .isConfigured());
    }

}

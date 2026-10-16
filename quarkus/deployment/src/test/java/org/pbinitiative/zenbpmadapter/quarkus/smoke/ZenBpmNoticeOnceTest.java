/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.smoke;

import static org.junit.jupiter.api.Assertions.assertAll;
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
 * A notice of the validation stands once in the block at the end of the start on Quarkus, and
 * nowhere else in the log.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmNoticeOnceTest {

    private static final String NOTICE = "'vanillabp.adapters.zenbpm.rest-address' already ends with '/v1'";

    @RegisterExtension
    static final QuarkusExtensionTest extensionTest = SmokeApplication
            .extensionTest()
            .overrideConfigKey("vanillabp.prioritized-adapters", "zenbpm")
            .overrideConfigKey("vanillabp.adapters.zenbpm.rest-address", "http://localhost:8080/v1")
            .assertLogRecords(records -> {
                final var log = SmokeApplication.logOf(records);
                final var block = StartupBlock.configuration(log);
                assertAll(
                        () -> assertTrue(block.contains("  zenbpm adapter 'zenbpm'\n"), block),
                        () -> assertEquals(1, block.split(NOTICE, -1).length - 1, block),
                        () -> assertEquals(1, log.split(NOTICE, -1).length - 1, log));
            });

    @Inject
    ZenBpmClientRegistry registry;

    @Test
    @DisplayName("An address which ends with the version boots complete, with a notice")
    void anAddressWithTheVersionBootsComplete() {
        assertTrue(registry
                .getFactory("zenbpm")
                .isConfigured());
    }

}

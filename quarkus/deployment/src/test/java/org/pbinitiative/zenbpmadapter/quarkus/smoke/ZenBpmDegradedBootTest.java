/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.smoke;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * An address defect of an adapter id which is nowhere first priority and whose
 * <code>deployment-failure</code> policy is <code>warn</code> boots degraded on Quarkus: the old
 * engine of a migration may be away for a while. The block at the end of the start says so.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmDegradedBootTest {

    @RegisterExtension
    static final QuarkusExtensionTest extensionTest = SmokeApplication
            .degradableOldEngine()
            .assertLogRecords(records -> {
                final var block = StartupBlock.configuration(SmokeApplication.logOf(records));
                assertAll(
                        () -> assertTrue(block.contains("  zenbpm adapter 'old'\n"), block),
                        () -> assertTrue(block.contains("ZenBPM adapter 'old' is configured inconsistently:"), block),
                        () -> assertTrue(block.contains(
                                "- vanillabp.adapters.old.rest-address needs the scheme http or https"), block),
                        () -> assertTrue(block.contains("so the application boots DEGRADED"), block));
            });

    @Inject
    ZenBpmClientRegistry registry;

    @Test
    @DisplayName("An address defect of an adapter id nowhere first with policy 'warn' boots degraded")
    void anAddressDefectWhichMayDegradeBootsDegraded() {
        assertFalse(registry
                .getFactory("old")
                .isConfigured());
    }

}

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
 * A degraded adapter id whose workflow module has a BPMN file lets the application boot on Quarkus
 * where it is nowhere first and its <code>deployment-failure</code> policy is <code>warn</code>: the
 * deployment fails with the message of the validation, and the platform reports it as a warning.
 * The platform's BPMS double is the first adapter, as the other BPMS of a migration. The adapter id
 * has a name-clash-avoidance mode ZenBPM serves, so preparing passes.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmDegradedWithBpmnTest {

    @RegisterExtension
    static final QuarkusExtensionTest extensionTest = SmokeApplication
            .withTheBpmsDoubleAndBpmnFiles()
            .overrideConfigKey("vanillabp.prioritized-adapters", "dummy,zenbpm")
            .overrideConfigKey("vanillabp.adapters.dummy.type", "dummy")
            .overrideConfigKey("vanillabp.adapters.dummy.test", "7")
            .overrideConfigKey("vanillabp.adapters.dummy.resources-location", SmokeApplication.WITH_BPMN)
            .overrideConfigKey("vanillabp.adapters.zenbpm.type", "zenbpm")
            .overrideConfigKey("vanillabp.adapters.zenbpm.deployment-failure", "warn")
            .overrideConfigKey("vanillabp.adapters.zenbpm.rest-address", "ftp://old-engine:8080")
            .overrideConfigKey("vanillabp.adapters.zenbpm.name-clash-avoidance", "use-prefix")
            .overrideConfigKey("vanillabp.adapters.zenbpm.resources-location", SmokeApplication.WITH_BPMN)
            .assertLogRecords(records -> {
                final var log = SmokeApplication.logOf(records);
                final var failure = log
                        .indexOf("Deployment of workflow module 'smoke-app' failed for adapter 'zenbpm'!");
                assertAll(
                        () -> assertTrue(failure >= 0, log),
                        () -> assertTrue(log.indexOf("ZenBPM adapter 'zenbpm' is configured inconsistently:",
                                failure) > 0, log));
            });

    @Inject
    ZenBpmClientRegistry registry;

    @Test
    @DisplayName("A degraded adapter id with BPMN files, nowhere first and with policy 'warn', lets the application boot on the adapter which is first")
    void aDegradedAdapterIdWithBpmnFilesBootsOnTheFirstAdapter() {
        assertFalse(registry
                .getFactory("zenbpm")
                .isConfigured());
    }

}

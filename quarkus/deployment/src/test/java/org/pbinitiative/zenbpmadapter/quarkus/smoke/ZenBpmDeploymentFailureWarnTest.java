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
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientRegistry;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;

/**
 * A deployment which fails lets the application boot on Quarkus where the adapter id is nowhere
 * first and its <code>deployment-failure</code> policy is <code>warn</code>: the platform's BPMS
 * double is first, and the log holds the failure. The engine is an address nothing listens on,
 * because this boot runs the test class in the application's own class loader, where no server of
 * the test can be started; that a file the engine refuses ends the start with the engine's reason is
 * shown by ZenBpmRefusedFileTest.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmDeploymentFailureWarnTest {

    // no engine listens on port 1
    private static final String CLOSED_PORT = "http://localhost:1";

    @RegisterExtension
    static final QuarkusExtensionTest extensionTest = SmokeApplication
            .withTheBpmsDoubleAndBpmnFiles()
            .overrideConfigKey("vanillabp.prioritized-adapters", "dummy,zenbpm")
            .overrideConfigKey("vanillabp.adapters.dummy.type", "dummy")
            .overrideConfigKey("vanillabp.adapters.dummy.test", "7")
            .overrideConfigKey("vanillabp.adapters.dummy.resources-location", SmokeApplication.WITH_BPMN)
            .overrideConfigKey("vanillabp.adapters.zenbpm.type", "zenbpm")
            .overrideConfigKey("vanillabp.adapters.zenbpm.deployment-failure", "warn")
            .overrideConfigKey("vanillabp.adapters.zenbpm.rest-address", CLOSED_PORT)
            .overrideConfigKey("vanillabp.adapters.zenbpm.startup-wait", "PT0S")
            .overrideConfigKey("vanillabp.adapters.zenbpm.name-clash-avoidance", "use-prefix")
            .overrideConfigKey("vanillabp.adapters.zenbpm.resources-location", SmokeApplication.WITH_BPMN)
            .assertLogRecords(records -> {
                final var log = SmokeApplication.logOf(records);
                final var failure = log
                        .indexOf("Deployment of workflow module 'smoke-app' failed for adapter 'zenbpm'!");
                assertAll(
                        () -> assertTrue(failure >= 0, log),
                        () -> assertTrue(log.indexOf(CLOSED_PORT, failure) > 0, log));
            });

    @Inject
    ZenBpmClientRegistry registry;

    @Test
    @DisplayName("A deployment which fails lets the application boot where the adapter id is nowhere first and set to 'warn'")
    void aFailedDeploymentOfANowhereFirstAdapterIdLetsTheApplicationBoot() {
        assertTrue(registry
                .getFactory("zenbpm")
                .isConfigured());
    }

}

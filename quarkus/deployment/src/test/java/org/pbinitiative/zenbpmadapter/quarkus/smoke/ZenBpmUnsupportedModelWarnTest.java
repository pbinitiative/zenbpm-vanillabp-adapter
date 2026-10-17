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
import io.vanillabp.integration.test.utils.OneFreePortPerJvm;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;

/**
 * A workflow module whose BPMN file uses what ZenBPM cannot run, on an adapter id which is nowhere
 * first and whose <code>deployment-failure</code> policy is <code>warn</code>: the application boots
 * on Quarkus on the adapter which is first, the platform's BPMS double, and the refusal stands in
 * the block. That nothing is deployed is shown by ZenBpmUnsupportedModelTest: this boot runs the
 * test class in the application's own class loader, so the address names a port nothing listens
 * on.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmUnsupportedModelWarnTest {

    @RegisterExtension
    static final QuarkusExtensionTest extensionTest = SmokeApplication
            .withTheBpmsDoubleAndBpmnFiles("with-bpmn-refused")
            .overrideConfigKey("vanillabp.prioritized-adapters", "dummy,zenbpm")
            .overrideConfigKey("vanillabp.adapters.dummy.type", "dummy")
            .overrideConfigKey("vanillabp.adapters.dummy.test", "7")
            .overrideConfigKey("vanillabp.adapters.dummy.resources-location", SmokeApplication.WITH_REFUSED_BPMN)
            .overrideConfigKey("vanillabp.adapters.zenbpm.type", "zenbpm")
            .overrideConfigKey("vanillabp.adapters.zenbpm.deployment-failure", "warn")
            .overrideConfigKey("vanillabp.adapters.zenbpm.rest-address", "http://localhost:%d".formatted(
                    OneFreePortPerJvm.getPort()))
            .overrideConfigKey("vanillabp.adapters.zenbpm.resources-location", SmokeApplication.WITH_REFUSED_BPMN)
            .assertLogRecords(records -> {
                final var log = SmokeApplication.logOf(records);
                final var failure = log
                        .indexOf("Deployment of workflow module 'smoke-app' failed for adapter 'zenbpm'!");
                assertAll(
                        () -> assertTrue(failure >= 0, log),
                        () -> assertTrue(log.indexOf("- SampleWorkflowService/Calculate (scriptTask)", failure) > 0,
                                log));
            });

    @Inject
    ZenBpmClientRegistry registry;

    @Test
    @DisplayName("With policy 'warn' on an adapter id nowhere first, the application boots on the first adapter, and the refusal stands in the block")
    void anUnsupportedModelOfAnAdapterIdWhichMayFailIsAWarning() {
        assertTrue(registry
                .getFactory("zenbpm")
                .isConfigured());
    }

}

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
import io.vanillabp.integration.spi.startup.StartupTopic;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What the adapter finds while the workflow processing of a module starts is a finding of the start
 * on Quarkus: the platform starts the processing before the block at the end of the start, so the
 * finding stands in the block, under its topic and with its scope. The application declares the old
 * id of a renamed process, and the server standing in for the engine does not answer for the models it
 * holds under that id.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmDeclaredProcessFindingTest {

    @RegisterExtension
    static final QuarkusExtensionTest extensionTest = SmokeApplication
            .withADeclaredProcess()
            .overrideConfigKey("vanillabp.prioritized-adapters", "zenbpm")
            .overrideConfigKey("vanillabp.adapters.zenbpm.rest-address", StandInEngine.addressOfOneTakingEveryFile())
            .overrideConfigKey("vanillabp.adapters.zenbpm.grpc-address", "localhost:1")
            .overrideConfigKey("vanillabp.adapters.zenbpm.name-clash-avoidance", "use-prefix")
            .overrideConfigKey("vanillabp.adapters.zenbpm.resources-location", SmokeApplication.WITH_SHIPPING)
            .assertLogRecords(records -> {
                final var block = StartupBlock.topic(SmokeApplication.logOf(records), StartupTopic.INFRASTRUCTURE);
                assertTrue(block.contains("""
                        process 'Parcels' of workflow module 'smoke-app', zenbpm adapter 'zenbpm'
                              This BPMN process is declared without a model deployed under it, and the models the \
                        engine holds as 'smoke-app__Parcels' could not be read (GET /v1/process-definitions was \
                        answered with status 404"""), block);
            });

    @Test
    @DisplayName("A finding of the start of the workflow processing stands in the block at the end of the start, scoped to the declared process")
    void aFindingOfTheStartOfTheProcessingStandsInTheBlock() {
        // the extension checks the block, which is written while the application starts
    }

}

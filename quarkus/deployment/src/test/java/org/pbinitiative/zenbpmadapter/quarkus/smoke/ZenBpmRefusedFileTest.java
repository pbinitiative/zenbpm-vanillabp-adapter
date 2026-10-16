/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.smoke;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * A BPMN file the engine refuses ends the start on Quarkus, naming file, workflow module, process
 * and the engine's reason.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmRefusedFileTest {

    static final String REASON = "failed to resolve references: no registered BaseElement with ID [nowhere]";

    private static final StandInEngine ENGINE = StandInEngine.refusingEveryFile(REASON);

    @RegisterExtension
    static final QuarkusExtensionTest extensionTest = SmokeApplication
            .withBpmnFiles()
            .overrideConfigKey("vanillabp.prioritized-adapters", "zenbpm")
            .overrideConfigKey("vanillabp.adapters.zenbpm.rest-address", ENGINE.address())
            .overrideConfigKey("vanillabp.adapters.zenbpm.name-clash-avoidance", "use-prefix")
            .overrideConfigKey("vanillabp.adapters.zenbpm.resources-location", SmokeApplication.WITH_BPMN)
            .assertException(failure -> assertTrue(SmokeApplication.messagesOf(failure).contains(
                    """
                            ZenBPM refuses BPMN file 'SampleWorkflowService.bpmn' of workflow module 'smoke-app' (process \
                            'SampleWorkflowService'): POST /v1/process-definitions was answered with status 400, BAD_REQUEST: \
                            %s. Correct the model where the engine's reason points, or take the file out of the workflow \
                            module."""
                            .formatted(REASON)),
                    SmokeApplication.messagesOf(failure)));

    @AfterAll
    static void stopTheEngine() {
        ENGINE.close();
    }

    @Test
    @DisplayName("A file the engine refuses ends the start, naming file, workflow module, process and the engine's reason")
    void aRefusedFileEndsTheStart() {
        // never runs: the start fails, and the extension checks how
    }

}

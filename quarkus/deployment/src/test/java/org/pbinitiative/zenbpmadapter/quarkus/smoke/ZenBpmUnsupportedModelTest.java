/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.smoke;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * A workflow module whose BPMN file uses what ZenBPM cannot run, booted on Quarkus: the start ends
 * with the message naming file, module, process and every element, and nothing reaches the engine
 * but the platform's startup question how many tasks of the process are open, a read.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmUnsupportedModelTest {

    private static final RecordingEngine ENGINE = RecordingEngine.start();

    @RegisterExtension
    static final QuarkusExtensionTest extensionTest = SmokeApplication
            .withBpmnFiles("with-bpmn-refused")
            .overrideConfigKey("vanillabp.prioritized-adapters", "zenbpm")
            .overrideConfigKey("vanillabp.adapters.zenbpm.rest-address", ENGINE.address())
            .overrideConfigKey("vanillabp.adapters.zenbpm.resources-location", SmokeApplication.WITH_REFUSED_BPMN)
            .assertException(failure -> assertAll(
                    () -> assertEquals(
                            """
                                    BPMN file 'SampleWorkflowService.bpmn' of workflow module 'smoke-app' uses what ZenBPM cannot run:
                                      ZenBPM refuses to deploy a file with these:
                                        - SampleWorkflowService/Calculate (scriptTask)
                                      These would start a workflow for which nobody built a workflow aggregate, because the engine starts it on its own:
                                        - SampleWorkflowService/OrderReceived (receiveTask with instantiate="true")
                                    Model them with what ZenBPM runs, or take them out of the file.""",
                            SmokeApplication.rootMessageOf(failure)),
                    () -> assertEquals(List.of("GET /v1/process-definitions"), ENGINE.requests())));

    @AfterAll
    static void stopTheEngine() {
        ENGINE.close();
    }

    @Test
    @DisplayName("A file with what ZenBPM cannot run ends the start, naming file, module, process and every element, and nothing is deployed")
    void anUnsupportedModelEndsTheStartBeforeAnythingIsDeployed() {
        // never runs: the start fails, and the extension checks how
    }

}

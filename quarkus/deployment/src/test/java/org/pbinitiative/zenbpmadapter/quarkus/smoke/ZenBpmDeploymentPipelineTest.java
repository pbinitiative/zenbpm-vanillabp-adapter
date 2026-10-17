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

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The deployment pipeline runs on Quarkus: the BPMN file under the configured resources location is
 * read, prepared and wired, and the deployment then waits for an engine which never answers, until
 * <code>startup-wait</code> ends the start with a message naming the address.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmDeploymentPipelineTest {

    // no engine listens on port 1
    private static final String CLOSED_PORT = "http://localhost:1";

    @RegisterExtension
    static final QuarkusExtensionTest extensionTest = SmokeApplication
            .withBpmnFiles()
            .overrideConfigKey("vanillabp.prioritized-adapters", "zenbpm")
            .overrideConfigKey("vanillabp.adapters.zenbpm.rest-address", CLOSED_PORT)
            .overrideConfigKey("vanillabp.adapters.zenbpm.startup-wait", "PT2S")
            .overrideConfigKey("vanillabp.adapters.zenbpm.name-clash-avoidance", "use-prefix")
            .overrideConfigKey("vanillabp.adapters.zenbpm.resources-location", SmokeApplication.WITH_BPMN)
            .assertLogRecords(records -> {
                final var log = SmokeApplication.logOf(records);
                assertTrue(log.contains(
                        "ZenBPM[zenbpm]: wired 0 task(s) and 0 user task(s) of BPMN process 'SampleWorkflowService' (file 'SampleWorkflowService.bpmn', workflow module 'smoke-app')"),
                        log);
            })
            .assertException(failure -> {
                final var messages = SmokeApplication.messagesOf(failure);
                assertAll(
                        () -> assertTrue(messages.contains(CLOSED_PORT), messages),
                        () -> assertTrue(messages.contains("vanillabp.adapters.zenbpm.startup-wait"), messages));
            });

    @Test
    @DisplayName("A BPMN file is read, prepared and wired on Quarkus, and the deployment ends the start in the engine wait, naming the address")
    void thePipelineRunsOnQuarkus() {
        // never runs: the start fails, and the extension checks how
    }

}

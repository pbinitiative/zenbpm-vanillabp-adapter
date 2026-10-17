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
 * The adapter wires the processes of a workflow module on Quarkus: a task nothing serves ends the
 * start with the platform's message, before the engine is asked anything.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmUnwiredTaskTest {

    @RegisterExtension
    static final QuarkusExtensionTest extensionTest = SmokeApplication
            .withBpmnFiles("with-unwired-task")
            .overrideConfigKey("vanillabp.prioritized-adapters", "zenbpm")
            .overrideConfigKey("vanillabp.adapters.zenbpm.rest-address", "http://localhost:8080")
            .overrideConfigKey("vanillabp.adapters.zenbpm.name-clash-avoidance", "use-prefix")
            .overrideConfigKey("vanillabp.adapters.zenbpm.resources-location", "classpath*:with-unwired-task")
            .assertException(failure -> {
                final var message = SmokeApplication.rootMessageOf(failure);
                assertAll(
                        () -> assertTrue(message.startsWith(
                                "Task wiring of BPMN process 'SampleWorkflowService' of workflow module 'smoke-app' is incomplete!"),
                                message),
                        () -> assertTrue(message.contains("  - task 'Ship' (task definition 'ship'):"), message));
            });

    @Test
    @DisplayName("A task no @WorkflowTask method serves ends the start with the platform's message naming the task")
    void aTaskNothingServesEndsTheStart() {
        // never runs: the start fails, and the extension checks how
    }

}

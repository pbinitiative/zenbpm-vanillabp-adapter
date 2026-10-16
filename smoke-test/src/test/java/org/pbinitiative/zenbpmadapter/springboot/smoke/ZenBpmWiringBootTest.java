/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.smoke;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The adapter wires the processes of a workflow module on Spring Boot: a task nothing serves ends
 * the start with the platform's message, before the engine is asked anything.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmWiringBootTest {

    // the process of the sample workflow service with a service task no method serves. The file is
    // named after the process, as in the other fixtures of this module
    private static final String WITH_UNWIRED_TASK = "classpath*:with-unwired-task";

    @Test
    @DisplayName("A task no @WorkflowTask method serves ends the start with the platform's message naming the task")
    void aTaskNothingServesEndsTheStart() {
        final var failure = assertThrows(Exception.class, () -> new SpringApplicationBuilder(
                SmokeTestApplication.class)
                .web(WebApplicationType.NONE)
                .run(Stream
                        .of("vanillabp.prioritized-adapters=zenbpm",
                                "vanillabp.adapters.zenbpm.rest-address=http://localhost:8080",
                                "vanillabp.adapters.zenbpm.name-clash-avoidance=use-prefix",
                                "vanillabp.adapters.zenbpm.resources-location=%s".formatted(WITH_UNWIRED_TASK))
                        .map("--%s"::formatted)
                        .toArray(String[]::new))
                .close());
        var root = (Throwable) failure;
        while (root.getCause() != null) {
            root = root.getCause();
        }

        final var message = root.getMessage();

        assertAll(
                () -> assertTrue(message.startsWith(
                        "Task wiring of BPMN process 'SampleWorkflowService' of workflow module 'smoke-app' is incomplete!"),
                        message),
                () -> assertTrue(message.contains("  - task 'Ship' (task definition 'ship'):"), message));
    }

}

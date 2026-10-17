/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.smoke;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * A BPMN file the engine refuses, booted on Spring Boot: the start ends naming file, workflow
 * module, process and the engine's reason; with <code>deployment-failure: warn</code> on an adapter
 * id which is nowhere first, the application boots and the log holds the same refusal. The engine is
 * a server standing in for ZenBPM which refuses every file.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmRefusedFileBootTest {

    private static final String WITH_BPMN = "classpath*:with-bpmn";

    private static final String REASON = "failed to resolve references: no registered BaseElement with ID [nowhere]";

    private StandInEngine engine;

    @BeforeEach
    void startTheEngine() {
        engine = StandInEngine.refusingEveryFile(REASON);
    }

    @AfterEach
    void stopTheEngine() {
        engine.close();
    }

    @Test
    @DisplayName("A file the engine refuses ends the start, naming file, workflow module, process and the engine's reason")
    void aRefusedFileEndsTheStart() {
        final var failure = assertThrows(Exception.class, () -> run("vanillabp.prioritized-adapters=zenbpm",
                "vanillabp.adapters.zenbpm.rest-address=%s".formatted(engine.address()),
                "vanillabp.adapters.zenbpm.name-clash-avoidance=use-prefix",
                "vanillabp.adapters.zenbpm.resources-location=%s".formatted(WITH_BPMN)).close());
        // the adapter's message, above the engine's answer it names
        final var messages = new ArrayList<String>();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            messages.add(cause.getMessage());
        }

        assertTrue(messages.contains("""
                ZenBPM refuses BPMN file 'SampleWorkflowService.bpmn' of workflow module 'smoke-app' (process \
                'SampleWorkflowService'): POST /v1/process-definitions was answered with status 400, BAD_REQUEST: \
                %s. Correct the model where the engine's reason points, or take the file out of the workflow \
                module.""".formatted(REASON)), messages::toString);
    }

    @Test
    @DisplayName("A file the engine refuses lets the application boot where the adapter id is nowhere first and set to 'warn'")
    void aRefusedFileOfANowhereFirstAdapterIdLetsTheApplicationBoot(final CapturedOutput output) {
        try (var context = run("vanillabp.prioritized-adapters=dummy,zenbpm", "vanillabp.adapters.dummy.type=dummy",
                "vanillabp.adapters.dummy.test=7",
                "vanillabp.adapters.dummy.resources-location=%s".formatted(WITH_BPMN),
                "vanillabp.adapters.zenbpm.type=zenbpm", "vanillabp.adapters.zenbpm.deployment-failure=warn",
                "vanillabp.adapters.zenbpm.rest-address=%s".formatted(engine.address()),
                "vanillabp.adapters.zenbpm.name-clash-avoidance=use-prefix",
                "vanillabp.adapters.zenbpm.resources-location=%s".formatted(WITH_BPMN))) {
            final var log = output.getAllOfThisTest();
            final var failure = log.indexOf("Deployment of workflow module 'smoke-app' failed for adapter 'zenbpm'!");

            assertAll(
                    () -> assertTrue(context.isActive()),
                    () -> assertTrue(failure >= 0, log),
                    () -> assertTrue(log.indexOf(REASON, failure) > 0, log));
        }
    }

    // command-line arguments win over every other source of properties
    private static ConfigurableApplicationContext run(final String... properties) {
        return new SpringApplicationBuilder(SmokeTestApplication.class)
                .web(WebApplicationType.NONE)
                .run(Stream
                        .of(properties)
                        .map("--%s"::formatted)
                        .toArray(String[]::new));
    }

}

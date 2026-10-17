/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.smoke;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pbinitiative.zenbpmadapter.processservice.ZenBpmProcessService;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The <code>history-time-to-live</code> a start sends on Spring Boot: the process service of an
 * adapter id sends the value of the workflow, else of its workflow module, else of the adapter id,
 * and a value with a fraction of a second, which the engine would cut, ends the start.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmHistoryTimeToLiveBootTest {

    private static final String MODULE = "vanillabp.workflow-modules.smoke-app";

    private static final String WORKFLOW = "%s.workflows.SampleWorkflowService".formatted(MODULE);

    @Test
    @DisplayName("The process service sends the history-time-to-live of the workflow, else of its module, else of the adapter id")
    void theProcessServiceSendsTheResolvedHistoryTimeToLive() {
        try (var context = run("vanillabp.prioritized-adapters=zenbpm",
                "vanillabp.adapters.zenbpm.rest-address=http://localhost:8080",
                "vanillabp.adapters.zenbpm.history-time-to-live=PT4H",
                "%s.adapters.zenbpm.history-time-to-live=PT3H".formatted(MODULE),
                "%s.adapters.zenbpm.history-time-to-live=PT2H".formatted(WORKFLOW))) {
            final ZenBpmProcessService<?> processService = context.getBean(ZenBpmProcessService.class);

            assertAll(
                    () -> assertEquals(Optional.of(Duration.ofHours(2)), processService.historyTimeToLiveOf(
                            "smoke-app", "SampleWorkflowService")),
                    () -> assertEquals(Optional.of(Duration.ofHours(3)), processService.historyTimeToLiveOf(
                            "smoke-app", "OtherWorkflow")),
                    () -> assertEquals(Optional.of(Duration.ofHours(4)), processService.historyTimeToLiveOf(
                            "other-module", "SampleWorkflowService")));
        }
    }

    @Test
    @DisplayName("Without the key at any level, the process service sends none, and the engine's own default applies")
    void withoutTheKeyNoneIsSent() {
        try (var context = run("vanillabp.prioritized-adapters=zenbpm",
                "vanillabp.adapters.zenbpm.rest-address=http://localhost:8080")) {
            assertEquals(Optional.empty(), context
                    .getBean(ZenBpmProcessService.class)
                    .historyTimeToLiveOf("smoke-app", "SampleWorkflowService"));
        }
    }

    @Test
    @DisplayName("A history-time-to-live with a fraction of a second ends the start, naming the key where it was written")
    void aFractionOfASecondEndsTheStart() {
        final var failure = rootOf(assertThrows(Exception.class, () -> run("vanillabp.prioritized-adapters=zenbpm",
                "vanillabp.adapters.zenbpm.rest-address=http://localhost:8080",
                "%s.adapters.zenbpm.history-time-to-live=PT0.5S".formatted(WORKFLOW))
                .close()));

        assertTrue(failure
                .getMessage()
                .contains(
                        "%s.adapters.zenbpm.history-time-to-live is PT0.5S, but the engine keeps whole seconds and would keep PT0S; write whole seconds, or remove the key, so the level above applies"
                                .formatted(WORKFLOW)),
                failure.getMessage());
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

    private static Throwable rootOf(final Throwable throwable) {
        var cause = throwable;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

}

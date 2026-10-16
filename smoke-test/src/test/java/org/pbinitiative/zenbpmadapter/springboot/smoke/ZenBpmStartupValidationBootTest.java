/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.smoke;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientRegistry;
import org.pbinitiative.zenbpmadapter.processservice.ZenBpmProcessService;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The configuration of a ZenBPM adapter id is validated while the application starts, and what the
 * validation finds is said where the platform says it: in the block at the end of the start, under
 * the heading <code>CONFIGURATION</code>, or in the exception which ends the start.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmStartupValidationBootTest {

    private static final String SCOPE = "zenbpm adapter 'zenbpm'";

    @Test
    @DisplayName("An adapter id without an address boots with a warning naming the key, and a use of the adapter fails with the same words")
    void anUnconfiguredAdapterIdBootsWithAWarning(final CapturedOutput output) {
        try (var context = run("vanillabp.prioritized-adapters=zenbpm")) {
            final var message = context
                    .getBean(ZenBpmClientRegistry.class)
                    .getFactory("zenbpm")
                    .unconfiguredMessage()
                    .orElseThrow();
            final var use = assertThrows(RuntimeException.class, () -> startAWorkflow(context));
            final var block = StartupBlock.configuration(output.getAllOfThisTest());

            assertAll(
                    () -> assertTrue(block.contains("  "
                            + SCOPE
                            + "\n"), block),
                    () -> assertTrue(block.contains(
                            "ZenBPM adapter 'zenbpm' is not configured: 'vanillabp.adapters.zenbpm.rest-address' is missing."),
                            block),
                    () -> assertTrue(message.startsWith(
                            "ZenBPM adapter 'zenbpm' is not configured: 'vanillabp.adapters.zenbpm.rest-address' is missing."),
                            message),
                    () -> assertEquals(message, use.getMessage()));
        }
    }

    @Test
    @DisplayName("A misspelt key is ignored on Spring Boot: the adapter id stays unconfigured, and the warning names the right key")
    void aMisspeltKeyIsIgnored(final CapturedOutput output) {
        try (var context = run("vanillabp.prioritized-adapters=zenbpm",
                "vanillabp.adapters.zenbpm.rest-adress=http://localhost:8080")) {
            final var block = StartupBlock.configuration(output.getAllOfThisTest());

            assertAll(
                    () -> assertTrue(context.isActive()),
                    () -> assertTrue(block.contains("'vanillabp.adapters.zenbpm.rest-address' is missing"), block));
        }
    }

    @Test
    @DisplayName("An inconsistent adapter id which is first priority ends the start, naming the keys")
    void anInconsistentFirstPriorityAdapterIdEndsTheStart() {
        final var failure = rootOf(assertThrows(Exception.class, () -> run("vanillabp.prioritized-adapters=zenbpm",
                "vanillabp.adapters.zenbpm.rest-address=ftp://engine:8080").close()));

        assertAll(
                () -> assertTrue(failure
                        .getMessage()
                        .startsWith("ZenBPM adapter 'zenbpm' is configured inconsistently:\n"
                                + "  - vanillabp.adapters.zenbpm.rest-address "),
                        failure.getMessage()),
                () -> assertTrue(failure
                        .getMessage()
                        .contains("The adapter is first priority somewhere, so it cannot boot degraded."),
                        failure.getMessage()));
    }

    @Test
    @DisplayName("An address defect of an adapter id nowhere first with policy 'warn' boots degraded, with a warning")
    void anAddressDefectWhichMayDegradeBootsDegraded(final CapturedOutput output) {
        try (var context = run(degraded())) {
            final var factory = context
                    .getBean(ZenBpmClientRegistry.class)
                    .getFactory("old");
            final var block = StartupBlock.configuration(output.getAllOfThisTest());

            assertAll(
                    () -> assertFalse(factory.isConfigured()),
                    () -> assertTrue(block.contains("  zenbpm adapter 'old'\n"), block),
                    () -> assertTrue(block.contains("ZenBPM adapter 'old' is configured inconsistently:"), block),
                    () -> assertTrue(
                            block.contains("- vanillabp.adapters.old.rest-address needs the scheme http or https"),
                            block),
                    () -> assertTrue(block.contains("so the application boots DEGRADED"), block));
        }
    }

    @Test
    @DisplayName("A number which cannot work ends the start even of an adapter id which may boot degraded")
    void aNumberWhichCannotWorkEndsTheStartOfADegradableAdapterId() {
        final var failure = rootOf(assertThrows(Exception.class, () -> run(Stream
                .concat(Stream.of(degraded()), Stream.of("vanillabp.adapters.old.worker-threads=0"))
                .toArray(String[]::new)).close()));

        assertAll(
                () -> assertTrue(failure
                        .getMessage()
                        .startsWith("ZenBPM adapter 'old' is configured inconsistently:"), failure.getMessage()),
                () -> assertTrue(failure
                        .getMessage()
                        .contains("vanillabp.adapters.old.worker-threads"), failure.getMessage()),
                () -> assertTrue(failure
                        .getMessage()
                        .contains("A number or a duration which cannot work ends the boot even where"),
                        failure.getMessage()));
    }

    @Test
    @DisplayName("A negative value below the adapter level ends the start, naming the key where it was written")
    void aNegativeScopedValueEndsTheStart() {
        final var failure = rootOf(assertThrows(Exception.class, () -> run("vanillabp.prioritized-adapters=zenbpm",
                "vanillabp.adapters.zenbpm.rest-address=http://localhost:8080",
                "vanillabp.workflow-modules.smoke-app.workflows.SampleWorkflowService.tasks.ship.adapters.zenbpm.retry-backoff=-PT1S")
                .close()));

        assertTrue(failure
                .getMessage()
                .contains(
                        "vanillabp.workflow-modules.smoke-app.workflows.SampleWorkflowService.tasks.ship.adapters.zenbpm.retry-backoff is PT-1S and must not be negative"),
                failure.getMessage());
    }

    @Test
    @DisplayName("A notice of the validation stands once in the block")
    void aNoticeStandsOnceInTheBlock(final CapturedOutput output) {
        try (var context = run("vanillabp.prioritized-adapters=zenbpm",
                "vanillabp.adapters.zenbpm.rest-address=http://localhost:8080/v1")) {
            final var block = StartupBlock.configuration(output.getAllOfThisTest());
            final var notice = "'vanillabp.adapters.zenbpm.rest-address' already ends with '/v1'";

            assertAll(
                    () -> assertTrue(block.contains("  "
                            + SCOPE
                            + "\n"), block),
                    () -> assertEquals(1, block
                            .split(notice, -1).length - 1, block),
                    () -> assertEquals(1, output
                            .getAllOfThisTest()
                            .split(notice, -1).length - 1, output.getAllOfThisTest()));
        }
    }

    /**
     * The smoke application names <code>vanillabp.outbox.housekeeping.zone</code>, because the
     * platform reports a missing zone in the same section wherever the JVM runs on UTC, as on CI.
     */
    @Test
    @DisplayName("A complete adapter id boots with nothing in the block, its client id starts with the application's name, and a use of it says that it is not built yet")
    void aCompleteAdapterIdBootsSilently(final CapturedOutput output) {
        try (var context = run("vanillabp.prioritized-adapters=zenbpm",
                "vanillabp.adapters.zenbpm.rest-address=http://localhost:8080")) {
            final var use = assertThrows(RuntimeException.class, () -> startAWorkflow(context));
            final var log = output.getAllOfThisTest();

            final var factory = context
                    .getBean(ZenBpmClientRegistry.class)
                    .getFactory("zenbpm");

            assertAll(
                    () -> assertTrue(factory.isConfigured()),
                    () -> assertTrue(factory
                            .clientId()
                            .startsWith("smoke-test-zenbpm-"), factory.clientId()),
                    () -> assertTrue(StartupBlock.isWritten(log), log),
                    () -> assertEquals("", StartupBlock.configuration(log), log),
                    () -> assertFalse(log.contains(SCOPE), log),
                    () -> assertEquals(
                            "ZenBPM adapter 'zenbpm': phase one of START_WORKFLOW is implemented in a later story",
                            use.getMessage()));
        }
    }

    // what the platform calls when the application starts a workflow, after its transaction
    private static void startAWorkflow(final ConfigurableApplicationContext context) {
        final ZenBpmProcessService<?> processService = context.getBean(ZenBpmProcessService.class);
        processService
                .phaseOperations()
                .get(PhaseOperation.START_WORKFLOW)
                .phaseOne(null);
    }

    private static String[] degraded() {
        return new String[]{
                "vanillabp.prioritized-adapters=new,old", "vanillabp.adapters.new.type=zenbpm", "vanillabp.adapters.new.rest-address=http://localhost:8080", "vanillabp.adapters.old.type=zenbpm", "vanillabp.adapters.old.deployment-failure=warn", "vanillabp.adapters.old.rest-address=ftp://old-engine:8080"
        };
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

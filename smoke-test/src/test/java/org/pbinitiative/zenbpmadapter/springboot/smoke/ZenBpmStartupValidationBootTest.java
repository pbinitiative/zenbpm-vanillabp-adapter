/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.smoke;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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

    // a workflow module with one BPMN file, the process of the sample workflow service. The file is
    // named after the process, because the BPMS double takes the process id from the file name
    private static final String WITH_BPMN = "classpath*:with-bpmn";

    // the platform's refusal of 'by-adapter', which ZenBPM cannot serve, with the place of the
    // remark whether anything is configured
    private static final String BY_ADAPTER_REFUSED = """
            ZenBPM has no isolation mechanism of its own, so the name-clash-avoidance mode 'by-adapter' \
            cannot be served by adapter 'zenbpm' - but it applies to workflow module 'smoke-app'%s. Choose \
            explicitly:
              vanillabp.adapters.zenbpm.name-clash-avoidance: use-prefix   # VanillaBP prefixes the identifiers
              vanillabp.adapters.zenbpm.name-clash-avoidance: none         # your identifiers are unique already
            The same key may be set per workflow module and workflow \
            (vanillabp.workflow-modules.smoke-app.adapters.zenbpm.name-clash-avoidance).""";

    // the adapter's refusal of 'by-adapter' which one workflow sets for itself
    private static final String WORKFLOW_BY_ADAPTER_REFUSED = """
            ZenBPM has no isolation mechanism of its own, so the name-clash-avoidance mode 'by-adapter' \
            cannot be served by adapter 'zenbpm' - but it is configured for workflow 'SampleWorkflowService' of \
            workflow module 'smoke-app'. Choose explicitly:
              vanillabp.workflow-modules.smoke-app.workflows.SampleWorkflowService.adapters.zenbpm.name-clash-avoidance: use-prefix   # VanillaBP prefixes the identifiers
              vanillabp.workflow-modules.smoke-app.workflows.SampleWorkflowService.adapters.zenbpm.name-clash-avoidance: none         # your identifiers are unique already
            or remove the key, so the mode of the workflow module applies.""";

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
    @DisplayName("An adapter id without an address and a workflow module with BPMN files end the start, naming the key")
    void anUnconfiguredAdapterIdWithBpmnFilesEndsTheStart() {
        final var failure = rootOf(assertThrows(Exception.class, () -> run("vanillabp.prioritized-adapters=zenbpm",
                "vanillabp.adapters.zenbpm.name-clash-avoidance=use-prefix",
                "vanillabp.adapters.zenbpm.resources-location=%s".formatted(WITH_BPMN))
                .close()));

        assertTrue(failure
                .getMessage()
                .startsWith(
                        "ZenBPM adapter 'zenbpm' is not configured: 'vanillabp.adapters.zenbpm.rest-address' is missing."),
                failure.getMessage());
    }

    @Test
    @DisplayName("A workflow module with BPMN files and no name-clash-avoidance configured ends the start with the platform's message naming use-prefix, none and the key")
    void nothingConfiguredEndsTheStartNamingBothWaysOut() {
        final var failure = rootOf(assertThrows(Exception.class, () -> run("vanillabp.prioritized-adapters=zenbpm",
                "vanillabp.adapters.zenbpm.rest-address=http://localhost:8080",
                "vanillabp.adapters.zenbpm.resources-location=%s".formatted(WITH_BPMN))
                .close()));

        assertEquals(BY_ADAPTER_REFUSED.formatted(" (nothing is configured, so the default applies)"), failure
                .getMessage());
    }

    @Test
    @DisplayName("A workflow module with BPMN files and name-clash-avoidance 'by-adapter' configured ends the start with the same message")
    void byAdapterConfiguredEndsTheStartNamingBothWaysOut() {
        final var failure = rootOf(assertThrows(Exception.class, () -> run("vanillabp.prioritized-adapters=zenbpm",
                "vanillabp.adapters.zenbpm.rest-address=http://localhost:8080",
                "vanillabp.adapters.zenbpm.name-clash-avoidance=by-adapter",
                "vanillabp.adapters.zenbpm.resources-location=%s".formatted(WITH_BPMN))
                .close()));

        assertEquals(BY_ADAPTER_REFUSED.formatted(""), failure.getMessage());
    }

    @Test
    @DisplayName("A workflow which sets name-clash-avoidance 'by-adapter' of its own, in a module under use-prefix, ends the start naming the workflow's key")
    void byAdapterOfOneWorkflowEndsTheStartNamingItsKey() {
        final var failure = rootOf(assertThrows(Exception.class, () -> run("vanillabp.prioritized-adapters=zenbpm",
                "vanillabp.adapters.zenbpm.rest-address=http://localhost:8080",
                "vanillabp.adapters.zenbpm.name-clash-avoidance=use-prefix",
                "vanillabp.workflow-modules.smoke-app.workflows.SampleWorkflowService.adapters.zenbpm.name-clash-avoidance=by-adapter",
                "vanillabp.adapters.zenbpm.resources-location=%s".formatted(WITH_BPMN))
                .close()));

        assertEquals(WORKFLOW_BY_ADAPTER_REFUSED, failure.getMessage());
    }

    @Test
    @DisplayName("A workflow module with BPMN files under name-clash-avoidance 'none' is warned about while it is prepared, naming the alternatives of ZenBPM")
    void noneIsWarnedAboutWhileTheModuleIsPrepared(final CapturedOutput output) {
        final var failure = rootOf(assertThrows(Exception.class, () -> run("vanillabp.prioritized-adapters=zenbpm",
                "vanillabp.adapters.zenbpm.rest-address=http://localhost:1",
                "vanillabp.adapters.zenbpm.startup-wait=PT0S",
                "vanillabp.adapters.zenbpm.name-clash-avoidance=none",
                "vanillabp.adapters.zenbpm.resources-location=%s".formatted(WITH_BPMN))
                .close()));
        final var log = output.getAllOfThisTest();

        assertAll(
                () -> assertTrue(log.contains(
                        "Workflow module 'smoke-app' is deployed to ZenBPM (adapter 'zenbpm') with name-clash-avoidance 'none'."),
                        log),
                () -> assertTrue(failure
                        .getMessage()
                        .contains("http://localhost:1"), failure.getMessage()));
    }

    @Test
    @DisplayName("A degraded adapter id with BPMN files, nowhere first and with policy 'warn', lets the application boot on the adapter which is first")
    void aDegradedAdapterIdWithBpmnFilesBootsOnTheFirstAdapter(final CapturedOutput output) {
        try (var context = run("vanillabp.prioritized-adapters=dummy,zenbpm", "vanillabp.adapters.dummy.type=dummy",
                "vanillabp.adapters.dummy.test=7",
                "vanillabp.adapters.dummy.resources-location=%s".formatted(WITH_BPMN),
                "vanillabp.adapters.zenbpm.type=zenbpm", "vanillabp.adapters.zenbpm.deployment-failure=warn",
                "vanillabp.adapters.zenbpm.rest-address=ftp://old-engine:8080",
                "vanillabp.adapters.zenbpm.name-clash-avoidance=use-prefix",
                "vanillabp.adapters.zenbpm.resources-location=%s".formatted(WITH_BPMN))) {
            final var log = output.getAllOfThisTest();

            final var failure = log.indexOf("Deployment of workflow module 'smoke-app' failed for adapter 'zenbpm'!");

            assertAll(
                    () -> assertTrue(context.isActive()),
                    () -> assertTrue(failure >= 0, log),
                    () -> assertTrue(log.indexOf("ZenBPM adapter 'zenbpm' is configured inconsistently:", failure) > 0,
                            log));
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
    @DisplayName("An async-task-lock-renewal not below the platform's delivery retention and an async-task-max-age-action which names no action end the start, naming the keys and the fix")
    void theKeysOfTasksLeftOpenAreValidatedAgainstThePlatform() {
        final var failure = rootOf(assertThrows(Exception.class, () -> run("vanillabp.prioritized-adapters=zenbpm",
                "vanillabp.adapters.zenbpm.rest-address=http://localhost:8080",
                "vanillabp.delivery.retention=PT1H",
                "vanillabp.adapters.zenbpm.async-task-lock-renewal=PT1H",
                "vanillabp.adapters.zenbpm.async-task-max-age-action=fail").close()));

        assertAll(
                () -> assertTrue(failure
                        .getMessage()
                        .contains(
                                "  - vanillabp.adapters.zenbpm.async-task-lock-renewal is PT1H, which is not shorter than vanillabp.delivery.retention (PT1H)."),
                        failure.getMessage()),
                () -> assertTrue(failure
                        .getMessage()
                        .contains("at most a tenth of it (PT6M here), or raise vanillabp.delivery.retention"),
                        failure.getMessage()),
                () -> assertTrue(failure
                        .getMessage()
                        .contains(
                                "  - vanillabp.adapters.zenbpm.async-task-max-age-action is 'fail'; it is 'report', the default, or 'incident'"),
                        failure.getMessage()));
    }

    @Test
    @DisplayName("A message-start-lookup-max-instances below one ends the start, naming the key and its default")
    void theBoundOfTheLookupOfAMessageStartIsValidated() {
        final var failure = rootOf(assertThrows(Exception.class, () -> run("vanillabp.prioritized-adapters=zenbpm",
                "vanillabp.adapters.zenbpm.rest-address=http://localhost:8080",
                "vanillabp.adapters.zenbpm.message-start-lookup=scan",
                "vanillabp.adapters.zenbpm.message-start-lookup-max-instances=0").close()));

        assertTrue(failure
                .getMessage()
                .contains(
                        "  - vanillabp.adapters.zenbpm.message-start-lookup-max-instances is 0 and has to be at least 1; the default is 1000"),
                failure.getMessage());
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

    @Test
    @DisplayName("A shutdown-grace above thirty seconds boots with a warning in the block, naming the budgets of the runtime to raise with it")
    void aLongShutdownGraceIsWarnedAboutInTheBlock(final CapturedOutput output) {
        try (var context = run("vanillabp.prioritized-adapters=zenbpm",
                "vanillabp.adapters.zenbpm.rest-address=http://localhost:8080",
                "vanillabp.adapters.zenbpm.shutdown-grace=PT45S")) {
            final var block = StartupBlock.configuration(output.getAllOfThisTest());
            final var warning = "vanillabp.adapters.zenbpm.shutdown-grace is PT45S, above the PT30S which Kubernetes grants a pod to stop by default: raise terminationGracePeriodSeconds";

            assertAll(
                    () -> assertTrue(context.isRunning()),
                    () -> assertEquals(1, block
                            .split(warning, -1).length - 1, block));
        }
    }

    /**
     * The smoke application names <code>vanillabp.outbox.housekeeping.zone</code>, because the
     * platform reports a missing zone in the same section wherever the JVM runs on UTC, as on CI.
     */
    @Test
    @DisplayName("A complete adapter id boots with nothing in the block, its client id starts with the application's name, and phase one of a start passes, because it has nothing to ask the engine")
    void aCompleteAdapterIdBootsSilently(final CapturedOutput output) {
        try (var context = run("vanillabp.prioritized-adapters=zenbpm",
                "vanillabp.adapters.zenbpm.rest-address=http://localhost:8080")) {
            assertDoesNotThrow(() -> startAWorkflow(context));
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
                    () -> assertFalse(log.contains(SCOPE), log));
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

/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.wiring;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pbinitiative.zenbpmadapter.client.ReceivedJob;

import io.vanillabp.integration.adapter.spi.AggregateSyncMode;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartResult;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.service.BpmsStartTrigger;

/**
 * What the handler of a start marker makes of its job: the context it hands to the platform's core,
 * the three answers of the core (a workflow without a name, whose aggregate the application built
 * and whose id is written into the instance before the job is completed; a workflow which carries
 * the name of an aggregate which exists, the application's own start and a second delivery, whose
 * job is completed without writing anything; and a refusal, which fails the job like a handler
 * which threw), and what at-least-once delivery leaves over. The process <code>Order</code> gives
 * its timer start event <code>Placed</code> a marker.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmBpmsInitiatedStartHandlerTest {

    private static final long ROOT = 100;

    private JobRunning jobs;

    @BeforeEach
    void startTheEngine() {
        jobs = new JobRunning(2);
    }

    @AfterEach
    void stopTheEngine() {
        jobs.close();
    }

    @Test
    @DisplayName("The context names the start event and its kind, the job's variables, the instance as the workflow, its version, the full sync of the values, the adapter id and no business key where the instance has none")
    void theContextNamesWhatTheJobAndItsInstanceSay() {
        jobs.holds(ROOT, "default", null, null, "\"orderRef\": \"o-1\"");
        jobs.takesTheCommands(ROOT);

        jobs.runs(JobRunning.startMarkerJob(ROOT, "vanillabp__started__Placed", "{\"orderRef\": \"o-1\"}"));

        final var start = jobs.starts
                .starts()
                .getFirst();
        final var context = start.context();
        assertAll(
                () -> assertEquals("shop", start.workflowModuleId()),
                () -> assertEquals("Order", start.bpmnProcessId()),
                () -> assertEquals("Placed", context.getStartEventId()),
                () -> assertEquals(BpmsStartTrigger.Kind.TIMER, context.getKind()),
                () -> assertEquals(Map.of("orderRef", "o-1"), context.getVariables()),
                () -> assertEquals("100", context.getNativeInstanceId()),
                () -> assertEquals("3", context.getProcessVersion()),
                () -> assertEquals(AggregateSyncMode.FULL, context.getAggregateSyncMode()),
                () -> assertEquals("orders", context.getAdapterId()),
                () -> assertNull(context.getBusinessKey()),
                () -> assertNull(context.getSignalName()),
                () -> assertFalse(context.runInCurrentTransaction()));
    }

    @Test
    @DisplayName("A workflow without a name gets the id and the values of the aggregate the application built written into its instance, and only then is the job completed")
    void theAggregateTheApplicationBuiltNamesTheWorkflow() {
        jobs.holds(ROOT, "default", null, null, "");
        jobs.takesTheCommands(ROOT);

        jobs.runs(JobRunning.startMarkerJob(ROOT, "vanillabp__started__Placed", "{}"));

        assertAll(
                () -> assertEquals(List.of("PATCH /v1/process-instances/100/variables", "POST /v1/jobs/42/complete"),
                        jobs.commands()),
                () -> assertTrue(jobs
                        .bodiesOf("PATCH", "/v1/process-instances/100/variables")
                        .getFirst()
                        .contains("\"id\":\"order-1\""),
                        () -> jobs
                                .bodiesOf("PATCH", "/v1/process-instances/100/variables")
                                .toString()),
                () -> assertTrue(jobs
                        .bodiesOf("PATCH", "/v1/process-instances/100/variables")
                        .getFirst()
                        .contains("\"status\":\"new\"")));
    }

    @Test
    @DisplayName("The application's own start carries the business key and the id: nothing is written, the job is completed at once, and the business key is reported")
    void theApplicationsOwnStartWritesNothing() {
        jobs.holds(ROOT, "default", null, "order-2", "\"id\": \"order-2\"");
        jobs.takesTheCommands(ROOT);
        jobs.starts.answers(context -> TestStartInvoker.found("order-2"));

        jobs.runs(JobRunning.startMarkerJob(ROOT, "vanillabp__started__Placed", "{\"id\": \"order-2\"}"));

        assertAll(
                () -> assertEquals(List.of("POST /v1/jobs/42/complete"), jobs.commands()),
                () -> assertEquals("order-2", jobs.starts
                        .starts()
                        .getFirst()
                        .context()
                        .getBusinessKey()));
    }

    @Test
    @DisplayName("A second delivery after the id was written finds it in the instance, although the engine hands the job out with the variables it had when it was created: the core builds nothing, and nothing is written again")
    void aRedeliveryFindsTheNameWrittenBefore() {
        jobs.holds(ROOT, "default", null, null, "\"id\": \"order-1\", \"orderRef\": \"o-1\"");
        jobs.takesTheCommands(ROOT);
        jobs.starts.answers(context -> context
                .getVariables()
                .containsKey("id") ? TestStartInvoker.found("order-1") : TestStartInvoker.built("order-1"));

        jobs.runs(JobRunning.startMarkerJob(ROOT, "vanillabp__started__Placed", "{\"orderRef\": \"o-1\"}"));

        assertAll(
                () -> assertEquals(List.of("POST /v1/jobs/42/complete"), jobs.commands()),
                () -> assertEquals(Map.of("id", "order-1", "orderRef", "o-1"), jobs.starts
                        .starts()
                        .getFirst()
                        .context()
                        .getVariables()));
    }

    @Test
    @DisplayName("The name the core hands back is written although the core built nothing, where the instance does not carry it: a workflow somebody started past VanillaBP with nothing but its business key")
    void aNameTheInstanceDoesNotCarryIsWritten() {
        jobs.holds(ROOT, "default", null, "order-3", "");
        jobs.takesTheCommands(ROOT);
        jobs.starts.answers(context -> TestStartInvoker.found("order-3"));

        jobs.runs(JobRunning.startMarkerJob(ROOT, "vanillabp__started__Placed", "{\"id\": \"order-9\"}"));

        assertEquals(List.of("PATCH /v1/process-instances/100/variables", "POST /v1/jobs/42/complete"), jobs
                .commands());
    }

    @Test
    @DisplayName("Where the core names no variable for the id, nothing is written and the job is completed")
    void withoutAnIdVariableNothingIsWritten() {
        jobs.holds(ROOT, "default", null, "order-4", "");
        jobs.takesTheCommands(ROOT);
        jobs.starts.answers(context -> new BpmsInitiatedStartResult("order-4", null, Map.of(), false));

        jobs.runs(JobRunning.startMarkerJob(ROOT, "vanillabp__started__Placed", "{}"));

        assertEquals(List.of("POST /v1/jobs/42/complete"), jobs.commands());
    }

    @Test
    @DisplayName("An empty business key, which a call activity gives a process called on an aggregate of its own, is reported as none")
    void anEmptyBusinessKeyIsNone() {
        jobs.holds(ROOT, "callActivity", 7L, "", "");
        jobs.takesTheCommands(ROOT);

        jobs.runs(JobRunning.startMarkerJob(ROOT, "vanillabp__started__Placed", "{}"));

        assertNull(jobs.starts
                .starts()
                .getFirst()
                .context()
                .getBusinessKey());
    }

    @Test
    @DisplayName("A start event the deployed model does not hold, of an older version, is reported as a plain one")
    void aStartEventTheModelDoesNotHoldIsPlain() {
        jobs.holds(ROOT, "default", null, null, "");
        jobs.takesTheCommands(ROOT);

        jobs.runs(JobRunning.startMarkerJob(ROOT, "vanillabp__started__Gone", "{}"));

        final var context = jobs.starts
                .starts()
                .getFirst()
                .context();
        assertAll(
                () -> assertEquals("Gone", context.getStartEventId()),
                () -> assertEquals(BpmsStartTrigger.Kind.NONE, context.getKind()));
    }

    @Test
    @DisplayName("A start the core refuses fails the job with the core's message and the configured retry-backoff, and without retries, so the engine counts the attempt")
    void aRefusalFailsTheJob(final CapturedOutput output) {
        jobs.holds(ROOT, "default", null, "stranger", "");
        jobs.takesTheCommands(ROOT);
        jobs.retryBackoffs.put("shop/Order/vanillabp__started", Duration.ofSeconds(10));
        jobs.starts.answers(context -> {
            throw new IllegalStateException("no workflow aggregate carries that id");
        });

        jobs.runs(JobRunning.startMarkerJob(ROOT, "vanillabp__started__Placed", "{}"));

        final var failure = jobs
                .bodiesOf("POST", "/v1/jobs/42/fail")
                .getFirst();
        assertAll(
                () -> assertEquals(List.of("POST /v1/jobs/42/fail"), jobs.commands()),
                () -> assertTrue(failure.contains(
                        "The start of the workflow at start event 'Placed' of process 'Order' (workflow module 'shop') failed: java.lang.IllegalStateException: no workflow aggregate carries that id"),
                        failure),
                () -> assertTrue(failure.contains("\"retryBackoff\":\"PT10S\""), failure),
                () -> assertFalse(failure.contains("\"retries\""), failure),
                () -> assertTrue(output
                        .getAllOfThisTest()
                        .contains("its job is failed with 0 retries left, so the engine raises an incident"),
                        output::getAllOfThisTest));
    }

    @Test
    @DisplayName("A failure without a message is named by its type, and a job with retries left is failed for the engine to hand it out again")
    void aFailureWithoutAMessageIsNamedByItsType(final CapturedOutput output) {
        jobs.holds(ROOT, "default", null, null, "");
        jobs.takesTheCommands(ROOT);
        jobs.starts.answers(context -> {
            throw new IllegalStateException();
        });
        final var marker = JobRunning.startMarkerJob(ROOT, "vanillabp__started__Placed", "{}");

        jobs.runs(new ReceivedJob(marker
                .job()
                .toBuilder()
                .setRetries(3)
                .build(), marker.receivedAt()));

        final var failure = jobs
                .bodiesOf("POST", "/v1/jobs/42/fail")
                .getFirst();
        assertAll(
                () -> assertTrue(failure.contains("failed: java.lang.IllegalStateException\""), failure),
                () -> assertTrue(output
                        .getAllOfThisTest()
                        .contains("its job is failed with 2 retries left"), output::getAllOfThisTest),
                () -> assertFalse(output
                        .getAllOfThisTest()
                        .contains("so the engine raises an incident"), output::getAllOfThisTest));
    }

    @Test
    @DisplayName("A start which fails while the module shuts down leaves the job to its lock, so it keeps its retries")
    void aFailureWhileStoppingKeepsTheRetries() {
        jobs.holds(ROOT, "default", null, null, "");
        jobs.takesTheCommands(ROOT);
        jobs.starts.answers(context -> {
            jobs.handler.stopping();
            throw new IllegalStateException("the transaction was cut off");
        });

        jobs.runs(JobRunning.startMarkerJob(ROOT, "vanillabp__started__Placed", "{}"));

        assertEquals(List.of(), jobs.commands());
    }

    @Test
    @DisplayName("A workflow which ended while its start was reported is not completed, and a WARN says that the aggregate is committed")
    void aWorkflowWhichEndedMeanwhileIsNotCompleted(final CapturedOutput output) {
        jobs.holds(ROOT, "default", null, null, "");
        jobs.engine.answers("PATCH", "/v1/process-instances/100/variables", 409, "{\"code\": \"CONFLICT\"}");

        jobs.runs(JobRunning.startMarkerJob(ROOT, "vanillabp__started__Placed", "{}"));

        assertAll(
                () -> assertEquals(List.of("PATCH /v1/process-instances/100/variables"), jobs.commands()),
                () -> assertTrue(output
                        .getAllOfThisTest()
                        .contains("ended or was cancelled while its start was reported"), output::getAllOfThisTest));
    }

    @Test
    @DisplayName("A job which is gone when it is to be completed, or failed, costs a WARN and nothing else")
    void aGoneJobCostsAWarn(final CapturedOutput output) {
        jobs.holds(ROOT, "default", null, null, "");
        jobs.engine.answers("PATCH", "/v1/process-instances/100/variables", 204, null);
        jobs.engine.answers("POST", "/v1/jobs/42/complete", 404, "{\"code\": \"NOT_FOUND\"}");
        jobs.engine.answers("POST", "/v1/jobs/42/fail", 404, "{\"code\": \"NOT_FOUND\"}");

        jobs.runs(JobRunning.startMarkerJob(ROOT, "vanillabp__started__Placed", "{}"));
        jobs.starts.answers(context -> {
            throw new IllegalStateException("refused");
        });
        jobs.runs(JobRunning.startMarkerJob(ROOT, "vanillabp__started__Placed", "{}"));

        assertAll(
                () -> assertTrue(output
                        .getAllOfThisTest()
                        .contains("was gone when it was to be completed"), output::getAllOfThisTest),
                () -> assertTrue(output
                        .getAllOfThisTest()
                        .contains("was gone when its failure was to be sent"), output::getAllOfThisTest));
    }

    @Test
    @DisplayName("A command the engine refuses otherwise leaves the job to its lock, whether it is the update, the completion or the failure")
    void aRefusedCommandLeavesTheJobToItsLock(final CapturedOutput output) {
        jobs.holds(ROOT, "default", null, null, "");
        jobs.engine.answers("PATCH", "/v1/process-instances/100/variables", 400, "{\"code\": \"BAD_REQUEST\"}");
        jobs.runs(JobRunning.startMarkerJob(ROOT, "vanillabp__started__Placed", "{}"));
        jobs.engine.answers("PATCH", "/v1/process-instances/100/variables", 204, null);
        jobs.engine.answers("POST", "/v1/jobs/42/complete", 400, "{\"code\": \"BAD_REQUEST\"}");
        jobs.runs(JobRunning.startMarkerJob(ROOT, "vanillabp__started__Placed", "{}"));
        jobs.engine.answers("POST", "/v1/jobs/42/fail", 400, "{\"code\": \"BAD_REQUEST\"}");
        jobs.starts.answers(context -> {
            throw new IllegalStateException("refused");
        });
        jobs.runs(JobRunning.startMarkerJob(ROOT, "vanillabp__started__Placed", "{}"));

        assertEquals(3, output
                .getAllOfThisTest()
                .split("could not be answered to the engine; it is left to its lock", -1).length - 1,
                output::getAllOfThisTest);
    }

}

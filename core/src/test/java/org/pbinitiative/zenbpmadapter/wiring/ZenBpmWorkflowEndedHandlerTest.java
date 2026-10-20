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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pbinitiative.zenbpmadapter.ZenBpmProcessingContext.WiredProcess;
import org.pbinitiative.zenbpmadapter.client.FakeEngine;
import org.pbinitiative.zenbpmadapter.client.ReceivedJob;

import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.service.WorkflowEnd;

/**
 * What the handler of an end marker makes of its job: the context it hands to the platform's core
 * before the job is completed, and what it reads first to tell an end of the workflow from the end
 * of one of its branches. The process <code>Order</code> has an end marker in front of its end
 * event <code>Shipped</code>, which ends its branch, and in front of <code>Withdrawn</code>, a
 * terminate end event. The instances are shaped as the engine answers them: a root instance whose
 * tokens stand at their elements, the variables of the instance, and child instances which a token
 * of the root waits for, or which an event sub-process started.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmWorkflowEndedHandlerTest {

    private static final long ROOT = 100;

    private static final String SHIPPED = "vanillabp__ended__Shipped";

    private static final String WITHDRAWN = "vanillabp__ended__Withdrawn";

    private static final long OLDER_VERSION = 4503599627370497L;

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
    @DisplayName("The context names the aggregate, the end event, a completed end, the root instance as the workflow, its business key, its version and the adapter id, and the job is completed only after the core was told")
    void theContextNamesWhatTheJobAndItsInstanceSay() {
        final var before = Instant.now();
        holds(ROOT, "order-1", "\"id\": \"order-1\"", SHIPPED);
        jobs.hasChildren(ROOT, "completed");
        jobs.takesTheCommands(ROOT);
        final var commandsWhenTold = new CopyOnWriteArrayList<List<String>>();
        jobs.ends.answers(context -> commandsWhenTold.add(jobs.commands()));

        jobs.runs(JobRunning.endMarkerJob(ROOT, SHIPPED));

        final var end = jobs.ends
                .ends()
                .getFirst();
        final var context = end.context();
        assertAll(
                () -> assertEquals("shop", end.workflowModuleId()),
                () -> assertEquals("Order", end.bpmnProcessId()),
                () -> assertEquals("order-1", context.getWorkflowAggregateId()),
                () -> assertEquals("Shipped", context.getEndEventId()),
                () -> assertEquals(WorkflowEnd.Kind.COMPLETED, context.getKind()),
                () -> assertEquals("100", context.getWorkflowId()),
                () -> assertEquals("order-1", context.getBusinessKey()),
                () -> assertEquals("3", context.getProcessVersion()),
                () -> assertEquals("orders", context.getAdapterId()),
                () -> assertFalse(context.runInCurrentTransaction()),
                () -> assertFalse(context
                        .getEndTime()
                        .isBefore(before)),
                () -> assertEquals(List.of(List.of()), commandsWhenTold),
                () -> assertEquals(List.of("POST /v1/jobs/42/complete"), jobs.commands()));
    }

    @Test
    @DisplayName("A workflow which names its aggregate by the id variable alone, as one a timer started, reports no business key, and so does one whose business key is empty")
    void aWorkflowWithoutABusinessKeyReportsNone() {
        holds(ROOT, null, "\"id\": \"order-1\"", SHIPPED);
        jobs.hasChildren(ROOT);
        jobs.takesTheCommands(ROOT);

        jobs.runs(JobRunning.endMarkerJob(ROOT, SHIPPED));
        holds(ROOT + 1, "", "\"id\": \"order-1\"", SHIPPED);
        jobs.hasChildren(ROOT + 1);
        jobs.runs(JobRunning.endMarkerJob(ROOT + 1, SHIPPED));

        assertAll(
                () -> assertEquals(2, jobs.ends
                        .ends()
                        .size()),
                () -> assertNull(jobs.ends
                        .ends()
                        .getFirst()
                        .context()
                        .getBusinessKey()),
                () -> assertNull(jobs.ends
                        .ends()
                        .getLast()
                        .context()
                        .getBusinessKey()));
    }

    @Test
    @DisplayName("An end event which ends its branch while a token of the workflow stands at another element reports nothing: the job is completed, and the last branch reports the end")
    void aBranchWhichEndsWhileAnotherGoesOnReportsNothing() {
        holds(ROOT, "order-1", "\"id\": \"order-1\"", SHIPPED, "Pack");
        jobs.takesTheCommands(ROOT);

        jobs.runs(JobRunning.endMarkerJob(ROOT, SHIPPED));

        assertAll(
                () -> assertEquals(List.of(), jobs.ends.ends()),
                () -> assertEquals(List.of("POST /v1/jobs/42/complete"), jobs.commands()));
    }

    @Test
    @DisplayName("Where every other token stands at an end marker, or at an end event whose message job runs, the end is reported, as each of two branches which end at once reports it")
    void branchesWhichEndAtOnceReportTheEnd() {
        holds(ROOT, "order-1", "\"id\": \"order-1\"", SHIPPED, "vanillabp__ended__Withdrawn", "Withdrawn");
        jobs.hasChildren(ROOT);
        jobs.takesTheCommands(ROOT);

        jobs.runs(JobRunning.endMarkerJob(ROOT, SHIPPED));

        assertAll(
                () -> assertEquals(1, jobs.ends
                        .ends()
                        .size()),
                () -> assertEquals(List.of("POST /v1/jobs/42/complete"), jobs.commands()));
    }

    @Test
    @DisplayName("An event sub-process which still runs, a child instance no token waits for, keeps the end from being reported: the job is left to its lock, and the engine hands it out again")
    void anEventSubProcessWhichStillRunsKeepsTheEndWaiting(final CapturedOutput output) {
        holds(ROOT, "order-1", "\"id\": \"order-1\"", SHIPPED);
        jobs.hasChildren(ROOT, "completed", "active");
        jobs.takesTheCommands(ROOT);

        jobs.runs(JobRunning.endMarkerJob(ROOT, SHIPPED));

        assertAll(
                () -> assertEquals(List.of(), jobs.ends.ends()),
                () -> assertEquals(List.of(), jobs.commands()),
                () -> assertTrue(output
                        .getAllOfThisTest()
                        .contains("while an event sub-process of it still runs"), output::getAllOfThisTest));
    }

    @Test
    @DisplayName("A terminate end event ends the workflow whatever else of it runs: the end is reported without asking what does")
    void aTerminateEndEventReportsWhateverElseRuns() {
        holds(ROOT, "order-1", "\"id\": \"order-1\"", WITHDRAWN, "Pack");
        jobs.hasChildren(ROOT, "active");
        jobs.takesTheCommands(ROOT);

        jobs.runs(JobRunning.endMarkerJob(ROOT, WITHDRAWN));

        assertAll(
                () -> assertEquals("Withdrawn", jobs.ends
                        .ends()
                        .getFirst()
                        .context()
                        .getEndEventId()),
                () -> assertFalse(jobs.engine
                        .requests()
                        .stream()
                        .anyMatch(request -> request
                                .path()
                                .endsWith("/child-processes"))),
                () -> assertEquals(List.of("POST /v1/jobs/42/complete"), jobs.commands()));
    }

    @Test
    @DisplayName("An end event is read by the model of the instance's own version, not by the model this application deployed: an older version's terminate end event reports the end while another branch runs, and an older version's end event of a branch, which the deployed model holds as a terminate end event, reports nothing")
    void anEndEventIsReadByTheModelOfTheInstancesVersion() {
        // version 2 holds 'Shipped' as the terminate end event and 'Withdrawn' as the end of a branch
        jobs.holdsTheModel(OLDER_VERSION, JobRunning.modelOfOrder("Withdrawn", "Shipped"));
        jobs.holdsOfVersion(ROOT, OLDER_VERSION, 2, "order-1", "\"id\": \"order-1\"", List.of(SHIPPED, "Pack"));
        jobs.holdsOfVersion(ROOT + 1, OLDER_VERSION, 2, "order-2", "\"id\": \"order-2\"", List.of(WITHDRAWN,
                "Pack"));
        jobs.hasChildren(ROOT);
        jobs.takesTheCommands(ROOT, ROOT + 1);

        jobs.runs(JobRunning.endMarkerJob(ROOT, SHIPPED));
        jobs.runs(JobRunning.endMarkerJob(ROOT + 1, WITHDRAWN));

        assertAll(
                () -> assertEquals(List.of("order-1"), jobs.ends
                        .ends()
                        .stream()
                        .map(end -> end
                                .context()
                                .getWorkflowAggregateId())
                        .toList()),
                () -> assertEquals(List.of("POST /v1/jobs/42/complete", "POST /v1/jobs/42/complete"), jobs
                        .commands()),
                () -> assertEquals(1, modelReadsOf(OLDER_VERSION), jobs.engine
                        .requests()::toString));
    }

    @Test
    @DisplayName("Where the model of the instance's version cannot be read, the job is left to its lock and nothing is reported, and the next delivery reads it again")
    void aModelWhichCannotBeReadLeavesTheJob(final CapturedOutput output) {
        jobs.engine.answers("GET", "/v1/process-definitions/%d".formatted(OLDER_VERSION), 500,
                "{\"code\": \"TECHNICAL_ERROR\", \"message\": \"the engine broke down\"}");
        jobs.holdsOfVersion(ROOT, OLDER_VERSION, 2, "order-1", "\"id\": \"order-1\"", List.of(SHIPPED));
        jobs.takesTheCommands(ROOT);

        jobs.runs(JobRunning.endMarkerJob(ROOT, SHIPPED));
        final var whileItFailed = jobs.commands();
        jobs.holdsTheModel(OLDER_VERSION, JobRunning.modelOfOrder("Shipped", "Withdrawn"));
        jobs.hasChildren(ROOT);
        jobs.runs(JobRunning.endMarkerJob(ROOT, SHIPPED));

        assertAll(
                () -> assertEquals(List.of(), whileItFailed),
                () -> assertEquals(1, jobs.ends
                        .ends()
                        .size()),
                () -> assertTrue(output
                        .getAllOfThisTest()
                        .contains("the model of version 2 of process 'Order' could not be read"),
                        output::getAllOfThisTest));
    }

    @Test
    @DisplayName("Where nobody listens to the end of the process's workflows any more, the end marker of a version deployed while somebody did is completed without a report, so its workflow ends")
    void anEndNobodyListensToAnyMoreIsCompletedWithoutAReport() {
        jobs.dispatcher.register(jobs.handlerOf("shop", NameClashAvoidance.USE_PREFIX,
                new WiredProcess("Order", "shop__Order", Set.of(JobRunning.JOB_TYPE), true, false, Map.of(), Map
                        .of(), Map.of())));
        holds(ROOT, "order-1", "\"id\": \"order-1\"", SHIPPED);
        jobs.takesTheCommands(ROOT);

        jobs.runs(JobRunning.endMarkerJob(ROOT, SHIPPED));

        assertAll(
                () -> assertEquals(List.of(), jobs.ends.ends()),
                () -> assertEquals(List.of("POST /v1/jobs/42/complete"), jobs.commands()));
    }

    @Test
    @DisplayName("Where the engine node asked does not know the instance when its tokens are read, the job is left to its lock and nothing is reported")
    void anInstanceTheNodeDoesNotKnowYetLeavesTheJob(final CapturedOutput output) {
        final var reads = new AtomicInteger();
        jobs.engine.answers("GET", "/v1/process-instances/%d".formatted(ROOT), exchange -> FakeEngine.respond(exchange,
                reads.getAndIncrement() == 0 ? 200 : 404, reads.get() == 1 ? """
                        {"key": 100, "processDefinitionKey": 4503599627370498, "bpmnProcessId": "shop__Order", \
                        "version": 3, "businessKey": "order-1", "createdAt": "2026-10-17T14:30:25.123Z", \
                        "state": "active", "processType": "default", "variables": {"id": "order-1"}, \
                        "activeElementInstances": []}""" : "{\"code\": \"NOT_FOUND\"}"));
        jobs.takesTheCommands(ROOT);

        jobs.runs(JobRunning.endMarkerJob(ROOT, SHIPPED));

        assertAll(
                () -> assertEquals(List.of(), jobs.ends.ends()),
                () -> assertEquals(List.of(), jobs.commands()),
                () -> assertTrue(output
                        .getAllOfThisTest()
                        .contains("instance 100 of end event 'Shipped' is not known to the engine node asked"),
                        output::getAllOfThisTest));
    }

    @Test
    @DisplayName("An instance which names no aggregate, which was started past VanillaBP, reports nothing and ends")
    void anInstanceWithoutAnAggregateReportsNothing(final CapturedOutput output) {
        holds(ROOT, null, "", SHIPPED);
        jobs.hasChildren(ROOT);
        jobs.takesTheCommands(ROOT);

        jobs.runs(JobRunning.endMarkerJob(ROOT, SHIPPED));

        assertAll(
                () -> assertEquals(List.of(), jobs.ends.ends()),
                () -> assertEquals(List.of("POST /v1/jobs/42/complete"), jobs.commands()),
                () -> assertTrue(output
                        .getAllOfThisTest()
                        .contains("names no workflow aggregate"), output::getAllOfThisTest));
    }

    @Test
    @DisplayName("A report which throws fails the job with a message naming the @WorkflowEnded method, the configured retry-backoff and no retries, so the engine counts the attempt")
    void aReportWhichThrowsFailsTheJob(final CapturedOutput output) {
        holds(ROOT, "order-1", "\"id\": \"order-1\"", WITHDRAWN);
        jobs.takesTheCommands(ROOT);
        jobs.retryBackoffs.put("shop/Order/vanillabp__ended", Duration.ofSeconds(10));
        jobs.ends.answers(context -> {
            throw new IllegalStateException("the archive is down");
        });

        jobs.runs(JobRunning.endMarkerJob(ROOT, WITHDRAWN));

        final var failure = jobs
                .bodiesOf("POST", "/v1/jobs/42/fail")
                .getFirst();
        assertAll(
                () -> assertEquals(List.of("POST /v1/jobs/42/fail"), jobs.commands()),
                () -> assertTrue(failure.contains(
                        "The end of workflow aggregate 'order-1' at end event 'Withdrawn' of process 'Order' (workflow module 'shop') could not be reported: the @WorkflowEnded method of the process, or the platform's work around it, threw java.lang.IllegalStateException: the archive is down"),
                        failure),
                () -> assertTrue(failure.contains("\"retryBackoff\":\"PT10S\""), failure),
                () -> assertTrue(failure.contains("\"deliveryToken\":7"), failure),
                () -> assertFalse(failure.contains("\"retries\""), failure),
                () -> assertTrue(output
                        .getAllOfThisTest()
                        .contains("its job is failed with 0 retries left, so the engine raises an incident"),
                        output::getAllOfThisTest));
    }

    @Test
    @DisplayName("A failure without a message is named by its type, and a job with retries left is failed for the engine to hand it out again")
    void aFailureWithoutAMessageIsNamedByItsType(final CapturedOutput output) {
        holds(ROOT, "order-1", "\"id\": \"order-1\"", WITHDRAWN);
        jobs.takesTheCommands(ROOT);
        jobs.ends.answers(context -> {
            throw new IllegalStateException();
        });
        final var marker = JobRunning.endMarkerJob(ROOT, WITHDRAWN);

        jobs.runs(new ReceivedJob(marker
                .job()
                .toBuilder()
                .setRetries(3)
                .build(), marker.receivedAt()));

        final var failure = jobs
                .bodiesOf("POST", "/v1/jobs/42/fail")
                .getFirst();
        assertAll(
                () -> assertTrue(failure.contains("threw java.lang.IllegalStateException\""), failure),
                () -> assertTrue(output
                        .getAllOfThisTest()
                        .contains("its job is failed with 2 retries left"), output::getAllOfThisTest),
                () -> assertFalse(output
                        .getAllOfThisTest()
                        .contains("so the engine raises an incident"), output::getAllOfThisTest));
    }

    @Test
    @DisplayName("A report which fails while the module shuts down leaves the job to its lock, so it keeps its retries")
    void aFailureWhileStoppingKeepsTheRetries() {
        holds(ROOT, "order-1", "\"id\": \"order-1\"", WITHDRAWN);
        jobs.takesTheCommands(ROOT);
        jobs.ends.answers(context -> {
            jobs.handler.stopping();
            throw new IllegalStateException("the transaction was cut off");
        });

        jobs.runs(JobRunning.endMarkerJob(ROOT, WITHDRAWN));

        assertEquals(List.of(), jobs.commands());
    }

    @Test
    @DisplayName("A job which is gone when it is to be completed, or failed, costs a WARN and nothing else")
    void aGoneJobCostsAWarn(final CapturedOutput output) {
        holds(ROOT, "order-1", "\"id\": \"order-1\"", WITHDRAWN);
        jobs.engine.answers("POST", "/v1/jobs/42/complete", 404, "{\"code\": \"NOT_FOUND\"}");
        jobs.engine.answers("POST", "/v1/jobs/42/fail", 404, "{\"code\": \"NOT_FOUND\"}");

        jobs.runs(JobRunning.endMarkerJob(ROOT, WITHDRAWN));
        jobs.ends.answers(context -> {
            throw new IllegalStateException("refused");
        });
        jobs.runs(JobRunning.endMarkerJob(ROOT, WITHDRAWN));

        assertAll(
                () -> assertTrue(output
                        .getAllOfThisTest()
                        .contains("of end event 'Withdrawn' of process 'Order' was gone when it was to be completed"),
                        output::getAllOfThisTest),
                () -> assertTrue(output
                        .getAllOfThisTest()
                        .contains("of end event 'Withdrawn' was gone when its failure was to be sent"),
                        output::getAllOfThisTest));
    }

    @Test
    @DisplayName("A command the engine refuses otherwise leaves the job to its lock, whether it is the completion or the failure")
    void aRefusedCommandLeavesTheJobToItsLock(final CapturedOutput output) {
        holds(ROOT, "order-1", "\"id\": \"order-1\"", WITHDRAWN);
        jobs.engine.answers("POST", "/v1/jobs/42/complete", 400, "{\"code\": \"BAD_REQUEST\"}");
        jobs.engine.answers("POST", "/v1/jobs/42/fail", 400, "{\"code\": \"BAD_REQUEST\"}");

        jobs.runs(JobRunning.endMarkerJob(ROOT, WITHDRAWN));
        jobs.ends.answers(context -> {
            throw new IllegalStateException("refused");
        });
        jobs.runs(JobRunning.endMarkerJob(ROOT, WITHDRAWN));

        assertEquals(2, output
                .getAllOfThisTest()
                .split("could not be answered to the engine; it is left to its lock", -1).length - 1,
                output::getAllOfThisTest);
    }

    private long modelReadsOf(final long definitionKey) {
        return jobs.engine
                .requests()
                .stream()
                .filter(request -> "/v1/process-definitions/%d".formatted(definitionKey).equals(request.path()))
                .count();
    }

    private void holds(
        final long key,
        final String businessKey,
        final String variables,
        final String... standingAt
    ) {
        jobs.holds(key, "shop__Order", "default", null, businessKey, variables, List.of(standingAt));
    }

}

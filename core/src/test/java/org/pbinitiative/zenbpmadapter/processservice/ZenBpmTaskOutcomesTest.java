/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.processservice;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pbinitiative.zenbpmadapter.TestCollaborators;
import org.pbinitiative.zenbpmadapter.TestScoping;
import org.pbinitiative.zenbpmadapter.client.FakeEngine;
import org.pbinitiative.zenbpmadapter.client.FakeJobStreamEngine;
import org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientFactory;
import org.pbinitiative.zenbpmadapter.client.grpc.Zenbpm.StreamSubscriptionRequest;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.vanillabp.integration.adapter.spi.AggregateSyncMode;
import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.adapter.spi.PhaseOneRequest;
import io.vanillabp.integration.adapter.spi.PhaseOperationHandler;
import io.vanillabp.integration.adapter.spi.PhaseTwoRequest;
import io.vanillabp.integration.adapter.spi.PreCommitRegistrar;
import io.vanillabp.integration.adapter.spi.WorkflowAggregateSync;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.TaskNotFoundException;

/**
 * The completion and the cancellation of a task the application left open, both phases. Phase one
 * checks the task right before the caller's transaction commits; phase two writes the shared values
 * into the job's instance and every instance above it, then completes the job or fails it with the
 * BPMN error the application named. The engine is a fake which answers what each test tells it to
 * and writes down what it was asked. The module <code>shop</code> is scoped with a prefix, so its
 * process is <code>shop__Order</code> at the engine, and the job waits in an instance of an embedded
 * sub-process below the workflow's root.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmTaskOutcomesTest {

    /**
     * A key of partition 1, the one partition the fake engine reports: a key carries its partition
     * in bits 12 to 21.
     */
    private static final long JOB = (1L << 52) | (1L << 12) | 600;

    private static final long INSTANCE = 4503599627370510L;

    private static final long ROOT = 4503599627370501L;

    private static final String JOB_PATH = "/v1/jobs/%d".formatted(JOB);

    private static final String INSTANCE_PATH = "/v1/process-instances/%d".formatted(INSTANCE);

    private static final String ROOT_PATH = "/v1/process-instances/%d".formatted(ROOT);

    private static final String JOB_TYPE = "shop__Order__approve";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final FakeEngine engine = FakeEngine.started();

    private final List<Runnable> checks = new ArrayList<>();

    private FakeJobStreamEngine streamEngine;

    private ZenBpmClientFactory factory = configured(Duration.ZERO, null);

    @AfterEach
    void stopTheEngine() {
        factory.close();
        engine.close();
        if (streamEngine != null) {
            streamEngine.close();
        }
    }

    @Test
    @DisplayName("A task which waits, of a process the module serves, passes the check, and the check only reads")
    void aWaitingTaskOfTheModulePassesTheCheck() {
        engine.answers("GET", JOB_PATH, 200, job("active"));
        engine.answers("GET", INSTANCE_PATH, 200, instance(INSTANCE, "subprocess", ROOT));

        registerTheChecks();

        assertAll(
                () -> assertDoesNotThrow(() -> checks.forEach(Runnable::run)),
                () -> assertTrue(engine
                        .requests()
                        .stream()
                        .allMatch(request -> "GET".equals(request.method())), () -> requests().toString()));
    }

    @ParameterizedTest(name = "a job which is {0}")
    @ValueSource(strings = {
            "completed", "terminated"
    })
    @DisplayName("A task whose job ended is not found, naming the task and the state, for a completion and a cancellation")
    void aTaskWhichEndedIsNotFound(final String state) {
        engine.answers("GET", JOB_PATH, 200, job(state));

        registerTheChecks();

        assertAll(checks
                .stream()
                .map(check -> () -> {
                    final var gone = assertThrows(TaskNotFoundException.class, check::run);
                    assertTrue(gone
                            .getMessage()
                            .contains("task '%d' is gone, the engine holds it as %s".formatted(JOB, state)),
                            gone
                                    .getMessage());
                }));
    }

    @Test
    @DisplayName("A task the engine does not know is not found once workflow-visibility-timeout passed, asked for again meanwhile, because the node asked may lag behind")
    void aTaskTheEngineDoesNotKnowIsNotFoundAfterTheWindow() {
        factory.close();
        factory = configured(Duration.ofMillis(600), null);
        engine.answers("GET", JOB_PATH, 404, """
                {"code":"NOT_FOUND","message":"job not found"}""");
        final var startedAt = Instant.now();

        final var gone = assertThrows(TaskNotFoundException.class, () -> check(PhaseOperation.COMPLETE_TASK));

        final var waited = Duration.between(startedAt, Instant.now());
        assertAll(
                () -> assertTrue(gone
                        .getMessage()
                        .contains("the engine does not know it, not even after PT0.6S"), gone.getMessage()),
                () -> assertTrue(waited.compareTo(Duration.ofMillis(600)) >= 0, waited::toString),
                () -> assertTrue(requests().size() >= 2, () -> requests().toString()));
    }

    @Test
    @DisplayName("A task a node which lags behind answers within the window passes the check")
    void aTaskALaggingNodeAnswersLatePassesTheCheck() {
        factory.close();
        factory = configured(Duration.ofSeconds(5), null);
        final var reads = new AtomicInteger();
        engine.answers("GET", JOB_PATH, exchange -> FakeEngine.respond(exchange, reads.incrementAndGet() < 3 ? 404
                : 200, reads.get() < 3 ? "{\"code\":\"NOT_FOUND\",\"message\":\"job not found\"}" : job("active")));
        final var instanceReads = new AtomicInteger();
        engine.answers("GET", INSTANCE_PATH, exchange -> FakeEngine.respond(exchange, instanceReads
                .incrementAndGet() < 2 ? 404 : 200, instanceReads.get() < 2
                        ? "{\"code\":\"NOT_FOUND\",\"message\":\"instance not found\"}"
                        : instance(INSTANCE, "subprocess", ROOT)));

        assertDoesNotThrow(() -> check(PhaseOperation.CANCEL_TASK));

        assertAll(
                () -> assertEquals(3, reads.get()),
                () -> assertEquals(2, instanceReads.get()));
    }

    @Test
    @DisplayName("An interrupted check asks no more, keeps the interrupt and finds the task it did not get gone")
    void anInterruptedCheckAsksNoMore() {
        factory.close();
        factory = configured(Duration.ofSeconds(30), null);
        final var checking = Thread.currentThread();
        final var reads = new AtomicInteger();
        // the interrupt reaches the check while it waits before it asks again
        engine.answers("GET", JOB_PATH, exchange -> {
            FakeEngine.respond(exchange, 404, "{\"code\":\"NOT_FOUND\",\"message\":\"job not found\"}");
            if (reads.incrementAndGet() == 1) {
                Thread
                        .ofVirtual()
                        .start(() -> {
                            LockSupport.parkNanos(Duration
                                    .ofMillis(100)
                                    .toNanos());
                            checking.interrupt();
                        });
            }
        });
        final var startedAt = Instant.now();

        try {
            assertThrows(TaskNotFoundException.class, () -> check(PhaseOperation.COMPLETE_TASK));
            assertTrue(Thread
                    .currentThread()
                    .isInterrupted());
        } finally {
            Thread.interrupted();
        }

        assertAll(
                () -> assertEquals(1, reads.get()),
                () -> assertTrue(Duration
                        .between(startedAt, Instant.now())
                        .compareTo(Duration.ofSeconds(5)) < 0));
    }

    @ParameterizedTest(name = "task id ''{0}''")
    @ValueSource(strings = {
            "Activity_Approve:2251799813685249", "0", "-4503599627370600", ""
    })
    @DisplayName("A task id which is no key of this engine is not found, and nothing is asked")
    void aTaskIdWhichIsNoKeyIsNotFound(final String taskId) {
        final var gone = assertThrows(TaskNotFoundException.class, () -> check(PhaseOperation.COMPLETE_TASK,
                taskId));

        assertAll(
                () -> assertTrue(gone
                        .getMessage()
                        .contains("it is no key of this engine"), gone.getMessage()),
                () -> assertEquals(List.of(), requests()));
    }

    @Test
    @DisplayName("A key of a partition the engine does not report is not found: the engine answers 502 for it, and its partitions tell it apart")
    void aKeyOfAPartitionTheEngineDoesNotHaveIsNotFound() {
        final var keyOfPartitionTwo = (1L << 52) | (2L << 12);
        engine.answers("GET", "/v1/jobs/%d".formatted(keyOfPartitionTwo), 502, """
                {"code":"CLUSTER_ERROR","message":"partition 2 not found"}""");
        engine.answers("GET", "/system/status", 200, """
                {"git": {"commitId": "bdd795a399b6"}, "build": {"version": "v1.8.0"}, "nodes": {"zen-1": {}},
                 "partitions": {"1": {"id": 1}}}""");

        final var gone = assertThrows(TaskNotFoundException.class, () -> check(PhaseOperation.COMPLETE_TASK, String
                .valueOf(keyOfPartitionTwo)));

        assertTrue(gone
                .getMessage()
                .contains("it names partition 2, which the engine does not have"), gone.getMessage());
    }

    @Test
    @DisplayName("An engine which does not answer rolls the transaction back with a message naming the keys, its answer the cause")
    void anEngineWhichDoesNotAnswerRollsTheTransactionBack() {
        engine.answers("GET", JOB_PATH, 503, null);

        final var refused = assertThrows(IllegalStateException.class, () -> check(PhaseOperation.COMPLETE_TASK));

        assertAll(
                () -> assertEquals("""
                        ZenBPM adapter 'orders' could not ask the engine whether task '%d' still waits, so the \
                        transaction which completes it is rolled back. Try again once the engine at \
                        vanillabp.adapters.orders.rest-address answers within \
                        vanillabp.adapters.orders.request-timeout.""".formatted(JOB), refused.getMessage()),
                () -> assertTrue(refused.getCause() != null));
    }

    @Test
    @DisplayName("An engine which does not answer for the task's instance rolls the transaction back with the same message, its answer the cause")
    void anEngineWhichDoesNotAnswerForTheInstanceRollsTheTransactionBack() {
        engine.answers("GET", JOB_PATH, 200, job("active"));
        engine.answers("GET", INSTANCE_PATH, 503, null);

        final var refused = assertThrows(IllegalStateException.class, () -> check(PhaseOperation.CANCEL_TASK));

        assertAll(
                () -> assertEquals("""
                        ZenBPM adapter 'orders' could not ask the engine whether task '%d' still waits, so the \
                        transaction which cancels it is rolled back. Try again once the engine at \
                        vanillabp.adapters.orders.rest-address answers within \
                        vanillabp.adapters.orders.request-timeout.""".formatted(JOB), refused.getMessage()),
                () -> assertTrue(refused.getCause() != null));
    }

    @Test
    @DisplayName("A task which carries an incident cannot be completed or cancelled until the incident is resolved, which the message says with the key of the incident action")
    void aTaskWithAnIncidentCannotBeEnded() {
        engine.answers("GET", JOB_PATH, 200, job("failed"));

        final var refused = assertThrows(IllegalStateException.class, () -> check(PhaseOperation.CANCEL_TASK));

        assertEquals("""
                ZenBPM adapter 'orders': task '%d' carries an incident at the engine, which takes neither a \
                completion nor a cancellation of the task until the incident is resolved, so the transaction which \
                cancels it is rolled back. Resolve the incident first; where \
                vanillabp.adapters.orders.async-task-max-age-action raised it, set that key back to 'report' before, \
                or the next delivery of the task raises it again.""".formatted(JOB), refused.getMessage());
    }

    @ParameterizedTest(name = "a task of process {0}")
    @ValueSource(strings = {
            "crm__Order", "hr__Order"
    })
    @DisplayName("A task of a process another workflow module serves, or none of this application, is refused, naming the task, its process and who serves it")
    void aTaskOfAnotherModuleIsRefused(final String scopedProcessId) {
        engine.answers("GET", JOB_PATH, 200, job("active"));
        engine.answers("GET", INSTANCE_PATH, 200, instance(INSTANCE, scopedProcessId, "default", null));

        final var refused = assertThrows(IllegalStateException.class, () -> check(PhaseOperation.COMPLETE_TASK));

        final var servedBy = "crm__Order".equals(scopedProcessId) ? "workflow module 'crm'"
                : "no workflow module of this application";
        assertEquals("""
                ZenBPM adapter 'orders': task '%d' is a task of process '%s' at the engine, which %s serves, not \
                workflow module 'shop', so the transaction which completes it is rolled back. Call the process \
                service of the workflow which the task belongs to.""".formatted(JOB, scopedProcessId, servedBy),
                refused.getMessage());
    }

    @Test
    @DisplayName("A task whose instance the node does not know cannot be placed, so the transaction is rolled back rather than a task of another module ended")
    void aTaskWhoseInstanceIsNotKnownCannotBePlaced() {
        engine.answers("GET", JOB_PATH, 200, job("active"));
        engine.answers("GET", INSTANCE_PATH, 404, """
                {"code":"NOT_FOUND","message":"instance not found"}""");

        final var refused = assertThrows(IllegalStateException.class, () -> check(PhaseOperation.COMPLETE_TASK));

        assertTrue(refused
                .getMessage()
                .contains("does not know its process instance %d, not even after PT0S".formatted(INSTANCE)),
                refused
                        .getMessage());
    }

    @Test
    @DisplayName("A completion writes the shared values and the id into the job's instance and every instance up to the workflow's root, and then completes the job with them")
    void aCompletionWritesTheValuesUpToTheWorkflowAndThenCompletes() throws Exception {
        aJobWaitsInASubProcess();
        engine.answers("POST", JOB_PATH
                + "/complete", 201, null);

        handler(PhaseOperation.COMPLETE_TASK).phaseTwo(request(String.valueOf(JOB), null));

        final var commands = commands();
        assertAll(
                () -> assertEquals(List.of("PATCH %s/variables".formatted(INSTANCE_PATH), "PATCH %s/variables"
                        .formatted(ROOT_PATH), "POST %s/complete".formatted(JOB_PATH)), commands
                                .stream()
                                .map(request -> "%s %s".formatted(request.method(), request.path()))
                                .toList()),
                () -> assertEquals(JSON.readTree("{\"variables\": {\"amount\": 1000, \"orderId\": \"order-1\"}}"),
                        JSON.readTree(commands
                                .getFirst()
                                .body())),
                () -> assertEquals(JSON.readTree("{\"variables\": {\"amount\": 1000, \"orderId\": \"order-1\"}}"),
                        JSON.readTree(commands
                                .getLast()
                                .body())));
    }

    @Test
    @DisplayName("A cancellation writes the same values, then fails the job with the error code as the deployed model carries it, the values and this node's client id, but no delivery token and no retries")
    void aCancellationWritesTheValuesAndFailsWithTheScopedCode(final CapturedOutput output) throws Exception {
        aJobWaitsInASubProcess();
        final var reads = new AtomicInteger();
        engine.answers("GET", JOB_PATH, exchange -> FakeEngine.respond(exchange, 200, job(reads.incrementAndGet() == 1
                ? "active"
                : "terminated")));
        engine.answers("POST", JOB_PATH
                + "/fail", 204, null);

        handler(PhaseOperation.CANCEL_TASK).phaseTwo(request(String.valueOf(JOB), "withdrawn"));

        final var failure = JSON.readTree(commands()
                .getLast()
                .body());
        assertAll(
                () -> assertEquals("POST %s/fail".formatted(JOB_PATH), "%s %s".formatted(commands()
                        .getLast()
                        .method(),
                        commands()
                                .getLast()
                                .path())),
                () -> assertEquals("shop__withdrawn", failure
                        .get("errorCode")
                        .asText()),
                () -> assertEquals(JSON.readTree("{\"amount\": 1000, \"orderId\": \"order-1\"}"), failure.get(
                        "variables")),
                () -> assertEquals(factory.clientId(), failure
                        .get("clientId")
                        .asText()),
                () -> assertFalse(failure.has("deliveryToken"), failure::toString),
                () -> assertFalse(failure.has("retries"), failure::toString),
                () -> assertEquals("""
                        Task %d (element 'Task_Approve', workflow aggregate 'order-1') was cancelled with BPMN error \
                        'withdrawn', which no error boundary event and no error event sub-process catches, its code"""
                        .formatted(JOB),
                        failure
                                .get("message")
                                .asText()),
                () -> assertFalse(output
                        .getOutOfThisTest()
                        .contains("catches the BPMN error"), output::getOutOfThisTest));
    }

    @Test
    @DisplayName("A cancellation whose code nothing catches is named in a WARN, read from the job right after the failure")
    void aCancellationNothingCatchesIsNamed(final CapturedOutput output) {
        aJobWaitsInASubProcess();
        final var reads = new AtomicInteger();
        engine.answers("GET", JOB_PATH, exchange -> FakeEngine.respond(exchange, 200, job(reads.incrementAndGet() == 1
                ? "active"
                : "failed")));
        engine.answers("POST", JOB_PATH
                + "/fail", 204, null);

        handler(PhaseOperation.CANCEL_TASK).phaseTwo(request(String.valueOf(JOB), "lost"));

        assertTrue(output
                .getOutOfThisTest()
                .contains("""
                        ZenBPM[orders]: no error boundary event and no error event sub-process catches the BPMN error \
                        'lost' with which task %d (workflow aggregate 'order-1') was cancelled, whose code is \
                        'shop__lost' at the engine, so the engine raised an incident on the task.""".formatted(JOB)),
                output::getOutOfThisTest);
    }

    @Test
    @DisplayName("A cancellation whose job cannot be read afterwards is done all the same")
    void aCancellationWhoseJobCannotBeReadAfterwardsIsDone(final CapturedOutput output) {
        aJobWaitsInASubProcess();
        final var reads = new AtomicInteger();
        engine.answers("GET", JOB_PATH, exchange -> {
            if (reads.incrementAndGet() == 1) {
                FakeEngine.respond(exchange, 200, job("active"));
                return;
            }
            FakeEngine.respond(exchange, 503, null);
        });
        engine.answers("POST", JOB_PATH
                + "/fail", 204, null);

        assertDoesNotThrow(() -> handler(PhaseOperation.CANCEL_TASK).phaseTwo(request(String.valueOf(JOB), "lost")));

        assertFalse(output
                .getOutOfThisTest()
                .contains("WARN"), output::getOutOfThisTest);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "completed", "terminated"
    })
    @DisplayName("Phase two of a task whose job ended meanwhile is a WARN and nothing else, so the outbox entry is done")
    void aTaskWhichEndedMeanwhileIsAWarnedNoOp(
        final String state,
        final CapturedOutput output
    ) {
        engine.answers("GET", JOB_PATH, 200, job(state));

        for (final var operation : List.of(PhaseOperation.COMPLETE_TASK, PhaseOperation.CANCEL_TASK)) {
            assertDoesNotThrow(() -> handler(operation).phaseTwo(request(String.valueOf(JOB), "withdrawn")));
        }

        assertAll(
                () -> assertEquals(List.of(), commands()),
                () -> assertTrue(output
                        .getOutOfThisTest()
                        .contains(
                                "ZenBPM[orders]: task %d of workflow aggregate 'order-1' is not completed: the engine holds it as %s"
                                        .formatted(JOB, state)),
                        output::getOutOfThisTest),
                () -> assertTrue(output
                        .getOutOfThisTest()
                        .contains("is not cancelled: the engine holds it as %s".formatted(state)),
                        output::getOutOfThisTest));
    }

    @ParameterizedTest(name = "answered {0}")
    @ValueSource(ints = {
            404, 409
    })
    @DisplayName("A completion or cancellation the engine refuses because the job ended is a WARN and nothing else")
    void aCommandWhichFindsTheJobGoneIsAWarnedNoOp(
        final int status,
        final CapturedOutput output
    ) {
        aJobWaitsInASubProcess();
        engine.answers("POST", JOB_PATH
                + "/complete", status, "{\"code\":\"CONFLICT\",\"message\":\"ended\"}");
        engine.answers("POST", JOB_PATH
                + "/fail", status, "{\"code\":\"CONFLICT\",\"message\":\"ended\"}");

        for (final var operation : List.of(PhaseOperation.COMPLETE_TASK, PhaseOperation.CANCEL_TASK)) {
            assertDoesNotThrow(() -> handler(operation).phaseTwo(request(String.valueOf(JOB), "withdrawn")));
        }

        assertAll(
                () -> assertTrue(output
                        .getOutOfThisTest()
                        .contains("is not completed: it ended meanwhile"), output::getOutOfThisTest),
                () -> assertTrue(output
                        .getOutOfThisTest()
                        .contains("is not cancelled: it ended meanwhile"), output::getOutOfThisTest));
    }

    @Test
    @DisplayName("Phase two of a task which meanwhile carries an incident is thrown, so the outbox repeats it once the incident is resolved, and nothing is sent")
    void aTaskWhichCarriesAnIncidentIsRepeated() {
        final var processService = processService();
        engine.answers("GET", JOB_PATH, 200, job("failed"));

        final var refusals = List
                .of(PhaseOperation.COMPLETE_TASK, PhaseOperation.CANCEL_TASK)
                .stream()
                .map(operation -> assertThrows(IllegalStateException.class, () -> handler(operation).phaseTwo(
                        request(String.valueOf(JOB), "withdrawn"))))
                .toList();

        assertAll(
                () -> assertEquals(List.of(), commands()),
                () -> assertEquals("""
                        ZenBPM adapter 'orders': task %d carries an incident at the engine, which takes neither a \
                        completion nor a cancellation of it until the incident is resolved, so it is not completed \
                        now, and the outbox repeats it. Resolve the incident; where \
                        vanillabp.adapters.orders.async-task-max-age-action raised it, set that key back to 'report' \
                        before, or the next delivery of the task raises it again.""".formatted(JOB), refusals
                        .getFirst()
                        .getMessage()),
                () -> assertTrue(refusals
                        .getLast()
                        .getMessage()
                        .contains("so it is not cancelled now"),
                        refusals
                                .getLast()::getMessage),
                () -> assertTrue(refusals
                        .stream()
                        .allMatch(processService::isPhaseTwoFailureRepeatable)));
    }

    @Test
    @DisplayName("A completion or cancellation the engine refuses because the job meanwhile carries an incident is thrown, so the outbox repeats it")
    void aCommandRefusedForAJobWhichCarriesAnIncidentIsRepeated(final CapturedOutput output) {
        final var processService = processService();
        final var refusals = new ArrayList<RuntimeException>();
        for (final var operation : List.of("complete", "fail")) {
            aJobWaitsInASubProcess();
            engine.answers("POST", "%s/%s".formatted(JOB_PATH, operation), exchange -> {
                engine.answers("GET", JOB_PATH, 200, job("failed"));
                FakeEngine.respond(exchange, 409, "{\"code\":\"CONFLICT\",\"message\":\"job is failed\"}");
            });
            refusals.add(assertThrows(IllegalStateException.class, () -> handler("complete".equals(operation)
                    ? PhaseOperation.COMPLETE_TASK
                    : PhaseOperation.CANCEL_TASK).phaseTwo(request(String.valueOf(JOB), "withdrawn"))));
        }

        assertAll(
                () -> assertTrue(refusals
                        .getFirst()
                        .getMessage()
                        .contains("carries an incident at the engine"),
                        refusals
                                .getFirst()::getMessage),
                () -> assertTrue(refusals
                        .getLast()
                        .getMessage()
                        .contains("so it is not cancelled now"),
                        refusals
                                .getLast()::getMessage),
                () -> assertTrue(refusals
                        .stream()
                        .allMatch(processService::isPhaseTwoFailureRepeatable)),
                () -> assertFalse(output
                        .getOutOfThisTest()
                        .contains("ended meanwhile"), output::getOutOfThisTest));
    }

    @Test
    @DisplayName("A job which cannot be read again after the engine refused a command for it is thrown with that read's failure, the refusal suppressed, so the outbox repeats it")
    void aJobWhichCannotBeReadAfterARefusalIsRepeated() {
        final var processService = processService();
        aJobWaitsInASubProcess();
        engine.answers("POST", JOB_PATH
                + "/complete", exchange -> {
                    engine.answers("GET", JOB_PATH, 503, null);
                    FakeEngine.respond(exchange, 409, "{\"code\":\"CONFLICT\",\"message\":\"ended\"}");
                });

        final var failure = assertThrows(RuntimeException.class, () -> handler(PhaseOperation.COMPLETE_TASK)
                .phaseTwo(request(String.valueOf(JOB), null)));

        assertAll(
                () -> assertEquals(1, failure.getSuppressed().length),
                () -> assertTrue(processService.isPhaseTwoFailureRepeatable(failure), failure::toString));
    }

    @Test
    @DisplayName("A workflow which ended before its values were written is a WARN, and the job is neither completed nor failed")
    void aWorkflowWhichEndedIsAWarnedNoOp(final CapturedOutput output) {
        aJobWaitsInASubProcess();
        engine.answers("PATCH", INSTANCE_PATH
                + "/variables", 409, """
                        {"code":"CONFLICT","message":"process instance is not active"}""");

        for (final var operation : List.of(PhaseOperation.COMPLETE_TASK, PhaseOperation.CANCEL_TASK)) {
            assertDoesNotThrow(() -> handler(operation).phaseTwo(request(String.valueOf(JOB), "withdrawn")));
        }

        assertAll(
                () -> assertTrue(commands()
                        .stream()
                        .allMatch(request -> "PATCH".equals(request.method())), () -> commands().toString()),
                () -> assertTrue(output
                        .getOutOfThisTest()
                        .contains("is not completed: its workflow ended or was cancelled meanwhile"),
                        output::getOutOfThisTest));
    }

    @Test
    @DisplayName("What may pass is thrown, so the outbox repeats phase two: a job or an instance the node does not know yet, an update which finds no instance, an engine which does not answer")
    void whatMayPassIsRepeated() {
        final var processService = processService();
        final var completion = handler(PhaseOperation.COMPLETE_TASK);
        final var failures = new ArrayList<RuntimeException>();

        engine.answers("GET", JOB_PATH, 404, "{\"code\":\"NOT_FOUND\",\"message\":\"job not found\"}");
        failures.add(assertThrows(IllegalStateException.class, () -> completion.phaseTwo(request(String.valueOf(JOB),
                null))));
        engine.answers("GET", JOB_PATH, 200, job("active"));
        engine.answers("GET", INSTANCE_PATH, 404, "{\"code\":\"NOT_FOUND\",\"message\":\"instance not found\"}");
        failures.add(assertThrows(IllegalStateException.class, () -> completion.phaseTwo(request(String.valueOf(JOB),
                null))));
        aJobWaitsInASubProcess();
        engine.answers("PATCH", ROOT_PATH
                + "/variables", 404, "{\"code\":\"NOT_FOUND\",\"message\":\"not found\"}");
        failures.add(assertThrows(RuntimeException.class, () -> completion.phaseTwo(request(String.valueOf(JOB),
                null))));
        engine.answers("PATCH", ROOT_PATH
                + "/variables", 204, null);
        engine.answers("POST", JOB_PATH
                + "/complete", 503, null);
        failures.add(assertThrows(RuntimeException.class, () -> completion.phaseTwo(request(String.valueOf(JOB),
                null))));
        engine.answers("POST", JOB_PATH
                + "/fail", 503, null);
        failures.add(assertThrows(RuntimeException.class, () -> handler(PhaseOperation.CANCEL_TASK).phaseTwo(
                request(String.valueOf(JOB), "withdrawn"))));

        assertAll(failures
                .stream()
                .map(failure -> () -> assertTrue(processService.isPhaseTwoFailureRepeatable(failure),
                        failure::toString)));
    }

    @Test
    @DisplayName("A task id which is no number never becomes one: phase two refuses it, and the outbox does not repeat it")
    void aTaskIdWhichIsNoNumberIsPermanent() {
        final var processService = processService();

        final var refused = assertThrows(NumberFormatException.class, () -> handler(PhaseOperation.CANCEL_TASK)
                .phaseTwo(request("Activity_Approve:2251799813685249", "withdrawn")));

        assertAll(
                () -> assertFalse(processService.isPhaseTwoFailureRepeatable(refused)),
                () -> assertEquals(List.of(), requests()));
    }

    @Test
    @DisplayName("A job this node parked stops raising the cap of its type once its task was completed or cancelled, because the engine dropped its lock; one which ended without a command keeps counting")
    void aJobThisNodeParkedStopsCountingOnceItsTaskEnded() {
        factory.close();
        streamEngine = new FakeJobStreamEngine().startOverTcp();
        factory = configured(Duration.ZERO, streamEngine.address());
        aJobWaitsInASubProcess();
        engine.answers("POST", JOB_PATH
                + "/complete", 201, null);
        final var stream = factory.stream();
        stream.subscribe("shop", JOB_TYPE, Duration.ofMinutes(5), 1);
        final var call = streamEngine.call(0);
        call.subscriptions(1);
        final var later = Instant
                .now()
                .plus(Duration.ofHours(1));

        stream.parked(JOB_TYPE, JOB, later);
        call.subscriptions(2);
        engine.answers("GET", JOB_PATH, 200, job("terminated"));
        handler(PhaseOperation.COMPLETE_TASK).phaseTwo(request(String.valueOf(JOB), null));
        // a second job parked afterwards shows whether the first still counts
        stream.parked(JOB_TYPE, JOB + 1, later);
        call.subscriptions(3);
        engine.answers("GET", JOB_PATH, 200, job("active"));
        handler(PhaseOperation.COMPLETE_TASK).phaseTwo(request(String.valueOf(JOB), null));

        assertEquals(List.of(1, 2, 3, 2), call
                .subscriptions(4)
                .stream()
                .map(StreamSubscriptionRequest::getMaxActiveJobs)
                .toList());
    }

    private void registerTheChecks() {
        handler(PhaseOperation.COMPLETE_TASK).phaseOne(phaseOne(String.valueOf(JOB)));
        handler(PhaseOperation.CANCEL_TASK).phaseOne(phaseOne(String.valueOf(JOB)));
    }

    private void check(final PhaseOperation operation) {
        check(operation, String.valueOf(JOB));
    }

    private void check(
        final PhaseOperation operation,
        final String taskId
    ) {
        checks.clear();
        handler(operation).phaseOne(phaseOne(taskId));
        assertEquals(1, checks.size());
        checks
                .getFirst()
                .run();
    }

    private void aJobWaitsInASubProcess() {
        engine.answers("GET", JOB_PATH, 200, job("active"));
        engine.answers("GET", INSTANCE_PATH, 200, instance(INSTANCE, "subprocess", ROOT));
        engine.answers("GET", ROOT_PATH, 200, instance(ROOT, "shop__Order", "default", null));
        engine.answers("PATCH", INSTANCE_PATH
                + "/variables", 204, null);
        engine.answers("PATCH", ROOT_PATH
                + "/variables", 204, null);
    }

    private PhaseOperationHandler<Object> handler(final PhaseOperation operation) {
        return processService()
                .phaseOperations()
                .get(operation);
    }

    private ZenBpmProcessService<Object> processService() {
        final PreCommitRegistrar registrar = (
            aggregateClass,
            check) -> checks.add(check);
        factory
                .deployedProcesses()
                .recordServed("shop", "shop__Order");
        factory
                .deployedProcesses()
                .recordServed("crm", "crm__Order");
        return new ZenBpmProcessService<>("orders", factory, TestCollaborators.withPreCommitRegistrar("orders",
                TestScoping.of(NameClashAvoidance.USE_PREFIX), new SharingTheAmount(), registrar), (
                    module,
                    process) -> null);
    }

    private List<String> requests() {
        return engine
                .requests()
                .stream()
                .map(request -> "%s %s".formatted(request.method(), request.path()))
                .toList();
    }

    // what changes something at the engine
    private List<FakeEngine.Request> commands() {
        return engine
                .requests()
                .stream()
                .filter(request -> !"GET".equals(request.method()))
                .toList();
    }

    private static PhaseOneRequest<Object> phaseOne(final String taskId) {
        return new PhaseOneRequest<>("shop", "Order", persistence(), new Object(), Map.of(PhaseTwoCall.ARG_TASK_ID,
                taskId, PhaseTwoCall.ARG_BPMN_ERROR_CODE, "withdrawn"));
    }

    private static PhaseTwoRequest<Object> request(
        final String taskId,
        final String bpmnErrorCode
    ) {
        final var args = bpmnErrorCode == null ? Map.of(PhaseTwoCall.ARG_TASK_ID, taskId) : Map.of(
                PhaseTwoCall.ARG_TASK_ID, taskId, PhaseTwoCall.ARG_BPMN_ERROR_CODE, bpmnErrorCode);
        return new PhaseTwoRequest<>("shop", "Order", persistence(), "order-1", args);
    }

    private static String job(final String state) {
        return """
                {"key": %d, "elementId": "Task_Approve", "type": "%s", "elementType": "SERVICE_TASK",
                 "processInstanceKey": %d, "elementInstanceKey": 4503599627370599, "state": "%s",
                 "createdAt": "2026-10-17T10:00:00Z", "inputVariables": {}}""".formatted(JOB, JOB_TYPE, INSTANCE,
                state);
    }

    private static String instance(
        final long key,
        final String processType,
        final long parent
    ) {
        return instance(key, "shop__Order", processType, parent);
    }

    private static String instance(
        final long key,
        final String scopedProcessId,
        final String processType,
        final Long parent
    ) {
        return """
                {"key": %d, "processDefinitionKey": 4503599627370401, "bpmnProcessId": "%s", "version": 1,
                 "createdAt": "2026-10-17T10:00:00Z", "state": "active", %s "processType": "%s",
                 "businessKey": "order-1", "variables": {"orderId": "order-1"}}""".formatted(key, scopedProcessId,
                parent == null ? "" : "\"parentProcessInstanceKey\": %d,".formatted(parent), processType);
    }

    private static AggregatePersistenceAware<Object> persistence() {
        return new AggregatePersistenceAware<>() {

            @Override
            public Class<Object> getAggregateClass() {
                return Object.class;
            }

            @Override
            public String getAggregateIdName() {
                return "orderId";
            }

            @Override
            public Object loadById(final Object aggregateId) {
                assertEquals("order-1", aggregateId);
                return new Object();
            }

        };
    }

    private ZenBpmClientFactory configured(
        final Duration workflowVisibilityTimeout,
        final String grpcAddress
    ) {
        final var configuration = new ZenBpmAdapterConfiguration();
        configuration.setRestAddress(engine.address());
        configuration.setWorkflowVisibilityTimeout(workflowVisibilityTimeout);
        if (grpcAddress != null) {
            configuration.setGrpcAddress(grpcAddress);
        }
        return new ZenBpmClientFactory("orders", configuration, () -> "shop");
    }

    /**
     * A sync model which shares the amount of every aggregate, and also an attribute named like the
     * id, which the id has to win over.
     */
    private static final class SharingTheAmount implements WorkflowAggregateSync {

        @Override
        public Map<String, Object> syncedValues(
            final Object workflowAggregate,
            final AggregateSyncMode adapterDefault
        ) {
            assertEquals(AggregateSyncMode.FULL, adapterDefault);
            return Map.of("orderId", "an attribute", "amount", 1000);
        }

        @Override
        public void validateSyncModel(final Class<?> workflowAggregateClass) {
        }

    }

}

/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.wiring;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pbinitiative.zenbpmadapter.observability.ZenBpmMetrics;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.json.JsonMapper;

import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What the handler of a workflow module sends where the <code>&#64;WorkflowTask</code> method threw:
 * one failure without an error code and without retries, so the engine counts one attempt down; the
 * configured <code>retry-backoff</code>, or none where no level sets it; the exception's type and
 * message beside the task and the aggregate; the delivery's token and the job stream's client id.
 * The WARN names the type, the attempt and the retries left, and a failure the engine answers with
 * <code>409</code> is a WARN and nothing else. A failure the engine took is counted, one it answered
 * with <code>409</code> is not.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmJobFailureTest {

    private static final long ROOT = 100;

    private JobRunning jobs;

    @BeforeEach
    void startTheEngine() {
        jobs = new JobRunning(2);
        jobs.holds(ROOT, "default", null, "order-1", "\"id\": \"order-1\"");
    }

    @AfterEach
    void stopTheEngine() {
        jobs.close();
    }

    @Test
    @DisplayName("A handler which threw is failed once, without an error code and without retries, with the backoff of the task, the exception's type and message, the delivery's token and the stream's client id")
    void aThrowingHandlerIsFailedAtTheEngine(final CapturedOutput output) {
        jobs.takesTheCommands(ROOT);
        jobs.retryBackoffs.put("shop/Order/ship", Duration.ofSeconds(7));
        throwing(new IllegalStateException("the warehouse is closed"));

        jobs.runs(JobRunning.job(ROOT, 3, 1));

        final var failure = json(jobs
                .bodiesOf("POST", "/v1/jobs/42/fail")
                .getFirst());
        final var log = output.getAllOfThisTest();
        assertAll(
                () -> assertEquals(List.of("POST /v1/jobs/42/fail"), jobs.commands()),
                () -> assertEquals(Map.of(
                        "message",
                        "Task 'ship' of workflow aggregate 'order-1' threw java.lang.IllegalStateException: the warehouse is closed",
                        "retryBackoff", "PT7S", "deliveryToken", 7, "clientId", jobs.factory.clientId()), failure),
                () -> assertTrue(log.contains(
                        "the handler of job 42 (task 'ship', workflow aggregate 'order-1') threw java.lang.IllegalStateException at attempt 1; the job is failed with 2 retries left, and the engine hands it out again after PT7S"),
                        log),
                () -> assertTrue(log.contains("java.lang.IllegalStateException: the warehouse is closed\n\tat "), log),
                () -> assertEquals(1, jobs.counted(ZenBpmMetrics.JOBS_FAILED)),
                () -> assertEquals(0, jobs.counted(ZenBpmMetrics.JOBS_COMPLETED)));
    }

    @Test
    @DisplayName("Where no level sets retry-backoff, the failure carries none, so the backoff of the model or the engine applies")
    void aBackoffNoLevelSetsIsLeftOut(final CapturedOutput output) {
        jobs.takesTheCommands(ROOT);
        jobs.retryBackoffs.put("shop/Order/pack", Duration.ofSeconds(7));
        throwing(new IllegalStateException("the warehouse is closed"));

        jobs.runs(JobRunning.job(ROOT, 3, 1));

        final var log = output.getAllOfThisTest();
        assertAll(
                () -> assertFalse(json(jobs
                        .bodiesOf("POST", "/v1/jobs/42/fail")
                        .getFirst()).containsKey("retryBackoff")),
                () -> assertTrue(log.contains(
                        "with 2 retries left, and the engine hands it out again after the retryBackoff of the task's model, else the engine's default"),
                        log));
    }

    @Test
    @DisplayName("The last attempt is failed like the others, and the WARN says that the engine raises an incident")
    void theLastAttemptSaysThatAnIncidentFollows(final CapturedOutput output) {
        jobs.takesTheCommands(ROOT);
        throwing(new IllegalStateException("the warehouse is closed"));

        jobs.runs(JobRunning.job(ROOT, 1, 2));

        final var log = output.getAllOfThisTest();
        assertAll(
                () -> assertFalse(json(jobs
                        .bodiesOf("POST", "/v1/jobs/42/fail")
                        .getFirst()).containsKey("retries")),
                () -> assertTrue(log.contains(
                        "threw java.lang.IllegalStateException at attempt 2, which leaves no retries; the job is failed, and the engine raises an incident"),
                        log));
    }

    @Test
    @DisplayName("An exception without a message is named by its type, not by 'null'")
    void anExceptionWithoutAMessageIsNamedByItsType() {
        jobs.takesTheCommands(ROOT);
        throwing(new NullPointerException());

        jobs.runs(JobRunning.job(ROOT, 3, 1));

        assertEquals("Task 'ship' of workflow aggregate 'order-1' threw java.lang.NullPointerException", json(jobs
                .bodiesOf("POST", "/v1/jobs/42/fail")
                .getFirst()).get("message"));
    }

    @Test
    @DisplayName("A failure the engine answers with 409, because another delivery superseded this one, is a WARN and nothing else")
    void aSupersededFailureIsAWarnAndNothingElse(final CapturedOutput output) {
        jobs.engine.answers("POST", "/v1/jobs/42/fail", 409, "{\"code\":\"CONFLICT\",\"message\":\"superseded\"}");
        throwing(new IllegalStateException("the warehouse is closed"));

        jobs.runs(JobRunning.job(ROOT, 3, 1));

        final var log = output.getAllOfThisTest();
        assertAll(
                () -> assertEquals(List.of("POST /v1/jobs/42/fail"), jobs.commands()),
                () -> assertTrue(log.contains(
                        "job 42 (task 'ship', workflow aggregate 'order-1') was gone when its failure was to be sent: the engine handed it out again and the newer delivery decides"),
                        log),
                () -> assertFalse(log.contains("could not be answered to the engine"), log),
                () -> assertEquals(0, jobs.counted(ZenBpmMetrics.JOBS_FAILED), "the engine took no failure"));
    }

    @Test
    @DisplayName("A failure the engine refuses for another reason than a gone job leaves the job to its lock, with a WARN")
    void aRefusedFailureLeavesTheJobToItsLock(final CapturedOutput output) {
        jobs.engine.answers("POST", "/v1/jobs/42/fail", 400, "{\"code\":\"BAD_REQUEST\",\"message\":\"no\"}");
        throwing(new IllegalStateException("the warehouse is closed"));

        jobs.runs(JobRunning.job(ROOT, 3, 1));

        final var log = output.getAllOfThisTest();
        assertAll(
                () -> assertEquals(List.of("POST /v1/jobs/42/fail"), jobs.commands()),
                () -> assertTrue(log.contains(
                        "job 42 (task 'ship') could not be answered to the engine; it is left to its lock, after which the engine hands it out again"),
                        log));
    }

    @Test
    @DisplayName("Without a configuration no task has a retry-backoff")
    void withoutAConfigurationNoTaskHasABackoff() {
        assertEquals(Optional.empty(), ZenBpmRetryBackoffResolver.NONE.retryBackoffFor("shop", "Order", "ship"));
    }

    private void throwing(final RuntimeException thrown) {
        jobs.invoker.answers(context -> {
            throw thrown;
        });
    }

    private static Map<String, Object> json(final String body) {
        try {
            return JsonMapper
                    .builder()
                    .build()
                    .readValue(body, new TypeReference<Map<String, Object>>() {
                    });
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

}

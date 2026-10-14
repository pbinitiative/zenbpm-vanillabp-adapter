/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.client;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pbinitiative.zenbpmadapter.client.api.CreateInstanceRequest;
import org.pbinitiative.zenbpmadapter.client.api.JobFilter;
import org.pbinitiative.zenbpmadapter.client.api.JobRef;
import org.pbinitiative.zenbpmadapter.client.api.ProcessInstanceRef;
import org.pbinitiative.zenbpmadapter.test.EngineUnderTest;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The statuses of the README's table "Which phase-two failures are repeated" which the pinned
 * engine can be made to answer, each read by {@link ZenBpmErrors} as the table says. What the
 * table says about a failure built by hand is {@link ZenBpmErrorsTest}.
 */
@ExtendWith(SuppressOutputExtension.class)
@Testcontainers(disabledWithoutDocker = true)
class ZenBpmErrorsIT {

    private static final String ADAPTER_ID = "errors";

    /**
     * How long the engine may take to move an instance on after a command. It answers the command
     * once the instance moved, so this only bounds a test which waits for an effect.
     */
    private static final Duration EFFECT_TIMEOUT = Duration.ofSeconds(10);

    /**
     * A key carries the partition which holds it in bits 12 to 21. This is the oldest key partition
     * 1 can hand out, at the epoch of the engine's key generator, so no job has it.
     */
    private static final long KEY_NOBODY_HOLDS = 1L << 12;

    /**
     * A key of partition 0, which holds what every partition shares, like the process
     * definitions. No instance and no job is routed there.
     */
    private static final long KEY_OF_NO_PARTITION = 1;

    /**
     * One byte more than the engine takes in a request by default (<code>MaxRequestBodyBytes</code>,
     * 10 MiB).
     */
    private static final int MORE_THAN_THE_ENGINE_TAKES = (10 * 1024 * 1024) + 1;

    @Container
    private static final EngineUnderTest ENGINE = new EngineUnderTest();

    private static ZenBpmRestClient client;

    @BeforeAll
    static void deployTheFixture() {
        final var configuration = new ZenBpmAdapterConfiguration();
        configuration.setRestAddress(ENGINE.restAddress());
        client = new ZenBpmRestClient(ADAPTER_ID, configuration);
        client.deployBpmn(fixture("contract-task.bpmn"));
    }

    @AfterAll
    static void closeTheClient() {
        client.close();
    }

    @Test
    @DisplayName("A job the engine does not hold is gone, for a completion, a failure and a lock extension")
    void aJobTheEngineDoesNotHoldIsGone() {
        final var completion = assertThrows(ZenBpmApiException.class, () -> client.completeJob(KEY_NOBODY_HOLDS,
                null));
        final var failure = assertThrows(ZenBpmApiException.class, () -> client.failJob(KEY_NOBODY_HOLDS, null,
                null));
        final var extension = assertThrows(ZenBpmApiException.class, () -> client.extendJobLock(KEY_NOBODY_HOLDS,
                "errors-node-1", Duration.ofMinutes(1)));

        assertAll(
                () -> assertEquals(404, completion.getStatus()),
                () -> assertTrue(ZenBpmErrors.isGoneJob(completion)),
                () -> assertEquals(404, failure.getStatus()),
                () -> assertTrue(ZenBpmErrors.isGoneJob(failure)),
                () -> assertEquals(404, extension.getStatus()),
                () -> assertTrue(ZenBpmErrors.isGoneJob(extension)),
                () -> assertFalse(ZenBpmErrors.lockLost(extension)));
    }

    @Test
    @DisplayName("A job which failed with an incident is gone for a completion and for a failure")
    void aJobWhichFailedWithAnIncidentIsGone() {
        final var job = waitingJobOf(start());
        client.failJob(job.key(), "errors-nobody-catches", null);
        eventually(() -> client
                .job(job.key())
                .filter(failed -> failed.state() == JobRef.State.FAILED));

        final var completion = assertThrows(ZenBpmApiException.class, () -> client.completeJob(job.key(), null));
        final var failure = assertThrows(ZenBpmApiException.class, () -> client.failJob(job.key(), null, null));

        assertAll(
                () -> assertEquals(409, completion.getStatus()),
                () -> assertTrue(ZenBpmErrors.isGoneJob(completion)),
                () -> assertEquals(409, failure.getStatus()),
                () -> assertTrue(ZenBpmErrors.isGoneJob(failure)));
    }

    @Test
    @DisplayName("The lock of a job nobody received over the job stream is lost, and the job is not gone")
    void theLockOfAJobNeverDeliveredIsLost() {
        final var job = waitingJobOf(start());

        final var extension = assertThrows(ZenBpmApiException.class, () -> client.extendJobLock(job.key(),
                "errors-node-1", Duration.ofMinutes(1)));

        assertAll(
                () -> assertEquals(409, extension.getStatus()),
                () -> assertTrue(ZenBpmErrors.lockLost(extension)),
                () -> assertFalse(ZenBpmErrors.isGoneJob(extension)),
                () -> assertTrue(ZenBpmErrors.isRepeatable(extension)));
    }

    @Test
    @DisplayName("A message nothing waits for is read as nobody waits, and is repeatable")
    void aMessageNothingWaitsForIsReadAsNobodyWaits() {
        final var publication = assertThrows(ZenBpmApiException.class, () -> client.publishMessage("contract-paid",
                uniqueBusinessKey(), null));

        assertAll(
                () -> assertEquals(404, publication.getStatus()),
                () -> assertTrue(ZenBpmErrors.nobodyWaits(publication)),
                () -> assertFalse(ZenBpmErrors.isGoneJob(publication)),
                () -> assertTrue(ZenBpmErrors.isRepeatable(publication)));
    }

    @Test
    @DisplayName("A start the engine refuses as a request is permanent")
    void aRefusedStartIsPermanent() {
        final var start = assertThrows(ZenBpmApiException.class, () -> client.createInstance(CreateInstanceRequest
                .latestVersionOf("contract-task")
                .withHistoryTimeToLive("not a time to live")));

        assertAll(
                () -> assertEquals(400, start.getStatus()),
                () -> assertTrue(ZenBpmErrors.permanentFailure(start)),
                () -> assertFalse(ZenBpmErrors.isRepeatable(start)));
    }

    @Test
    @DisplayName("A start of a process the engine does not hold is a 404, and repeatable")
    void aStartOfAProcessTheEngineDoesNotHoldIsRepeatable() {
        final var start = assertThrows(ZenBpmApiException.class, () -> client.createInstance(CreateInstanceRequest
                .latestVersionOf("errors-never-deployed")));

        assertAll(
                () -> assertEquals(404, start.getStatus()),
                () -> assertFalse(ZenBpmErrors.permanentFailure(start)),
                () -> assertTrue(ZenBpmErrors.isRepeatable(start)));
    }

    @Test
    @DisplayName("A request larger than the engine takes is permanent")
    void aRequestLargerThanTheEngineTakesIsPermanent() {
        final var deployment = assertThrows(ZenBpmApiException.class, () -> client.deployBpmn(
                new byte[MORE_THAN_THE_ENGINE_TAKES]));

        assertAll(
                () -> assertEquals(413, deployment.getStatus()),
                () -> assertTrue(ZenBpmErrors.permanentFailure(deployment)));
    }

    @Test
    @DisplayName("A key of a partition the engine does not have reads as unavailable")
    void aKeyOfAPartitionTheEngineDoesNotHaveReadsAsUnavailable() {
        final var read = assertThrows(ZenBpmApiException.class, () -> client.instance(KEY_OF_NO_PARTITION));

        assertAll(
                () -> assertEquals(502, read.getStatus()),
                () -> assertTrue(ZenBpmErrors.isUnavailable(read)),
                () -> assertTrue(ZenBpmErrors.isRepeatable(read)));
    }

    private static ProcessInstanceRef start() {
        final var businessKey = uniqueBusinessKey();
        return client.createInstance(CreateInstanceRequest
                .latestVersionOf("contract-task")
                .withBusinessKey(businessKey)
                .withVariables(Map.of("orderId", businessKey)));
    }

    private static JobRef waitingJobOf(final ProcessInstanceRef instance) {
        return eventually(() -> client
                .jobs(JobFilter
                        .anyJob()
                        .withJobType("contract-work")
                        .withState(JobRef.State.ACTIVE)
                        .withProcessInstanceKey(instance.key()))
                .items()
                .stream()
                .findFirst());
    }

    private static <T> T eventually(final Supplier<Optional<T>> read) {
        final var deadline = System.nanoTime() + EFFECT_TIMEOUT.toNanos();
        while (true) {
            final var value = read.get();
            if (value.isPresent()) {
                return value.get();
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Nothing was read within %s".formatted(EFFECT_TIMEOUT));
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread
                        .currentThread()
                        .interrupt();
                throw new AssertionError(e);
            }
        }
    }

    private static byte[] fixture(final String name) {
        try (var resource = ZenBpmErrorsIT.class.getResourceAsStream("/contract/%s".formatted(name))) {
            return resource.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String uniqueBusinessKey() {
        return "order-%s".formatted(UUID.randomUUID());
    }

}

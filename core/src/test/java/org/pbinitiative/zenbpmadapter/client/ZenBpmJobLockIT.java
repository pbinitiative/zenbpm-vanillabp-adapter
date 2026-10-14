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
import static org.pbinitiative.zenbpmadapter.client.ContractFixtures.oneServiceTask;
import static org.pbinitiative.zenbpmadapter.client.ContractFixtures.uniqueBusinessKey;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pbinitiative.zenbpmadapter.client.api.CreateInstanceRequest;
import org.pbinitiative.zenbpmadapter.test.EngineUnderTest;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The lock a subscription asks for, as the pinned engine keeps it: a job stays with the stream
 * it was handed to until the lock lapses, is extended, or the stream closes. Each test subscribes
 * a job type of its own, so the jobs of one test never reach the streams of another.
 */
@ExtendWith(SuppressOutputExtension.class)
@Testcontainers(disabledWithoutDocker = true)
class ZenBpmJobLockIT {

    private static final String ADAPTER_ID = "lock";

    private static final Duration LOCK = Duration.ofSeconds(2);

    /**
     * How long a job may take from the start of its instance, or from the lapse of its lock, to a
     * stream: the engine looks for jobs to hand out about once a second.
     */
    private static final Duration DELIVERY = Duration.ofSeconds(5);

    @Container
    private static final EngineUnderTest ENGINE = new EngineUnderTest();

    private static ScheduledExecutorService timing;

    private static ZenBpmRestClient rest;

    @BeforeAll
    static void connect() {
        timing = Executors.newScheduledThreadPool(2);
        rest = new ZenBpmRestClient(ADAPTER_ID, configuration());
    }

    @AfterAll
    static void disconnect() throws InterruptedException {
        rest.close();
        timing.shutdownNow();
        timing.awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("A job whose 2 s lock lapses goes to another stream after its lock, and not before")
    void aLapsedLockHandsTheJobToAnotherStream() {
        final var jobType = deployed("lapse");
        final var firstInbox = new JobInbox();
        final var secondInbox = new JobInbox();
        try (var first = stream(firstInbox); var second = stream(secondInbox)) {
            first.subscribe("module-a", jobType, LOCK, 0);
            final var instance = start(jobType);
            final var held = firstInbox.next(instance, DELIVERY);
            // the first stream takes nothing more, so the engine has only the second to hand the job to
            first.unsubscribe("module-a", jobType);
            second.subscribe("module-a", jobType, LOCK, 0);

            final var handedOn = secondInbox.next(instance, LOCK.plus(DELIVERY));

            final var lapsed = Instant.ofEpochMilli(held
                    .received()
                    .job()
                    .getLockUntil());
            assertAll(
                    () -> assertEquals(held.key(), handedOn.key()),
                    () -> assertFalse(handedOn
                            .received()
                            .receivedAt()
                            .isBefore(lapsed),
                            "handed on at %s, before the lock lapsed at %s".formatted(handedOn
                                    .received()
                                    .receivedAt(), lapsed)),
                    () -> assertTrue(handedOn
                            .received()
                            .job()
                            .getDeliveryToken() > held
                                    .received()
                                    .job()
                                    .getDeliveryToken()));
        }
    }

    @Test
    @DisplayName("Extending the lock by 5 s keeps the job away from another stream, and a lapsed lock answers 409")
    void anExtendedLockKeepsTheJobAway() {
        final var jobType = deployed("extend");
        final var firstInbox = new JobInbox();
        final var secondInbox = new JobInbox();
        final var firstClientId = clientId();
        try (var first = stream(firstInbox, firstClientId); var second = stream(secondInbox)) {
            first.subscribe("module-a", jobType, LOCK, 0);
            final var instance = start(jobType);
            final var held = firstInbox.next(instance, DELIVERY);
            first.unsubscribe("module-a", jobType);

            final var extendedUntil = rest.extendJobLock(held.key(), firstClientId, Duration.ofSeconds(5));
            second.subscribe("module-a", jobType, LOCK, 0);
            final var handedOn = secondInbox.next(instance, Duration
                    .ofSeconds(5)
                    .plus(DELIVERY));

            final var lost = assertThrows(ZenBpmApiException.class, () -> rest.extendJobLock(held.key(), firstClientId,
                    Duration.ofSeconds(5)));
            assertAll(
                    () -> assertFalse(handedOn
                            .received()
                            .receivedAt()
                            .isBefore(extendedUntil),
                            "handed on at %s, before the extended lock lapsed at %s"
                                    .formatted(handedOn
                                            .received()
                                            .receivedAt(), extendedUntil)),
                    () -> assertEquals(409, lost.getStatus()),
                    () -> assertTrue(ZenBpmErrors.lockLost(lost)));
        }
    }

    @Test
    @DisplayName("Closing a stream hands its locked job to another stream at once, not after the lock")
    void closingAStreamHandsItsJobOnAtOnce() {
        final var jobType = deployed("close");
        final var firstInbox = new JobInbox();
        final var secondInbox = new JobInbox();
        final var longLock = Duration.ofMinutes(1);
        try (var second = stream(secondInbox)) {
            final Instant closedAt;
            final JobInbox.Arrival held;
            final long instance;
            try (var first = stream(firstInbox)) {
                first.subscribe("module-a", jobType, longLock, 0);
                instance = start(jobType);
                held = firstInbox.next(instance, DELIVERY);
                second.subscribe("module-a", jobType, longLock, 0);
                closedAt = Instant.now();
            }

            final var handedOn = secondInbox.next(instance, DELIVERY);

            assertAll(
                    () -> assertEquals(held.key(), handedOn.key()),
                    () -> assertTrue(Duration
                            .between(closedAt, handedOn
                                    .received()
                                    .receivedAt())
                            .compareTo(DELIVERY) <= 0));
        }
    }

    private static ZenBpmAdapterConfiguration configuration() {
        final var configuration = new ZenBpmAdapterConfiguration();
        configuration.setRestAddress(ENGINE.restAddress());
        configuration.setGrpcAddress(ENGINE.grpcAddress());
        return configuration;
    }

    private static String clientId() {
        return "lock-it-%s".formatted(UUID.randomUUID());
    }

    private static ZenBpmJobStream stream(final JobInbox inbox) {
        return stream(inbox, clientId());
    }

    private static ZenBpmJobStream stream(
        final JobInbox inbox,
        final String clientId
    ) {
        final var stream = new ZenBpmJobStream(ADAPTER_ID, configuration(), clientId, timing);
        stream.onJob(inbox);
        return stream;
    }

    /**
     * Deploys a process waiting in a job of a type no other test uses.
     *
     * @param name What the test is about
     * @return The job type
     */
    private static String deployed(final String name) {
        final var jobType = "lock-%s".formatted(name);
        rest.deployBpmn(oneServiceTask(jobType, jobType));
        return jobType;
    }

    private static long start(final String processId) {
        return rest
                .createInstance(CreateInstanceRequest
                        .latestVersionOf(processId)
                        .withBusinessKey(uniqueBusinessKey()))
                .key();
    }

}

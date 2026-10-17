/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.wiring;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pbinitiative.zenbpmadapter.client.FakeEngine;
import org.pbinitiative.zenbpmadapter.client.ReceivedJob;
import org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration;
import org.pbinitiative.zenbpmadapter.client.ZenBpmRestClient;
import org.pbinitiative.zenbpmadapter.client.grpc.Zenbpm.WaitingJob;

import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * How the lock of a running handler's job is kept: extended at once only where less than half of
 * the job-timeout is left, then whenever half of it is left; ended by a lost lock with a WARN, by a
 * job the engine no longer holds quietly, by any other refusal with a WARN, and by the end of the
 * handler; repeated within the lock where the engine could not be asked.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmLockRenewalTest {

    private static final String EXTEND = "/v1/jobs/42/extend-lock";

    private static final Duration JOB_TIMEOUT = Duration.ofMillis(600);

    private static final Duration PATIENCE = Duration.ofSeconds(5);

    private FakeEngine engine;

    private ZenBpmRestClient rest;

    private ScheduledExecutorService timing;

    private ZenBpmLockRenewal lockRenewal;

    @BeforeEach
    void startTheEngine() {
        engine = FakeEngine.started();
        final var configuration = new ZenBpmAdapterConfiguration();
        configuration.setRestAddress(engine.address());
        rest = new ZenBpmRestClient("orders", configuration);
        timing = Executors.newSingleThreadScheduledExecutor();
        lockRenewal = new ZenBpmLockRenewal("orders", rest, "shop-1", timing);
        // the first request of a JVM loads the HTTP client, which takes longer than the locks here
        try {
            rest.ready();
        } catch (final RuntimeException e) {
            // the engine knows no such request, which is all the warming needs
        }
    }

    @AfterEach
    void stopTheEngine() {
        timing.shutdownNow();
        rest.close();
        engine.close();
    }

    @Test
    @DisplayName("A handler which starts with more than half of the job-timeout left extends nothing at once, and extends once half of it is left, by the job-timeout and for the stream's client")
    void aFullLockIsExtendedOnceHalfOfItIsLeft() {
        engine.answers("POST", EXTEND, granting(JOB_TIMEOUT));

        try (var renewal = lockRenewal.start(job(JOB_TIMEOUT), JOB_TIMEOUT, "ship")) {
            pause(Duration.ofMillis(150));
            final var atFirst = extensions();
            awaitRequests(1);

            assertAll(
                    () -> assertEquals(List.of(), atFirst),
                    () -> assertEquals("{\"clientId\":\"shop-1\",\"lockDuration\":\"PT1S\"}", extensions()
                            .getFirst()
                            .body()),
                    () -> assertTrue(renewal
                            .lockEnd()
                            .isAfter(Instant.now())));
        }
    }

    @Test
    @DisplayName("A handler whose job waited until less than half of the job-timeout is left extends the lock at once, and then whenever half of it is left")
    void aLockWhichWaitedIsExtendedAtOnceAndThenRegularly() {
        engine.answers("POST", EXTEND, granting(JOB_TIMEOUT));
        final var started = System.nanoTime();

        try (var renewal = lockRenewal.start(job(Duration.ofMillis(200)), JOB_TIMEOUT, "ship")) {
            awaitRequests(1);
            final var firstAfter = Duration.ofNanos(System.nanoTime() - started);
            awaitRequests(3);

            assertTrue(firstAfter.compareTo(Duration.ofMillis(150)) < 0, firstAfter::toString);
        }
    }

    @Test
    @DisplayName("A lost lock ends the renewal with a WARN naming job, task and aggregate, and that a second delivery may run")
    void aLostLockEndsTheRenewal(final CapturedOutput output) {
        engine.answers("POST", EXTEND, 409, "{\"code\":\"CONFLICT\",\"message\":\"lock not held\"}");

        try (var renewal = lockRenewal.start(job(Duration.ZERO), JOB_TIMEOUT, "ship")) {
            renewal.describeAggregate("order-7");
            awaitLog(output, "is lost while its handler runs");
            pause(JOB_TIMEOUT);
        }

        final var log = output.getAllOfThisTest();
        assertAll(
                () -> assertEquals(1, extensions().size()),
                () -> assertTrue(log.contains(
                        "the lock of job 42 (task 'ship', workflow aggregate 'order-7') is lost while its handler runs"),
                        log),
                () -> assertTrue(log.contains("a second delivery may run beside this one"), log));
    }

    @Test
    @DisplayName("An extension the engine could not be asked for is sent again within the lock")
    void anUnansweredExtensionIsSentAgain() {
        final var calls = new AtomicInteger();
        engine.answers("POST", EXTEND, exchange -> {
            if (calls.incrementAndGet() == 1) {
                FakeEngine.respond(exchange, 502, "{\"code\":\"CLUSTER_ERROR\",\"message\":\"no leader\"}");
                return;
            }
            granting(JOB_TIMEOUT).handle(exchange);
        });

        try (var renewal = lockRenewal.start(job(Duration.ofMillis(200)), JOB_TIMEOUT, "ship")) {
            awaitRequests(2);
        }

        assertEquals(2, calls.get());
    }

    @Test
    @DisplayName("A lock the engine could not be asked to extend before it lapses ends the renewal with a WARN naming when it lapses")
    void aLockWhichCannotBeExtendedInTimeIsReported(final CapturedOutput output) {
        engine.answers("POST", EXTEND, 502, "{\"code\":\"CLUSTER_ERROR\",\"message\":\"no leader\"}");

        try (var renewal = lockRenewal.start(job(Duration.ofMillis(500)), JOB_TIMEOUT, "ship")) {
            awaitLog(output, "could not be renewed while its handler runs");
        }

        final var log = output.getAllOfThisTest();
        assertAll(
                () -> assertTrue(extensions().size() > 1, extensions()::toString),
                () -> assertTrue(log.contains("so it lapses at"), log));
    }

    @Test
    @DisplayName("A job the engine no longer holds ends the renewal without a WARN")
    void aGoneJobEndsTheRenewalQuietly(final CapturedOutput output) {
        engine.answers("POST", EXTEND, 404, "{\"code\":\"NOT_FOUND\",\"message\":\"no such job\"}");

        try (var renewal = lockRenewal.start(job(Duration.ZERO), JOB_TIMEOUT, "ship")) {
            awaitRequests(1);
            pause(JOB_TIMEOUT);
        }

        assertAll(
                () -> assertEquals(1, extensions().size()),
                () -> assertFalse(output
                        .getAllOfThisTest()
                        .contains("WARN"), output::getAllOfThisTest));
    }

    @Test
    @DisplayName("Any other refusal ends the renewal with a WARN")
    void anyOtherRefusalEndsTheRenewal(final CapturedOutput output) {
        engine.answers("POST", EXTEND, 400, "{\"code\":\"BAD_REQUEST\",\"message\":\"no\"}");

        try (var renewal = lockRenewal.start(job(Duration.ZERO), JOB_TIMEOUT, "ship")) {
            awaitLog(output, "could not be renewed while its handler runs");
        }

        assertEquals(1, extensions().size());
    }

    @Test
    @DisplayName("The renewal ends with the handler: nothing is extended afterwards, and the answer to an extension under way is not reported")
    void theRenewalEndsWithTheHandler(final CapturedOutput output) {
        engine.answers("POST", EXTEND, exchange -> {
            pause(Duration.ofMillis(300));
            FakeEngine.respond(exchange, 409, "{\"code\":\"CONFLICT\",\"message\":\"lock not held\"}");
        });

        try (var renewal = lockRenewal.start(job(Duration.ZERO), JOB_TIMEOUT, "ship")) {
            awaitRequests(1);
        }
        pause(JOB_TIMEOUT.plusMillis(300));

        assertAll(
                () -> assertEquals(1, extensions().size()),
                () -> assertFalse(output
                        .getAllOfThisTest()
                        .contains("is lost"), output::getAllOfThisTest));
    }

    @Test
    @DisplayName("An extension the engine grants after the handler ended schedules no further one")
    void anExtensionGrantedAfterTheEndSchedulesNoFurtherOne() {
        engine.answers("POST", EXTEND, exchange -> {
            pause(Duration.ofMillis(300));
            granting(JOB_TIMEOUT).handle(exchange);
        });

        try (var renewal = lockRenewal.start(job(Duration.ZERO), JOB_TIMEOUT, "ship")) {
            awaitRequests(1);
        }
        pause(JOB_TIMEOUT.plusMillis(300));

        assertEquals(1, extensions().size());
    }

    @Test
    @DisplayName("A lock the engine grants beyond the job-timeout counts as the job-timeout, one it grants shorter is extended at half of what it granted")
    void theGrantedLockIsCountedTheCarefulWay() {
        final var grants = new AtomicInteger();
        engine.answers("POST", EXTEND, exchange -> granting(grants.incrementAndGet() == 1 ? Duration.ofHours(1)
                : Duration.ofMillis(100)).handle(exchange));

        try (var renewal = lockRenewal.start(job(Duration.ZERO), JOB_TIMEOUT, "ship")) {
            awaitRequests(1);
            pause(Duration.ofMillis(50));
            final var counted = renewal.lockEnd();
            awaitRequests(4);

            assertTrue(counted.isBefore(Instant
                    .now()
                    .plus(JOB_TIMEOUT)), counted::toString);
        }
    }

    @Test
    @DisplayName("Without timing threads, which end only once no handler runs, nothing is renewed")
    void withoutTimingThreadsNothingIsRenewed() {
        timing.shutdownNow();

        try (var renewal = lockRenewal.start(job(Duration.ZERO), JOB_TIMEOUT, "ship")) {
            pause(Duration.ofMillis(200));
        }

        assertEquals(List.of(), extensions());
    }

    private static ReceivedJob job(final Duration lockLeft) {
        final var now = Instant.now();
        return new ReceivedJob(WaitingJob
                .newBuilder()
                .setKey(42)
                .setType("ship")
                .setLockUntil(now
                        .plus(lockLeft)
                        .toEpochMilli())
                .build(), now);
    }

    private static FakeEngine.Handler granting(final Duration lock) {
        return exchange -> FakeEngine.respond(exchange, 200, "{\"lockUntil\":\"%s\"}".formatted(Instant
                .now()
                .plus(lock)));
    }

    private List<FakeEngine.Request> extensions() {
        return engine
                .requests()
                .stream()
                .filter(request -> EXTEND.equals(request.path()))
                .toList();
    }

    private void awaitRequests(final int count) {
        await(() -> extensions().size() >= count, "%d request(s), received %s".formatted(count, extensions()));
    }

    private static void awaitLog(
        final CapturedOutput output,
        final String text
    ) {
        await(() -> output
                .getAllOfThisTest()
                .contains(text), "the log to say '%s'".formatted(text));
    }

    private static void await(
        final BooleanSupplier condition,
        final String what
    ) {
        final var deadline = Instant
                .now()
                .plus(PATIENCE);
        while (!condition.getAsBoolean()) {
            assertTrue(Instant
                    .now()
                    .isBefore(deadline), () -> "waited %s for %s".formatted(PATIENCE, what));
            pause(Duration.ofMillis(10));
        }
    }

    private static void pause(final Duration duration) {
        LockSupport.parkNanos(duration.toNanos());
    }

}

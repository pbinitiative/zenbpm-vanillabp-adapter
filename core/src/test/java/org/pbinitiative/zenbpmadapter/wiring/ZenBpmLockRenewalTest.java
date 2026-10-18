/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.wiring;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
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
import org.pbinitiative.zenbpmadapter.client.ZenBpmCommandRetry;
import org.pbinitiative.zenbpmadapter.client.ZenBpmErrors;
import org.pbinitiative.zenbpmadapter.client.ZenBpmRestClient;
import org.pbinitiative.zenbpmadapter.client.grpc.Zenbpm.WaitingJob;

import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * How the lock of a running handler's job is kept: extended at once only where less than half of
 * the job-timeout is left, then whenever half of it is left; ended by a lost lock with a WARN, by a
 * job the engine no longer holds quietly, by any other refusal with a WARN, and by the end of the
 * handler; repeated within the lock where the engine could not be asked. Every extension names its
 * delivery, and the lock is counted on the application's clock: an engine clock behind it is named
 * once and changes nothing. A parked job is extended once by its window, after an extension under
 * way came back, and a window the engine lowered is named once.
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
    @DisplayName("A handler which starts with more than half of the job-timeout left extends nothing at once, and extends once half of it is left, by the job-timeout, for the stream's client and for its own delivery")
    void aFullLockIsExtendedOnceHalfOfItIsLeft() {
        engine.answers("POST", EXTEND, granting(JOB_TIMEOUT));

        try (var renewal = lockRenewal.start(job(JOB_TIMEOUT), JOB_TIMEOUT, "ship")) {
            pause(Duration.ofMillis(150));
            final var atFirst = extensions();
            awaitRequests(1);

            assertAll(
                    () -> assertEquals(List.of(), atFirst),
                    () -> assertEquals("{\"clientId\":\"shop-1\",\"lockDuration\":\"PT1S\",\"deliveryToken\":7}",
                            extensions()
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
    @DisplayName("A lost lock ends the renewal with a WARN naming job, task as the application knows it and aggregate, and that a second delivery may run")
    void aLostLockEndsTheRenewal(final CapturedOutput output) {
        engine.answers("POST", EXTEND, 409, "{\"code\":\"CONFLICT\",\"message\":\"lock not held\"}");

        try (var renewal = lockRenewal.start(job(Duration.ZERO), JOB_TIMEOUT, "shop__Orders__ship")) {
            renewal.describe("ship", "order-7");
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
    @DisplayName("A lock the engine grants beyond the job-timeout counts as the job-timeout, counted from the sending of the extension")
    void theGrantedLockIsCountedFromTheSending() {
        engine.answers("POST", EXTEND, granting(Duration.ofHours(1)));

        try (var renewal = lockRenewal.start(job(Duration.ZERO), JOB_TIMEOUT, "ship")) {
            final var before = Instant.now();
            awaitRequests(1);
            pause(Duration.ofMillis(50));
            final var counted = renewal.lockEnd();

            assertAll(
                    () -> assertTrue(counted.isBefore(Instant
                            .now()
                            .plus(JOB_TIMEOUT)), counted::toString),
                    () -> assertTrue(counted.isAfter(before), counted::toString));
        }
    }

    @Test
    @DisplayName("An engine clock which runs behind neither shortens the lock nor makes the renewal extend more often, and a WARN names both causes and the key once")
    void anEngineClockBehindIsNamedOnceAndChangesNothing(final CapturedOutput output) {
        final var behind = Duration.ofHours(1);
        engine.answers("POST", EXTEND, exchange -> FakeEngine.respond(exchange, 200, "{\"lockUntil\":\"%s\"}"
                .formatted(Instant
                        .now()
                        .minus(behind)
                        .plus(JOB_TIMEOUT))));
        final var now = Instant.now();
        final var behindTheClock = new ReceivedJob(WaitingJob
                .newBuilder()
                .setKey(42)
                .setDeliveryToken(7)
                .setLockUntil(now
                        .minus(behind)
                        .plus(JOB_TIMEOUT)
                        .toEpochMilli())
                .build(), now);

        try (var renewal = lockRenewal.start(behindTheClock, JOB_TIMEOUT, "ship")) {
            final var counted = renewal.lockEnd();
            awaitRequests(1);
            pause(JOB_TIMEOUT);

            assertAll(
                    () -> assertEquals(now.plus(JOB_TIMEOUT), counted),
                    // one at half of the first lock, then one at every half of the job-timeout
                    () -> assertTrue(extensions().size() <= 4, () -> "%d extensions".formatted(extensions().size())),
                    () -> assertEquals(1, output
                            .getAllOfThisTest()
                            .split("the engine says the lock of job 42 lapses at", -1).length - 1),
                    () -> assertTrue(output
                            .getAllOfThisTest()
                            .contains("jobManager.maxLockDurationMs"), output::getAllOfThisTest),
                    () -> assertTrue(output
                            .getAllOfThisTest()
                            .contains("vanillabp.adapters.orders.job-timeout"), output::getAllOfThisTest));
        }
    }

    @Test
    @DisplayName("A lock the engine says lapses within the time a job travels raises no WARN")
    void aLockAsCountedRaisesNoWarn(final CapturedOutput output) {
        engine.answers("POST", EXTEND, granting(JOB_TIMEOUT.minusMillis(500)));

        try (var renewal = lockRenewal.start(job(Duration.ZERO), JOB_TIMEOUT, "ship")) {
            awaitRequests(2);
        }

        assertFalse(output
                .getAllOfThisTest()
                .contains("the engine says the lock"), output::getAllOfThisTest);
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

    @Test
    @DisplayName("A parked job is extended once by the window, for the stream's client and its own delivery, and the renewal of its handler extends it no more")
    void aParkedJobIsExtendedOnceByTheWindow() {
        engine.answers("POST", EXTEND, granting(Duration.ofHours(1)));

        try (var renewal = lockRenewal.start(job(JOB_TIMEOUT), JOB_TIMEOUT, "ship")) {
            renewal.park(Duration.ofHours(1), retryOf(renewal));
            pause(JOB_TIMEOUT.multipliedBy(2));
        }

        assertEquals(List.of("{\"clientId\":\"shop-1\",\"lockDuration\":\"PT3600S\",\"deliveryToken\":7}"),
                extensions()
                        .stream()
                        .map(FakeEngine.Request::body)
                        .toList());
    }

    @Test
    @DisplayName("A parked job's lock is counted to end one window after the extension was sent, on the application's clock, also where the engine answers an earlier end")
    void aParkedLockIsCountedOnTheApplicationsClock() {
        engine.answers("POST", EXTEND, granting(Duration.ofMinutes(10)));

        try (var renewal = lockRenewal.start(job(JOB_TIMEOUT), JOB_TIMEOUT, "ship")) {
            final var before = Instant.now();
            final var lockedUntil = renewal.park(Duration.ofHours(1), retryOf(renewal));
            final var after = Instant.now();

            assertAll(
                    () -> assertFalse(lockedUntil.isBefore(before.plus(Duration.ofHours(1))), lockedUntil::toString),
                    () -> assertFalse(lockedUntil.isAfter(after.plus(Duration.ofHours(1))), lockedUntil::toString));
        }
    }

    @Test
    @DisplayName("A job is parked only once the extension of its running handler under way came back, which would shorten the window if it arrived later")
    void aParkingWaitsForTheExtensionUnderWay() {
        final var events = new CopyOnWriteArrayList<String>();
        final var calls = new AtomicInteger();
        engine.answers("POST", EXTEND, exchange -> {
            final var call = calls.incrementAndGet();
            events.add("extension %d arrived".formatted(call));
            if (call == 1) {
                pause(Duration.ofMillis(300));
                events.add("extension 1 answered");
            }
            granting(JOB_TIMEOUT).handle(exchange);
        });

        try (var renewal = lockRenewal.start(job(Duration.ZERO), JOB_TIMEOUT, "ship")) {
            awaitRequests(1);
            renewal.park(Duration.ofHours(1), retryOf(renewal));
        }

        assertEquals(List.of("extension 1 arrived", "extension 1 answered", "extension 2 arrived"), events);
    }

    @Test
    @DisplayName("An extension whose time came just before the parking, but which runs only after it, sends nothing")
    void anExtensionDueBeforeTheParkingSendsNothingAfterIt() {
        final var due = new CopyOnWriteArrayList<Runnable>();
        final var heldBack = new ScheduledThreadPoolExecutor(1) {

            @Override
            public ScheduledFuture<?> schedule(
                final Runnable command,
                final long delay,
                final TimeUnit unit
            ) {
                due.add(command);
                return super.schedule(() -> {
                }, delay, unit);
            }

        };
        engine.answers("POST", EXTEND, granting(Duration.ofHours(1)));
        try {
            final var renewal = new ZenBpmLockRenewal("orders", rest, "shop-1", heldBack).start(job(Duration.ZERO),
                    JOB_TIMEOUT, "ship");
            renewal.park(Duration.ofHours(1), retryOf(renewal));
            due
                    .getFirst()
                    .run();
            pause(Duration.ofMillis(300));
        } finally {
            heldBack.shutdownNow();
        }

        assertEquals(List.of("PT3600S"), extensions()
                .stream()
                .map(request -> request
                        .body()
                        .replaceAll(".*\"lockDuration\":\"([^\"]+)\".*", "$1"))
                .toList());
    }

    @Test
    @DisplayName("A parking the engine lowered, as to its maximum lock duration, is named once per adapter id with the key and the engine's setting; one as counted raises no WARN")
    void aParkingTheEngineLoweredIsNamedOnce(final CapturedOutput output) {
        final var calls = new AtomicInteger();
        engine.answers("POST", EXTEND, exchange -> granting(calls.incrementAndGet() == 1 ? Duration.ofHours(1)
                : Duration.ofMinutes(10)).handle(exchange));

        for (var parking = 0; parking < 3; ++parking) {
            try (var renewal = lockRenewal.start(job(JOB_TIMEOUT), JOB_TIMEOUT, "ship")) {
                renewal.park(Duration.ofHours(1), retryOf(renewal));
            }
        }

        final var log = output.getAllOfThisTest();
        assertAll(
                () -> assertEquals(1, log
                        .lines()
                        .filter(line -> line.contains("whose task stays open for its asynchronous"))
                        .count(), log),
                () -> assertTrue(log.contains("before the async-task-lock-renewal of PT1H"), log),
                () -> assertTrue(log.contains("JOB_MANAGER_MAX_LOCK_DURATION_MS"), log),
                () -> assertTrue(log.contains("set vanillabp.adapters.orders.async-task-lock-renewal below"), log));
    }

    @Test
    @DisplayName("A parking the engine says lapses within the time an answer travels raises no WARN")
    void aParkingAsCountedRaisesNoWarn(final CapturedOutput output) {
        engine.answers("POST", EXTEND, granting(Duration
                .ofHours(1)
                .minusMillis(500)));

        try (var renewal = lockRenewal.start(job(JOB_TIMEOUT), JOB_TIMEOUT, "ship")) {
            renewal.park(Duration.ofHours(1), retryOf(renewal));
        }

        assertFalse(output
                .getAllOfThisTest()
                .contains("whose task stays open for its asynchronous"), output::getAllOfThisTest);
    }

    @Test
    @DisplayName("A parking the engine refuses passes the refusal on, so the handler decides")
    void aRefusedParkingIsPassedOn() {
        engine.answers("POST", EXTEND, 409, "{\"code\":\"CONFLICT\",\"message\":\"lock not held\"}");

        try (var renewal = lockRenewal.start(job(JOB_TIMEOUT), JOB_TIMEOUT, "ship")) {
            final var refusal = assertThrows(RuntimeException.class, () -> renewal.park(Duration.ofHours(1),
                    retryOf(renewal)));

            assertTrue(ZenBpmErrors.lockLost(refusal), refusal::toString);
        }
    }

    // a job received as long ago as its lock of the job-timeout has run, with clocks which agree
    private static ReceivedJob job(final Duration lockLeft) {
        final var now = Instant.now();
        return new ReceivedJob(WaitingJob
                .newBuilder()
                .setKey(42)
                .setType("ship")
                .setDeliveryToken(7)
                .setLockUntil(now
                        .plus(lockLeft)
                        .toEpochMilli())
                .build(), now.minus(JOB_TIMEOUT.minus(lockLeft)));
    }

    private static ZenBpmCommandRetry retryOf(final ZenBpmLockRenewal.Renewal renewal) {
        return new ZenBpmCommandRetry("orders", 42, "ship", renewal::lockEnd, () -> false);
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

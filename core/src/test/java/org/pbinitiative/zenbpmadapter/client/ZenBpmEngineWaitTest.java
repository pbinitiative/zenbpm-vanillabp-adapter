/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.client;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.pbinitiative.zenbpmadapter.client.ContractFixtures.eventually;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pbinitiative.zenbpmadapter.client.ZenBpmEngineWait.Pacing;

import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * How the start waits for an engine which is not ready yet, against an engine a test plays. That
 * it waits for the real one, started late, is proven by {@link ZenBpmEngineWaitIT}.
 */
@ExtendWith(SuppressOutputExtension.class)
// a wait which never ends would hold the build up instead of failing it. JUnit interrupts a test
// which runs out of time, and an interrupt ends the wait
@Timeout(30)
class ZenBpmEngineWaitTest {

    private static final String ADAPTER_ID = "orders";

    private static final String READY = "{\"status\":\"UP\",\"reasons\":[]}";

    private static final String NOT_READY = "{\"status\":\"DOWN\",\"reasons\":[\"no cluster leader elected\"]}";

    /**
     * Short pauses, so a test does not wait two seconds per attempt.
     */
    private static final Pacing QUICK = new Pacing(Duration.ofMillis(20), Duration.ofMillis(100));

    private static final Duration A_LONG_WAIT = Duration.ofMinutes(10);

    private FakeEngine engine;

    @BeforeEach
    void startTheEngine() {
        engine = FakeEngine.started();
    }

    @AfterEach
    void stopTheEngine() {
        engine.close();
    }

    @Test
    @DisplayName("The real wait asks every two seconds and says where it stands every ten")
    void theRealPacing() {
        assertEquals(new Pacing(Duration.ofSeconds(2), Duration.ofSeconds(10)), ZenBpmEngineWait.PACING);
    }

    @Test
    @DisplayName("The wait returns once the engine reports ready, and says where it stood meanwhile")
    void theWaitReturnsOnceTheEngineIsReady(final CapturedOutput output) {
        final var asked = new AtomicInteger();
        engine.answers("GET", "/system/health/ready", exchange -> FakeEngine.respond(exchange, asked
                .incrementAndGet() < 10 ? 503 : 200, asked.get() < 10 ? NOT_READY : READY));

        try (var factory = factory(A_LONG_WAIT)) {
            ZenBpmEngineWait.awaitReady(factory, QUICK);

            final var printed = output.getAllOfThisTest();
            assertAll(
                    () -> assertEquals(10, asked.get()),
                    () -> assertTrue(printed.contains(
                            "INFO  o.p.z.client.ZenBpmEngineWait - ZenBPM[orders]: waiting up to PT10M for the engine at %s to report ready"
                                    .formatted(engine.address())),
                            printed),
                    () -> assertTrue(printed.contains(
                            "ZenBPM[orders]: still waiting for the engine at %s to report ready".formatted(engine
                                    .address())),
                            printed),
                    () -> assertTrue(printed.contains("last answer: not ready (no cluster leader elected)"), printed),
                    () -> assertTrue(printed.contains("ZenBPM[orders]: the engine at %s reported ready after"
                            .formatted(engine.address())), printed));
        }
    }

    @Test
    @DisplayName("The wait says where it stands every log interval, not on every attempt")
    void theWaitSaysWhereItStandsEveryLogInterval(final CapturedOutput output) {
        engine.answers("GET", "/system/health/ready", 503, NOT_READY);

        try (var factory = factory(Duration.ofMillis(1_100))) {
            assertThrows(IllegalStateException.class, () -> ZenBpmEngineWait.awaitReady(factory, new Pacing(Duration
                    .ofMillis(20), Duration.ofMillis(400))));

            final var progressLines = Pattern
                    .compile("still waiting for the engine")
                    .matcher(output.getAllOfThisTest())
                    .results()
                    .count();
            final var attempts = engine
                    .requests()
                    .size();
            assertAll(
                    () -> assertTrue(attempts > 10, "attempts: %d".formatted(attempts)),
                    () -> assertTrue((progressLines >= 1) && (progressLines <= 3), "progress lines: %d"
                            .formatted(progressLines)));
        }
    }

    @Test
    @DisplayName("A wait which runs out names the address, the time waited, the last answer and the key to raise")
    void aWaitWhichRunsOutSaysEverything() {
        engine.answers("GET", "/system/health/ready", 503, NOT_READY);

        try (var factory = factory(Duration.ofMillis(300))) {
            final var failure = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertThrows(
                    IllegalStateException.class, () -> ZenBpmEngineWait.awaitReady(factory, QUICK)));

            assertTrue(Pattern
                    .compile(Pattern.quote(
                            "ZenBPM adapter 'orders': the engine at %s did not report ready within 'vanillabp.adapters.orders.startup-wait: PT0.3S' (waited PT0."
                                    .formatted(engine.address()))
                            + "\\d+S"
                            + Pattern.quote(
                                    "). The last answer was: not ready (no cluster leader elected). Start the engine, correct 'vanillabp.adapters.orders.rest-address', or raise 'vanillabp.adapters.orders.startup-wait'."))
                    .matcher(failure.getMessage())
                    .matches(), failure.getMessage());
        }
    }

    @Test
    @DisplayName("An engine out of reach is named with what the HTTP client reported")
    void anEngineOutOfReachIsNamedWithTheTransportFailure() throws IOException {
        try (var factory = factory(closedPortAddress(), Duration.ofMillis(300))) {
            final var failure = assertThrows(IllegalStateException.class, () -> ZenBpmEngineWait.awaitReady(factory,
                    QUICK));

            assertTrue(failure
                    .getMessage()
                    .contains(
                            "The last answer was: GET /system/health/ready got no answer (java.net.ConnectException"),
                    failure.getMessage());
        }
    }

    @Test
    @DisplayName("A status another attempt may change is waited out like a missing readiness")
    void aRepeatableStatusIsWaitedOut() {
        final var asked = new AtomicInteger();
        engine.answers("GET", "/system/health/ready", exchange -> FakeEngine.respond(exchange, asked
                .incrementAndGet() == 1 ? 500 : 200, asked.get() == 1 ? "{\"code\":\"X\",\"message\":\"boom\"}"
                        : READY));

        try (var factory = factory(A_LONG_WAIT)) {
            ZenBpmEngineWait.awaitReady(factory, QUICK);

            assertEquals(2, asked.get());
        }
    }

    @Test
    @DisplayName("An answer no repetition changes ends the wait at once, naming the key it did not wait out")
    void aPermanentAnswerEndsTheWaitAtOnce() {
        engine.answers("GET", "/system/health/ready", 400, "{\"code\":\"BAD\",\"message\":\"no such thing\"}");

        try (var factory = factory(A_LONG_WAIT)) {
            final var failure = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertThrows(
                    IllegalStateException.class, () -> ZenBpmEngineWait.awaitReady(factory, QUICK)));

            assertAll(
                    () -> assertEquals(1, engine
                            .requests()
                            .size()),
                    () -> assertEquals(
                            "ZenBPM adapter 'orders': the engine at %s refused the readiness request, and waiting longer ('vanillabp.adapters.orders.startup-wait: PT10M') cannot change an answer like that. It answered: GET /system/health/ready was answered with status 400, BAD: no such thing. Check 'vanillabp.adapters.orders.rest-address': it has to name the engine itself."
                                    .formatted(engine.address()),
                            failure.getMessage()));
        }
    }

    @Test
    @DisplayName("A startup wait of zero asks once")
    void aZeroWaitAsksOnce() {
        engine.answers("GET", "/system/health/ready", 503, NOT_READY);

        try (var factory = factory(Duration.ZERO)) {
            assertThrows(IllegalStateException.class, () -> ZenBpmEngineWait.awaitReady(factory, QUICK));

            assertEquals(1, engine
                    .requests()
                    .size());
        }
        engine.answers("GET", "/system/health/ready", 200, READY);
        try (var factory = factory(Duration.ZERO)) {
            ZenBpmEngineWait.awaitReady(factory, QUICK);
        }
    }

    @Test
    @DisplayName("An engine which is not ready and gives no reason is named as not ready")
    void anEngineWithoutAReasonIsNotReady() {
        engine.answers("GET", "/system/health/ready", 503, "{\"status\":\"DOWN\",\"reasons\":[]}");

        try (var factory = factory(Duration.ZERO)) {
            final var failure = assertThrows(IllegalStateException.class, () -> ZenBpmEngineWait.awaitReady(factory,
                    QUICK));

            assertTrue(failure
                    .getMessage()
                    .contains("The last answer was: not ready. Start the engine"), failure.getMessage());
        }
    }

    @Test
    @DisplayName("A second workflow module of the adapter id does not ask again once the engine was ready")
    void aSecondModuleDoesNotAskAgain() {
        engine.answers("GET", "/system/health/ready", 200, READY);

        try (var factory = factory(A_LONG_WAIT)) {
            ZenBpmEngineWait.awaitReady(factory, QUICK);
            ZenBpmEngineWait.awaitReady(factory);

            assertEquals(1, engine
                    .requests()
                    .size());
        }
    }

    @Test
    @DisplayName("After a failed wait a later workflow module asks once, and fails at once where the engine is still not ready")
    void aLaterModuleAsksOnceAfterAFailedWait() {
        engine.answers("GET", "/system/health/ready", 503, NOT_READY);

        try (var factory = factory(Duration.ofMillis(100))) {
            final var first = assertThrows(IllegalStateException.class, () -> ZenBpmEngineWait.awaitReady(factory,
                    QUICK));
            final var asked = engine
                    .requests()
                    .size();

            final var second = assertThrows(IllegalStateException.class, () -> ZenBpmEngineWait.awaitReady(factory,
                    QUICK));
            final var third = assertThrows(IllegalStateException.class, () -> ZenBpmEngineWait.awaitReady(factory,
                    QUICK));

            assertAll(
                    () -> assertEquals(asked + 2, engine
                            .requests()
                            .size()),
                    () -> assertEquals("%s Asked once more, it answered: not ready (no cluster leader elected)."
                            .formatted(first.getMessage()), second.getMessage()),
                    () -> assertSame(first, second.getCause()),
                    () -> assertEquals(second.getMessage(), third.getMessage(), "the first failure is kept"),
                    () -> assertSame(first, third.getCause()));
        }
    }

    @Test
    @DisplayName("An engine which became ready after a failed wait is taken by the next module, and asked no more")
    void anEngineReadyAfterAFailedWaitIsTaken(final CapturedOutput output) {
        engine.answers("GET", "/system/health/ready", 503, NOT_READY);

        try (var factory = factory(Duration.ofMillis(100))) {
            assertThrows(IllegalStateException.class, () -> ZenBpmEngineWait.awaitReady(factory, QUICK));
            final var asked = engine
                    .requests()
                    .size();
            engine.answers("GET", "/system/health/ready", 200, READY);

            ZenBpmEngineWait.awaitReady(factory, QUICK);
            ZenBpmEngineWait.awaitReady(factory, QUICK);

            assertAll(
                    () -> assertEquals(asked + 1, engine
                            .requests()
                            .size()),
                    () -> assertTrue(output
                            .getAllOfThisTest()
                            .contains(
                                    "ZenBPM[orders]: the engine at %s reported ready when it was asked once more after the wait had failed"
                                            .formatted(engine.address())),
                            output.getAllOfThisTest()));
        }
    }

    @Test
    @DisplayName("An answer no repetition changes is final: a later module asks nothing and fails with it")
    void aRefusalIsFinal() {
        engine.answers("GET", "/system/health/ready", 400, "{\"code\":\"BAD\",\"message\":\"no such thing\"}");

        try (var factory = factory(A_LONG_WAIT)) {
            final var first = assertThrows(IllegalStateException.class, () -> ZenBpmEngineWait.awaitReady(factory,
                    QUICK));
            engine.answers("GET", "/system/health/ready", 200, READY);

            final var second = assertThrows(IllegalStateException.class, () -> ZenBpmEngineWait.awaitReady(factory,
                    QUICK));

            assertAll(
                    () -> assertEquals(1, engine
                            .requests()
                            .size()),
                    () -> assertEquals(first.getMessage(), second.getMessage()),
                    () -> assertSame(first, second.getCause()));
        }
    }

    @Test
    @DisplayName("A refusal met when a later module asks once more is final as well")
    void aRefusalAskedOnceMoreIsFinal() {
        engine.answers("GET", "/system/health/ready", 503, NOT_READY);

        try (var factory = factory(Duration.ofMillis(100))) {
            assertThrows(IllegalStateException.class, () -> ZenBpmEngineWait.awaitReady(factory, QUICK));
            engine.answers("GET", "/system/health/ready", 400, "{\"code\":\"BAD\",\"message\":\"no such thing\"}");
            final var refused = assertThrows(IllegalStateException.class, () -> ZenBpmEngineWait.awaitReady(factory,
                    QUICK));
            final var asked = engine
                    .requests()
                    .size();

            final var later = assertThrows(IllegalStateException.class, () -> ZenBpmEngineWait.awaitReady(factory,
                    QUICK));

            assertAll(
                    () -> assertEquals(asked, engine
                            .requests()
                            .size()),
                    () -> assertTrue(refused
                            .getMessage()
                            .contains("refused the readiness request"), refused.getMessage()),
                    () -> assertSame(refused, later.getCause()));
        }
    }

    @Test
    @DisplayName("A second workflow module which arrives while the first one waits waits for its answer")
    void aSecondModuleWaitsForTheFirstOnesAnswer() throws Exception {
        // the engine holds every answer until the test lets it go, so the first module is inside
        // its first attempt for as long as the test likes
        final var answer = new CountDownLatch(1);
        engine.answers("GET", "/system/health/ready", exchange -> {
            try {
                answer.await(10, TimeUnit.SECONDS);
            } catch (final InterruptedException e) {
                Thread
                        .currentThread()
                        .interrupt();
            }
            FakeEngine.respond(exchange, 200, READY);
        });

        try (var factory = factory(A_LONG_WAIT)) {
            final var first = waiting(factory);
            eventually(() -> engine
                    .requests()
                    .isEmpty() ? Optional.empty() : Optional.of(true));
            final var second = waiting(factory);
            try {
                eventually(() -> factory
                        .engineWait().lock
                        .hasQueuedThread(second.thread()) ? Optional.of(true) : Optional.empty());
                assertEquals(1, engine
                        .requests()
                        .size(), "the second module asked the engine itself");
            } finally {
                answer.countDown();
            }

            assertAll(
                    () -> assertEquals(Optional.empty(), first.outcome(), "first"),
                    () -> assertEquals(Optional.empty(), second.outcome(), "second"),
                    () -> assertEquals(1, engine
                            .requests()
                            .size()));
        }
    }

    @Test
    @DisplayName("A module which waits for another one's wait can be interrupted, and the other one's wait goes on")
    void aModuleWaitingForAnotherOnesWaitCanBeInterrupted() throws Exception {
        final var answer = new CountDownLatch(1);
        engine.answers("GET", "/system/health/ready", exchange -> {
            try {
                answer.await(10, TimeUnit.SECONDS);
            } catch (final InterruptedException e) {
                Thread
                        .currentThread()
                        .interrupt();
            }
            FakeEngine.respond(exchange, 200, READY);
        });

        try (var factory = factory(A_LONG_WAIT)) {
            final var first = waiting(factory);
            eventually(() -> engine
                    .requests()
                    .isEmpty() ? Optional.empty() : Optional.of(true));
            final var second = waiting(factory);
            final Optional<Throwable> interrupted;
            try {
                eventually(() -> factory
                        .engineWait().lock
                        .hasQueuedThread(second.thread()) ? Optional.of(true) : Optional.empty());
                second
                        .thread()
                        .interrupt();
                interrupted = second.outcome();
                assertTrue(first
                        .thread()
                        .isAlive(), "the first module's wait ended with the second one's interrupt");
            } finally {
                answer.countDown();
            }

            assertAll(
                    () -> assertEquals(
                            "ZenBPM adapter 'orders' was interrupted while it waited for the engine at %s to report ready."
                                    .formatted(engine.address()),
                            interrupted
                                    .orElseThrow()
                                    .getMessage()),
                    () -> assertTrue(second.interruptedAfterwards(), "interrupt flag lost"),
                    () -> assertEquals(Optional.empty(), first.outcome()));
        }
    }

    @Test
    @DisplayName("The last attempt is made when the wait runs out, not a whole pause later")
    void theLastPauseEndsWithTheWait() {
        engine.answers("GET", "/system/health/ready", 503, NOT_READY);

        try (var factory = factory(Duration.ofMillis(300))) {
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertThrows(IllegalStateException.class,
                    () -> ZenBpmEngineWait.awaitReady(factory, new Pacing(Duration.ofMinutes(1), Duration
                            .ofMinutes(1)))));

            assertEquals(2, engine
                    .requests()
                    .size());
        }
    }

    @Test
    @DisplayName("An adapter id without clients fails with the message of the validation and asks nothing")
    void anAdapterIdWithoutClientsFailsWithItsMessage() {
        final var factory = ZenBpmClientFactory.unconfigured(ADAPTER_ID, new ZenBpmAdapterConfiguration(),
                "configure me");

        final var failure = assertThrows(IllegalStateException.class, () -> ZenBpmEngineWait.awaitReady(factory));

        assertEquals("configure me", failure.getMessage());
    }

    @Test
    @DisplayName("An interrupted wait ends, and the thread stays interrupted")
    void anInterruptedWaitEnds() throws Exception {
        engine.answers("GET", "/system/health/ready", 503, NOT_READY);

        try (var factory = factory(A_LONG_WAIT)) {
            final var outcome = new CompletableFuture<Throwable>();
            final var waiting = Thread
                    .ofPlatform()
                    .start(() -> {
                        try {
                            ZenBpmEngineWait.awaitReady(factory, new Pacing(Duration.ofSeconds(30), Duration
                                    .ofSeconds(30)));
                            outcome.complete(null);
                        } catch (final IllegalStateException e) {
                            outcome.complete(Thread
                                    .currentThread()
                                    .isInterrupted() ? e : new AssertionError("interrupt flag lost", e));
                        }
                    });
            eventually(() -> engine
                    .requests()
                    .isEmpty() ? Optional.empty() : Optional.of(true));
            waiting.interrupt();

            final var failure = outcome.get(5, TimeUnit.SECONDS);
            assertEquals(
                    "ZenBPM adapter 'orders' was interrupted while it waited for the engine at %s to report ready."
                            .formatted(engine.address()),
                    failure.getMessage());
        }
    }

    @Test
    @DisplayName("An interrupted wait is no answer of the engine: the next module waits on its own")
    void anInterruptedWaitIsNotRemembered() {
        engine.answers("GET", "/system/health/ready", 503, NOT_READY);

        try (var factory = factory(A_LONG_WAIT)) {
            final var first = waiting(factory, new Pacing(Duration.ofSeconds(30), Duration.ofSeconds(30)));
            eventually(() -> engine
                    .requests()
                    .isEmpty() ? Optional.empty() : Optional.of(true));
            first
                    .thread()
                    .interrupt();
            assertTrue(first
                    .outcome()
                    .isPresent());
            // ready only from the third request on: a module which merely asked once more would
            // fail, one which waits on its own does not
            final var asked = new AtomicInteger();
            engine.answers("GET", "/system/health/ready", exchange -> FakeEngine.respond(exchange, asked
                    .incrementAndGet() < 3 ? 503 : 200, asked.get() < 3 ? NOT_READY : READY));

            ZenBpmEngineWait.awaitReady(factory, QUICK);

            assertEquals(3, asked.get());
        }
    }

    @Test
    @DisplayName("Closing the factory ends a wait with its next attempt, and every later wait the same way")
    void closingEndsTheWait() {
        engine.answers("GET", "/system/health/ready", 503, NOT_READY);
        final var factory = factory(A_LONG_WAIT);
        final var first = waiting(factory);
        eventually(() -> engine
                .requests()
                .isEmpty() ? Optional.empty() : Optional.of(true));

        factory.close();

        final var failure = first
                .outcome()
                .orElseThrow();
        final var later = assertThrows(IllegalStateException.class, () -> ZenBpmEngineWait.awaitReady(factory, QUICK));
        assertAll(
                () -> assertEquals(
                        "ZenBPM adapter 'orders': its clients were closed while it waited for the engine at %s to report ready, because the application stops."
                                .formatted(engine.address()),
                        failure.getMessage()),
                () -> assertEquals(failure.getMessage(), later.getMessage()));
    }

    @Test
    @DisplayName("A ready answer which arrives after the wait ran out is taken")
    void aLateReadyAnswerIsTaken() {
        engine.answers("GET", "/system/health/ready", exchange -> {
            try {
                Thread.sleep(400);
            } catch (final InterruptedException e) {
                Thread
                        .currentThread()
                        .interrupt();
            }
            FakeEngine.respond(exchange, 200, READY);
        });

        try (var factory = factory(Duration.ofMillis(100))) {
            ZenBpmEngineWait.awaitReady(factory, QUICK);

            assertEquals(1, engine
                    .requests()
                    .size());
        }
    }

    @Test
    @DisplayName("A failing wait takes up to startup-wait plus request-timeout: the attempt under way when it runs out ends at its own deadline")
    void aFailingWaitEndsWithinStartupWaitAndRequestTimeout() throws IOException {
        try (var blackHole = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            final var held = new CopyOnWriteArrayList<Socket>();
            Thread
                    .ofVirtual()
                    .start(() -> {
                        while (!blackHole.isClosed()) {
                            try {
                                held.add(blackHole.accept());
                            } catch (final IOException e) {
                                // closed
                            }
                        }
                    });
            final var startupWait = Duration.ofMillis(300);
            final var requestTimeout = Duration.ofSeconds(1);

            try (var factory = factory("http://127.0.0.1:%d".formatted(blackHole.getLocalPort()), startupWait,
                    requestTimeout)) {
                final var startedAt = System.nanoTime();
                final var failure = assertTimeoutPreemptively(startupWait
                        .plus(requestTimeout)
                        .plusSeconds(1),
                        () -> assertThrows(IllegalStateException.class, () -> ZenBpmEngineWait
                                .awaitReady(factory, QUICK)));
                final var took = Duration.ofNanos(System.nanoTime() - startedAt);

                assertAll(
                        () -> assertTrue(took.compareTo(requestTimeout) >= 0, took.toString()),
                        () -> assertTrue(failure
                                .getMessage()
                                .contains("The last answer was: GET /system/health/ready got no answer within PT1S"),
                                failure.getMessage()));
            } finally {
                for (final var socket : held) {
                    socket.close();
                }
            }
        }
    }

    private ZenBpmClientFactory factory(final Duration startupWait) {
        return factory(engine.address(), startupWait);
    }

    private static ZenBpmClientFactory factory(
        final String restAddress,
        final Duration startupWait
    ) {
        return factory(restAddress, startupWait, Duration.ofSeconds(2));
    }

    private static ZenBpmClientFactory factory(
        final String restAddress,
        final Duration startupWait,
        final Duration requestTimeout
    ) {
        final var configuration = new ZenBpmAdapterConfiguration();
        configuration.setRestAddress(restAddress);
        configuration.setStartupWait(startupWait);
        configuration.setRequestTimeout(requestTimeout);
        return new ZenBpmClientFactory(ADAPTER_ID, configuration, () -> "shop");
    }

    private static Waiting waiting(final ZenBpmClientFactory factory) {
        return waiting(factory, QUICK);
    }

    /**
     * A wait on a thread of its own, which a test can find queued at the lock and interrupt.
     */
    private static Waiting waiting(
        final ZenBpmClientFactory factory,
        final Pacing pacing
    ) {
        final var outcome = new CompletableFuture<Optional<Throwable>>();
        final var interruptedAfterwards = new AtomicBoolean();
        final var thread = Thread
                .ofPlatform()
                .start(() -> {
                    try {
                        ZenBpmEngineWait.awaitReady(factory, pacing);
                        outcome.complete(Optional.empty());
                    } catch (final RuntimeException e) {
                        interruptedAfterwards.set(Thread
                                .currentThread()
                                .isInterrupted());
                        outcome.complete(Optional.of(e));
                    }
                });
        return new Waiting(thread, outcome, interruptedAfterwards);
    }

    /**
     * A wait started by {@link #waiting(ZenBpmClientFactory, Pacing)}.
     *
     * @param thread The thread it runs on
     * @param result What it ended with: empty where the engine was ready
     * @param interrupted Whether the thread was still interrupted when the wait failed
     */
    private record Waiting(
        Thread thread,
        CompletableFuture<Optional<Throwable>> result,
        AtomicBoolean interrupted
    ) {

        Optional<Throwable> outcome() {
            try {
                return result.get(5, TimeUnit.SECONDS);
            } catch (final Exception e) {
                throw new AssertionError("the wait did not end", e);
            }
        }

        boolean interruptedAfterwards() {
            outcome();
            return interrupted.get();
        }

    }

    private static String closedPortAddress() throws IOException {
        try (var socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return "http://127.0.0.1:%d".formatted(socket.getLocalPort());
        }
    }

}

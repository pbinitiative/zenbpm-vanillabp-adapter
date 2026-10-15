/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.client;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.pbinitiative.zenbpmadapter.client.ContractFixtures.eventually;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
                            "INFO  o.p.z.client.ZenBpmEngineWait - ZenBPM[orders]: waiting up to PT10M for the engine at %s/v1 to report ready"
                                    .formatted(engine.address())),
                            printed),
                    () -> assertTrue(printed.contains(
                            "ZenBPM[orders]: still waiting for the engine at %s/v1 to report ready".formatted(engine
                                    .address())),
                            printed),
                    () -> assertTrue(printed.contains("last answer: not ready (no cluster leader elected)"), printed),
                    () -> assertTrue(printed.contains("ZenBPM[orders]: the engine at %s/v1 reported ready after"
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
                            "ZenBPM adapter 'orders': the engine at %s/v1 did not report ready within 'vanillabp.adapters.orders.startup-wait: PT0.3S' (waited PT0."
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
                            "The last answer was: ZenBPM adapter 'orders': GET /system/health/ready got no answer (java.net.ConnectException"),
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
                            "ZenBPM adapter 'orders': the engine at %s/v1 refused the readiness request, and waiting longer ('vanillabp.adapters.orders.startup-wait: PT10M') cannot change an answer like that. It answered: ZenBPM adapter 'orders': GET /system/health/ready was answered with status 400, BAD: no such thing. Check 'vanillabp.adapters.orders.rest-address': it has to name the engine itself."
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
    @DisplayName("A second workflow module of the adapter id fails at once where the wait failed")
    void aSecondModuleFailsAtOnceAfterAFailedWait() {
        engine.answers("GET", "/system/health/ready", 503, NOT_READY);

        try (var factory = factory(Duration.ofMillis(100))) {
            final var first = assertThrows(IllegalStateException.class, () -> ZenBpmEngineWait.awaitReady(factory,
                    QUICK));
            final var asked = engine
                    .requests()
                    .size();
            engine.answers("GET", "/system/health/ready", 200, READY);

            final var second = assertThrows(IllegalStateException.class, () -> ZenBpmEngineWait.awaitReady(factory,
                    QUICK));

            assertAll(
                    () -> assertEquals(asked, engine
                            .requests()
                            .size()),
                    () -> assertEquals(first.getMessage(), second.getMessage()),
                    () -> assertSame(first, second.getCause()));
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
            final var first = CompletableFuture.runAsync(() -> ZenBpmEngineWait.awaitReady(factory, QUICK));
            eventually(() -> engine
                    .requests()
                    .isEmpty() ? Optional.empty() : Optional.of(true));
            final var second = CompletableFuture.runAsync(() -> ZenBpmEngineWait.awaitReady(factory, QUICK));
            Thread.sleep(300);
            assertAll(
                    () -> assertEquals(1, engine
                            .requests()
                            .size(), "the second module asked the engine itself"),
                    () -> assertFalse(second.isDone(), "the second module returned while the first one still waited"));

            answer.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            assertEquals(1, engine
                    .requests()
                    .size());
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
                    "ZenBPM adapter 'orders' was interrupted while it waited for the engine at %s/v1 to report ready."
                            .formatted(engine.address()),
                    failure.getMessage());
        }
    }

    private ZenBpmClientFactory factory(final Duration startupWait) {
        return factory(engine.address(), startupWait);
    }

    private static ZenBpmClientFactory factory(
        final String restAddress,
        final Duration startupWait
    ) {
        final var configuration = new ZenBpmAdapterConfiguration();
        configuration.setRestAddress(restAddress);
        configuration.setStartupWait(startupWait);
        configuration.setRequestTimeout(Duration.ofSeconds(2));
        return new ZenBpmClientFactory(ADAPTER_ID, configuration, () -> "shop");
    }

    private static String closedPortAddress() throws IOException {
        try (var socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return "http://127.0.0.1:%d".formatted(socket.getLocalPort());
        }
    }

}

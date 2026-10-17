/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.client;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ConnectException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What the repetition around a handler's commands repeats, and what bounds it: a call the engine
 * could not be asked for and what the caller names are repeated, five attempts at most, never past
 * the job's lock and not at all while the module shuts down; a refusal and an interrupted pause end
 * it at once, and a read is asked again while its answer is not there yet.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmCommandRetryTest {

    private static final Duration LONG_LOCK = Duration.ofMinutes(5);

    @AfterEach
    void forgetTheInterrupt() {
        // a test which interrupts its own thread must not hand the flag to the next one
        Thread.interrupted();
    }

    @Test
    @DisplayName("A command the engine could not be asked for is sent again until it goes through")
    void aCommandTheEngineCouldNotBeAskedForIsSentAgain() {
        final var calls = new AtomicInteger();

        retry(LONG_LOCK, false).send("completion", () -> {
            if (calls.incrementAndGet() < 3) {
                throw unavailable();
            }
        });

        assertEquals(3, calls.get());
    }

    @Test
    @DisplayName("A command is sent five times at most, after pauses of 50, 100, 200 and 400 ms, and the last failure is thrown")
    void aCommandIsSentFiveTimesAtMost() {
        final var calls = new AtomicInteger();
        final var last = unavailable();
        final var started = System.nanoTime();

        final var thrown = assertThrows(ZenBpmUnavailableException.class, () -> retry(LONG_LOCK, false).send(
                "completion", () -> {
                    if (calls.incrementAndGet() == ZenBpmCommandRetry.MAX_ATTEMPTS) {
                        throw last;
                    }
                    throw unavailable();
                }));

        final var waited = Duration.ofNanos(System.nanoTime() - started);
        assertAll(
                () -> assertEquals(5, calls.get()),
                () -> assertSame(last, thrown),
                () -> assertTrue(waited.compareTo(Duration.ofMillis(750)) >= 0, waited::toString));
    }

    @Test
    @DisplayName("A refusal the engine repeats however often it is asked comes back at once")
    void aRefusalIsNotRepeated() {
        final var calls = new AtomicInteger();

        assertThrows(ZenBpmApiException.class, () -> retry(LONG_LOCK, false).send("completion", () -> {
            calls.incrementAndGet();
            throw refused(400);
        }));

        assertEquals(1, calls.get());
    }

    @Test
    @DisplayName("A failure the caller names as worth another attempt is repeated as well")
    void aFailureTheCallerNamesIsRepeated() {
        final var calls = new AtomicInteger();

        retry(LONG_LOCK, false).send("update of the variables",
                failure -> failure instanceof ZenBpmApiException refusal && refusal.getStatus() == 404, () -> {
                    if (calls.incrementAndGet() < 2) {
                        throw refused(404);
                    }
                });

        assertEquals(2, calls.get());
    }

    @Test
    @DisplayName("While the workflow module shuts down nothing is repeated")
    void nothingIsRepeatedWhileTheModuleShutsDown() {
        final var calls = new AtomicInteger();

        assertThrows(ZenBpmUnavailableException.class, () -> retry(LONG_LOCK, true).send("completion", () -> {
            calls.incrementAndGet();
            throw unavailable();
        }));

        assertEquals(1, calls.get());
    }

    @Test
    @DisplayName("No attempt is made whose pause would end after the job's lock")
    void noAttemptOutlastsTheLock() {
        final var calls = new AtomicInteger();

        assertThrows(ZenBpmUnavailableException.class, () -> retry(Duration.ofMillis(20), false).send("completion",
                () -> {
                    calls.incrementAndGet();
                    throw unavailable();
                }));

        assertEquals(1, calls.get());
    }

    @Test
    @DisplayName("A read is asked again while its answer is not there yet, and the last answer is returned once no attempt is left")
    void aReadIsAskedAgainWhileItsAnswerIsNotThereYet() {
        final var calls = new AtomicInteger();
        final var never = new AtomicInteger();

        final var found = retry(LONG_LOCK, false).read("instance", () -> calls.incrementAndGet() < 3 ? Optional
                .empty() : Optional.of("instance"), Optional::isEmpty);
        final var notFound = retry(LONG_LOCK, false).read("instance", () -> {
            never.incrementAndGet();
            return Optional.empty();
        }, Optional::isEmpty);

        assertAll(
                () -> assertEquals(Optional.of("instance"), found),
                () -> assertEquals(3, calls.get()),
                () -> assertEquals(Optional.empty(), notFound),
                () -> assertEquals(5, never.get()));
    }

    @Test
    @DisplayName("An interrupted pause ends the repetition and keeps the thread interrupted")
    void anInterruptedPauseEndsTheRepetition() {
        final var calls = new AtomicInteger();
        Thread
                .currentThread()
                .interrupt();

        assertThrows(ZenBpmUnavailableException.class, () -> retry(LONG_LOCK, false).send("completion", () -> {
            calls.incrementAndGet();
            throw unavailable();
        }));

        assertAll(
                () -> assertEquals(1, calls.get()),
                () -> assertTrue(Thread
                        .currentThread()
                        .isInterrupted()));
    }

    private static ZenBpmCommandRetry retry(
        final Duration lock,
        final boolean shuttingDown
    ) {
        final var lockEnd = Instant
                .now()
                .plus(lock);
        return new ZenBpmCommandRetry("orders", 42, "ship", () -> lockEnd, () -> shuttingDown);
    }

    private static ZenBpmUnavailableException unavailable() {
        return new ZenBpmUnavailableException("orders", "no connection", new ConnectException("refused"), "POST", "/v1/jobs/42/complete");
    }

    private static ZenBpmApiException refused(final int status) {
        return new ZenBpmApiException("orders", status, "REFUSED", "refused", "POST", "/v1/jobs/42/complete");
    }

}

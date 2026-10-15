/*
 * Copyright 2026 Phactum Softwareentwicklung GmbH
 * Adapted for zenbpm-vanillabp-adapter from vanillabp/camunda8-adapter, see NOTICE.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.pbinitiative.zenbpmadapter.client;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import org.pbinitiative.zenbpmadapter.client.api.Readiness;

import lombok.extern.slf4j.Slf4j;

/**
 * Where the start waits for an engine which is not ready yet. The most common reason why a start
 * cannot reach its engine is an engine which starts together with the application, and then every
 * step of the deployment fails, not only the first one. So the deployment of each adapter id waits
 * once, before its first request, and nothing behind the wait is repeated.
 * <p>
 * The wait asks <code>GET /system/health/ready</code> every two seconds. The engine answers
 * <code>503</code> until its cluster has a leader and its partitions are initialized, and
 * <code>200</code> once it can take work. It ends, whichever comes first:
 * <ul>
 * <li>when the engine reports ready;</li>
 * <li>when <code>startup-wait</code> is used up, with a message naming the address, the time
 * waited, the last answer and the key to raise. <code>startup-wait</code> limits when an attempt
 * is started, not how long it runs: an attempt under way when it runs out ends at its own deadline,
 * <code>request-timeout</code>, and a ready engine is taken whenever it answers. So a failing wait
 * takes at most <code>startup-wait</code> + <code>request-timeout</code> + 0.25 s. The 0.25 s is
 * what the REST client gives a body which stopped arriving. A wait of zero asks once;</li>
 * <li>at once, where the engine answers something no repetition changes (see
 * {@link ZenBpmErrors#permanentFailure(Throwable)});</li>
 * <li>at once where the thread is interrupted, and with the next attempt where the factory of
 * the adapter id was closed.</li>
 * </ul>
 * A line before the first attempt names the wait, and a line every ten seconds names the time gone
 * and the last answer, so a start which waits does not look like a start which hangs, and a typo
 * in the address shows as "connection refused" on the first of those lines. The lines stay in the
 * log: the platform's startup report is written at the end of the start, too late for progress.
 * <p>
 * Each adapter id waits once. A second workflow module waits for the first one's answer, and
 * returns at once where the engine was ready. Where the wait failed, a later module does not wait
 * the whole <code>startup-wait</code> again, which under the <code>warn</code> policy of
 * <code>deployment-failure</code> every further module would: it asks once, and fails at once
 * where the engine is still not ready. An answer no repetition changes is final, and nobody asks
 * again. An interrupt is no answer of the engine, so the next module waits on its own. Every wait
 * of a closed factory ends with its first attempt.
 */
@Slf4j
public final class ZenBpmEngineWait {

    /**
     * How often the engine is asked, and how often the wait says where it stands.
     */
    static final Pacing PACING = new Pacing(Duration.ofSeconds(2), Duration.ofSeconds(10));

    private ZenBpmEngineWait() {
    }

    /**
     * Waits until the engine of an adapter id reports ready, once per adapter id.
     *
     * @param factory The factory of the adapter id
     * @throws IllegalStateException Where the adapter id has no clients (with the message of the
     *             validation), where the engine did not report ready within
     *             <code>startup-wait</code>, where it answered something no repetition changes,
     *             where the factory was closed, or where the thread was interrupted, also while
     *             it waited for another module's wait
     */
    public static void awaitReady(final ZenBpmClientFactory factory) {
        awaitReady(factory, PACING);
    }

    /**
     * The wait with pauses a test chooses.
     *
     * @param factory The factory of the adapter id
     * @param pacing How often to ask and to log
     */
    static void awaitReady(
        final ZenBpmClientFactory factory,
        final Pacing pacing
    ) {
        final var rest = factory.rest();
        final var address = factory
                .configuration()
                .engineAddress();
        final var memory = factory.engineWait();
        // a known answer is not refused to a thread which happens to be interrupted
        if (memory.ready) {
            return;
        }
        try {
            memory.lock.lockInterruptibly();
        } catch (final InterruptedException e) {
            Thread
                    .currentThread()
                    .interrupt();
            throw interrupted(factory.getAdapterId(), address, e);
        }
        try {
            awaitReady(factory, address, memory, pacing, rest::ready);
        } finally {
            memory.lock.unlock();
        }
    }

    /**
     * Called holding {@link Memory#lock}.
     */
    private static void awaitReady(
        final ZenBpmClientFactory factory,
        final URI address,
        final Memory memory,
        final Pacing pacing,
        final Supplier<Readiness> readiness
    ) {
        if (memory.ready) {
            return;
        }
        final var earlier = memory.failure;
        if ((earlier != null) && memory.failureIsFinal) {
            throw new IllegalStateException(earlier.getMessage(), earlier);
        }
        try {
            if (earlier == null) {
                waitUntilReady(factory, address, pacing, readiness);
            } else {
                askOnceMore(factory, address, earlier, readiness);
            }
            memory.ready = true;
        } catch (final Refused refused) {
            remember(memory, refused, true);
            throw refused;
        } catch (final RuntimeException failure) {
            // after a failed wait the first failure is kept, so a message does not grow with
            // every module
            if (earlier == null) {
                remember(memory, failure, false);
            }
            throw failure;
        }
    }

    private static void waitUntilReady(
        final ZenBpmClientFactory factory,
        final URI address,
        final Pacing pacing,
        final Supplier<Readiness> readiness
    ) {
        final var adapterId = factory.getAdapterId();
        final var startupWait = factory
                .configuration()
                .getStartupWait();
        log.info("ZenBPM[{}]: waiting up to {} for the engine at {} to report ready", adapterId, startupWait, address);
        final var startedAt = System.nanoTime();
        var loggedAt = startedAt;
        while (true) {
            final var attemptStartedAt = System.nanoTime();
            final var answer = whyNotReady(factory, address, readiness);
            if (answer.isEmpty()) {
                log.info("ZenBPM[{}]: the engine at {} reported ready after {}", adapterId, address, timeSince(
                        startedAt));
                return;
            }
            final var waited = timeSince(startedAt);
            if (waited.compareTo(startupWait) >= 0) {
                throw new IllegalStateException(
                        "ZenBPM adapter '%s': the engine at %s did not report ready within '%s: %s' (waited %s). The last answer was: %s. Start the engine, correct '%s', or raise '%s'."
                                .formatted(adapterId, address, startupWaitKey(adapterId), startupWait, waited, answer
                                        .get(), restAddressKey(adapterId), startupWaitKey(adapterId)));
            }
            if (System.nanoTime() - loggedAt >= pacing
                    .logEvery()
                    .toNanos()) {
                loggedAt = System.nanoTime();
                log.info(
                        "ZenBPM[{}]: still waiting for the engine at {} to report ready - {} of {} gone, last answer: {}",
                        adapterId, address, waited, startupWait, answer.get());
            }
            pauseUntilTheNextAttempt(adapterId, address, attemptStartedAt, pacing.askEvery(), startedAt, startupWait);
        }
    }

    // one request, bounded by request-timeout: an engine which became ready after the wait ran
    // out is taken, and one which did not costs no second startup-wait
    private static void askOnceMore(
        final ZenBpmClientFactory factory,
        final URI address,
        final RuntimeException earlier,
        final Supplier<Readiness> readiness
    ) {
        final var answer = whyNotReady(factory, address, readiness);
        if (answer.isPresent()) {
            throw new IllegalStateException("%s Asked once more, it answered: %s.".formatted(earlier.getMessage(),
                    answer.get()), earlier);
        }
        log.info("ZenBPM[{}]: the engine at {} reported ready when it was asked once more after the wait had failed",
                factory.getAdapterId(), address);
    }

    /**
     * One attempt.
     *
     * @return Why the engine is not ready: what it answered, or what the call failed with; empty
     *         where it is ready
     * @throws IllegalStateException Where the engine answered something no repetition changes,
     *             or where the factory was closed
     */
    private static Optional<String> whyNotReady(
        final ZenBpmClientFactory factory,
        final URI address,
        final Supplier<Readiness> readiness
    ) {
        final var answer = whatTheEngineSays(factory, address, readiness);
        // a closed REST client fails every call at once, and no wait changes that
        if (answer.isPresent() && factory.isClosed()) {
            throw new IllegalStateException(
                    "ZenBPM adapter '%s': its clients were closed while it waited for the engine at %s to report ready, because the application stops."
                            .formatted(factory.getAdapterId(), address));
        }
        return answer;
    }

    private static Optional<String> whatTheEngineSays(
        final ZenBpmClientFactory factory,
        final URI address,
        final Supplier<Readiness> readiness
    ) {
        final var adapterId = factory.getAdapterId();
        try {
            final var answer = readiness.get();
            if (answer.ready()) {
                return Optional.empty();
            }
            return Optional.of(answer
                    .reasons()
                    .isEmpty() ? "not ready" : "not ready (%s)".formatted(String.join("; ", answer.reasons())));
        } catch (final RuntimeException e) {
            if (ZenBpmErrors.permanentFailure(e)) {
                throw new Refused(
                        "ZenBPM adapter '%s': the engine at %s refused the readiness request, and waiting longer ('%s: %s') cannot change an answer like that. It answered: %s. Check '%s': it has to name the engine itself."
                                .formatted(adapterId, address, startupWaitKey(adapterId), factory
                                        .configuration()
                                        .getStartupWait(), ZenBpmErrors.withoutAdapterId(adapterId, e),
                                        restAddressKey(adapterId)), e);
            }
            return Optional.of(ZenBpmErrors.withoutAdapterId(adapterId, e));
        }
    }

    // never beyond the end of the wait, so the last attempt is made when the wait runs out rather
    // than up to a pause later
    private static void pauseUntilTheNextAttempt(
        final String adapterId,
        final URI address,
        final long attemptStartedAt,
        final Duration askEvery,
        final long startedAt,
        final Duration startupWait
    ) {
        final var now = System.nanoTime();
        final var untilTheNextAttempt = askEvery.toNanos() - (now - attemptStartedAt);
        final var untilTheEnd = startupWait.toNanos() - (now - startedAt);
        final var pause = Math.min(untilTheNextAttempt, untilTheEnd);
        try {
            // without a pause left, an interrupted thread fails its next request and then this sleep
            if (pause > 0) {
                Thread.sleep(Duration.ofNanos(pause));
            }
        } catch (final InterruptedException e) {
            Thread
                    .currentThread()
                    .interrupt();
            throw interrupted(adapterId, address, e);
        }
    }

    /**
     * Keeps what a wait failed with for the later modules of the adapter id, unless an interrupt
     * ended it, which is no answer of the engine.
     */
    private static void remember(
        final Memory memory,
        final RuntimeException failure,
        final boolean isFinal
    ) {
        if (Thread
                .currentThread()
                .isInterrupted()) {
            return;
        }
        memory.failure = failure;
        memory.failureIsFinal = isFinal;
    }

    private static IllegalStateException interrupted(
        final String adapterId,
        final URI address,
        final InterruptedException cause
    ) {
        return new IllegalStateException("ZenBPM adapter '%s' was interrupted while it waited for the engine at %s to report ready."
                .formatted(adapterId, address), cause);
    }

    private static String startupWaitKey(final String adapterId) {
        return ZenBpmAdapterConfiguration.propertyKey(adapterId, "startup-wait");
    }

    private static String restAddressKey(final String adapterId) {
        return ZenBpmAdapterConfiguration.propertyKey(adapterId, "rest-address");
    }

    // rounded to milliseconds: nanoseconds tell an operator nothing
    private static Duration timeSince(final long nanos) {
        return Duration.ofMillis(Duration
                .ofNanos(System.nanoTime() - nanos)
                .toMillis());
    }

    /**
     * How often the wait asks the engine, and how often it says where it stands.
     *
     * @param askEvery The pause between the starts of two attempts
     * @param logEvery The least time between two lines saying where the wait stands
     */
    record Pacing(
        Duration askEvery,
        Duration logEvery
    ) {
    }

    /**
     * What the wait of one adapter id found, kept by its factory. Every field is guarded by
     * {@link #lock}, which a module holds while it waits, so a second module waits for the first
     * one's answer instead of asking itself. A lock and not a monitor, so a module which waits for
     * another one's answer can be interrupted.
     */
    static final class Memory {

        final ReentrantLock lock = new ReentrantLock();

        /**
         * Whether the engine reported ready once. Written holding {@link #lock}, and read without
         * it as well.
         */
        private volatile boolean ready;

        /**
         * What the first wait failed with, <code>null</code> where none failed.
         */
        private RuntimeException failure;

        /**
         * Whether {@link #failure} is an answer no repetition changes, so nobody asks again.
         */
        private boolean failureIsFinal;

    }

    /**
     * The engine refused the readiness request in a way no repetition changes.
     */
    private static final class Refused extends IllegalStateException {

        private static final long serialVersionUID = 1L;

        private Refused(
            final String message,
            final Throwable cause
        ) {
            super(message, cause);
        }

    }

}

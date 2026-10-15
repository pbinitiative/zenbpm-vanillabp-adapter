/*
 * Copyright 2026 Phactum Softwareentwicklung GmbH
 * Adapted for zenbpm-vanillabp-adapter from vanillabp/camunda8-adapter, see NOTICE.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.pbinitiative.zenbpmadapter.client;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;
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
 * waited, the last answer and the key to raise. A wait of zero asks once;</li>
 * <li>at once, where the engine answers something no repetition changes (see
 * {@link ZenBpmErrors#permanentFailure(Throwable)}).</li>
 * </ul>
 * A line before the first attempt names the wait, and a line every ten seconds names the time gone
 * and the last answer, so a start which waits does not look like a start which hangs, and a typo
 * in the address shows as "connection refused" on the first of those lines. The lines stay in the
 * log: the platform's startup report is written at the end of the start, too late for progress.
 * <p>
 * Each adapter id waits once. A second workflow module waits for the first one's answer and
 * takes it, whether the engine became ready or the wait failed: under the <code>warn</code>
 * policy of <code>deployment-failure</code> the start goes on after a failed wait, and every
 * further module would otherwise wait the whole <code>startup-wait</code> again.
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
     *             or where the thread was interrupted
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
        factory.onceTheEngineIsReady(() -> awaitReady(factory.getAdapterId(), factory
                .configuration(), pacing, rest::ready));
    }

    private static void awaitReady(
        final String adapterId,
        final ZenBpmAdapterConfiguration configuration,
        final Pacing pacing,
        final Supplier<Readiness> ask
    ) {
        final var address = configuration.restBaseUri();
        final var startupWait = configuration.getStartupWait();
        log.info("ZenBPM[{}]: waiting up to {} for the engine at {} to report ready", adapterId, startupWait, address);
        final var startedAt = System.nanoTime();
        var loggedAt = startedAt;
        while (true) {
            final var attemptStartedAt = System.nanoTime();
            final var answer = whatTheEngineAnswered(adapterId, address, startupWait, ask);
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
                                        .get(),
                                        ZenBpmAdapterConfiguration.propertyKey(adapterId, "rest-address"),
                                        startupWaitKey(adapterId)));
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

    /**
     * One attempt.
     *
     * @return Empty where the engine is ready, else what it answered or what the call failed with
     * @throws IllegalStateException Where the engine answered something no repetition changes
     */
    private static Optional<String> whatTheEngineAnswered(
        final String adapterId,
        final URI address,
        final Duration startupWait,
        final Supplier<Readiness> ask
    ) {
        try {
            final var readiness = ask.get();
            if (readiness.ready()) {
                return Optional.empty();
            }
            return Optional.of(readiness
                    .reasons()
                    .isEmpty() ? "not ready" : "not ready (%s)".formatted(String.join("; ", readiness.reasons())));
        } catch (final RuntimeException e) {
            if (ZenBpmErrors.permanentFailure(e)) {
                throw new IllegalStateException(
                        "ZenBPM adapter '%s': the engine at %s refused the readiness request, and waiting longer ('%s: %s') cannot change an answer like that. It answered: %s. Check '%s': it has to name the engine itself."
                                .formatted(adapterId, address, startupWaitKey(adapterId), startupWait, e.getMessage(),
                                        ZenBpmAdapterConfiguration.propertyKey(adapterId, "rest-address")), e);
            }
            return Optional.of(e.getMessage());
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
            throw new IllegalStateException("ZenBPM adapter '%s' was interrupted while it waited for the engine at %s to report ready."
                    .formatted(adapterId, address), e);
        }
    }

    private static String startupWaitKey(final String adapterId) {
        return ZenBpmAdapterConfiguration.propertyKey(adapterId, "startup-wait");
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

}

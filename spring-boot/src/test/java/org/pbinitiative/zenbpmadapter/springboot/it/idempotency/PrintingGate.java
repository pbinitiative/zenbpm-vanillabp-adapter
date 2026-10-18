/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.idempotency;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Holds the print of a ticket as long as the test asks, and counts how often the handler was
 * called for it, which a rolled-back or a second run counts as well: the database only holds what
 * was committed.
 */
public final class PrintingGate {

    /**
     * How long a print waits at most, so a test which fails before it lets the handler go does not
     * keep a handler thread for the rest of the run.
     */
    private static final Duration LONGEST_HOLD = Duration.ofSeconds(60);

    private static final Map<String, Duration> HELD_FOR = new ConcurrentHashMap<>();

    /**
     * How many of the first prints of a ticket wait for the release, by ticket.
     */
    private static final Map<String, AtomicInteger> HELD_PRINTS = new ConcurrentHashMap<>();

    private static final Map<String, CountDownLatch> RELEASES = new ConcurrentHashMap<>();

    private static final Map<String, AtomicInteger> CALLS = new ConcurrentHashMap<>();

    private static final Map<String, AtomicInteger> WAITING = new ConcurrentHashMap<>();

    private PrintingGate() {
    }

    /**
     * Lets every print of a ticket take a while.
     *
     * @param ticketId The ticket
     * @param duration How long each print takes
     */
    public static void holdFor(
        final String ticketId,
        final Duration duration
    ) {
        HELD_FOR.put(ticketId, duration);
    }

    /**
     * Holds the first prints of a ticket until the test releases them.
     *
     * @param ticketId The ticket
     * @param prints How many of the first prints wait
     */
    public static void holdUntilReleased(
        final String ticketId,
        final int prints
    ) {
        HELD_PRINTS.put(ticketId, new AtomicInteger(prints));
        RELEASES.put(ticketId, new CountDownLatch(1));
    }

    /**
     * Lets the held prints of a ticket go on.
     *
     * @param ticketId The ticket
     */
    public static void release(final String ticketId) {
        RELEASES
                .getOrDefault(ticketId, new CountDownLatch(1))
                .countDown();
    }

    /**
     * How often the handler was called for a ticket.
     *
     * @param ticketId The ticket
     * @return The calls, whether their work was committed or not
     */
    public static int calls(final String ticketId) {
        return CALLS
                .getOrDefault(ticketId, new AtomicInteger())
                .get();
    }

    /**
     * How many prints of a ticket wait in their handlers right now.
     *
     * @param ticketId The ticket
     * @return The prints waiting
     */
    public static int waiting(final String ticketId) {
        return WAITING
                .getOrDefault(ticketId, new AtomicInteger())
                .get();
    }

    /**
     * Counts the call, and waits where the test holds the print of the ticket.
     *
     * @param ticketId The ticket
     */
    static void passOn(final String ticketId) {
        CALLS
                .computeIfAbsent(ticketId, id -> new AtomicInteger())
                .incrementAndGet();
        final var waiting = WAITING.computeIfAbsent(ticketId, id -> new AtomicInteger());
        waiting.incrementAndGet();
        try {
            final var heldFor = HELD_FOR.get(ticketId);
            if (heldFor != null) {
                Thread.sleep(heldFor);
            }
            final var heldPrints = HELD_PRINTS.get(ticketId);
            if ((heldPrints != null) && (heldPrints.getAndDecrement() > 0)) {
                RELEASES
                        .get(ticketId)
                        .await(LONGEST_HOLD.toMillis(), TimeUnit.MILLISECONDS);
            }
        } catch (final InterruptedException e) {
            Thread
                    .currentThread()
                    .interrupt();
        } finally {
            waiting.decrementAndGet();
        }
    }

}

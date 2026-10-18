/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.task;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

/**
 * When the handlers of the payments ran, outside their transactions: a handler which throws rolls
 * its transaction back, so the aggregate keeps nothing of an attempt. It also holds a handler until
 * the test lets it go on.
 */
@Component
public class PaymentAttempts {

    /**
     * How long a held handler waits at most, so a test which fails before it lets the handler go
     * does not keep a handler slot for the rest of the class.
     */
    private static final Duration LONGEST_HOLD = Duration.ofSeconds(30);

    private final Map<String, List<Instant>> attempts = new ConcurrentHashMap<>();

    private final Map<String, CountDownLatch> holds = new ConcurrentHashMap<>();

    /**
     * Built by Spring.
     */
    public PaymentAttempts() {
    }

    /**
     * Records that a handler of a payment started.
     *
     * @param paymentId The payment
     * @return How many attempts the payment had, this one included
     */
    public int record(final String paymentId) {
        final var recorded = attempts.computeIfAbsent(paymentId, id -> new CopyOnWriteArrayList<>());
        recorded.add(Instant.now());
        return recorded.size();
    }

    /**
     * When the handlers of a payment started.
     *
     * @param paymentId The payment
     * @return The moments, in the order the handlers started
     */
    public List<Instant> of(final String paymentId) {
        return List.copyOf(attempts.getOrDefault(paymentId, List.of()));
    }

    /**
     * Waits until the test lets the handler of a payment go on.
     *
     * @param paymentId The payment
     */
    public void hold(final String paymentId) {
        try {
            holds
                    .computeIfAbsent(paymentId, id -> new CountDownLatch(1))
                    .await(LONGEST_HOLD.toMillis(), TimeUnit.MILLISECONDS);
        } catch (final InterruptedException e) {
            Thread
                    .currentThread()
                    .interrupt();
        }
    }

    /**
     * Lets the held handler of a payment go on.
     *
     * @param paymentId The payment
     */
    public void release(final String paymentId) {
        holds
                .computeIfAbsent(paymentId, id -> new CountDownLatch(1))
                .countDown();
    }

}

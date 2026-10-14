/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.client;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The consumer of a job stream in an integration test: it keeps every job, with the thread it
 * arrived on, until a test takes the one of its instance. Tests sharing an engine hand out jobs
 * of each other's instances, which a test passes over.
 */
final class JobInbox implements Consumer<ReceivedJob> {

    private final BlockingQueue<Arrival> arrivals = new LinkedBlockingQueue<>();

    @Override
    public void accept(final ReceivedJob job) {
        arrivals.add(new Arrival(job, Thread
                .currentThread()
                .getName()));
    }

    /**
     * The next job of an instance.
     *
     * @param instanceKey The instance
     * @param within How long to wait for it
     * @return The job
     * @throws AssertionError Where none arrives in time
     */
    Arrival next(
        final long instanceKey,
        final Duration within
    ) {
        return nextIfAny(instanceKey, within).orElseThrow(() -> new AssertionError(
                "No job of instance %d arrived within %s".formatted(instanceKey, within)));
    }

    /**
     * The next job of an instance, where one arrives in time.
     *
     * @param instanceKey The instance
     * @param within How long to wait for it
     * @return The job, empty where none arrived in time
     */
    Optional<Arrival> nextIfAny(
        final long instanceKey,
        final Duration within
    ) {
        final var deadline = System.nanoTime() + within.toNanos();
        try {
            while (true) {
                final var arrival = arrivals.poll(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                if (arrival == null) {
                    return Optional.empty();
                }
                if (arrival
                        .received()
                        .job()
                        .getInstanceKey() == instanceKey) {
                    return Optional.of(arrival);
                }
            }
        } catch (InterruptedException e) {
            Thread
                    .currentThread()
                    .interrupt();
            throw new AssertionError(e);
        }
    }

    /**
     * A job and the thread it was handed to the consumer on.
     *
     * @param received The job
     * @param thread The name of the thread
     */
    record Arrival(
        ReceivedJob received,
        String thread
    ) {

        /**
         * The job's key.
         *
         * @return The key
         */
        long key() {
            return received
                    .job()
                    .getKey();
        }

    }

}

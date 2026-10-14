/*
 * Copyright 2026 Phactum Softwareentwicklung GmbH
 * Adapted for zenbpm-vanillabp-adapter from vanillabp/camunda8-adapter, see NOTICE.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.pbinitiative.zenbpmadapter.client;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The executor of an adapter id which runs <code>worker-threads</code> as a number. It has as many
 * platform threads named <code>zenbpm-&lt;id&gt;-handler-N</code> as the number says. The timing
 * runs on the separate threads which {@link ZenBpmExecutor} keeps for it.
 * <p>
 * {@link ZenBpmExecutor} never hands the pool more handlers than it has threads. A thread which ran
 * a handler may still be on its way back into the pool when the next one arrives, so the pool has
 * a queue of as many places as threads, which is the most that can wait for such a thread.
 */
public class ZenBpmPlatformThreadExecutor extends ZenBpmExecutor {

    /**
     * Builds the pool, with as many threads as the configured number.
     *
     * @param adapterId The adapter id, used to name the threads
     * @param bound How many handlers may run at the same time
     */
    public ZenBpmPlatformThreadExecutor(
        final String adapterId,
        final int bound
    ) {
        super(adapterId, bound, handlingHalf(adapterId, bound));
    }

    private static ThreadPoolExecutor handlingHalf(
        final String adapterId,
        final int bound
    ) {
        final var nextThread = new AtomicInteger();
        // a bound below one reaches the constructor of ZenBpmExecutor, which refuses it with a
        // message, rather than the pool, which refuses it without one
        final var threads = Math.max(1, bound);
        return new ThreadPoolExecutor(threads, threads, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(
                threads), runnable -> {
                    final var thread = new Thread(runnable, "zenbpm-%s-handler-%d"
                            .formatted(adapterId, nextThread.getAndIncrement()));
                    // daemon, like the virtual threads of the other model and like the timing
                    // threads. A shutdown which forgot this executor must not keep the JVM up
                    thread.setDaemon(true);
                    return thread;
                });
    }

}

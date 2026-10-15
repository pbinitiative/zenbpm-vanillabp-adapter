/*
 * Copyright 2026 Phactum Softwareentwicklung GmbH
 * Adapted for zenbpm-vanillabp-adapter from vanillabp/camunda8-adapter, see NOTICE.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.pbinitiative.zenbpmadapter.client;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The executor of an adapter id which runs <code>worker-threads</code> as a number. It has as many
 * platform threads named <code>zenbpm-&lt;id&gt;-handler-N</code> as the number says. The timing
 * runs on the separate threads which {@link ZenBpmExecutor} keeps for it.
 * <p>
 * {@link ZenBpmExecutor} hands the pool one handler per slot. A slot is given back just before its
 * thread is back in the pool, so the next handler can arrive while every thread is still busy. The
 * pool keeps such handlers in a queue with one place per thread, which is the most that can wait
 * this way. The queue takes memory only for what it holds, so a large number of threads costs
 * nothing until they are used.
 */
public class ZenBpmPlatformThreadExecutor extends ZenBpmExecutor {

    /**
     * Builds the pool, with as many threads as the configured number.
     *
     * @param adapterId The adapter id, used to name the threads
     * @param slots How many handlers may run at the same time
     */
    public ZenBpmPlatformThreadExecutor(
        final String adapterId,
        final int slots
    ) {
        super(adapterId, slots, handlerThreads(adapterId, slots));
    }

    private static ThreadPoolExecutor handlerThreads(
        final String adapterId,
        final int slots
    ) {
        final var nextThread = new AtomicInteger();
        // fewer than one slot reaches the constructor of ZenBpmExecutor, which refuses it with a
        // message, rather than the pool, which refuses it without one
        final var threads = Math.max(1, slots);
        return new ThreadPoolExecutor(threads, threads, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(
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

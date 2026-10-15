/*
 * Copyright 2026 Phactum Softwareentwicklung GmbH
 * Adapted for zenbpm-vanillabp-adapter from vanillabp/camunda8-adapter, see NOTICE.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.pbinitiative.zenbpmadapter.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The executor of the platform-thread mode, which an adapter id uses unless
 * <code>worker-threads: virtual</code> says otherwise. It runs the handlers on a pool as wide as
 * the configured number, and the timing on threads of its own. {@link ZenBpmExecutorTest} holds
 * what both models promise.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmPlatformThreadExecutorTest {

    /**
     * How long a wait for the executor goes on before the test gives up. It guards against
     * something which never got its turn, and it measures nothing.
     */
    private static final long UNTIL_THE_EXECUTOR_COUNTS_AS_STUCK = 30_000;

    @Test
    @DisplayName("A handler runs on a platform thread named after the adapter id")
    void aHandlerRunsOnANamedPlatformThread() throws Exception {
        final var executor = new ZenBpmPlatformThreadExecutor("orders", 4);
        try {
            final var virtual = new AtomicBoolean(true);
            final var name = new AtomicReference<String>();
            final var ran = new CountDownLatch(1);

            executor.execute(() -> {
                virtual.set(Thread
                        .currentThread()
                        .isVirtual());
                name.set(Thread
                        .currentThread()
                        .getName());
                ran.countDown();
            });

            assertTrue(ran.await(UNTIL_THE_EXECUTOR_COUNTS_AS_STUCK, TimeUnit.MILLISECONDS), "the handler ran");
            assertFalse(virtual.get(), "the handler runs on a platform thread");
            assertTrue(name
                    .get()
                    .startsWith("zenbpm-orders-handler-"), name.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("A timing task runs on a platform thread")
    void aTimingTaskRunsOnAPlatformThread() throws Exception {
        final var executor = new ZenBpmPlatformThreadExecutor("orders", 4);
        try {
            final var virtual = executor
                    .timing()
                    .schedule(() -> Thread
                            .currentThread()
                            .isVirtual(), 1, TimeUnit.MILLISECONDS)
                    .get(UNTIL_THE_EXECUTOR_COUNTS_AS_STUCK, TimeUnit.MILLISECONDS);

            assertFalse(virtual, "the timing never runs on a virtual thread");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("A handler which throws a checked exception sneakily does not cost its thread")
    void aSneakyCheckedExceptionKeepsTheThread() throws Exception {
        final var executor = new ZenBpmPlatformThreadExecutor("orders", 1);
        try {
            executor.execute(() -> SneakyThrow.sneakily(new IOException("checked failure")));
            final var name = new AtomicReference<String>();
            final var ran = new CountDownLatch(1);
            executor.execute(() -> {
                name.set(Thread
                        .currentThread()
                        .getName());
                ran.countDown();
            });

            assertTrue(ran.await(UNTIL_THE_EXECUTOR_COUNTS_AS_STUCK, TimeUnit.MILLISECONDS));
            assertEquals("zenbpm-orders-handler-0", name.get(), "the same thread serves the next handler");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("A listener of freed slots which throws a checked exception sneakily does not cost the thread")
    void aSneakyListenerKeepsTheThread() throws Exception {
        final var executor = new ZenBpmPlatformThreadExecutor("orders", 1);
        try {
            final var told = new CountDownLatch(1);
            executor.onSlotFreed(() -> {
                told.countDown();
                SneakyThrow.sneakily(new IOException("listener failure"));
            });
            executor.execute(() -> {
            });
            assertTrue(told.await(UNTIL_THE_EXECUTOR_COUNTS_AS_STUCK, TimeUnit.MILLISECONDS));
            final var name = new AtomicReference<String>();
            final var ran = new CountDownLatch(1);
            executor.execute(() -> {
                name.set(Thread
                        .currentThread()
                        .getName());
                ran.countDown();
            });

            assertTrue(ran.await(UNTIL_THE_EXECUTOR_COUNTS_AS_STUCK, TimeUnit.MILLISECONDS));
            assertEquals("zenbpm-orders-handler-0", name.get(), "the same thread serves the next handler");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("An Error thrown by a handler is passed on, so its thread ends and another one takes over")
    void anErrorEndsItsThread() throws Exception {
        final var executor = new ZenBpmPlatformThreadExecutor("orders", 1);
        try {
            executor.execute(() -> {
                throw new StackOverflowError("too deep");
            });
            final var name = new AtomicReference<String>();
            final var ran = new CountDownLatch(1);
            executor.execute(() -> {
                name.set(Thread
                        .currentThread()
                        .getName());
                ran.countDown();
            });

            assertTrue(ran.await(UNTIL_THE_EXECUTOR_COUNTS_AS_STUCK, TimeUnit.MILLISECONDS));
            assertEquals("zenbpm-orders-handler-1", name.get(), "the Error was not swallowed");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("The largest number of threads builds an executor which works")
    void theLargestNumberOfThreadsWorks() throws Exception {
        final var executor = ZenBpmExecutor.of("orders", ZenBpmExecutionModel.resolve("orders", String.valueOf(
                Integer.MAX_VALUE), null));
        try {
            final var ran = new CountDownLatch(1);
            executor.execute(ran::countDown);

            assertTrue(ran.await(UNTIL_THE_EXECUTOR_COUNTS_AS_STUCK, TimeUnit.MILLISECONDS));
            assertEquals(Integer.MAX_VALUE, executor.getSlots());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("shutdown ends the handler and the timing threads")
    void shutdownEndsBothHalves() throws Exception {
        final var executor = new ZenBpmPlatformThreadExecutor("orders", 2);

        executor.shutdown();

        assertTrue(executor.awaitTermination(Duration.ofMillis(UNTIL_THE_EXECUTOR_COUNTS_AS_STUCK)),
                "both halves terminate");
        assertTrue(executor.isShutdown());
        assertTrue(executor.isTerminated());
    }

}

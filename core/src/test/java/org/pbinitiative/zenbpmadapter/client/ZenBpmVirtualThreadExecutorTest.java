/*
 * Copyright 2026 Phactum Softwareentwicklung GmbH
 * Adapted for zenbpm-vanillabp-adapter from vanillabp/camunda8-adapter, see NOTICE.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.pbinitiative.zenbpmadapter.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * The executor of the virtual-thread mode. It runs each handler on a virtual thread of its own,
 * never more of them at once than there are slots, and the timing on threads of its own.
 * {@link ZenBpmExecutorTest} holds what both models promise.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmVirtualThreadExecutorTest {

    /**
     * How long a wait for the executor goes on before the test gives up. It guards against
     * something which never got its turn, and it measures nothing.
     */
    private static final long UNTIL_THE_EXECUTOR_COUNTS_AS_STUCK = 30_000;

    @Test
    @DisplayName("A handler runs on a virtual thread named after the adapter id")
    void aHandlerRunsOnANamedVirtualThread() throws Exception {
        final var executor = new ZenBpmVirtualThreadExecutor("orders", 4);
        try {
            final var virtual = new AtomicBoolean(false);
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
            assertTrue(virtual.get(), "the handler runs on a virtual thread");
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
        final var executor = new ZenBpmVirtualThreadExecutor("orders", 4);
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
    @DisplayName("shutdown ends the handler and the timing threads")
    void shutdownEndsBothHalves() throws Exception {
        final var executor = new ZenBpmVirtualThreadExecutor("orders", 2);

        executor.shutdown();

        assertTrue(executor.awaitTermination(Duration.ofMillis(UNTIL_THE_EXECUTOR_COUNTS_AS_STUCK)),
                "both halves terminate");
        assertTrue(executor.isShutdown());
        assertTrue(executor.isTerminated());
    }

}

/*
 * Copyright 2026 Phactum Softwareentwicklung GmbH
 * Adapted for zenbpm-vanillabp-adapter from vanillabp/camunda8-adapter, see NOTICE.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.pbinitiative.zenbpmadapter.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.pbinitiative.zenbpmadapter.client.ContractFixtures.eventually;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What both execution models promise, asserted against each of them: a bound which holds by making
 * the submitter wait instead of queuing, slots for the dispatcher, and timing threads which no
 * handler occupies.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmExecutorTest {

    private static final String ADAPTER_ID = "orders";

    /**
     * How long a wait for the executor goes on before the test gives up. It guards against
     * something which never got its turn, and it measures nothing. These tests read their claims
     * from which thread ran the work and from what was still inside its slot, so a loaded machine
     * makes a test slower, not red.
     */
    private static final long UNTIL_THE_EXECUTOR_COUNTS_AS_STUCK = 30_000;

    @ParameterizedTest(name = "{0}")
    @MethodSource("executionModels")
    @DisplayName("More submissions than the bound make the submitter wait, and nothing is queued")
    void moreSubmissionsThanTheBoundBlockTheSubmitter(
        final String model,
        final IntFunction<ZenBpmExecutor> executionModel
    ) throws Exception {
        final var bound = 2;
        final var executor = executionModel.apply(bound);
        try {
            final var running = new AtomicInteger();
            final var peak = new AtomicInteger();
            final var release = new CountDownLatch(1);
            final var everyThreadTaken = new CountDownLatch(bound);
            final var finished = new CountDownLatch(bound + 1);
            final var submitted = new AtomicInteger();
            final var submitter = Thread.ofPlatform().start(() -> {
                for (int job = 0; job < bound + 1; job++) {
                    executor.execute(() -> {
                        peak.accumulateAndGet(running.incrementAndGet(), Math::max);
                        everyThreadTaken.countDown();
                        try {
                            awaitQuietly(release);
                        } finally {
                            running.decrementAndGet();
                            finished.countDown();
                        }
                    });
                    submitted.incrementAndGet();
                }
            });

            assertTrue(everyThreadTaken.await(UNTIL_THE_EXECUTOR_COUNTS_AS_STUCK, TimeUnit.MILLISECONDS),
                    "the bound is used");
            eventually(() -> Optional
                    .of(submitter.getState())
                    .filter(Thread.State.TIMED_WAITING::equals));
            assertEquals(bound, submitted.get(), "the submission beyond the bound has not returned");
            assertTrue(submitter.isAlive(), "the submitter waits inside execute");
            assertEquals(bound, running.get(), "nothing beyond the bound runs");

            release.countDown();
            assertTrue(finished.await(UNTIL_THE_EXECUTOR_COUNTS_AS_STUCK, TimeUnit.MILLISECONDS),
                    "the waiting handler runs once a thread returned");
            submitter.join(UNTIL_THE_EXECUTOR_COUNTS_AS_STUCK);
            assertEquals(bound + 1, submitted.get());
            assertEquals(bound, peak.get(), "never more handlers at once than the bound allows");
        } finally {
            executor.shutdownNow();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("executionModels")
    @DisplayName("A timing task runs on a timing thread while every handler thread is busy")
    void theTimingIsNotStarvedByBusyHandlers(
        final String model,
        final IntFunction<ZenBpmExecutor> executionModel
    ) throws Exception {
        final var bound = 2;
        final var executor = executionModel.apply(bound);
        try {
            final var release = new CountDownLatch(1);
            final var handlerThreads = takeEveryHandlerThread(executor, bound, release);

            final var timingThreads = ConcurrentHashMap.<String>newKeySet();
            final var ran = new CountDownLatch(4);
            executor
                    .timing()
                    .schedule(() -> recordThread(timingThreads, ran), 1, TimeUnit.MILLISECONDS);
            for (int task = 0; task < 3; task++) {
                executor
                        .timing()
                        .execute(() -> recordThread(timingThreads, ran));
            }

            assertTrue(ran.await(UNTIL_THE_EXECUTOR_COUNTS_AS_STUCK, TimeUnit.MILLISECONDS),
                    "the timing tasks ran while every handler was inside");
            assertEquals(1, release.getCount(), "the handlers were still inside");
            assertTrue(timingThreads
                    .stream()
                    .allMatch(name -> name.matches("zenbpm-orders-timing-[01]")), timingThreads::toString);
            assertTrue(handlerThreads
                    .stream()
                    .allMatch(name -> name.startsWith("zenbpm-orders-handler-")), handlerThreads::toString);
            release.countDown();
        } finally {
            executor.shutdownNow();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("executionModels")
    @DisplayName("Slots are handed out up to the bound, and one more given back is refused")
    void slotsAreHandedOutUpToTheBound(
        final String model,
        final IntFunction<ZenBpmExecutor> executionModel
    ) {
        final var executor = executionModel.apply(2);
        try {
            assertEquals(2, executor.getBound());
            assertEquals(2, executor.getFreeSlots());
            assertEquals(0, executor.getBusySlots());

            assertTrue(executor.tryAcquireSlot());
            assertTrue(executor.tryAcquireSlot());
            assertFalse(executor.tryAcquireSlot(), "no third slot");
            assertEquals(0, executor.getFreeSlots());
            assertEquals(2, executor.getBusySlots());

            executor.releaseSlot();
            assertEquals(1, executor.getFreeSlots());
            executor.releaseSlot();
            final var exception = assertThrows(IllegalStateException.class, executor::releaseSlot);
            assertEquals("ZenBPM[orders]: a slot was given back which nobody took", exception.getMessage());
            assertEquals(2, executor.getFreeSlots(), "the bound did not grow");
        } finally {
            executor.shutdownNow();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("executionModels")
    @DisplayName("A dispatcher which submits only with a slot in hand runs every job on handler threads")
    void aDispatcherWithSlotsRunsEveryJob(
        final String model,
        final IntFunction<ZenBpmExecutor> executionModel
    ) throws Exception {
        final var executor = executionModel.apply(1);
        try {
            final var jobs = 20;
            final var waiting = new AtomicInteger(jobs);
            final var done = new CountDownLatch(jobs);
            final var handlerThreads = ConcurrentHashMap.<String>newKeySet();
            final var dispatch = new AtomicReference<Runnable>();
            // the way the dispatcher is meant to work: it runs on a timing thread, takes a slot
            // before it submits, and the handler gives the slot back and asks for the next job on
            // a timing thread
            dispatch.set(() -> {
                while ((waiting.get() > 0) && executor.tryAcquireSlot()) {
                    waiting.decrementAndGet();
                    executor.execute(() -> {
                        try {
                            recordThread(handlerThreads, done);
                        } finally {
                            executor.releaseSlot();
                            executor
                                    .timing()
                                    .execute(dispatch.get());
                        }
                    });
                }
            });

            executor
                    .timing()
                    .execute(dispatch.get());

            assertTrue(done.await(UNTIL_THE_EXECUTOR_COUNTS_AS_STUCK, TimeUnit.MILLISECONDS), "every job ran");
            assertTrue(handlerThreads
                    .stream()
                    .allMatch(name -> name.startsWith("zenbpm-orders-handler-")), handlerThreads::toString);
            eventually(() -> Optional
                    .of(executor.getFreeSlots())
                    .filter(free -> free == 1));
        } finally {
            executor.shutdownNow();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("executionModels")
    @DisplayName("A handler cannot submit a handler, which could wait for its own thread")
    void aHandlerCannotSubmitAHandler(
        final String model,
        final IntFunction<ZenBpmExecutor> executionModel
    ) throws Exception {
        final var executor = executionModel.apply(1);
        try {
            final var refusal = new AtomicReference<Exception>();
            final var ran = new CountDownLatch(1);
            executor.execute(() -> {
                try {
                    executor.execute(() -> {
                    });
                } catch (final IllegalStateException e) {
                    refusal.set(e);
                } finally {
                    ran.countDown();
                }
            });

            assertTrue(ran.await(UNTIL_THE_EXECUTOR_COUNTS_AS_STUCK, TimeUnit.MILLISECONDS));
            assertInstanceOf(IllegalStateException.class, refusal.get());
            assertTrue(refusal
                    .get()
                    .getMessage()
                    .startsWith("ZenBPM[orders]: a handler submitted another handler"),
                    refusal
                            .get()
                            .getMessage());
            final var later = new CountDownLatch(1);
            executor.execute(later::countDown);
            assertTrue(later.await(UNTIL_THE_EXECUTOR_COUNTS_AS_STUCK, TimeUnit.MILLISECONDS),
                    "the thread serves the next handler");
        } finally {
            executor.shutdownNow();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("executionModels")
    @DisplayName("A handler which throws is logged, and its thread serves the next one")
    void aThrowingHandlerIsLogged(
        final String model,
        final IntFunction<ZenBpmExecutor> executionModel,
        final CapturedOutput output
    ) throws Exception {
        final var executor = executionModel.apply(1);
        try {
            executor.execute(() -> {
                throw new IllegalArgumentException("broken handler");
            });
            final var next = new CountDownLatch(1);
            executor.execute(next::countDown);

            assertTrue(next.await(UNTIL_THE_EXECUTOR_COUNTS_AS_STUCK, TimeUnit.MILLISECONDS));
            eventually(() -> Optional
                    .of(output.getAllOfThisTest())
                    .filter(printed -> printed.contains("ZenBPM[orders]: a handler threw"))
                    .filter(printed -> printed.contains("broken handler")));
        } finally {
            executor.shutdownNow();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("executionModels")
    @DisplayName("A submission waiting for a thread is refused when the executor shuts down")
    void aWaitingSubmissionIsRefusedAtShutdown(
        final String model,
        final IntFunction<ZenBpmExecutor> executionModel
    ) throws Exception {
        final var executor = executionModel.apply(1);
        try {
            final var release = new CountDownLatch(1);
            takeEveryHandlerThread(executor, 1, release);
            final var refusal = new AtomicReference<RuntimeException>();
            final var ran = new AtomicInteger();
            final var submitter = Thread.ofPlatform().start(() -> {
                try {
                    executor.execute(ran::incrementAndGet);
                } catch (final RuntimeException e) {
                    refusal.set(e);
                }
            });
            eventually(() -> Optional
                    .of(submitter.getState())
                    .filter(Thread.State.TIMED_WAITING::equals));

            executor.shutdown();

            // the handler stays inside, so a refusal can only come from the wait itself, which
            // looks for a shutdown every 100 ms
            submitter.join(5_000);
            assertInstanceOf(RejectedExecutionException.class, refusal.get(), "refused while still waiting");
            assertEquals("ZenBPM[orders]: the adapter shuts down, so the handler is not run", refusal
                    .get()
                    .getMessage());
            assertEquals(null, refusal
                    .get()
                    .getCause(), "refused by the wait, not by the handler half once a thread was free");
            release.countDown();
            assertTrue(executor.awaitTermination(Duration.ofMillis(UNTIL_THE_EXECUTOR_COUNTS_AS_STUCK)));
            assertEquals(0, ran.get(), "the refused handler never ran");
        } finally {
            executor.shutdownNow();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("executionModels")
    @DisplayName("A submitter interrupted while it waits is refused and keeps its interrupt")
    void anInterruptedSubmitterIsRefused(
        final String model,
        final IntFunction<ZenBpmExecutor> executionModel
    ) throws Exception {
        final var executor = executionModel.apply(1);
        try {
            final var release = new CountDownLatch(1);
            takeEveryHandlerThread(executor, 1, release);
            final var refusal = new AtomicReference<RuntimeException>();
            final var interrupted = new AtomicReference<Boolean>();
            final var submitter = Thread.ofPlatform().start(() -> {
                try {
                    executor.execute(() -> {
                    });
                } catch (final RuntimeException e) {
                    refusal.set(e);
                    interrupted.set(Thread
                            .currentThread()
                            .isInterrupted());
                }
            });
            eventually(() -> Optional
                    .of(submitter.getState())
                    .filter(Thread.State.TIMED_WAITING::equals));

            submitter.interrupt();

            submitter.join(UNTIL_THE_EXECUTOR_COUNTS_AS_STUCK);
            assertInstanceOf(RejectedExecutionException.class, refusal.get());
            assertInstanceOf(InterruptedException.class, refusal
                    .get()
                    .getCause());
            assertTrue(interrupted.get(), "the interrupt is kept");
            release.countDown();
            final var later = new CountDownLatch(1);
            executor.execute(later::countDown);
            assertTrue(later.await(UNTIL_THE_EXECUTOR_COUNTS_AS_STUCK, TimeUnit.MILLISECONDS),
                    "the refused submission took no thread with it");
        } finally {
            executor.shutdownNow();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("executionModels")
    @DisplayName("A submission after the shutdown is refused and takes no thread with it")
    void aSubmissionAfterTheShutdownIsRefused(
        final String model,
        final IntFunction<ZenBpmExecutor> executionModel
    ) throws Exception {
        final var executor = executionModel.apply(1);
        executor.shutdown();

        final var first = assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> {
        }));
        final var second = assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> {
        }));

        assertEquals("ZenBPM[orders]: the adapter shuts down, so the handler is not run", first.getMessage());
        assertInstanceOf(RejectedExecutionException.class, first.getCause(), "the handler half refused it");
        assertInstanceOf(RejectedExecutionException.class, second.getCause(),
                "the second reached the handler half too, it did not wait for the thread the first took");
        assertTrue(executor.awaitTermination(Duration.ofMillis(UNTIL_THE_EXECUTOR_COUNTS_AS_STUCK)));
        assertTrue(executor.isShutdown());
        assertTrue(executor.isTerminated());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("executionModels")
    @DisplayName("shutdownNow interrupts the handlers and hands back the timing tasks which never ran")
    void shutdownNowInterruptsAndHandsBack(
        final String model,
        final IntFunction<ZenBpmExecutor> executionModel
    ) throws Exception {
        final var executor = executionModel.apply(1);
        final var interrupted = new CountDownLatch(1);
        final var started = new CountDownLatch(1);
        executor.execute(() -> {
            started.countDown();
            try {
                Thread.sleep(UNTIL_THE_EXECUTOR_COUNTS_AS_STUCK);
            } catch (final InterruptedException e) {
                interrupted.countDown();
            }
        });
        assertTrue(started.await(UNTIL_THE_EXECUTOR_COUNTS_AS_STUCK, TimeUnit.MILLISECONDS));
        executor
                .timing()
                .schedule(() -> {
                }, 1, TimeUnit.HOURS);

        final var pending = executor.shutdownNow();

        assertEquals(1, pending.size(), "the timing task due in an hour");
        assertTrue(interrupted.await(UNTIL_THE_EXECUTOR_COUNTS_AS_STUCK, TimeUnit.MILLISECONDS));
        assertTrue(executor.awaitTermination(Duration.ofMillis(UNTIL_THE_EXECUTOR_COUNTS_AS_STUCK)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("executionModels")
    @DisplayName("Shutting down only the timing threads does not count as shut down")
    void halfAShutdownIsNoShutdown(
        final String model,
        final IntFunction<ZenBpmExecutor> executionModel
    ) throws Exception {
        final var executor = executionModel.apply(1);
        try {
            executor
                    .timing()
                    .shutdown();

            assertFalse(executor.isShutdown());
            assertFalse(executor.isTerminated());
            assertFalse(executor.awaitTermination(Duration.ofMillis(10)), "the handler half still runs");
        } finally {
            executor.shutdownNow();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("executionModels")
    @DisplayName("A cancelled timing task leaves nothing behind")
    void aCancelledTimingTaskLeavesNothingBehind(
        final String model,
        final IntFunction<ZenBpmExecutor> executionModel
    ) {
        final var executor = executionModel.apply(1);
        try {
            executor
                    .timing()
                    .schedule(() -> {
                    }, 1, TimeUnit.HOURS)
                    .cancel(false);

            assertEquals(List.of(), executor.shutdownNow(), "the cancelled task is gone from the queue");
        } finally {
            executor.shutdownNow();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("executionModels")
    @DisplayName("The timing threads cannot be resized by whoever receives them")
    void theTimingThreadsCannotBeResized(
        final String model,
        final IntFunction<ZenBpmExecutor> executionModel
    ) {
        final var executor = executionModel.apply(1);
        try {
            assertFalse(executor.timing() instanceof ScheduledThreadPoolExecutor);
        } finally {
            executor.shutdownNow();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("executionModels")
    @DisplayName("A bound below one is refused with the adapter id")
    void aBoundBelowOneIsRefused(
        final String model,
        final IntFunction<ZenBpmExecutor> executionModel
    ) {
        final var exception = assertThrows(IllegalArgumentException.class, () -> executionModel.apply(0));

        assertEquals("ZenBPM[orders]: an executor needs at least one slot, not 0", exception.getMessage());
    }

    @Test
    @DisplayName("Building an executor starts no thread, the first use does")
    void buildingStartsNoThread() throws Exception {
        // an adapter id of its own, so no thread of another test is counted
        final var platform = new ZenBpmPlatformThreadExecutor("built", 2);
        final var virtual = new ZenBpmVirtualThreadExecutor("built", 2);
        try {
            assertEquals(0, threadsNamed("zenbpm-built-"), "no thread before the first use");

            platform
                    .timing()
                    .submit(() -> {
                    })
                    .get(UNTIL_THE_EXECUTOR_COUNTS_AS_STUCK, TimeUnit.MILLISECONDS);

            assertEquals(1, threadsNamed("zenbpm-built-timing-"), "the first timing task starts one");
        } finally {
            platform.shutdownNow();
            virtual.shutdownNow();
        }
    }

    @Test
    @DisplayName("An execution model builds the executor it names")
    void aModelBuildsItsExecutor() {
        final var platform = ZenBpmExecutor.of(ADAPTER_ID, new ZenBpmExecutionModel(false, 3));
        final var virtual = ZenBpmExecutor.of(ADAPTER_ID, new ZenBpmExecutionModel(true, 5));
        try {
            assertInstanceOf(ZenBpmPlatformThreadExecutor.class, platform);
            assertEquals(3, platform.getBound());
            assertInstanceOf(ZenBpmVirtualThreadExecutor.class, virtual);
            assertEquals(5, virtual.getBound());
        } finally {
            platform.shutdownNow();
            virtual.shutdownNow();
        }
    }

    /**
     * The two models, each as a way of building an executor of a given bound.
     *
     * @return The models under test, named after the value which configures them
     */
    static Stream<Arguments> executionModels() {
        return Stream.of(
                Arguments.of("worker-threads: <number>",
                        (IntFunction<ZenBpmExecutor>) bound -> new ZenBpmPlatformThreadExecutor(
                                ADAPTER_ID, bound)),
                Arguments.of("worker-threads: virtual",
                        (IntFunction<ZenBpmExecutor>) bound -> new ZenBpmVirtualThreadExecutor(
                                ADAPTER_ID, bound)));
    }

    /**
     * Fills every handler thread with a handler which stays inside until the latch is counted down.
     *
     * @return The names of the threads the handlers run on
     */
    private static Set<String> takeEveryHandlerThread(
        final ZenBpmExecutor executor,
        final int bound,
        final CountDownLatch release
    ) throws InterruptedException {
        final var names = ConcurrentHashMap.<String>newKeySet();
        final var everyThreadTaken = new CountDownLatch(bound);
        for (int job = 0; job < bound; job++) {
            executor.execute(() -> {
                recordThread(names, everyThreadTaken);
                awaitQuietly(release);
            });
        }
        assertTrue(everyThreadTaken.await(UNTIL_THE_EXECUTOR_COUNTS_AS_STUCK, TimeUnit.MILLISECONDS),
                "the handlers took every thread");
        return names;
    }

    /**
     * Counts the live platform threads whose name starts with the prefix.
     */
    private static long threadsNamed(final String prefix) {
        return Thread
                .getAllStackTraces()
                .keySet()
                .stream()
                .filter(Thread::isAlive)
                .filter(thread -> thread
                        .getName()
                        .startsWith(prefix))
                .count();
    }

    private static void recordThread(
        final Set<String> names,
        final CountDownLatch ran
    ) {
        names.add(Thread
                .currentThread()
                .getName());
        ran.countDown();
    }

    private static void awaitQuietly(final CountDownLatch release) {
        try {
            // the handlers stay inside until the test lets them out, so what the test reads
            // meanwhile happens while they are there
            release.await(UNTIL_THE_EXECUTOR_COUNTS_AS_STUCK, TimeUnit.MILLISECONDS);
        } catch (final InterruptedException e) {
            Thread
                    .currentThread()
                    .interrupt();
        }
    }

}

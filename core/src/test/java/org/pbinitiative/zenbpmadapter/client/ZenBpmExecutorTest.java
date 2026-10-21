/*
 * Copyright 2026 Phactum Softwareentwicklung GmbH
 * Adapted for zenbpm-vanillabp-adapter from vanillabp/camunda8-adapter, see NOTICE.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.pbinitiative.zenbpmadapter.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.pbinitiative.zenbpmadapter.client.ContractFixtures.eventually;

import java.io.IOException;
import java.time.Duration;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
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
 * What both execution models promise, asserted against each of them: slots which hold, a
 * submission which waits or is refused instead of queuing, timing threads which no handler
 * occupies, and a shutdown which leaves the timing threads to the handlers still running.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmExecutorTest {

    private static final String HANDLER_THREW = "ZenBPM[orders]: a handler threw instead of handling its failure itself, which is a defect of the adapter: report it with this stack trace";

    private static final String LISTENER_THREW = "ZenBPM[orders]: the listener of freed slots threw while it handed the next queued job on, which is a defect of the adapter: report it with this stack trace";

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
    @DisplayName("More submissions than slots make the submitter wait, and nothing is queued")
    void moreSubmissionsThanSlotsBlockTheSubmitter(
        final String model,
        final IntFunction<ZenBpmExecutor> executionModel
    ) throws Exception {
        final var slots = 2;
        final var executor = executionModel.apply(slots);
        try {
            final var running = new AtomicInteger();
            final var peak = new AtomicInteger();
            final var release = new CountDownLatch(1);
            final var everySlotTaken = new CountDownLatch(slots);
            final var finished = new CountDownLatch(slots + 1);
            final var submitted = new AtomicInteger();
            final var submitter = Thread
                    .ofPlatform()
                    .start(() -> {
                        for (int job = 0; job < slots + 1; job++) {
                            executor.execute(() -> {
                                peak.accumulateAndGet(running.incrementAndGet(), Math::max);
                                everySlotTaken.countDown();
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

            assertTrue(everySlotTaken.await(UNTIL_THE_EXECUTOR_COUNTS_AS_STUCK, TimeUnit.MILLISECONDS),
                    "every slot is used");
            eventually(() -> Optional
                    .of(submitter.getState())
                    .filter(Thread.State.TIMED_WAITING::equals));
            assertEquals(slots, submitted.get(), "the submission beyond the slots has not returned");
            assertTrue(submitter.isAlive(), "the submitter waits inside execute");
            assertEquals(slots, running.get(), "nothing beyond the slots runs");

            release.countDown();
            assertTrue(finished.await(UNTIL_THE_EXECUTOR_COUNTS_AS_STUCK, TimeUnit.MILLISECONDS),
                    "the waiting handler runs once a slot is free");
            submitter.join(UNTIL_THE_EXECUTOR_COUNTS_AS_STUCK);
            assertEquals(slots + 1, submitted.get());
            assertEquals(slots, peak.get(), "never more handlers at once than there are slots");
        } finally {
            executor.shutdownNow();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("executionModels")
    @DisplayName("tryExecute answers false at once where every slot is taken, whoever took them")
    void tryExecuteNeverWaits(
        final String model,
        final IntFunction<ZenBpmExecutor> executionModel
    ) throws Exception {
        final var executor = executionModel.apply(2);
        final var release = new CountDownLatch(1);
        try {
            assertEquals(2, executor.getSlots());
            assertEquals(2, executor.getFreeSlots());
            assertEquals(0, executor.getBusySlots());
            // one slot taken by each way of submitting: a handler submitted with execute is
            // counted in the same slots as one submitted with tryExecute
            takeEverySlot(executor, 1, release);
            final var inside = new CountDownLatch(1);
            assertTrue(executor.tryExecute(() -> {
                inside.countDown();
                awaitQuietly(release);
            }));
            assertTrue(inside.await(UNTIL_THE_EXECUTOR_COUNTS_AS_STUCK, TimeUnit.MILLISECONDS));
            assertEquals(0, executor.getFreeSlots());
            assertEquals(2, executor.getBusySlots());

            final var ran = new AtomicInteger();
            assertFalse(executor.tryExecute(ran::incrementAndGet), "no third slot, and no wait for one");

            release.countDown();
            eventually(() -> Optional
                    .of(executor.getFreeSlots())
                    .filter(free -> free == 2));
            assertEquals(0, ran.get(), "the refused handler never ran");
            final var later = new CountDownLatch(1);
            assertTrue(executor.tryExecute(later::countDown), "the slots came back once the handlers returned");
            assertTrue(later.await(UNTIL_THE_EXECUTOR_COUNTS_AS_STUCK, TimeUnit.MILLISECONDS));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("executionModels")
    @DisplayName("The two timing threads run at the same time while every slot is taken")
    void theTimingIsNotStarvedByBusyHandlers(
        final String model,
        final IntFunction<ZenBpmExecutor> executionModel
    ) throws Exception {
        final var slots = 2;
        final var executor = executionModel.apply(slots);
        final var release = new CountDownLatch(1);
        final var bothInside = new CountDownLatch(2);
        try {
            final var handlerThreads = takeEverySlot(executor, slots, release);

            final var timingThreads = ConcurrentHashMap.<String>newKeySet();
            executor
                    .timing()
                    .schedule(() -> stayUntilBothAreInside(timingThreads, bothInside), 1, TimeUnit.MILLISECONDS);
            executor
                    .timing()
                    .execute(() -> stayUntilBothAreInside(timingThreads, bothInside));

            // each task stays until the other one entered, so a single timing thread would never
            // let the second one in
            assertTrue(bothInside.await(UNTIL_THE_EXECUTOR_COUNTS_AS_STUCK, TimeUnit.MILLISECONDS),
                    "two timing tasks ran at the same time while every handler was inside");
            assertEquals(slots, executor.getBusySlots(), "the handlers were still inside");
            assertEquals(Set.of("zenbpm-orders-timing-0", "zenbpm-orders-timing-1"), timingThreads);
            assertTrue(handlerThreads
                    .stream()
                    .allMatch(name -> name.startsWith("zenbpm-orders-handler-")), handlerThreads::toString);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("executionModels")
    @DisplayName("A dispatcher told about freed slots runs every job on handler threads, one per slot")
    void aDispatcherToldAboutFreedSlotsRunsEveryJob(
        final String model,
        final IntFunction<ZenBpmExecutor> executionModel
    ) throws Exception {
        final var slots = 2;
        final var executor = executionModel.apply(slots);
        try {
            final var jobs = 40;
            final var done = new CountDownLatch(jobs);
            final var running = new AtomicInteger();
            final var peak = new AtomicInteger();
            final var handlerThreads = ConcurrentHashMap.<String>newKeySet();
            final var dispatcher = new WaitingJobs(executor, () -> {
            });
            executor.onSlotFreed(dispatcher::dispatch);

            for (int job = 0; job < jobs; job++) {
                dispatcher.add(() -> {
                    peak.accumulateAndGet(running.incrementAndGet(), Math::max);
                    recordThread(handlerThreads, done);
                    running.decrementAndGet();
                });
            }

            assertTrue(done.await(UNTIL_THE_EXECUTOR_COUNTS_AS_STUCK, TimeUnit.MILLISECONDS), "every job ran");
            assertEquals(List.of(), dispatcher.refusals, "no hand-over was refused");
            assertTrue(peak.get() <= slots, "never more handlers at once than there are slots");
            assertTrue(handlerThreads
                    .stream()
                    .allMatch(name -> name.startsWith("zenbpm-orders-handler-")), handlerThreads::toString);
            eventually(() -> Optional
                    .of(executor.getFreeSlots())
                    .filter(free -> free == slots));
        } finally {
            executor.shutdownNow();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("executionModels")
    @DisplayName("A job put back after a refusal runs although its slot came free before it was back")
    void aJobPutBackAfterARefusalIsNotStranded(
        final String model,
        final IntFunction<ZenBpmExecutor> executionModel
    ) throws Exception {
        final var executor = executionModel.apply(1);
        final var release = new CountDownLatch(1);
        try {
            final var listenerDone = new CountDownLatch(1);
            // the window the rule of the dispatcher closes: the only slot comes free, and its
            // listener finds nothing waiting, while the refused job is still on its way back
            final var dispatcher = new WaitingJobs(executor, () -> {
                release.countDown();
                awaitQuietly(listenerDone);
            });
            executor.onSlotFreed(() -> {
                dispatcher.dispatch();
                listenerDone.countDown();
            });
            assertTrue(executor.tryExecute(() -> awaitQuietly(release)));
            final var second = new CountDownLatch(1);

            dispatcher.add(second::countDown);

            assertTrue(second.await(UNTIL_THE_EXECUTOR_COUNTS_AS_STUCK, TimeUnit.MILLISECONDS),
                    "the job ran once it was back, although no slot was freed after that");
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("executionModels")
    @DisplayName("A handler cannot wait for a slot of its own threads, but it may try for one")
    void aHandlerCannotWaitForASlot(
        final String model,
        final IntFunction<ZenBpmExecutor> executionModel
    ) throws Exception {
        final var executor = executionModel.apply(1);
        try {
            final var refusal = new AtomicReference<Exception>();
            final var tried = new AtomicReference<Boolean>();
            final var ran = new CountDownLatch(1);
            executor.execute(() -> {
                try {
                    tried.set(executor.tryExecute(() -> {
                    }));
                    executor.execute(() -> {
                    });
                } catch (final IllegalStateException e) {
                    refusal.set(e);
                } finally {
                    ran.countDown();
                }
            });

            assertTrue(ran.await(UNTIL_THE_EXECUTOR_COUNTS_AS_STUCK, TimeUnit.MILLISECONDS));
            assertFalse(tried.get(), "the only slot is the handler's own, and trying does not wait for it");
            assertInstanceOf(IllegalStateException.class, refusal.get());
            assertTrue(refusal
                    .get()
                    .getMessage()
                    .startsWith("ZenBPM[orders]: a handler waited for a slot of the threads it runs on"),
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
    @DisplayName("A handler which throws is logged, a checked exception included, and its slot comes back")
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
            executor.execute(() -> SneakyThrow.sneakily(new IOException("checked failure")));
            final var next = new CountDownLatch(1);
            executor.execute(next::countDown);

            assertTrue(next.await(UNTIL_THE_EXECUTOR_COUNTS_AS_STUCK, TimeUnit.MILLISECONDS));
            eventually(() -> Optional
                    .of(output.getAllOfThisTest())
                    .filter(printed -> printed.contains(HANDLER_THREW))
                    .filter(printed -> printed.contains("broken handler"))
                    .filter(printed -> printed.contains("java.io.IOException: checked failure")));
        } finally {
            executor.shutdownNow();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("executionModels")
    @DisplayName("A listener of freed slots which throws is logged; one refused by a shutdown is not an error")
    void aThrowingListenerIsLogged(
        final String model,
        final IntFunction<ZenBpmExecutor> executionModel,
        final CapturedOutput output
    ) throws Exception {
        final var executor = executionModel.apply(1);
        try {
            final var told = new CountDownLatch(2);
            executor.onSlotFreed(() -> {
                told.countDown();
                throw told.getCount() == 1
                        ? new IllegalStateException("broken listener")
                        : new RejectedExecutionException("refused hand-over");
            });
            executor.execute(() -> {
            });
            executor.execute(() -> {
            });

            assertTrue(told.await(UNTIL_THE_EXECUTOR_COUNTS_AS_STUCK, TimeUnit.MILLISECONDS));
            eventually(() -> Optional
                    .of(output.getAllOfThisTest())
                    .filter(printed -> printed.contains(LISTENER_THREW))
                    .filter(printed -> printed.contains("broken listener")));
            assertEquals(1, output
                    .getAllOfThisTest()
                    .split("the listener of freed slots threw", -1).length - 1,
                    "only the broken listener is an error, the refused hand-over is not");
            eventually(() -> Optional
                    .of(executor.getFreeSlots())
                    .filter(free -> free == 1));
        } finally {
            executor.shutdownNow();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("executionModels")
    @DisplayName("A listener of freed slots which throws a checked exception sneakily is logged")
    void aSneakyListenerIsLogged(
        final String model,
        final IntFunction<ZenBpmExecutor> executionModel,
        final CapturedOutput output
    ) throws Exception {
        final var executor = executionModel.apply(1);
        try {
            executor.onSlotFreed(() -> SneakyThrow.sneakily(new IOException("listener failure")));
            executor.execute(() -> {
            });

            eventually(() -> Optional
                    .of(output.getAllOfThisTest())
                    .filter(printed -> printed.contains(LISTENER_THREW))
                    .filter(printed -> printed.contains("java.io.IOException: listener failure")));
        } finally {
            executor.shutdownNow();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("executionModels")
    @DisplayName("A handler which throws an Error is logged with the adapter id, and the Error goes on")
    void aHandlerErrorIsLoggedAndGoesOn(
        final String model,
        final IntFunction<ZenBpmExecutor> executionModel,
        final CapturedOutput output
    ) throws Exception {
        final var executor = executionModel.apply(1);
        try {
            executor.execute(() -> {
                throw new StackOverflowError("too deep");
            });
            final var next = new CountDownLatch(1);
            executor.execute(next::countDown);

            assertTrue(next.await(UNTIL_THE_EXECUTOR_COUNTS_AS_STUCK, TimeUnit.MILLISECONDS), "the slot came back");
            eventually(() -> Optional
                    .of(output.getAllOfThisTest())
                    .filter(printed -> printed.contains(HANDLER_THREW))
                    .filter(printed -> printed.contains("java.lang.StackOverflowError: too deep")));
        } finally {
            executor.shutdownNow();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("executionModels")
    @DisplayName("A timing thread cannot wait for a slot, but it may try for one")
    void aTimingThreadCannotWaitForASlot(
        final String model,
        final IntFunction<ZenBpmExecutor> executionModel
    ) throws Exception {
        final var executor = executionModel.apply(1);
        try {
            final var waiting = executor
                    .timing()
                    .submit(() -> {
                        executor.execute(() -> {
                        });
                        return null;
                    });
            final var trying = executor
                    .timing()
                    .submit(() -> executor.tryExecute(() -> {
                    }));

            final var refusal = assertThrows(ExecutionException.class, () -> waiting
                    .get(UNTIL_THE_EXECUTOR_COUNTS_AS_STUCK, TimeUnit.MILLISECONDS));
            assertInstanceOf(IllegalStateException.class, refusal.getCause());
            assertEquals(
                    "ZenBPM[orders]: a timing thread waited for a slot, which holds the job stream back for as long as a handler runs; use tryExecute there",
                    refusal
                            .getCause()
                            .getMessage());
            assertTrue(trying.get(UNTIL_THE_EXECUTOR_COUNTS_AS_STUCK, TimeUnit.MILLISECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("executionModels")
    @DisplayName("An interrupted submitter which finds a free slot is not refused, and keeps its interrupt")
    void anInterruptedSubmitterWithAFreeSlotIsServed(
        final String model,
        final IntFunction<ZenBpmExecutor> executionModel
    ) throws Exception {
        final var executor = executionModel.apply(1);
        try {
            final var ran = new CountDownLatch(1);
            final var interrupted = new AtomicReference<Boolean>();
            final var submitter = Thread
                    .ofPlatform()
                    .start(() -> {
                        Thread
                                .currentThread()
                                .interrupt();
                        executor.execute(ran::countDown);
                        interrupted.set(Thread
                                .currentThread()
                                .isInterrupted());
                    });

            submitter.join(UNTIL_THE_EXECUTOR_COUNTS_AS_STUCK);
            assertTrue(ran.await(UNTIL_THE_EXECUTOR_COUNTS_AS_STUCK, TimeUnit.MILLISECONDS), "nothing was waited for");
            assertTrue(interrupted.get(), "the interrupt is kept");
        } finally {
            executor.shutdownNow();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("executionModels")
    @DisplayName("A wait for the end with a timeout of a thousand years answers")
    void aVeryLongWaitForTheEndAnswers(
        final String model,
        final IntFunction<ZenBpmExecutor> executionModel
    ) throws Exception {
        final var executor = executionModel.apply(1);
        executor.shutdown();

        assertTrue(executor.awaitTermination(Duration.ofDays(365L * 1000)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("executionModels")
    @DisplayName("No handler is refused later than at once: null fails without waiting or taking a slot")
    void aMissingHandlerIsRefusedAtOnce(
        final String model,
        final IntFunction<ZenBpmExecutor> executionModel
    ) throws Exception {
        final var executor = executionModel.apply(1);
        final var release = new CountDownLatch(1);
        try {
            takeEverySlot(executor, 1, release);

            assertThrows(NullPointerException.class, () -> executor.execute(null), "no wait for a slot first");
            assertThrows(NullPointerException.class, () -> executor.tryExecute(null));

            release.countDown();
            eventually(() -> Optional
                    .of(executor.getFreeSlots())
                    .filter(free -> free == 1));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("executionModels")
    @DisplayName("A submission waiting for a slot is refused when the executor shuts down")
    void aWaitingSubmissionIsRefusedAtShutdown(
        final String model,
        final IntFunction<ZenBpmExecutor> executionModel
    ) throws Exception {
        final var executor = executionModel.apply(1);
        final var release = new CountDownLatch(1);
        try {
            takeEverySlot(executor, 1, release);
            final var refusal = new AtomicReference<RuntimeException>();
            final var ran = new AtomicInteger();
            final var submitter = Thread
                    .ofPlatform()
                    .start(() -> {
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
            // checks for a shutdown every 100 ms
            submitter.join(5_000);
            assertInstanceOf(RejectedExecutionException.class, refusal.get(), "refused while still waiting");
            assertEquals("ZenBPM[orders]: the adapter shuts down, so the handler is not run", refusal
                    .get()
                    .getMessage());
            assertNull(refusal
                    .get()
                    .getCause(), "refused by the wait, not by the handler threads once a slot was free");
            release.countDown();
            assertTrue(executor.awaitTermination(Duration.ofMillis(UNTIL_THE_EXECUTOR_COUNTS_AS_STUCK)));
            assertEquals(0, ran.get(), "the refused handler never ran");
        } finally {
            release.countDown();
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
        final var release = new CountDownLatch(1);
        try {
            takeEverySlot(executor, 1, release);
            final var refusal = new AtomicReference<RuntimeException>();
            final var interrupted = new AtomicReference<Boolean>();
            final var submitter = Thread
                    .ofPlatform()
                    .start(() -> {
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
            assertEquals("ZenBPM[orders]: interrupted while the handler waited for a slot", refusal
                    .get()
                    .getMessage());
            assertInstanceOf(InterruptedException.class, refusal
                    .get()
                    .getCause());
            assertTrue(interrupted.get(), "the interrupt is kept");
            release.countDown();
            final var later = new CountDownLatch(1);
            executor.execute(later::countDown);
            assertTrue(later.await(UNTIL_THE_EXECUTOR_COUNTS_AS_STUCK, TimeUnit.MILLISECONDS),
                    "the refused submission took no slot with it");
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("executionModels")
    @DisplayName("A submission after the shutdown is refused and takes no slot with it")
    void aSubmissionAfterTheShutdownIsRefused(
        final String model,
        final IntFunction<ZenBpmExecutor> executionModel
    ) throws Exception {
        final var executor = executionModel.apply(1);
        executor.shutdown();

        final var waiting = assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> {
        }));
        final var trying = assertThrows(RejectedExecutionException.class, () -> executor.tryExecute(() -> {
        }));

        assertEquals("ZenBPM[orders]: the adapter shuts down, so the handler is not run", waiting.getMessage());
        assertInstanceOf(RejectedExecutionException.class, waiting.getCause(), "the handler threads refused it");
        assertEquals("ZenBPM[orders]: the adapter shuts down, so the handler is not run", trying.getMessage());
        assertEquals(1, executor.getFreeSlots(), "the refused handler gave its slot back");
        assertTrue(executor.awaitTermination(Duration.ofMillis(UNTIL_THE_EXECUTOR_COUNTS_AS_STUCK)));
        assertTrue(executor.isShutdown());
        assertTrue(executor.isTerminated());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("executionModels")
    @DisplayName("After shutdown the timing threads serve the handlers still running, then stop")
    void theTimingOutlivesTheHandlers(
        final String model,
        final IntFunction<ZenBpmExecutor> executionModel,
        final CapturedOutput output
    ) throws Exception {
        final var executor = executionModel.apply(1);
        final var release = new CountDownLatch(1);
        try {
            final var inside = new CountDownLatch(1);
            final var handedBack = new CountDownLatch(1);
            final var told = new AtomicInteger();
            executor.onSlotFreed(told::incrementAndGet);
            // a handler as the dispatcher's will be: it hands something to the timing threads
            // once it is done
            executor.execute(() -> {
                inside.countDown();
                awaitQuietly(release);
                executor
                        .timing()
                        .execute(handedBack::countDown);
            });
            assertTrue(inside.await(UNTIL_THE_EXECUTOR_COUNTS_AS_STUCK, TimeUnit.MILLISECONDS));

            executor.shutdown();

            assertTrue(executor.isShutdown());
            assertFalse(executor.isTerminated());
            assertFalse(executor
                    .timing()
                    .isShutdown(), "the timing threads still run for the handler inside");
            assertThrows(RejectedExecutionException.class, () -> executor.tryExecute(() -> {
            }), "refused, not merely answered false because the only slot is taken");
            release.countDown();
            assertTrue(handedBack.await(UNTIL_THE_EXECUTOR_COUNTS_AS_STUCK, TimeUnit.MILLISECONDS),
                    "the handler handed something to the timing threads after the shutdown");
            assertTrue(executor.awaitTermination(Duration.ofMillis(UNTIL_THE_EXECUTOR_COUNTS_AS_STUCK)));
            assertTrue(executor
                    .timing()
                    .isTerminated(), "the timing threads stopped after the last handler");
            assertEquals(0, told.get(), "nobody is told about a slot nothing may use any longer");
            assertFalse(output
                    .getAllOfThisTest()
                    .contains("ERROR"), output.getAllOfThisTest());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("executionModels")
    @DisplayName("A timing task due later does not hold the shutdown up, one which runs does")
    void aDelayedTimingTaskDoesNotHoldTheShutdownUp(
        final String model,
        final IntFunction<ZenBpmExecutor> executionModel
    ) throws Exception {
        final var executor = executionModel.apply(1);
        final var release = new CountDownLatch(1);
        try {
            final var dueLater = executor
                    .timing()
                    .schedule(() -> {
                    }, 1, TimeUnit.HOURS);
            final var inside = new CountDownLatch(1);
            executor
                    .timing()
                    .execute(() -> {
                        inside.countDown();
                        awaitQuietly(release);
                    });
            assertTrue(inside.await(UNTIL_THE_EXECUTOR_COUNTS_AS_STUCK, TimeUnit.MILLISECONDS));

            executor.shutdown();

            assertFalse(executor.awaitTermination(Duration.ofMillis(200)), "the running timing task is waited for");
            release.countDown();
            assertTrue(executor.awaitTermination(Duration.ofMillis(UNTIL_THE_EXECUTOR_COUNTS_AS_STUCK)),
                    "the task due in an hour is not");
            assertTrue(dueLater.isCancelled());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("executionModels")
    @DisplayName("shutdownNow interrupts the handlers and hands back the timing tasks which never ran")
    void shutdownNowInterruptsAndHandsBack(
        final String model,
        final IntFunction<ZenBpmExecutor> executionModel
    ) throws Exception {
        final var executor = executionModel.apply(1);
        final var neverReleased = new CountDownLatch(1);
        try {
            final var interrupted = new CountDownLatch(1);
            final var started = new CountDownLatch(1);
            executor.execute(() -> {
                started.countDown();
                try {
                    neverReleased.await();
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
        } finally {
            neverReleased.countDown();
            executor.shutdownNow();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("executionModels")
    @DisplayName("Whoever receives the timing threads can neither shut them down nor resize them")
    void theTimingThreadsStayWithTheExecutor(
        final String model,
        final IntFunction<ZenBpmExecutor> executionModel
    ) throws Exception {
        final var executor = executionModel.apply(1);
        try {
            final var timing = executor.timing();

            final var refusal = assertThrows(UnsupportedOperationException.class, timing::shutdown);
            assertThrows(UnsupportedOperationException.class, timing::shutdownNow);
            assertThrows(UnsupportedOperationException.class, () -> {
                try (var closing = executor.timing()) {
                    closing.execute(() -> {
                    });
                }
            }, "not by try-with-resources either");

            assertEquals("ZenBPM[orders]: the timing threads stop with the executor which owns them, not before",
                    refusal.getMessage());
            assertFalse(timing.isShutdown());
            assertFalse(timing instanceof ThreadPoolExecutor, "no resizing");
            assertTrue(timing
                    .submit(() -> true)
                    .get(UNTIL_THE_EXECUTOR_COUNTS_AS_STUCK, TimeUnit.MILLISECONDS), "the timing threads still work");
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
    @DisplayName("Fewer than one slot is refused with the adapter id")
    void fewerThanOneSlotIsRefused(
        final String model,
        final IntFunction<ZenBpmExecutor> executionModel
    ) {
        final var exception = assertThrows(IllegalArgumentException.class, () -> executionModel.apply(0));

        assertEquals("ZenBPM[orders]: an executor needs at least one slot, not 0", exception.getMessage());
    }

    @Test
    @DisplayName("A hand-over which a shutdown overtook still stops the timing threads once it is refused")
    void aHandOverOvertakenByTheShutdownStillStopsTheTiming() throws Exception {
        final var owner = new AtomicReference<ZenBpmExecutor>();
        // handler threads which see the shutdown arrive between the slot taken and the hand-over,
        // the moment no other test can reach: shutdown() finds the slot taken and leaves the
        // timing threads to whoever gives it back
        final var handlerThreads = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>()) {

            @Override
            public void execute(final Runnable command) {
                owner
                        .get()
                        .shutdown();
                super.execute(command);
            }

        };
        owner.set(new ZenBpmExecutor("overtaken", 1, handlerThreads) {
        });

        assertThrows(RejectedExecutionException.class, () -> owner
                .get()
                .tryExecute(() -> {
                }));

        assertEquals(1, owner
                .get()
                .getFreeSlots(), "the slot came back");
        assertTrue(owner
                .get()
                .awaitTermination(Duration.ofSeconds(5)), "the timing threads stopped with it");
    }

    @Test
    @DisplayName("shutdownNow hands back the timing tasks although the last handler ended during the call")
    void shutdownNowKeepsTheTimingTasksOfAHandlerEndingMeanwhile() throws Exception {
        // handler threads whose shutdownNow returns only after the interrupted handler ended, the
        // moment in which the handler gave its slot back while the timing threads still ran
        final var handlerThreads = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>()) {

            @Override
            public List<Runnable> shutdownNow() {
                final var pending = super.shutdownNow();
                try {
                    awaitTermination(UNTIL_THE_EXECUTOR_COUNTS_AS_STUCK, TimeUnit.MILLISECONDS);
                } catch (final InterruptedException e) {
                    Thread
                            .currentThread()
                            .interrupt();
                }
                return pending;
            }

        };
        final var executor = new ZenBpmExecutor("now", 1, handlerThreads) {
        };
        final var started = new CountDownLatch(1);
        final var neverReleased = new CountDownLatch(1);
        try {
            executor.execute(() -> {
                started.countDown();
                awaitQuietly(neverReleased);
            });
            assertTrue(started.await(UNTIL_THE_EXECUTOR_COUNTS_AS_STUCK, TimeUnit.MILLISECONDS));
            final var dueLater = executor
                    .timing()
                    .schedule(() -> {
                    }, 1, TimeUnit.HOURS);

            final var pending = executor.shutdownNow();

            assertEquals(0, executor.getBusySlots(), "the handler ended during the call");
            assertEquals(1, pending.size(), "the timing task due in an hour");
            assertFalse(dueLater.isCancelled(), "handed back, not cancelled by an orderly stop");
        } finally {
            neverReleased.countDown();
            executor.shutdownNow();
        }
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
            assertEquals(3, platform.getSlots());
            assertInstanceOf(ZenBpmVirtualThreadExecutor.class, virtual);
            assertEquals(5, virtual.getSlots());
        } finally {
            platform.shutdownNow();
            virtual.shutdownNow();
        }
    }

    /**
     * The two models, each as a way of building an executor with a given number of slots.
     *
     * @return The models under test, named after the value which configures them
     */
    static Stream<Arguments> executionModels() {
        return Stream.of(
                Arguments.of("worker-threads: <number>",
                        (IntFunction<ZenBpmExecutor>) slots -> new ZenBpmPlatformThreadExecutor(
                                ADAPTER_ID, slots)),
                Arguments.of("worker-threads: virtual",
                        (IntFunction<ZenBpmExecutor>) slots -> new ZenBpmVirtualThreadExecutor(
                                ADAPTER_ID, slots)));
    }

    /**
     * Fills slots with handlers which stay inside until the latch is counted down.
     *
     * @return The names of the threads the handlers run on
     */
    private static Set<String> takeEverySlot(
        final ZenBpmExecutor executor,
        final int slots,
        final CountDownLatch release
    ) throws InterruptedException {
        final var names = ConcurrentHashMap.<String>newKeySet();
        final var everySlotTaken = new CountDownLatch(slots);
        for (int job = 0; job < slots; job++) {
            executor.execute(() -> {
                recordThread(names, everySlotTaken);
                awaitQuietly(release);
            });
        }
        assertTrue(everySlotTaken.await(UNTIL_THE_EXECUTOR_COUNTS_AS_STUCK, TimeUnit.MILLISECONDS),
                "the handlers took the slots");
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

    private static void stayUntilBothAreInside(
        final Set<String> names,
        final CountDownLatch bothInside
    ) {
        recordThread(names, bothInside);
        awaitQuietly(bothInside);
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

    /**
     * The dispatcher's rule in miniature: a job is taken only with a free slot, and a job refused
     * one is put back BEFORE the dispatcher looks for a free slot once more. A slot which comes free
     * meanwhile is then either seen by that look, or its listener, which runs after the slot is back,
     * sees the job.
     */
    static final class WaitingJobs {

        final List<Throwable> refusals = new CopyOnWriteArrayList<>();

        private final ZenBpmExecutor executor;

        private final Deque<Runnable> jobs = new ConcurrentLinkedDeque<>();

        private final Runnable afterARefusal;

        /**
         * @param afterARefusal What runs between a refusal and the job put back, for a test which
         *            opens that window
         */
        WaitingJobs(
            final ZenBpmExecutor executor,
            final Runnable afterARefusal
        ) {
            this.executor = executor;
            this.afterARefusal = afterARefusal;
        }

        void add(final Runnable job) {
            jobs.addLast(job);
            dispatch();
        }

        void dispatch() {
            try {
                Runnable job;
                while ((job = jobs.pollFirst()) != null) {
                    if (executor.tryExecute(job)) {
                        continue;
                    }
                    afterARefusal.run();
                    jobs.addFirst(job);
                    if (executor.getFreeSlots() == 0) {
                        return;
                    }
                }
            } catch (final RuntimeException e) {
                refusals.add(e);
            }
        }

    }

}

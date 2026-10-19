/*
 * Copyright 2026 Phactum Softwareentwicklung GmbH
 * Adapted for zenbpm-vanillabp-adapter from vanillabp/camunda8-adapter, see NOTICE.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.pbinitiative.zenbpmadapter.client;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import lombok.extern.slf4j.Slf4j;

/**
 * The threads of one ZenBPM adapter id, whatever the execution model is. It keeps two roles
 * apart, and it holds the slots which limit the handlers.
 * <p>
 * <b>The timing threads.</b> {@link #timing()} is two platform threads named
 * <code>zenbpm-&lt;id&gt;-timing-N</code>, for everything which waits or repeats, and no handler
 * can use them. The job stream hands every job over on them and opens its call again on them. What
 * runs there must not stay longer than a hand-over, so that a handler which blocks never delays a
 * reconnect. Their queue has no limit, so handing something to them never waits.
 * <p>
 * <b>The slots.</b> Every handler runs in a slot, and there are as many slots as the execution
 * model says. A slot is taken before the handler is handed to the handler threads, and given back
 * after it returned. {@link #tryExecute(Runnable)} takes a free slot or answers <code>false</code>
 * at once, so the dispatcher, which runs on the timing threads, and the user-task poller, whose looks
 * the timing threads start, never wait.
 * {@link #execute(Runnable)} waits for a slot in the submitting thread. Either way no queue of
 * handlers grows here: the jobs which wait for a slot stay with the dispatcher, which is told
 * through {@link #onSlotFreed(Runnable)} when it can hand over the next one.
 * <p>
 * <b>The handler threads.</b> The subclass builds them: <code>worker-threads</code> platform
 * threads, or a virtual thread per handler.
 * <p>
 * <b>The shutdown.</b> {@link #shutdown()} takes no new handler, and the handlers which run go on.
 * The timing threads keep running until the last of them returned, because a running handler may
 * still need them. Then they stop, and a timing task which is not due yet is dropped.
 * <p>
 * Camunda 8's executor holds the polls of its client back while every slot is busy. This one has no
 * such gate: the engine never hands a client more active jobs of one type than its subscription
 * allows, and the subscription asks for as many as there are slots, see decision 9 in the
 * repository's DECISIONS.md. With several job types more jobs than slots can arrive. They wait with
 * the dispatcher, whose handler renews the lock when it starts.
 */
@Slf4j
public abstract class ZenBpmExecutor {

    /**
     * How many platform threads do the timing. There are two, so a task which is due does not
     * wait for another one which is just being handed over.
     */
    static final int TIMING_THREADS = 2;

    /**
     * How often {@link #execute(Runnable)}, while it waits for a slot, checks whether the executor
     * is shutting down. A slot which is given back ends the wait at once; only a shutdown is found
     * this late.
     */
    static final Duration LOOK_FOR_A_SHUTDOWN_AGAIN = Duration.ofMillis(100);

    private static final Duration LONGEST_WAIT = Duration.ofNanos(Long.MAX_VALUE);

    private static final String SHUTS_DOWN = "ZenBPM[%s]: the adapter shuts down, so the handler is not run";

    private final String adapterId;

    private final int slots;

    private final TimingThreads timing;

    private final ExecutorService handling;

    private final Semaphore freeSlots;

    /**
     * Set on a handler thread once it ran one of this executor's handlers. Waiting for a slot
     * there could mean waiting for the thread itself.
     */
    private final ThreadLocal<Boolean> onAHandlerThread = ThreadLocal.withInitial(() -> false);

    private volatile Runnable slotFreed;

    private volatile boolean shuttingDown;

    /**
     * Set by {@link #shutdownNow()} before {@link #shuttingDown}, so a handler which sees the one
     * sees the other and leaves the timing threads to it: an orderly stop of its own would drop
     * the timing tasks which <code>shutdownNow</code> hands back.
     */
    private volatile boolean stoppingNow;

    /**
     * Builds the timing threads and the slots which every execution model has.
     *
     * @param adapterId The adapter id, used to name the threads
     * @param slots How many handlers may run at the same time
     * @param handling The handler threads; they are given at most one handler per slot, plus the
     *            ones whose thread is still on its way back from the handler before
     */
    protected ZenBpmExecutor(
        final String adapterId,
        final int slots,
        final ExecutorService handling
    ) {
        if (slots < 1) {
            throw new IllegalArgumentException("ZenBPM[%s]: an executor needs at least one slot, not %d"
                    .formatted(adapterId, slots));
        }
        this.adapterId = adapterId;
        this.slots = slots;
        this.handling = handling;
        this.freeSlots = new Semaphore(slots);
        this.timing = new TimingThreads(adapterId);
    }

    /**
     * Builds the executor of an execution model.
     *
     * @param adapterId The adapter id, used to name the threads
     * @param model The resolved execution model
     * @return Platform or virtual handler threads, as the model says
     */
    public static ZenBpmExecutor of(
        final String adapterId,
        final ZenBpmExecutionModel model
    ) {
        return model.virtual()
                ? new ZenBpmVirtualThreadExecutor(adapterId, model.slots())
                : new ZenBpmPlatformThreadExecutor(adapterId, model.slots());
    }

    /**
     * The timing threads, which no handler uses. Whoever receives them can neither resize them
     * nor shut them down: they stop with the executor, see {@link #shutdown()}.
     *
     * @return Two platform threads named <code>zenbpm-&lt;id&gt;-timing-N</code>
     */
    public ScheduledExecutorService timing() {
        return Executors.unconfigurableScheduledExecutorService(timing);
    }

    /**
     * How many handlers may run at the same time, whichever execution model this adapter id uses.
     *
     * @return The number of slots
     */
    public int getSlots() {
        return slots;
    }

    /**
     * How many slots are taken right now, by a handler which runs or is about to.
     *
     * @return Between zero and {@link #getSlots()}
     */
    public int getBusySlots() {
        return slots - freeSlots.availablePermits();
    }

    /**
     * How many slots are free right now.
     *
     * @return Between zero and {@link #getSlots()}
     */
    public int getFreeSlots() {
        return freeSlots.availablePermits();
    }

    /**
     * Who is told that a slot became free. It is called on the handler thread right after the
     * handler returned and its slot was given back, but not once the executor shuts down. It must
     * not wait; it may call {@link #tryExecute(Runnable)}. An exception it throws is logged. A
     * listener set before is replaced.
     * <p>
     * A dispatcher which keeps jobs for later puts a job <code>tryExecute</code> refused back
     * BEFORE it looks for a free slot once more, and tries again where it finds one. A slot which
     * comes free meanwhile is then either seen by that look, or its listener, which runs after the
     * slot is back, sees the job. The other way round, a job can wait beside a free slot with
     * nobody left to hand it over.
     *
     * @param listener Called once per freed slot
     */
    public void onSlotFreed(final Runnable listener) {
        this.slotFreed = listener;
    }

    /**
     * Runs a handler on a handler thread if a slot is free, without waiting. The slot is given back
     * once the handler returned. An exception the handler throws is logged.
     *
     * @param handler The handler
     * @return Whether the handler was handed over; <code>false</code> where every slot is taken
     * @throws RejectedExecutionException Where the executor shuts down
     */
    public boolean tryExecute(final Runnable handler) {
        Objects.requireNonNull(handler, "handler");
        if (shuttingDown) {
            throw new RejectedExecutionException(SHUTS_DOWN.formatted(adapterId));
        }
        if (!freeSlots.tryAcquire()) {
            return false;
        }
        handOver(handler);
        return true;
    }

    /**
     * Runs a handler on a handler thread, and waits for a free slot first where every slot is
     * taken. The slot is given back once the handler returned. An exception the handler throws is
     * logged.
     *
     * @param handler The handler
     * @throws RejectedExecutionException Where the executor shuts down, also while the submission
     *             waits, or where the submitting thread is interrupted while it waits for a slot
     * @throws IllegalStateException Where it is called on one of this executor's handler threads,
     *             which could wait for itself, or on one of its timing threads, which would hold
     *             every reconnect and hand-over back; {@link #tryExecute(Runnable)} may be called
     *             there
     */
    public void execute(final Runnable handler) {
        Objects.requireNonNull(handler, "handler");
        if (onAHandlerThread.get()) {
            throw new IllegalStateException("""
                    ZenBPM[%s]: a handler waited for a slot of the threads it runs on, which can wait \
                    for itself where every slot is taken; use tryExecute there""".formatted(adapterId));
        }
        if (timing.runsOn(Thread.currentThread())) {
            throw new IllegalStateException("""
                    ZenBPM[%s]: a timing thread waited for a slot, which holds the job stream back for \
                    as long as a handler runs; use tryExecute there""".formatted(adapterId));
        }
        waitForAFreeSlot();
        handOver(handler);
    }

    /**
     * Starts an orderly shutdown. No new handler is taken, a submission which waits for a slot is
     * refused, and the handlers which run go on. The timing threads stop once the last of them
     * returned; what is due by then still runs, a timing task due later is dropped.
     */
    public void shutdown() {
        shuttingDown = true;
        handling.shutdown();
        stopTheTimingOnceNoHandlerRuns();
    }

    /**
     * Shuts both the handler and the timing threads down at once, interrupting the handlers which
     * run. A handler which was handed over but never started is dropped, and its slot stays taken.
     *
     * @return The timing tasks which never ran
     */
    public List<Runnable> shutdownNow() {
        stoppingNow = true;
        shuttingDown = true;
        handling.shutdownNow();
        return timing.endNow();
    }

    /**
     * Whether {@link #shutdown()} or {@link #shutdownNow()} was called.
     *
     * @return Whether no new handler is taken
     */
    public boolean isShutdown() {
        return shuttingDown;
    }

    /**
     * Whether the handler and the timing threads ended after a shutdown.
     *
     * @return Whether nothing runs any longer
     */
    public boolean isTerminated() {
        return handling.isTerminated() && timing.isTerminated();
    }

    /**
     * Waits for the handler threads and then the timing threads to end after a shutdown.
     *
     * @param timeout How long to wait at most, for both together; anything beyond about 292 years
     *            counts as that long
     * @return Whether both ended in time
     * @throws InterruptedException Where the waiting thread is interrupted
     */
    public boolean awaitTermination(final Duration timeout) throws InterruptedException {
        final var timeoutNanos = timeout.compareTo(LONGEST_WAIT) >= 0 ? Long.MAX_VALUE : timeout.toNanos();
        final var start = System.nanoTime();
        final var handlersEnded = handling.awaitTermination(timeoutNanos, TimeUnit.NANOSECONDS);
        final var remaining = Math.max(0, timeoutNanos - (System.nanoTime() - start));
        return timing.awaitTermination(remaining, TimeUnit.NANOSECONDS) && handlersEnded;
    }

    private void waitForAFreeSlot() {
        // a free slot is taken at once, so a submitter which happens to be interrupted is not
        // refused for a wait it never had
        if (freeSlots.tryAcquire()) {
            return;
        }
        try {
            while (!freeSlots.tryAcquire(LOOK_FOR_A_SHUTDOWN_AGAIN.toNanos(), TimeUnit.NANOSECONDS)) {
                if (shuttingDown) {
                    throw new RejectedExecutionException(SHUTS_DOWN.formatted(adapterId));
                }
            }
        } catch (final InterruptedException e) {
            Thread
                    .currentThread()
                    .interrupt();
            throw new RejectedExecutionException(
                    "ZenBPM[%s]: interrupted while the handler waited for a slot".formatted(adapterId), e);
        }
    }

    private void handOver(final Runnable handler) {
        try {
            handling.execute(() -> runOnAHandlerThread(handler));
        } catch (final RejectedExecutionException e) {
            freeSlots.release();
            stopTheTimingOnceNoHandlerRuns();
            throw new RejectedExecutionException(SHUTS_DOWN.formatted(adapterId), e);
        }
    }

    private void runOnAHandlerThread(final Runnable handler) {
        // never taken back: a handler thread runs nothing but handlers
        onAHandlerThread.set(true);
        try {
            handler.run();
        } catch (final Exception e) {
            // Exception, not only RuntimeException: a handler can throw a checked one sneakily,
            // and it would end a platform thread unlogged
            log.error("ZenBPM[{}]: a handler threw", adapterId, e);
        } catch (final Error e) {
            // logged with the adapter id, and not swallowed: the thread ends, and the JVM decides
            log.error("ZenBPM[{}]: a handler threw", adapterId, e);
            throw e;
        } finally {
            freeSlots.release();
            if (shuttingDown) {
                stopTheTimingOnceNoHandlerRuns();
            } else {
                tellASlotWasFreed();
            }
        }
    }

    private void tellASlotWasFreed() {
        final var listener = slotFreed;
        if (listener == null) {
            return;
        }
        try {
            listener.run();
        } catch (final RejectedExecutionException e) {
            log.debug("ZenBPM[{}]: a freed slot is not used, because the adapter shuts down", adapterId, e);
        } catch (final Exception e) {
            // Exception, as for a handler: a listener can throw a checked one sneakily as well
            log.error("ZenBPM[{}]: the listener of freed slots threw", adapterId, e);
        }
    }

    /**
     * Stops the timing threads where the executor shuts down and no slot is taken.
     * {@link #shutdown()}, every handler which returns and every hand-over refused by the handler
     * threads call it, so whichever comes last finds both true. After {@link #shutdownNow()} it does
     * nothing, since that stops the timing threads itself and hands back what they still held.
     */
    private void stopTheTimingOnceNoHandlerRuns() {
        if (shuttingDown && !stoppingNow && (freeSlots.availablePermits() == slots)) {
            timing.end();
        }
    }

    /**
     * The timing threads, which only the executor which owns them shuts down: a receiver closing
     * them, for example with try-with-resources, would stop the job stream's reconnects while the
     * executor still counts as running.
     */
    private static final class TimingThreads extends ScheduledThreadPoolExecutor {

        private final String adapterId;

        private final Set<Thread> threads;

        private TimingThreads(final String adapterId) {
            this(adapterId, ConcurrentHashMap.newKeySet());
        }

        private TimingThreads(
            final String adapterId,
            final Set<Thread> threads
        ) {
            super(TIMING_THREADS, named(adapterId, threads));
            this.adapterId = adapterId;
            this.threads = threads;
            // a reconnect cancelled by a closing stream leaves nothing behind
            setRemoveOnCancelPolicy(true);
            // a check due later, like the stream's, must not hold the end of the adapter up
            setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        }

        @Override
        public void shutdown() {
            throw refusal();
        }

        @Override
        public List<Runnable> shutdownNow() {
            throw refusal();
        }

        private boolean runsOn(final Thread thread) {
            return threads.contains(thread);
        }

        private void end() {
            super.shutdown();
        }

        private List<Runnable> endNow() {
            return super.shutdownNow();
        }

        private UnsupportedOperationException refusal() {
            return new UnsupportedOperationException(
                    "ZenBPM[%s]: the timing threads stop with the executor which owns them, not before"
                            .formatted(adapterId));
        }

        private static ThreadFactory named(
            final String adapterId,
            final Set<Thread> threads
        ) {
            final var nextThread = new AtomicInteger();
            return runnable -> {
                final var thread = new Thread(runnable, "zenbpm-%s-timing-%d"
                        .formatted(adapterId, nextThread.getAndIncrement()));
                threads.add(thread);
                // daemon, like the handler threads: a shutdown which forgot this executor must not
                // keep the JVM up
                thread.setDaemon(true);
                return thread;
            };
        }

    }

}

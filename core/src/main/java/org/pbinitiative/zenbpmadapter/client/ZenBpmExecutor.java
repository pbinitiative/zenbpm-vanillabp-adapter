/*
 * Copyright 2026 Phactum Softwareentwicklung GmbH
 * Adapted for zenbpm-vanillabp-adapter from vanillabp/camunda8-adapter, see NOTICE.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.pbinitiative.zenbpmadapter.client;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import lombok.extern.slf4j.Slf4j;

/**
 * The threads of one ZenBPM adapter id, whatever the execution model is. It keeps two roles
 * apart, and it holds the slots which bound the handlers.
 * <p>
 * <b>The timing threads.</b> {@link #timing()} is two platform threads named
 * <code>zenbpm-&lt;id&gt;-timing-N</code>, for everything which waits or repeats, and no handler
 * can use them. The job stream hands every job over on them and opens its call again on them. What
 * runs there must not stay longer than a hand-over, so that a handler which blocks never delays a
 * reconnect. Their queue has no limit, so handing something to them never waits.
 * <p>
 * <b>The handler threads.</b> What {@link #execute(Runnable)} is given runs on the handling half,
 * which the subclass builds: <code>worker-threads</code> platform threads, or a virtual thread per
 * handler. Never more handlers are handed to it than the bound of the execution model: a
 * submission while that many have not returned waits in the submitting thread, so no queue of
 * handlers grows here. The jobs which wait for a slot belong to the dispatcher, which receives them.
 * <p>
 * <b>The slots.</b> The dispatcher is meant to accept a job only while a slot is free:
 * {@link #tryAcquireSlot()} takes one without waiting, and the handler gives it back with
 * {@link #releaseSlot()} once it is done. A handler thread may still be returning when its slot is
 * free again, so a submission with a slot in hand waits at most for that. The busy and the free
 * slots are there for the metrics. A caller which submits without a slot still finds the bound.
 * <p>
 * Camunda 8's executor holds the polls of its client back while every slot is busy. This one has
 * no such gate: the engine never hands a client more active jobs of a type than its subscription
 * allows, and the subscription asks for as many as there are slots, see decision 9 in the
 * repository's DECISIONS.md.
 */
@Slf4j
public abstract class ZenBpmExecutor implements Executor {

    /**
     * How many platform threads do the timing. There are two, so a task which is due does not
     * wait for another one which is just being handed over.
     */
    static final int TIMING_THREADS = 2;

    /**
     * How often a submission which waits for a handler thread looks whether the executor shuts
     * down meanwhile. A thread which returns ends the wait at once; only a shutdown is found this
     * late.
     */
    static final Duration LOOK_FOR_A_SHUTDOWN_AGAIN = Duration.ofMillis(100);

    private static final String SHUTS_DOWN = "ZenBPM[%s]: the adapter shuts down, so the handler is not run";

    private final String adapterId;

    private final int bound;

    private final ScheduledThreadPoolExecutor timing;

    private final ExecutorService handling;

    /**
     * The handlers submitted and not yet returned, at most {@link #bound}.
     */
    private final Semaphore handlers;

    /**
     * The slots the dispatcher took and did not give back yet, at most {@link #bound}.
     */
    private final AtomicInteger busySlots = new AtomicInteger();

    /**
     * Set on a handler thread once it ran one of this executor's handlers. A submission from there
     * could wait for the thread it runs on.
     */
    private final ThreadLocal<Boolean> onAHandlerThread = ThreadLocal.withInitial(() -> false);

    /**
     * Builds the timing threads, the bound and the slots which every execution model has.
     *
     * @param adapterId The adapter id, used to name the threads
     * @param bound How many handlers may run at the same time
     * @param handling The half which runs the handlers; it is given at most <code>bound</code> of
     *            them at once
     */
    protected ZenBpmExecutor(
        final String adapterId,
        final int bound,
        final ExecutorService handling
    ) {
        if (bound < 1) {
            throw new IllegalArgumentException("ZenBPM[%s]: an executor needs at least one slot, not %d"
                    .formatted(adapterId, bound));
        }
        this.adapterId = adapterId;
        this.bound = bound;
        this.handling = handling;
        this.handlers = new Semaphore(bound);
        final var nextThread = new AtomicInteger();
        this.timing = new ScheduledThreadPoolExecutor(TIMING_THREADS, runnable -> {
            final var thread = new Thread(runnable, "zenbpm-%s-timing-%d"
                    .formatted(adapterId, nextThread.getAndIncrement()));
            // daemon, like the handler threads: a shutdown which forgot this executor must not
            // keep the JVM up
            thread.setDaemon(true);
            return thread;
        });
        // a reconnect cancelled by a closing stream leaves nothing behind
        this.timing.setRemoveOnCancelPolicy(true);
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
     * The timing threads, which no handler uses. Shutting them down is the business of
     * {@link #shutdown()}.
     *
     * @return Two platform threads named <code>zenbpm-&lt;id&gt;-timing-N</code>
     */
    public ScheduledExecutorService timing() {
        return Executors.unconfigurableScheduledExecutorService(timing);
    }

    /**
     * The limit this adapter id runs its handlers under, whichever execution model it uses.
     *
     * @return How many handlers may run at the same time
     */
    public int getBound() {
        return bound;
    }

    /**
     * Takes a slot if one is free, without waiting.
     *
     * @return Whether the caller holds a slot now, which it gives back with
     *         {@link #releaseSlot()}
     */
    public boolean tryAcquireSlot() {
        final var before = busySlots.getAndUpdate(busy -> busy < bound ? busy + 1 : busy);
        return before < bound;
    }

    /**
     * Gives back a slot {@link #tryAcquireSlot()} handed out.
     *
     * @throws IllegalStateException Where no slot is taken: giving back one more than was taken
     *             would raise the bound for good
     */
    public void releaseSlot() {
        final var before = busySlots.getAndUpdate(busy -> busy > 0 ? busy - 1 : busy);
        if (before == 0) {
            throw new IllegalStateException("ZenBPM[%s]: a slot was given back which nobody took"
                    .formatted(adapterId));
        }
    }

    /**
     * How many slots are taken right now.
     *
     * @return Between zero and the {@link #getBound() bound}
     */
    public int getBusySlots() {
        return busySlots.get();
    }

    /**
     * How many slots are free right now.
     *
     * @return Between zero and the {@link #getBound() bound}
     */
    public int getFreeSlots() {
        return bound - busySlots.get();
    }

    /**
     * Runs a handler on a handler thread. Where as many handlers run as the bound allows, it waits
     * until one of them returns. An exception the handler throws is logged.
     *
     * @param handler The handler
     * @throws RejectedExecutionException Where the executor shuts down, also while the submission
     *             waits, or where the submitting thread is interrupted while it waits
     * @throws IllegalStateException Where it is called on one of this executor's handler threads,
     *             which could wait for itself
     */
    @Override
    public void execute(final Runnable handler) {
        if (onAHandlerThread.get()) {
            throw new IllegalStateException("""
                    ZenBPM[%s]: a handler submitted another handler to the threads it runs on, which can \
                    wait for itself where every thread is busy; submit it from a timing thread""".formatted(
                    adapterId));
        }
        waitForAHandlerThread();
        try {
            handling.execute(() -> runOnAHandlerThread(handler));
        } catch (final RejectedExecutionException e) {
            handlers.release();
            throw new RejectedExecutionException(SHUTS_DOWN.formatted(adapterId), e);
        }
    }

    /**
     * Starts an orderly shutdown of both halves: what was handed over still runs, nothing new is
     * taken, and a submission which waits for a handler thread is refused.
     */
    public void shutdown() {
        timing.shutdown();
        handling.shutdown();
    }

    /**
     * Shuts both halves down at once, interrupting the handlers which run.
     *
     * @return The timing tasks which never ran
     */
    public List<Runnable> shutdownNow() {
        final var pending = new ArrayList<>(timing.shutdownNow());
        pending.addAll(handling.shutdownNow());
        return pending;
    }

    /**
     * Whether {@link #shutdown()} or {@link #shutdownNow()} was called.
     *
     * @return Whether both halves take nothing new
     */
    public boolean isShutdown() {
        return timing.isShutdown() && handling.isShutdown();
    }

    /**
     * Whether both halves ended after a shutdown.
     *
     * @return Whether nothing runs any longer
     */
    public boolean isTerminated() {
        return timing.isTerminated() && handling.isTerminated();
    }

    /**
     * Waits for both halves to end after a shutdown.
     *
     * @param timeout How long to wait at most
     * @return Whether both ended in time
     * @throws InterruptedException Where the waiting thread is interrupted
     */
    public boolean awaitTermination(final Duration timeout) throws InterruptedException {
        final var deadline = System.nanoTime() + timeout.toNanos();
        final var timingTerminated = timing.awaitTermination(timeout.toNanos(), TimeUnit.NANOSECONDS);
        final var remaining = Math.max(0, deadline - System.nanoTime());
        return handling.awaitTermination(remaining, TimeUnit.NANOSECONDS) && timingTerminated;
    }

    private void waitForAHandlerThread() {
        try {
            while (!handlers.tryAcquire(LOOK_FOR_A_SHUTDOWN_AGAIN.toNanos(), TimeUnit.NANOSECONDS)) {
                if (handling.isShutdown()) {
                    throw new RejectedExecutionException(SHUTS_DOWN.formatted(adapterId));
                }
            }
        } catch (final InterruptedException e) {
            Thread
                    .currentThread()
                    .interrupt();
            throw new RejectedExecutionException(
                    "ZenBPM[%s]: interrupted while the handler waited for a thread".formatted(adapterId), e);
        }
    }

    private void runOnAHandlerThread(final Runnable handler) {
        // never taken back: a handler thread runs nothing but handlers
        onAHandlerThread.set(true);
        try {
            handler.run();
        } catch (final RuntimeException e) {
            log.error("ZenBPM[{}]: a handler threw", adapterId, e);
        } finally {
            handlers.release();
        }
    }

}

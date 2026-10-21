/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.wiring;

import java.time.Duration;
import java.time.Instant;
import java.util.Deque;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

import org.pbinitiative.zenbpmadapter.ZenBpmInstanceCache;
import org.pbinitiative.zenbpmadapter.ZenBpmInstanceCache.InstanceFacts;
import org.pbinitiative.zenbpmadapter.ZenBpmProcessingContext.WiredProcess;
import org.pbinitiative.zenbpmadapter.client.ReceivedJob;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientFactory;
import org.pbinitiative.zenbpmadapter.client.ZenBpmCommandRetry;
import org.pbinitiative.zenbpmadapter.client.ZenBpmExecutor;
import org.pbinitiative.zenbpmadapter.observability.ZenBpmMetrics;

import lombok.extern.slf4j.Slf4j;

/**
 * Takes the jobs of the job stream of one adapter id and hands each to a handler slot, one
 * dispatcher per adapter id. Every workflow module whose processing runs registers its
 * {@link ZenBpmJobHandler}.
 * <p>
 * <b>Waiting for a slot.</b> The stream hands a job over on a timing thread, which must never wait.
 * So a job waits here until {@link ZenBpmExecutor#tryExecute(Runnable)} takes it, and the executor
 * says when a slot came free. What waits is bounded by the subscriptions: the engine hands out no
 * more active jobs of a type than its subscription asks for, which is the number of slots, so
 * nothing is dropped for lack of room, see decision 9 in the repository's DECISIONS.md. A job whose
 * lock lapsed while it waited is not started but left to the engine, which hands it out again: its
 * handler would work without a lock, beside the next delivery of the same job.
 * <p>
 * <b>Which module.</b> A job names its instance and its type. On the handler thread, the facts of
 * the instance are read through the instance cache of the adapter id, and the job goes to the
 * module which claims the instance's process and serves the job type in it. A job of a type no
 * module subscribes, and one whose instance belongs to a process no module serves with that type,
 * is logged and left to its lock. So is a job whose instance the engine does not know yet: the read
 * is answered by a node of the engine which may lag behind, so it is repeated within the lock first.
 * <p>
 * Every job runs under a {@link ZenBpmLockRenewal}, from the moment its handler starts until it
 * returns.
 * <p>
 * <b>What is counted.</b> Every job the stream hands over, and among them every delivery of a job
 * the engine had handed out before, which its delivery token says: the engine raises the token of a
 * job with each delivery, starting at 1. And every job which is still waiting once it arrived and a
 * slot was looked for, once however long it waits.
 */
@Slf4j
public final class ZenBpmJobDispatcher implements Consumer<ReceivedJob> {

    private final String adapterId;

    private final ZenBpmExecutor executor;

    private final ZenBpmInstanceCache instances;

    private final ZenBpmLockRenewal lockRenewal;

    private final ZenBpmMetrics metrics;

    private final Deque<Waiting> waiting = new ConcurrentLinkedDeque<>();

    /**
     * The handlers of the workflow modules whose processing runs, by workflow module id.
     */
    private final Map<String, ZenBpmJobHandler> handlers = new ConcurrentHashMap<>();

    /**
     * The handlers of the workflow modules which stopped, by workflow module id: a job handed over
     * before its module stopped, whose instance was still being read, finds its module here and
     * is told about the shutdown instead of being taken for a job nobody serves.
     */
    private final Map<String, ZenBpmJobHandler> stoppedHandlers = new ConcurrentHashMap<>();

    /**
     * The dispatcher of an adapter id, which is told by the executor of the adapter id whenever a
     * slot comes free. Contacts nothing.
     *
     * @param adapterId The adapter id
     * @param factory The clients of the adapter id
     * @param metrics What the dispatcher and the renewal of the locks count into
     */
    public ZenBpmJobDispatcher(
        final String adapterId,
        final ZenBpmClientFactory factory,
        final ZenBpmMetrics metrics
    ) {
        this(adapterId, factory.executor(), factory
                .instances(), new ZenBpmLockRenewal(adapterId, factory.rest(), factory.clientId(), factory
                        .executor()
                        .timing(), metrics), metrics);
    }

    ZenBpmJobDispatcher(
        final String adapterId,
        final ZenBpmExecutor executor,
        final ZenBpmInstanceCache instances,
        final ZenBpmLockRenewal lockRenewal,
        final ZenBpmMetrics metrics
    ) {
        this.adapterId = adapterId;
        this.executor = executor;
        this.instances = instances;
        this.lockRenewal = lockRenewal;
        this.metrics = metrics;
        executor.onSlotFreed(this::handOver);
    }

    /**
     * Hands the jobs of a workflow module's job types to its handler from now on.
     *
     * @param handler The handler of the module
     */
    public void register(final ZenBpmJobHandler handler) {
        handlers.put(handler.workflowModuleId(), handler);
        stoppedHandlers.remove(handler.workflowModuleId());
    }

    /**
     * Hands no more jobs to a workflow module, and tells its handler that the module shuts down.
     * A job of the module which still waits for a slot is left to its lock once it would start; one
     * whose instance is being read goes to the handler, which does not start it either.
     *
     * @param workflowModuleId The workflow module
     */
    public void unregister(final String workflowModuleId) {
        final var handler = handlers.get(workflowModuleId);
        if (handler == null) {
            return;
        }
        // kept among the stopped ones before it leaves the running ones, so a job on its way to
        // the module finds it in one of them
        stoppedHandlers.put(workflowModuleId, handler);
        handler.stopping();
        handlers.remove(workflowModuleId, handler);
    }

    /**
     * Takes a job the job stream received, on a timing thread, without waiting.
     *
     * @param received The job
     */
    @Override
    public void accept(final ReceivedJob received) {
        final var job = received.job();
        metrics.jobReceived(adapterId);
        if (job.getDeliveryToken() > 1) {
            metrics.jobRedelivered(adapterId);
        }
        if (jobTimeoutOf(job.getType()).isEmpty()) {
            log.warn(
                    "ZenBPM[{}]: a job of type '{}' (key {}) arrived, which no workflow module of this application subscribes; it is left to its lock",
                    adapterId, job.getType(), job.getKey());
            return;
        }
        final var arrived = new Waiting(received);
        waiting.addLast(arrived);
        handOver();
        if (!arrived.settled) {
            metrics.jobQueued(adapterId);
        }
    }

    // a job tryExecute refused is put back BEFORE the next look for a free slot, so a slot which
    // comes free meanwhile is seen either by that look or by its own call of this method
    private void handOver() {
        while (true) {
            final var next = waiting.pollFirst();
            if (next == null) {
                return;
            }
            // out of the queue for good, unless it is put back below
            next.settled = true;
            final var received = next.received;
            final var job = received.job();
            final var jobTimeout = jobTimeoutOf(job.getType());
            if (jobTimeout.isEmpty()) {
                log.debug(
                        "ZenBPM[{}]: job {} of type '{}' is left to its lock, no workflow module subscribes the type any more",
                        adapterId, job.getKey(), job.getType());
                continue;
            }
            if (!received
                    .lockEnd(jobTimeout.get())
                    .isAfter(Instant.now())) {
                log.info("""
                        ZenBPM[{}]: job {} of type '{}' waited for a handler slot until its lock lapsed, so it is not \
                        started; the engine hands it out again. Handlers which run longer than the job-timeout of \
                        the jobs waiting behind them ask for more worker-threads.""", adapterId, job.getKey(),
                        job.getType());
                continue;
            }
            final boolean handedOver;
            try {
                handedOver = executor.tryExecute(() -> run(received, jobTimeout.get()));
            } catch (final RejectedExecutionException e) {
                log.debug(
                        "ZenBPM[{}]: the adapter shuts down, so job {} and the {} job(s) waiting behind it are left to their locks",
                        adapterId, job.getKey(), waiting.size());
                waiting.clear();
                return;
            }
            if (handedOver) {
                continue;
            }
            next.settled = false;
            waiting.addFirst(next);
            if (executor.getFreeSlots() == 0) {
                return;
            }
        }
    }

    // on a handler thread
    private void run(
        final ReceivedJob received,
        final Duration jobTimeout
    ) {
        final var job = received.job();
        try (var renewal = lockRenewal.start(received, jobTimeout, job.getType())) {
            final var retry = new ZenBpmCommandRetry(adapterId, job.getKey(), job
                    .getType(), renewal::lockEnd, executor::isShutdown);
            final Optional<InstanceFacts> facts;
            try {
                facts = retry.read("instance", () -> instances.facts(job.getInstanceKey(), this::idVariableNameOf),
                        Optional::isEmpty);
            } catch (final RuntimeException e) {
                log.warn(
                        "ZenBPM[{}]: the instance of job {} (type '{}') could not be read; the job is left to its lock, after which the engine hands it out again",
                        adapterId, job.getKey(), job.getType(), e);
                return;
            }
            if (facts.isEmpty()) {
                log.info("""
                        ZenBPM[{}]: instance {} of job {} (type '{}') is not known to the engine node asked, which may \
                        lag behind; the job is left to its lock, after which the engine hands it out again""",
                        adapterId, job.getInstanceKey(), job.getKey(), job.getType());
                return;
            }
            final var scopedProcessId = facts
                    .get()
                    .scopedProcessId();
            // a module which stopped meanwhile still takes the job, and says that it does not
            // start it because the module shuts down
            final var served = served(handlers, scopedProcessId, job.getType()).or(() -> served(stoppedHandlers,
                    scopedProcessId, job.getType()));
            if (served.isEmpty()) {
                log.warn("""
                        ZenBPM[{}]: job {} of type '{}' belongs to instance {} of process '{}', which no workflow \
                        module of this application serves with that type. Another application or adapter id uses \
                        the same job type on this engine, which name-clash-avoidance 'use-prefix' keeps apart. The \
                        job is left to its lock, after which the engine hands it out again.""", adapterId, job
                        .getKey(), job.getType(), job.getInstanceKey(),
                        facts
                                .get()
                                .scopedProcessId());
                return;
            }
            served
                    .get()
                    .handler()
                    .handle(received, facts.get(), served
                            .get()
                            .process(), renewal);
        }
    }

    private Optional<Duration> jobTimeoutOf(final String jobType) {
        return handlers
                .values()
                .stream()
                .map(handler -> handler
                        .jobTimeouts()
                        .get(jobType))
                .filter(Objects::nonNull)
                .findFirst();
    }

    private String idVariableNameOf(final String scopedProcessId) {
        return handlers
                .values()
                .stream()
                .map(handler -> handler.idVariableNameOf(scopedProcessId))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private static Optional<Served> served(
        final Map<String, ZenBpmJobHandler> among,
        final String scopedProcessId,
        final String jobType
    ) {
        return among
                .values()
                .stream()
                .flatMap(handler -> handler
                        .processServing(scopedProcessId, jobType)
                        .map(process -> new Served(handler, process))
                        .stream())
                .findFirst();
    }

    /**
     * A job on its way to a slot, and whether it left the queue: handed over, left to its lock, or
     * taken out to be handed over.
     */
    private static final class Waiting {

        private final ReceivedJob received;

        private volatile boolean settled;

        private Waiting(final ReceivedJob received) {
            this.received = received;
        }

    }

    private record Served(
        ZenBpmJobHandler handler,
        WiredProcess process
    ) {
    }

}

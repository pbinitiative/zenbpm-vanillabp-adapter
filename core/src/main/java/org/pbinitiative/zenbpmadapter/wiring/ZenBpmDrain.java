/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.wiring;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.LockSupport;

import org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration;

import lombok.extern.slf4j.Slf4j;

/**
 * What one workflow module of one adapter id has inside the application while it shuts down: the
 * handlers which run, and whether the module stops.
 * <p>
 * Every handler of the module enters the drain with its job before it asks the core for anything
 * and leaves it when it returns, whatever the outcome. So the shutdown can wait for the handlers
 * before the job stream closes under them, and name the ones it gave up on: the job key, the task
 * and the workflow aggregate. While the module stops, a handler which threw is not failed at the
 * engine: the failure would spend one of the job's retries for a cause which is the shutdown, not
 * the job (see decision 27 in the repository's DECISIONS.md). Its lock is released when the job
 * stream closes, so the engine hands the job to another node at once, with its retries untouched.
 * An outcome which a handler reached is still reported: its work is committed.
 * <p>
 * The grace is one budget for every workflow module of the adapter id, because the platform stops
 * the modules one after another: {@link #awaitEveryModuleQuiet(Collection, Duration)} waits for all
 * of them together, see decision 39 in the repository's DECISIONS.md.
 */
@Slf4j
public final class ZenBpmDrain {

    /**
     * How often the wait looks whether the handlers returned. Short enough that a shutdown with
     * nothing to wait for is not delayed noticeably.
     */
    static final Duration POLL = Duration.ofMillis(50);

    private final String adapterId;

    private final String workflowModuleId;

    private final Map<Long, Running> running = new ConcurrentHashMap<>();

    private volatile boolean stopping;

    /**
     * The drain of a workflow module whose processing starts.
     *
     * @param adapterId The adapter id
     * @param workflowModuleId The workflow module
     */
    public ZenBpmDrain(
        final String adapterId,
        final String workflowModuleId
    ) {
        this.adapterId = adapterId;
        this.workflowModuleId = workflowModuleId;
    }

    /**
     * The workflow module this drain belongs to.
     *
     * @return The workflow module id
     */
    public String workflowModuleId() {
        return workflowModuleId;
    }

    /**
     * Marks the module as shutting down. Called before the module's handlers are waited for, so a
     * handler which fails from now on can tell its failure from the shutdown's.
     */
    public void stopping() {
        stopping = true;
    }

    /**
     * Whether the module shuts down.
     *
     * @return <code>true</code> once {@link #stopping()} was called
     */
    public boolean isStopping() {
        return stopping;
    }

    /**
     * Registers the handler of a job, which runs from now on.
     *
     * @param jobKey The job key
     * @param taskDefinition The task, as the application knows it
     */
    public void enter(
        final long jobKey,
        final String taskDefinition
    ) {
        running.put(jobKey, new Running(jobKey, taskDefinition, null, Instant.now()));
    }

    /**
     * Names the workflow aggregate of a running handler, once the handler found it.
     *
     * @param jobKey The job key
     * @param aggregateId The id of the workflow aggregate
     */
    public void describe(
        final long jobKey,
        final String aggregateId
    ) {
        running.computeIfPresent(jobKey, (
            key,
            entered) -> new Running(key, entered.taskDefinition(), aggregateId, entered.since()));
    }

    /**
     * Deregisters the handler of a job, which returned. Called in a <code>finally</code>, so a
     * handler which threw leaves as well.
     *
     * @param jobKey The job key
     */
    public void leave(final long jobKey) {
        running.remove(jobKey);
    }

    /**
     * The handlers which run right now, the longest running first.
     *
     * @return The handlers
     */
    public List<Running> running() {
        return running
                .values()
                .stream()
                .sorted(Comparator.comparing(Running::since))
                .toList();
    }

    /**
     * Waits until no handler of any of the drains runs, or until the grace is over, and reports
     * per workflow module what the wait ended with: an INFO where its handlers returned, a WARN per
     * handler which still runs. The modules are waited for together, so a shutdown takes the grace
     * once, however many modules the adapter id has.
     *
     * @param drains The drains of the workflow modules which stopped
     * @param grace How long to wait at most, all modules together
     * @return Whether every handler returned within the grace
     */
    public static boolean awaitEveryModuleQuiet(
        final Collection<ZenBpmDrain> drains,
        final Duration grace
    ) {
        final var startedAt = System.nanoTime();
        final var deadline = startedAt + Math.max(0, grace.toNanos());
        while (!everyModuleIsQuiet(drains) && (System.nanoTime() - deadline < 0)) {
            if (Thread
                    .currentThread()
                    .isInterrupted()) {
                break;
            }
            LockSupport.parkNanos(Math.min(POLL.toNanos(), Math.max(1, deadline - System.nanoTime())));
        }
        final var waited = Duration.ofNanos(System.nanoTime() - startedAt);
        drains.forEach(drain -> drain.report(grace, waited));
        return everyModuleIsQuiet(drains);
    }

    private void report(
        final Duration grace,
        final Duration waited
    ) {
        final var stillRunning = running();
        if (stillRunning.isEmpty()) {
            log.info("ZenBPM[{}]: workflow module '{}' drained after {} ms, no handler of it runs any more",
                    adapterId, workflowModuleId, waited.toMillis());
            return;
        }
        final var now = Instant.now();
        stillRunning.forEach(handler -> log.warn("""
                ZenBPM[{}]: the handler of job {} (task '{}', workflow aggregate '{}', workflow module '{}') \
                still runs after the shutdown waited {} for it, for {} ms by now, and is cut off. The job is not \
                failed, so it keeps its retries; its lock is released when the job stream closes, and the engine \
                hands it to another node, whose delivery VanillaBP answers from its record where this one \
                committed. Raise {} where handlers legitimately run that long, and the shutdown budget of the \
                runtime with it (spring.lifecycle.timeout-per-shutdown-phase, Kubernetes' \
                terminationGracePeriodSeconds).""", adapterId, handler.jobKey(), handler.taskDefinition(),
                handler.aggregateId() == null ? "not read yet" : handler.aggregateId(), workflowModuleId, grace,
                Duration
                        .between(handler.since(), now)
                        .toMillis(),
                ZenBpmAdapterConfiguration.propertyKey(adapterId, "shutdown-grace")));
    }

    private static boolean everyModuleIsQuiet(final Collection<ZenBpmDrain> drains) {
        return drains
                .stream()
                .allMatch(drain -> drain.running.isEmpty());
    }

    /**
     * A handler which runs.
     *
     * @param jobKey The job key
     * @param taskDefinition The task, as the application knows it
     * @param aggregateId The id of the workflow aggregate; <code>null</code> until the handler found it
     * @param since When the handler entered
     */
    public record Running(
        long jobKey,
        String taskDefinition,
        String aggregateId,
        Instant since
    ) {
    }

}

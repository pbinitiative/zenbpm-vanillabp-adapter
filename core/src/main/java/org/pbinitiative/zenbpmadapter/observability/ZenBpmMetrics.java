/*
 * Copyright 2026 Phactum Softwareentwicklung GmbH
 * Adapted for zenbpm-vanillabp-adapter from vanillabp/camunda8-adapter, see NOTICE.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.pbinitiative.zenbpmadapter.observability;

import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
 * What this adapter measures on top of what the VanillaBP core measures for every BPMS, written
 * without any metrics library. An application without a metrics backend gets {@link #NONE}, and so
 * does every part of the adapter until a platform module hands in something else.
 * <p>
 * The core counts deliveries by their outcome, whatever the BPMS. What only this adapter can tell
 * is what happens between the engine and the handler slots: the jobs the job stream brought, those
 * the engine handed out before, those which had to wait for a slot, the locks it kept and lost, the
 * job stream's reconnects and the looks at the user tasks. An operator alerts on two pictures in
 * particular. A job stream which cannot reach the engine shows as {@link #STREAM_RECONNECTS} rising
 * while {@link #JOBS_RECEIVED} stays flat, because the health check is UP wherever the engine
 * answers over REST, see decision 58 in the repository's DECISIONS.md. And an application whose
 * handlers hold every slot shows as {@link #SLOTS_FREE} at zero while {@link #JOBS_QUEUED} rises.
 * Where each counter moves, and why there, is decision 59 in the repository's DECISIONS.md.
 * <p>
 * The Micrometer implementation is {@link MicrometerZenBpmMetrics}. Micrometer is optional: without
 * it none of it is loaded, and the adapter runs unchanged.
 * <p>
 * The names follow the scheme of the platform: they start with <code>vanillabp.</code>, and the
 * adapter id is a tag, never part of the name, so the meters of two adapter ids differ only in
 * {@link #TAG_ADAPTER}. Every meter of an adapter id carries it.
 */
public interface ZenBpmMetrics {

    /**
     * Measures nothing.
     */
    ZenBpmMetrics NONE = new ZenBpmMetrics() {
    };

    /**
     * Counter of the jobs the job stream of an adapter id handed over, every delivery, those of a
     * type no workflow module subscribes included. Where it stays flat while the engine holds work,
     * the job stream delivers nothing.
     */
    String JOBS_RECEIVED = "vanillabp.zenbpm.jobs.received";

    /**
     * Counter of the jobs a handler answered with a completion: its method returned, or threw a
     * BPMN error, which is an outcome the method chose, or the start or the end of a workflow was
     * reported. A task left open for its asynchronous completion is not counted here, nor is a job
     * which was gone when it was to be completed.
     */
    String JOBS_COMPLETED = "vanillabp.zenbpm.jobs.completed";

    /**
     * Counter of the jobs the adapter failed at the engine: a handler threw, which spends one of the
     * job's retries, or the adapter failed the job without retries, so an incident shows it to an
     * operator. A BPMN error is not a failure.
     */
    String JOBS_FAILED = "vanillabp.zenbpm.jobs.failed";

    /**
     * Counter of the jobs which found no free handler slot and waited for one, each delivery
     * counted once however long it waited. Where it rises while {@link #SLOTS_FREE} stays at zero,
     * the handlers take longer than the work arriving allows.
     */
    String JOBS_QUEUED = "vanillabp.zenbpm.jobs.queued";

    /**
     * Counter of the deliveries of a job the engine had handed out before, which the delivery token
     * of the job says: a lock which lapsed, a failure the engine retries, a task left open which
     * comes back once its window passed, or an answer which got lost. Part of
     * {@link #JOBS_RECEIVED}.
     */
    String JOBS_REDELIVERED = "vanillabp.zenbpm.jobs.redelivered";

    /**
     * Counter of the extensions of the lock of a running handler's job which the engine granted.
     */
    String LOCK_RENEWED = "vanillabp.zenbpm.lock.renewed";

    /**
     * Counter of the locks which ended while their handler still ran, because the engine said the
     * lock is lost or could not be asked before it lapsed. A second delivery of the job may then run
     * beside the handler.
     */
    String LOCK_LOST = "vanillabp.zenbpm.lock.lost";

    /**
     * Counter of the attempts of the job stream to open its call to the engine again after it broke,
     * successful or not.
     */
    String STREAM_RECONNECTS = "vanillabp.zenbpm.stream.reconnects";

    /**
     * Counter of the looks at the user tasks, one per round of the poller of an adapter id, the
     * rounds the engine did not answer included.
     */
    String USER_TASK_POLLS = "vanillabp.zenbpm.usertasks.polls";

    /**
     * Gauge of the handler slots of an adapter id which are taken right now, by a handler which
     * runs or is about to.
     */
    String SLOTS_BUSY = "vanillabp.zenbpm.slots.busy";

    /**
     * Gauge of the handler slots of an adapter id which are free right now.
     */
    String SLOTS_FREE = "vanillabp.zenbpm.slots.free";

    /**
     * The tag naming the adapter id a measurement belongs to.
     */
    String TAG_ADAPTER = "adapter";

    /**
     * Registers what is read of an adapter id rather than counted: the reconnects of its job stream
     * and its handler slots. Called once per adapter id with clients. The values are read where they
     * are kept, in memory, so reading a meter never asks the engine anything.
     *
     * @param adapterId The adapter id
     * @param streamReconnects How often the job stream opened its call again
     * @param busySlots How many handler slots are taken
     * @param freeSlots How many handler slots are free
     */
    default void registerAdapter(
        final String adapterId,
        final LongSupplier streamReconnects,
        final IntSupplier busySlots,
        final IntSupplier freeSlots
    ) {
    }

    /**
     * Counts a job the job stream handed over, see {@link #JOBS_RECEIVED}.
     *
     * @param adapterId The adapter id
     */
    default void jobReceived(final String adapterId) {
    }

    /**
     * Counts a delivery of a job the engine had handed out before, see {@link #JOBS_REDELIVERED}.
     *
     * @param adapterId The adapter id
     */
    default void jobRedelivered(final String adapterId) {
    }

    /**
     * Counts a job which waits for a handler slot, see {@link #JOBS_QUEUED}.
     *
     * @param adapterId The adapter id
     */
    default void jobQueued(final String adapterId) {
    }

    /**
     * Counts a job the engine took a completion of, see {@link #JOBS_COMPLETED}.
     *
     * @param adapterId The adapter id
     */
    default void jobCompleted(final String adapterId) {
    }

    /**
     * Counts a job the engine took a failure of, see {@link #JOBS_FAILED}.
     *
     * @param adapterId The adapter id
     */
    default void jobFailed(final String adapterId) {
    }

    /**
     * Counts an extension of a running handler's lock, see {@link #LOCK_RENEWED}.
     *
     * @param adapterId The adapter id
     */
    default void lockRenewed(final String adapterId) {
    }

    /**
     * Counts a lock which ended while its handler ran, see {@link #LOCK_LOST}.
     *
     * @param adapterId The adapter id
     */
    default void lockLost(final String adapterId) {
    }

    /**
     * Counts a look at the user tasks, see {@link #USER_TASK_POLLS}.
     *
     * @param adapterId The adapter id
     */
    default void userTaskPoll(final String adapterId) {
    }

}

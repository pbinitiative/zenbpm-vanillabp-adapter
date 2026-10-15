/*
 * Copyright 2026 Phactum Softwareentwicklung GmbH
 * Adapted for zenbpm-vanillabp-adapter from vanillabp/camunda8-adapter, see NOTICE.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.pbinitiative.zenbpmadapter.client;

import static org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration.DEFAULT_WORKER_THREADS;
import static org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration.VIRTUAL_WORKER_THREADS;
import static org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration.propertyKey;

/**
 * How one ZenBPM adapter instance runs the handlers of the jobs it receives. It is the resolved
 * value of <code>vanillabp.adapters.&lt;id&gt;.worker-threads</code>, together with the number of
 * handlers which may run at the same time.
 * <p>
 * The adapter has one executor per adapter id. So the number does not say "how parallel may one
 * task be". It says "how much of everything this adapter id delivers may be in flight at once",
 * shared by every job type of every workflow module the adapter id serves. Every job type is
 * subscribed with this number as its cap of active jobs, see decision 9 in the repository's
 * DECISIONS.md.
 * <p>
 * <b>The number counts handlers, not threads shared with the timing.</b> Whichever model is
 * configured, the job stream and everything else which waits or repeats runs on timing threads
 * which no handler can use ({@link ZenBpmExecutor}). So the number means what it says: how many
 * handler invocations may be inside the application at the same time.
 * <p>
 * <b>Why the default is four platform threads.</b> It is more than one, because with one thread
 * every job of the adapter id waits for the one handler which is running. It is small, because
 * every handler holds a database connection inside VanillaBP's transaction while it runs. The
 * usual pools have ten (Hikari) to twenty (Agroal) connections, so four leaves room for the rest of
 * the application. So size the number against the connection pool, not the CPU.
 * <p>
 * <b>The virtual mode.</b> <code>worker-threads: virtual</code> runs each handler on a virtual
 * thread of its own ({@link ZenBpmVirtualThreadExecutor}), not on a pool of platform threads.
 * Virtual threads have no limit by nature, so that model is limited by
 * <code>worker-threads-bound</code>. Its default is the number the platform mode would use, so
 * switching the mode changes how threads are made, not how much runs at once.
 * <p>
 * {@link ZenBpmAdapterConfiguration#validate} reports an unusable value of either key at startup.
 * {@link #resolve(String, String, Integer)} checks them again, for a caller which skipped that.
 *
 * @param virtual Whether handlers run on virtual threads
 * @param slots How many handlers may run at the same time (platform threads, or the bound of the
 *            virtual-thread executor)
 */
public record ZenBpmExecutionModel(
    boolean virtual,
    int slots
) {

    /**
     * The number of execution slots an adapter id has if nothing is configured. The class comment
     * gives the reasons.
     */
    public static final int DEFAULT_SLOTS = Integer.parseInt(DEFAULT_WORKER_THREADS);

    /**
     * The default execution model: {@link #DEFAULT_SLOTS} platform threads.
     */
    public static final ZenBpmExecutionModel DEFAULT = new ZenBpmExecutionModel(false, DEFAULT_SLOTS);

    /**
     * Resolves the execution model of one adapter instance from its configuration. It hands on
     * <code>worker-threads-bound</code> as written, not the bound in use, so a bound beside
     * platform threads is refused rather than taken for their number.
     *
     * @param adapterId The adapter id, used to build the property keys in messages
     * @param configuration The configuration of the adapter id
     * @return The resolved model
     * @throws IllegalStateException If a value is not usable, naming the property and the accepted
     *             forms
     */
    public static ZenBpmExecutionModel resolve(
        final String adapterId,
        final ZenBpmAdapterConfiguration configuration
    ) {
        return resolve(adapterId, configuration.getWorkerThreads(), configuration.getWorkerThreadsBound());
    }

    /**
     * Resolves the execution model of one adapter instance.
     *
     * @param adapterId The adapter id, used to build the property keys in messages
     * @param workerThreads The configured <code>worker-threads</code> value, or <code>null</code>
     * @param workerThreadsBound The configured <code>worker-threads-bound</code> value, or
     *            <code>null</code>
     * @return The resolved model
     * @throws IllegalStateException If a value is not usable, naming the property and the accepted
     *             forms
     */
    public static ZenBpmExecutionModel resolve(
        final String adapterId,
        final String workerThreads,
        final Integer workerThreadsBound
    ) {
        final var configured = (workerThreads == null) || workerThreads.isBlank()
                ? null
                : workerThreads.trim();
        final var isVirtual = VIRTUAL_WORKER_THREADS.equalsIgnoreCase(configured);

        if (!isVirtual && (workerThreadsBound != null)) {
            throw new IllegalStateException("""
                    ZenBPM adapter '%s' configures '%s' but its '%s' is '%s'. The bound sizes the \
                    virtual-thread executor and is ignored by the platform-thread mode, where the number \
                    of threads IS the bound. Either set '%s: %s' or remove the bound."""
                    .formatted(
                            adapterId,
                            propertyKey(adapterId, "worker-threads-bound"),
                            propertyKey(adapterId, "worker-threads"),
                            configured == null ? DEFAULT_WORKER_THREADS : configured,
                            propertyKey(adapterId, "worker-threads"),
                            VIRTUAL_WORKER_THREADS));
        }

        if (isVirtual) {
            final var bound = workerThreadsBound == null
                    ? DEFAULT_SLOTS
                    : workerThreadsBound;
            if (bound < 1) {
                throw new IllegalStateException("""
                        ZenBPM adapter '%s' has '%s: %d'. The bound is the number of handlers which may \
                        run at the same time and has to be at least 1. Size it against the database \
                        connection pool of the application, since every running handler holds a connection \
                        (the default is %d)."""
                        .formatted(adapterId, propertyKey(adapterId, "worker-threads-bound"), bound, DEFAULT_SLOTS));
            }
            return new ZenBpmExecutionModel(true, bound);
        }

        if (configured == null) {
            return DEFAULT;
        }

        final int threads;
        try {
            threads = Integer.parseInt(configured);
        } catch (final NumberFormatException e) {
            throw new IllegalStateException(unparseable(adapterId, configured), e);
        }
        if (threads < 1) {
            throw new IllegalStateException("""
                    ZenBPM adapter '%s' has '%s: %d'. An adapter without a handler thread runs no \
                    handler, and one thread means that every job of this adapter waits for the one \
                    handler which is running, which is why the default is %d. Size it against the \
                    database connection pool of the application, since every running handler holds a \
                    connection."""
                    .formatted(adapterId, propertyKey(adapterId, "worker-threads"), threads, DEFAULT_SLOTS));
        }
        return new ZenBpmExecutionModel(false, threads);
    }

    private static String unparseable(
        final String adapterId,
        final String configured
    ) {
        if (configured.matches("[+-]?\\d+")) {
            return """
                    ZenBPM adapter '%s' has '%s: %s', which is too large a number. Size it against the \
                    database connection pool of the application, since every running handler holds a \
                    connection (the default is %d)."""
                    .formatted(adapterId, propertyKey(adapterId, "worker-threads"), configured, DEFAULT_SLOTS);
        }
        return """
                ZenBPM adapter '%s' has '%s: %s', which is neither a number nor the literal \
                '%s'. Two forms are accepted:
                  %s: %d         # this many platform threads run the handlers (the default)
                  %s: %s   # a virtual thread per handler, bounded by '%s'"""
                .formatted(
                        adapterId,
                        propertyKey(adapterId, "worker-threads"),
                        configured,
                        VIRTUAL_WORKER_THREADS,
                        propertyKey(adapterId, "worker-threads"),
                        DEFAULT_SLOTS,
                        propertyKey(adapterId, "worker-threads"),
                        VIRTUAL_WORKER_THREADS,
                        propertyKey(adapterId, "worker-threads-bound"));
    }

}

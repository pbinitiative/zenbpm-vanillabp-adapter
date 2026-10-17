/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.wiring;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.pbinitiative.zenbpmadapter.client.ReceivedJob;
import org.pbinitiative.zenbpmadapter.client.ZenBpmErrors;
import org.pbinitiative.zenbpmadapter.client.ZenBpmRestClient;

import lombok.extern.slf4j.Slf4j;

/**
 * Keeps the lock of a job held while its handler runs, one per adapter id, see decision 9 in the
 * repository's DECISIONS.md. The engine hands a job out again once its lock lapsed, so a handler
 * which runs longer than <code>job-timeout</code> would have a second delivery of its job running
 * beside it.
 * <p>
 * A handler which starts with less than half of <code>job-timeout</code> left on its lock, such as
 * one whose job waited for a slot, has the lock extended by <code>job-timeout</code> at once. From
 * then on the lock is extended whenever half of <code>job-timeout</code> is left, so every half of
 * it. Each extension (<code>POST /v1/jobs/{key}/extend-lock</code>) names the delivery, so the lock
 * of a later delivery of the same job to the same client is never extended by the handler of an
 * earlier one; it is scheduled on the timing threads of the adapter id and sent from a virtual
 * thread of its own, so an engine which is slow to answer never holds up the hand-over of jobs.
 * <p>
 * The lock is counted on the application's clock, from the receipt of the job and from the sending
 * of each extension, see decision 36 in the repository's DECISIONS.md. Where the engine's own
 * deadline is shorter by more than {@link #CLOCK_TOLERANCE}, a WARN says so once per adapter id: the
 * engine's clock runs behind, or the engine lowered the lock to its maximum.
 * <p>
 * An extension the engine could not be asked for (no answer, a broken connection, <code>502</code>)
 * is sent again after a pause which starts at 50 ms and doubles up to an eighth of the job-timeout, as long as the
 * lock has not lapsed meanwhile. A lock the engine says is lost (<code>409</code>: it lapsed, or
 * another client holds the job) ends the renewal with a WARN, because a second delivery of the job
 * may run beside the handler now; the platform's record of deliveries decides which of them
 * counts. A job the engine no longer holds ends it quietly. The renewal ends with the handler.
 */
@Slf4j
public final class ZenBpmLockRenewal {

    /**
     * The pause before an extension is sent again after the engine could not be asked.
     */
    static final Duration FIRST_PAUSE = Duration.ofMillis(50);

    /**
     * How much shorter the engine's deadline may be than the lock counted on the application's
     * clock before a WARN says so: the time a job or an answer takes to travel.
     */
    static final Duration CLOCK_TOLERANCE = Duration.ofSeconds(1);

    private final String adapterId;

    private final ZenBpmRestClient rest;

    private final String clientId;

    private final ScheduledExecutorService timing;

    private final ThreadFactory calls;

    private final AtomicBoolean shorterLockReported = new AtomicBoolean();

    /**
     * The renewal of one adapter id. Contacts nothing.
     *
     * @param adapterId The adapter id, for the messages and the names of the threads
     * @param rest The REST client of the adapter id
     * @param clientId The client id of the job stream the jobs are delivered to
     * @param timing The timing threads of the adapter id, which schedule the extensions
     */
    public ZenBpmLockRenewal(
        final String adapterId,
        final ZenBpmRestClient rest,
        final String clientId,
        final ScheduledExecutorService timing
    ) {
        this.adapterId = adapterId;
        this.rest = rest;
        this.clientId = clientId;
        this.timing = timing;
        this.calls = Thread
                .ofVirtual()
                .name("zenbpm-%s-lock-renewal".formatted(adapterId))
                .factory();
    }

    /**
     * Starts the renewal of a job's lock, for the handler which starts now.
     *
     * @param received The job as the job stream received it
     * @param jobTimeout The <code>job-timeout</code> its job type was subscribed with
     * @param taskDefinition The task definition, for the messages
     * @return The renewal, which the handler closes when it ends
     */
    public Renewal start(
        final ReceivedJob received,
        final Duration jobTimeout,
        final String taskDefinition
    ) {
        final var job = received.job();
        final var renewal = new Renewal(job.getKey(), job.getDeliveryToken(), jobTimeout, taskDefinition, received
                .lockEnd(jobTimeout));
        received
                .lockUntil()
                .ifPresent(lockUntil -> reportAShorterLock(job.getKey(), lockUntil, renewal.lockEnd, jobTimeout));
        // where less than half is left, the moment lies in the past, and the extension goes at once
        renewal.schedule(Duration
                .between(Instant.now(), renewal.lockEnd)
                .minus(jobTimeout.dividedBy(2)));
        return renewal;
    }

    // once per adapter id: either cause holds for every job, and the lock counted on the
    // application's clock stays the one the renewal goes by
    private void reportAShorterLock(
        final long jobKey,
        final Instant engineDeadline,
        final Instant counted,
        final Duration jobTimeout
    ) {
        final var shorterBy = Duration.between(engineDeadline, counted);
        if ((shorterBy.compareTo(CLOCK_TOLERANCE) <= 0) || shorterLockReported.getAndSet(true)) {
            return;
        }
        log.warn("""
                ZenBPM[{}]: the engine says the lock of job {} lapses at {}, {} before the job-timeout of {} \
                counted on this application's clock. Either the engine's clock runs behind this application's, \
                which the adapter does not rely on, or the job-timeout is above the engine's \
                jobManager.maxLockDurationMs (24 hours by default), which lowers every lock to it; then set \
                vanillabp.adapters.{}.job-timeout, or the more specific key of the task, below that maximum, or \
                the lock lapses while the handler runs. This is said once.""", adapterId, jobKey,
                engineDeadline, shorterBy, jobTimeout, adapterId);
    }

    /**
     * The renewal of the lock of one job while its handler runs.
     */
    public final class Renewal implements AutoCloseable {

        private final long jobKey;

        private final long deliveryToken;

        private final Duration jobTimeout;

        private final String taskDefinition;

        private volatile Instant lockEnd;

        private volatile String aggregateId;

        private volatile boolean ended;

        /**
         * The extension scheduled next. Guarded by <code>this</code>.
         */
        private Future<?> next;

        /**
         * How often the extension under way failed because the engine could not be asked. Only the
         * one extension under way touches it.
         */
        private int unanswered;

        private Renewal(
            final long jobKey,
            final long deliveryToken,
            final Duration jobTimeout,
            final String taskDefinition,
            final Instant lockEnd
        ) {
            this.jobKey = jobKey;
            this.deliveryToken = deliveryToken;
            this.jobTimeout = jobTimeout;
            this.taskDefinition = taskDefinition;
            this.lockEnd = lockEnd;
        }

        /**
         * When the lock lapses as far as the renewal knows, which moves with every extension.
         *
         * @return The moment, on the application's clock
         */
        public Instant lockEnd() {
            return lockEnd;
        }

        /**
         * Names the workflow aggregate of the job once the handler knows it, so a lost lock names
         * it as well.
         *
         * @param aggregateId The aggregate's id
         */
        public void describeAggregate(final String aggregateId) {
            this.aggregateId = aggregateId;
        }

        /**
         * Ends the renewal: no further extension is sent. An extension under way may still reach
         * the engine; its answer is not reported.
         */
        @Override
        public void close() {
            synchronized (this) {
                ended = true;
                if (next != null) {
                    next.cancel(false);
                }
            }
        }

        private void schedule(final Duration delay) {
            synchronized (this) {
                if (ended) {
                    return;
                }
                try {
                    next = timing.schedule(() -> calls
                            .newThread(this::extend)
                            .start(), delay.toNanos(), TimeUnit.NANOSECONDS);
                } catch (final RejectedExecutionException e) {
                    // the timing threads ran out only once no handler runs any more
                    ended = true;
                }
            }
        }

        private void extend() {
            // the engine extends from the moment the request reaches it, which comes after this one
            final var sent = Instant.now();
            final Instant granted;
            try {
                granted = rest.extendJobLock(jobKey, clientId, jobTimeout, deliveryToken);
            } catch (final RuntimeException e) {
                notExtended(e);
                return;
            }
            unanswered = 0;
            lockEnd = sent.plus(jobTimeout);
            reportAShorterLock(jobKey, granted, lockEnd, jobTimeout);
            schedule(Duration
                    .between(Instant.now(), lockEnd)
                    .dividedBy(2));
        }

        private void notExtended(final RuntimeException failure) {
            // the handler ended meanwhile, so whatever the engine answered no longer matters
            if (ended) {
                return;
            }
            if (ZenBpmErrors.lockLost(failure)) {
                ended = true;
                log.warn("""
                        ZenBPM[{}]: the lock of job {} (task '{}', workflow aggregate '{}') is lost while its handler \
                        runs: the lock lapsed, or another client holds the job. The engine may hand the job out \
                        again, so a second delivery may run beside this one.""", adapterId, jobKey, taskDefinition,
                        aggregateId);
                return;
            }
            if (ZenBpmErrors.isGoneJob(failure)) {
                ended = true;
                log.debug(
                        "ZenBPM[{}]: the lock of job {} (task '{}') is not renewed, the engine no longer holds the job",
                        adapterId, jobKey, taskDefinition);
                return;
            }
            if (ZenBpmErrors.isUnavailable(failure)) {
                // an eighth of the job-timeout at most, so an engine which is back soon is asked again
                // several times before the lock lapses
                final var doubled = FIRST_PAUSE.multipliedBy(1L << Math.min(unanswered, 20));
                final var longest = jobTimeout.dividedBy(8);
                final var pause = doubled.compareTo(longest) > 0 ? longest : doubled;
                ++unanswered;
                if (Instant
                        .now()
                        .plus(pause)
                        .isBefore(lockEnd)) {
                    schedule(pause);
                    return;
                }
            }
            ended = true;
            log.warn("""
                    ZenBPM[{}]: the lock of job {} (task '{}', workflow aggregate '{}') could not be renewed while its \
                    handler runs, so it lapses at {}. The engine may hand the job out again then, so a second \
                    delivery may run beside this one: {}""", adapterId, jobKey, taskDefinition, aggregateId, lockEnd,
                    failure.getMessage());
        }

    }

}

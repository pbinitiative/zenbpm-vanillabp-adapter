/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.client;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

import org.pbinitiative.zenbpmadapter.client.grpc.Zenbpm.WaitingJob;

/**
 * A job the engine handed out over the job stream, as it arrived, and the moment it arrived.
 * <p>
 * The job's <code>lock_until</code> is a time on the engine's clock. Where the clocks of engine
 * and application cannot be trusted to agree, the engine recommends counting the lock from the
 * moment of receipt instead, with the lock duration of the subscription the job came under.
 * That moment is {@link #receivedAt()}.
 *
 * @param job The job as the engine sent it
 * @param receivedAt When the job stream received it, on the application's clock
 */
public record ReceivedJob(
    WaitingJob job,
    Instant receivedAt
) {

    /**
     * Refuses a job or a moment which is missing.
     */
    public ReceivedJob {
        Objects.requireNonNull(job, "job");
        Objects.requireNonNull(receivedAt, "receivedAt");
    }

    /**
     * When the lock of this delivery lapses, counted the careful way: the earlier of the job's
     * <code>lock_until</code> and the moment of receipt plus the lock duration the job was
     * subscribed with. So neither an engine clock ahead of the application's nor a lock the engine
     * lowered to its maximum makes the lock look longer than it is. A job without a
     * <code>lock_until</code> counts from the moment of receipt alone.
     *
     * @param lockDuration The lock duration of the subscription the job came under
     * @return The moment, on the application's clock
     */
    public Instant lockEnd(final Duration lockDuration) {
        final var counted = receivedAt.plus(lockDuration);
        if (job.getLockUntil() <= 0) {
            return counted;
        }
        final var lockUntil = Instant.ofEpochMilli(job.getLockUntil());
        return lockUntil.isBefore(counted) ? lockUntil : counted;
    }

    /**
     * The input variables of the job, which the engine sends as a JSON object, decoded to the
     * types {@link org.pbinitiative.zenbpmadapter.client.api.Variables} describes, as the REST
     * answers are.
     *
     * @return The variables, which cannot be changed; empty where the job carries none
     * @throws IllegalStateException Where the job carries something which is no JSON object
     */
    public Map<String, Object> inputVariables() {
        return ZenBpmRestClient.variablesIn(job
                .getInputVariables()
                .toByteArray());
    }

}

/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.client;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

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
     * When the lock of this delivery lapses, counted on the application's clock from the moment
     * of receipt with the lock duration the job was subscribed with, as the engine recommends where
     * the clocks cannot be trusted to agree, see decision 36 in the repository's DECISIONS.md. The
     * job's <code>lock_until</code> is not part of it: an engine clock which runs behind the
     * application's would make every lock look shorter, by as much as it runs behind.
     *
     * @param lockDuration The lock duration of the subscription the job came under
     * @return The moment, on the application's clock
     */
    public Instant lockEnd(final Duration lockDuration) {
        return receivedAt.plus(lockDuration);
    }

    /**
     * When the engine says the lock of this delivery lapses, the job's <code>lock_until</code>.
     *
     * @return The moment, on the engine's clock; empty where the job carries none
     */
    public Optional<Instant> lockUntil() {
        if (job.getLockUntil() <= 0) {
            return Optional.empty();
        }
        return Optional.of(Instant.ofEpochMilli(job.getLockUntil()));
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

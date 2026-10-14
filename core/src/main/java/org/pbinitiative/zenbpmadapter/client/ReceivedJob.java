/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.client;

import java.time.Instant;
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

}

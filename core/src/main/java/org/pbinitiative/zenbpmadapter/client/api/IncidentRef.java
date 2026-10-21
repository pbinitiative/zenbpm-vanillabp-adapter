/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.client.api;

import java.time.Instant;

/**
 * One incident of a process instance as the engine reports it: a flow element whose job ran out of
 * retries, or which failed on something the engine could not do.
 *
 * @param key The key of the incident
 * @param elementInstanceKey The key of the entry of the instance's history at which it happened
 * @param elementId The id of the flow element
 * @param instanceKey The key of the process instance
 * @param message What the engine says went wrong
 * @param createdAt When it happened
 */
public record IncidentRef(
    long key,
    long elementInstanceKey,
    String elementId,
    long instanceKey,
    String message,
    Instant createdAt
) {
}

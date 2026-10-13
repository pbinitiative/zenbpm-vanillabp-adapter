/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.client.api;

import java.time.Instant;

/**
 * One flow element a process instance visited.
 *
 * @param key The key of the visit
 * @param elementId The id of the BPMN element
 * @param elementType The type of the element, e.g. <code>SERVICE_TASK</code> or
 *            <code>SEQUENCE_FLOW</code>
 * @param instanceKey The key of the process instance
 * @param createdAt When the element was entered
 * @param completedAt When the element was left; <code>null</code> where the engine reports no
 *            such moment, as for an element still active
 */
public record HistoryEntry(
    long key,
    String elementId,
    String elementType,
    long instanceKey,
    Instant createdAt,
    Instant completedAt
) {
}

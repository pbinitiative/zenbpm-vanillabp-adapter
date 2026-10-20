/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.client.api;

import java.time.Instant;
import java.util.Map;

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
 * @param inputVariables What the element was handed when it was entered; for a multi-instance
 *            element, the collection its iterations take their items from, as the engine evaluated
 *            it then, under the name of its input element (<code>""</code> where it names none)
 */
public record HistoryEntry(
    long key,
    String elementId,
    String elementType,
    long instanceKey,
    Instant createdAt,
    Instant completedAt,
    Map<String, Object> inputVariables
) {

    /**
     * Keeps the variables unmodifiable, so the record stays as it was read; none read are none.
     */
    public HistoryEntry {
        inputVariables = Variables.unmodifiable(inputVariables);
    }

}

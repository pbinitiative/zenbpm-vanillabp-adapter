/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.client.api;

import java.util.Map;

/**
 * How many element instances of one process definition are active or carry an incident, per BPMN
 * element (<code>GET /process-definitions/{key}/statistics</code>), summed over the engine's
 * partitions.
 *
 * @param elements The counts by element id
 */
public record DefinitionStatistics(
    Map<String, ElementCounts> elements
) {

    /**
     * Copies the counts, so the record stays as it was read.
     */
    public DefinitionStatistics {

        elements = Map.copyOf(elements);

    }

    /**
     * The counts of one BPMN element.
     *
     * @param active The element instances which are active now
     * @param incidents The incidents on the element
     */
    public record ElementCounts(
        long active,
        long incidents
    ) {
    }

}

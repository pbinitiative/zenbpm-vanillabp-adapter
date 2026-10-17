/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.client.api;

import java.util.List;

/**
 * What the engine reports about its build and its cluster (<code>GET /system/status</code>).
 *
 * @param version The version the engine was built as. A build of the engine's <code>main</code>
 *            reports the last release's version, so the commit is what tells two builds apart.
 * @param commit The commit the engine was built from, shortened by the engine
 * @param nodes The ids of the cluster's nodes, sorted
 * @param partitions The ids of the cluster's partitions, sorted. A key carries the partition which
 *            holds it, so a key of another partition is none the engine handed out.
 */
public record EngineStatus(
    String version,
    String commit,
    List<String> nodes,
    List<Integer> partitions
) {

    /**
     * Copies the node and partition ids, so the record stays as it was read.
     */
    public EngineStatus {
        nodes = List.copyOf(nodes);
        partitions = List.copyOf(partitions);
    }

}

/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.client.api;

import java.util.List;

/**
 * What the engine answers when asked whether it is ready (<code>GET /system/health/ready</code>).
 * Not ready is an answer like ready, not a failure: the engine says it with status 503 until its
 * cluster has a leader and its partitions are initialized.
 *
 * @param ready Whether the engine answered 200
 * @param reasons Why it is not ready, in the engine's words; empty when it is ready
 */
public record Readiness(
    boolean ready,
    List<String> reasons
) {

    /**
     * Copies the reasons, so the record stays as it was read.
     */
    public Readiness {

        reasons = List.copyOf(reasons);

    }

}

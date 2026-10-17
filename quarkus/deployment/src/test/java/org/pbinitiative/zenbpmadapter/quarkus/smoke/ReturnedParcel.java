/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.smoke;

import io.vanillabp.spi.service.NoSyncWithBPMS;

/**
 * The workflow aggregate of {@link ReturnsWorkflowService}, an aggregate of its own, because each
 * workflow service here is its aggregate's persistence. It shares nothing with the engine.
 */
@NoSyncWithBPMS
public class ReturnedParcel {
}

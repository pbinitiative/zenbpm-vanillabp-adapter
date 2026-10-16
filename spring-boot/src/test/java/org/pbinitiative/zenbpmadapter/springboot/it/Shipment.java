/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it;

import io.vanillabp.spi.service.NoSyncWithBPMS;

/**
 * The workflow aggregate of {@link ShipmentWorkflow}, which shares nothing with the engine.
 */
@NoSyncWithBPMS
public class Shipment {
}

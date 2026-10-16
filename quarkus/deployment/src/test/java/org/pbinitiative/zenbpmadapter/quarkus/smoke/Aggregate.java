/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.smoke;

import io.vanillabp.spi.service.NoSyncWithBPMS;

/**
 * The workflow aggregate of the smoke tests. It shares nothing with the engine: an aggregate which
 * marks nothing ends the start of an application, because it would hand every attribute to the
 * engine without anybody having decided so.
 */
@NoSyncWithBPMS
public class Aggregate {
}

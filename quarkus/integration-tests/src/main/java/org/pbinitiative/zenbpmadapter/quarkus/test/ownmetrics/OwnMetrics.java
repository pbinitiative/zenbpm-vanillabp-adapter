/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.test.ownmetrics;

import org.pbinitiative.zenbpmadapter.observability.ZenBpmMetrics;

import jakarta.inject.Singleton;

/**
 * The application's own measurement of the adapter's numbers, which takes the place of the
 * adapter's meter binder.
 */
@Singleton
public class OwnMetrics implements ZenBpmMetrics {

    /**
     * Built by CDI.
     */
    public OwnMetrics() {
    }

}

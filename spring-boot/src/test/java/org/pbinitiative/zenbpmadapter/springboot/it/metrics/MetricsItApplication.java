/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.metrics;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The application <code>ZenBpmMetricsIT</code> boots against the pinned engine: the workflow
 * {@link CrateWorkflow} of the JPA aggregate {@link Crate}. It scans this package only, so the
 * application of another integration test, in a package of its own, stays out of it.
 */
@SpringBootApplication
public class MetricsItApplication {

    /**
     * Built by Spring.
     */
    public MetricsItApplication() {
    }

}

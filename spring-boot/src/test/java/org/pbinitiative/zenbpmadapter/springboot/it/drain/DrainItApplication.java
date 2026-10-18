/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.drain;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The application <code>ZenBpmShutdownDrainIT</code> boots once or twice against one engine, as
 * the nodes of one application on one database: the workflow {@link ParcelDrainWorkflow} of the JPA
 * aggregate {@link DrainedParcel}. It scans this package only, so the application of another
 * integration test, in a package of its own, stays out of it.
 */
@SpringBootApplication
public class DrainItApplication {

    /**
     * Built by Spring.
     */
    public DrainItApplication() {
    }

}

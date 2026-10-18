/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.lifecycle;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The application of <code>ZenBpmLifecycleIT</code>: the workflows of three JPA aggregates, each
 * with a <code>&#64;WorkflowStartedByBpms</code> method. {@link TickWorkflow} is started by a timer,
 * {@link DeliveryWorkflow} by the application or past VanillaBP, and it calls
 * {@link InvoiceWorkflow}, which works on an aggregate of its own. It scans this package only, so
 * the application of another integration test, in a package of its own, stays out of it.
 */
@SpringBootApplication
public class LifecycleItApplication {

    /**
     * Built by Spring.
     */
    public LifecycleItApplication() {
    }

}

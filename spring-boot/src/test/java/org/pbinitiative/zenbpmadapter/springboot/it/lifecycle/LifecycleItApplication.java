/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.lifecycle;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The application of <code>ZenBpmLifecycleIT</code>: the workflows of six JPA aggregates. Three of
 * them have a <code>&#64;WorkflowStartedByBpms</code> method: {@link TickWorkflow} is started by a
 * timer, {@link DeliveryWorkflow} by the application or past VanillaBP, and it calls
 * {@link InvoiceWorkflow}, which works on an aggregate of its own. Two have a
 * <code>&#64;WorkflowEnded</code> method and are started by the application: {@link ReturnWorkflow},
 * whose branches end apart, and {@link SettlementWorkflow}, which ends while an event sub-process of
 * it may still run. {@link PickingWorkflow} iterates over the parcels of its aggregate in three
 * multi-instance elements, one of which calls a process of its own on the same aggregate. It scans this package only, so the application of another integration test, in
 * a package of its own, stays out of it.
 */
@SpringBootApplication
public class LifecycleItApplication {

    /**
     * Built by Spring.
     */
    public LifecycleItApplication() {
    }

}

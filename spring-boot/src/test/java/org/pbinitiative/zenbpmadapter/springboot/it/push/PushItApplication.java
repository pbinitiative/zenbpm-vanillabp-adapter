/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.push;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The application of <code>ZenBpmAggregateChangedIT</code>: the workflow {@link ShipmentWorkflow} of
 * the JPA aggregate {@link Shipment}, whose gateways decide on a value the aggregate pushes. It
 * scans this package only, so the application of another integration test, in a package of its own,
 * stays out of it.
 */
@SpringBootApplication
public class PushItApplication {

    /**
     * Built by Spring.
     */
    public PushItApplication() {
    }

}

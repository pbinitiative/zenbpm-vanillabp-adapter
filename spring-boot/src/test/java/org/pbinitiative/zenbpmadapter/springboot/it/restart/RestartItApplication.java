/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.restart;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The application <code>ZenBpmRestartDeliveryIT</code> boots, stops and boots again against one
 * engine and one database, as a restart does: the workflow {@link BookingWorkflow} of the JPA
 * aggregate {@link Booking}. It scans this package only, so the application of another integration
 * test, in a package of its own, stays out of it.
 */
@SpringBootApplication
public class RestartItApplication {

    /**
     * Built by Spring.
     */
    public RestartItApplication() {
    }

}

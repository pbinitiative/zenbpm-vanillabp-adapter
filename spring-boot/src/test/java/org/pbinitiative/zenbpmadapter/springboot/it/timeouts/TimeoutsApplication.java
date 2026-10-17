/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.timeouts;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The application <code>ZenBpmJobTimeoutBootTest</code> boots: two workflows, {@link PackingWorkflow}
 * and {@link ReturnsWorkflow}, whose tasks share one task definition, so under
 * <code>name-clash-avoidance: none</code> one job type. It scans this package only, so the
 * application of another test, in a package of its own, stays out of it.
 */
@SpringBootApplication
public class TimeoutsApplication {

    /**
     * Built by Spring.
     */
    public TimeoutsApplication() {
    }

}

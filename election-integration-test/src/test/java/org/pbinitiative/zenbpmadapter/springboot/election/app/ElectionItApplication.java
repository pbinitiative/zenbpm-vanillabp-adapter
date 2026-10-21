/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.election.app;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The application the election tests boot against the pinned engine: the workflows
 * {@link ShippingWorkflow} and {@link ParcelingWorkflow} of JPA aggregates on an in-memory
 * database. Which adapter ids serve them is configured by each test.
 * <p>
 * The test classes of this module are one directory of the classpath with one descriptor, so its
 * workflows are those of the workflow module <code>election-it</code>.
 */
@SpringBootApplication
public class ElectionItApplication {

    /**
     * Built by Spring.
     */
    public ElectionItApplication() {
    }

}

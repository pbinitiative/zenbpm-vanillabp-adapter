/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.start;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The application <code>ZenBpmDeploymentAndStartIT</code> boots against the pinned engine: the
 * workflow {@link OrderWorkflow} of the JPA aggregate {@link Order} on an in-memory database, which
 * the platform serves with its JDBC phase-two outbox. It scans this package only, so the
 * application of another integration test, in a package of its own, stays out of it.
 * <p>
 * The test classes of this module are one directory of the classpath with one descriptor, so its
 * workflow is one of the workflow module <code>deploy-it</code>.
 */
@SpringBootApplication
public class StartItApplication {

    /**
     * Built by Spring.
     */
    public StartItApplication() {
    }

}

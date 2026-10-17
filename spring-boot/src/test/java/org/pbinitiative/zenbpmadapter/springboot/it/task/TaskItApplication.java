/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.task;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The application <code>ZenBpmTaskProcessingIT</code> boots against the pinned engine: the workflow
 * {@link ParcelWorkflow} of the JPA aggregate {@link Parcel} on an in-memory database, whose tasks
 * the adapter delivers. It scans this package only, so the application of another integration test,
 * in a package of its own, stays out of it.
 * <p>
 * The test classes of this module are one directory of the classpath with one descriptor, so its
 * workflow is one of the workflow module <code>deploy-it</code>.
 */
@SpringBootApplication
public class TaskItApplication {

    /**
     * Built by Spring.
     */
    public TaskItApplication() {
    }

}

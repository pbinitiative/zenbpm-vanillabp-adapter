/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.usertask;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The application <code>ZenBpmUserTaskIT</code> boots against the pinned engine: the workflows
 * {@link ApprovalWorkflow}, {@link OfferWorkflow}, {@link QuoteWorkflow} and {@link SignatureWorkflow} of JPA aggregates on
 * an in-memory database, whose user tasks the adapter looks for. It scans this package only, so the
 * application of another integration test, in a package of its own, stays out of it.
 * <p>
 * The test classes of this module are one directory of the classpath with one descriptor, so its
 * workflows are those of the workflow module <code>deploy-it</code>.
 */
@SpringBootApplication
public class UserTaskItApplication {

    /**
     * Built by Spring.
     */
    public UserTaskItApplication() {
    }

}

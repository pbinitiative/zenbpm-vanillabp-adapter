/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.purchaseapproval;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The application: the workflow module <code>purchase-approval</code> on an in-memory database,
 * running its process on the ZenBPM engine <code>application.yaml</code> names.
 */
@SpringBootApplication
public class PurchaseApprovalApplication {

    /**
     * Starts the application.
     *
     * @param args The command line
     */
    public static void main(final String[] args) {
        SpringApplication.run(PurchaseApprovalApplication.class, args);
    }

}

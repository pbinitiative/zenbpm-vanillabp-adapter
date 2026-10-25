/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.banktransferorder;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The application: the workflow module <code>bank-transfer-order</code> on an in-memory database,
 * running its process on the ZenBPM engine <code>application.yaml</code> names.
 */
@SpringBootApplication
public class BankTransferOrderApplication {

    /**
     * Starts the application.
     *
     * @param args The command line
     */
    public static void main(final String[] args) {
        SpringApplication.run(BankTransferOrderApplication.class, args);
    }

}

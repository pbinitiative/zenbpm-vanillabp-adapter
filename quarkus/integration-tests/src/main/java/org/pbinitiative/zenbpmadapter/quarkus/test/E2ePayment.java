/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.test;

import io.vanillabp.spi.service.NoSyncWithBPMS;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The workflow aggregate of the process <code>TaskFailures</code>: a payment whose card is
 * declined every time it is charged. It shares nothing with the engine but its id.
 */
@NoSyncWithBPMS
@Entity
@Table(name = "ZENBPM_E2E_PAYMENT")
public class E2ePayment {

    @Id
    private String id;

    /**
     * Built by JPA.
     */
    protected E2ePayment() {
    }

    /**
     * A payment which is not saved yet.
     *
     * @param id The id, which becomes the business key of its workflow
     */
    public E2ePayment(final String id) {
        this.id = id;
    }

    /**
     * @return The id
     */
    public String getId() {
        return id;
    }

}

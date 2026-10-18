/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.lifecycle;

import io.vanillabp.spi.service.NoSyncWithBPMS;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The workflow aggregate of the process a delivery calls: an aggregate of its own, not the
 * delivery's, and whether its task ran.
 */
@NoSyncWithBPMS
@Entity
@Table(name = "LIFECYCLE_IT_INVOICE")
public class Invoice {

    @Id
    private String id;

    private boolean issued;

    /**
     * For JPA.
     */
    protected Invoice() {
    }

    /**
     * An invoice the application's method built.
     *
     * @param id The id
     */
    public Invoice(final String id) {
        this.id = id;
    }

    /**
     * The id.
     *
     * @return The id
     */
    public String getId() {
        return id;
    }

    /**
     * Whether the task of the workflow ran.
     *
     * @return <code>true</code> once it ran
     */
    public boolean isIssued() {
        return issued;
    }

    /**
     * Writes down that the task of the workflow ran.
     */
    public void issue() {
        issued = true;
    }

}

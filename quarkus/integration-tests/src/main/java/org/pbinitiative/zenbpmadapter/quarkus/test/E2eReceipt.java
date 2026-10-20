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
 * The workflow aggregate of the process <code>Receiving</code>, which only ever starts past
 * VanillaBP: the application's <code>&#64;WorkflowStartedByBpms</code> method builds it.
 */
@NoSyncWithBPMS
@Entity
@Table(name = "ZENBPM_E2E_RECEIPT")
public class E2eReceipt {

    @Id
    private String id;

    private String orderRef;

    private boolean unloaded;

    /**
     * Built by JPA.
     */
    protected E2eReceipt() {
    }

    /**
     * The receipt of an order, not unloaded yet.
     *
     * @param id The id, which the workflow is named after
     * @param orderRef The order, which the start carried
     */
    public E2eReceipt(
        final String id,
        final String orderRef
    ) {
        this.id = id;
        this.orderRef = orderRef;
    }

    /**
     * @return The id
     */
    public String getId() {
        return id;
    }

    /**
     * @return The order the start carried
     */
    public String getOrderRef() {
        return orderRef;
    }

    /**
     * @return Whether the goods were unloaded
     */
    public boolean isUnloaded() {
        return unloaded;
    }

    /**
     * Writes down that the goods were unloaded.
     */
    public void unload() {
        unloaded = true;
    }

}

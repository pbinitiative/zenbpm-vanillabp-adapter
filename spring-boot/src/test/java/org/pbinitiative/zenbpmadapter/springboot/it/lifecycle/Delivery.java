/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.lifecycle;

import io.vanillabp.spi.service.NoSyncWithBPMS;
import io.vanillabp.spi.service.SyncWithBPMS;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The workflow aggregate of the process the application starts itself, or somebody past
 * VanillaBP: the order it delivers, which every start carries as a variable, and whether its task
 * ran.
 */
@NoSyncWithBPMS
@Entity
@Table(name = "LIFECYCLE_IT_DELIVERY")
public class Delivery {

    @Id
    private String id;

    @SyncWithBPMS
    private String orderRef;

    private boolean delivered;

    /**
     * For JPA.
     */
    protected Delivery() {
    }

    /**
     * A delivery of an order.
     *
     * @param id The id
     * @param orderRef The order
     */
    public Delivery(
        final String id,
        final String orderRef
    ) {
        this.id = id;
        this.orderRef = orderRef;
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
     * The order it delivers, which travels with the workflow.
     *
     * @return The order
     */
    public String getOrderRef() {
        return orderRef;
    }

    /**
     * Whether the task of the workflow ran.
     *
     * @return <code>true</code> once it ran
     */
    public boolean isDelivered() {
        return delivered;
    }

    /**
     * Writes down that the task of the workflow ran.
     */
    public void deliver() {
        delivered = true;
    }

}

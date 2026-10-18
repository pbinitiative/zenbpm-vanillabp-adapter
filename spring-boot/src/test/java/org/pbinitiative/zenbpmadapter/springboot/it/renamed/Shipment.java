/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.renamed;

import io.vanillabp.spi.service.NoSyncWithBPMS;
import io.vanillabp.spi.service.SyncWithBPMS;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The workflow aggregate of both versions of the application of the rename: a shipment, which the
 * task shipping it marks with the version of the application which ran it and the destination the
 * task was given. It shares its destination.
 */
@NoSyncWithBPMS
@Entity
@Table(name = "RENAMED_IT_SHIPMENT")
public class Shipment {

    @Id
    private String id;

    @SyncWithBPMS
    private String destination;

    private String shippedBy;

    private String shippedTo;

    /**
     * For JPA.
     */
    protected Shipment() {
    }

    /**
     * A new shipment.
     *
     * @param id The id
     * @param destination The destination, which the workflow carries
     */
    public Shipment(
        final String id,
        final String destination
    ) {
        this.id = id;
        this.destination = destination;
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
     * The destination, which the workflow carries.
     *
     * @return The destination
     */
    public String getDestination() {
        return destination;
    }

    /**
     * The version of the application which shipped it.
     *
     * @return The version, <code>null</code> before
     */
    public String getShippedBy() {
        return shippedBy;
    }

    /**
     * The destination the task which shipped it was given.
     *
     * @return The destination, <code>null</code> before
     */
    public String getShippedTo() {
        return shippedTo;
    }

    /**
     * Ships the shipment.
     *
     * @param version The version of the application which ships it
     * @param givenDestination The destination the task was given
     */
    public void ship(
        final String version,
        final String givenDestination
    ) {
        this.shippedBy = version;
        this.shippedTo = givenDestination;
    }

}

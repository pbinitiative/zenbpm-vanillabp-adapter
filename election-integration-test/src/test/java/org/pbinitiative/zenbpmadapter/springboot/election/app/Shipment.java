/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.election.app;

import io.vanillabp.spi.service.NoSyncWithBPMS;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The workflow aggregate of the process <code>Shipping</code>: a shipment, which a person confirms
 * in a user task before a service task ships it. It shares nothing with the engine but its id.
 */
@NoSyncWithBPMS
@Entity
@Table(name = "ELECTION_IT_SHIPMENT")
public class Shipment {

    @Id
    private String id;

    private String confirmationTaskId;

    private boolean shipped;

    /**
     * For JPA.
     */
    protected Shipment() {
    }

    /**
     * A new shipment.
     *
     * @param id The id
     */
    public Shipment(final String id) {
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
     * The task id of the user task which asks for the confirmation, as the notification of its
     * creation named it.
     *
     * @return The id, <code>null</code> before the notification
     */
    public String getConfirmationTaskId() {
        return confirmationTaskId;
    }

    /**
     * Whether the service task shipped it.
     *
     * @return Whether it was shipped
     */
    public boolean isShipped() {
        return shipped;
    }

    /**
     * Writes down that a person was asked to confirm the shipment.
     *
     * @param taskId The user task
     */
    public void confirmationAsked(final String taskId) {
        this.confirmationTaskId = taskId;
    }

    /**
     * Ships the shipment.
     */
    public void ship() {
        this.shipped = true;
    }

}

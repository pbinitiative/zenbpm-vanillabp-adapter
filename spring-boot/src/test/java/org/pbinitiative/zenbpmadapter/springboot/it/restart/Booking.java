/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.restart;

import io.vanillabp.spi.service.NoSyncWithBPMS;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The workflow aggregate of the restart: a booking whose confirmation the application sends later.
 * It counts every committed run of the task's handler, so a test sees a handler which ran twice in
 * the database, whichever application ran it.
 */
@NoSyncWithBPMS
@Entity
@Table(name = "RESTART_IT_BOOKING")
public class Booking {

    @Id
    private String id;

    private String confirmationTaskId;

    private int confirmationRequests;

    private boolean confirmed;

    /**
     * For JPA.
     */
    protected Booking() {
    }

    /**
     * A new booking.
     *
     * @param id The id
     */
    public Booking(final String id) {
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
     * The task the confirmation completes.
     *
     * @return The task's id, <code>null</code> before the task was delivered
     */
    public String getConfirmationTaskId() {
        return confirmationTaskId;
    }

    /**
     * How often the handler of the task asked for the confirmation, as committed.
     *
     * @return The number of committed runs
     */
    public int getConfirmationRequests() {
        return confirmationRequests;
    }

    /**
     * Whether the application confirmed the booking.
     *
     * @return <code>true</code> once confirmed
     */
    public boolean isConfirmed() {
        return confirmed;
    }

    /**
     * Asks for the confirmation, which completes the task later.
     *
     * @param taskId The task
     */
    public void requestConfirmation(final String taskId) {
        confirmationTaskId = taskId;
        ++confirmationRequests;
    }

    /**
     * Confirms the booking.
     */
    public void confirm() {
        confirmed = true;
    }

}

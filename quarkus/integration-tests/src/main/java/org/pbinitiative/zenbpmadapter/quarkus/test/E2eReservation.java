/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.test;

import org.hibernate.annotations.DynamicUpdate;

import io.vanillabp.spi.service.NoSyncWithBPMS;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The workflow aggregate of the process <code>TaskTakenAway</code>: a room is held and a payment
 * checked, both left open for the application, and a timer takes the hold away. The two tasks run
 * in parallel and each writes attributes of its own, so only what changed is written.
 */
@DynamicUpdate
@NoSyncWithBPMS
@Entity
@Table(name = "ZENBPM_E2E_RESERVATION")
public class E2eReservation {

    @Id
    private String id;

    private String holdTaskId;

    private String checkTaskId;

    private int holdCancellations;

    /**
     * Built by JPA.
     */
    protected E2eReservation() {
    }

    /**
     * A reservation which is not saved yet.
     *
     * @param id The id, which becomes the business key of its workflow
     */
    public E2eReservation(final String id) {
        this.id = id;
    }

    /**
     * @return The id
     */
    public String getId() {
        return id;
    }

    /**
     * @return The task holding the room, <code>null</code> before it was delivered
     */
    public String getHoldTaskId() {
        return holdTaskId;
    }

    /**
     * @return The task checking the payment, <code>null</code> before it was delivered
     */
    public String getCheckTaskId() {
        return checkTaskId;
    }

    /**
     * @return How often the hold was reported cancelled
     */
    public int getHoldCancellations() {
        return holdCancellations;
    }

    void hold(final String taskId) {
        holdTaskId = taskId;
    }

    void holdCanceled() {
        ++holdCancellations;
    }

    void check(final String taskId) {
        checkTaskId = taskId;
    }

}

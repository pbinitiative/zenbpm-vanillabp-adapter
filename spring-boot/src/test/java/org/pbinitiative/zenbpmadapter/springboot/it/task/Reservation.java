/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.task;

import org.hibernate.annotations.DynamicUpdate;

import io.vanillabp.spi.service.NoSyncWithBPMS;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The workflow aggregate of two tasks left open side by side, one of which an interrupting timer
 * boundary event takes away. It writes down what happened to each, so a test reads whether the
 * platform reported the task taken away as cancelled, and how often. The model reads none of it.
 * <p>
 * The handlers of the two branches run side by side and each writes an attribute of its own, so an
 * update writes the changed attributes only, and neither overwrites what the other wrote.
 */
@NoSyncWithBPMS
@DynamicUpdate
@Entity
@Table(name = "TASK_IT_RESERVATION")
public class Reservation {

    @Id
    private String id;

    private String holdTaskId;

    private String checkTaskId;

    private int holdCancellations;

    /**
     * For JPA.
     */
    protected Reservation() {
    }

    /**
     * A new reservation.
     *
     * @param id The id
     */
    public Reservation(final String id) {
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
     * The task id of the hold, which the timer takes away.
     *
     * @return The id, <code>null</code> before its handler ran
     */
    public String getHoldTaskId() {
        return holdTaskId;
    }

    /**
     * The task id of the check, which stays open.
     *
     * @return The id, <code>null</code> before its handler ran
     */
    public String getCheckTaskId() {
        return checkTaskId;
    }

    /**
     * How often the hold was reported cancelled.
     *
     * @return The number of reports
     */
    public int getHoldCancellations() {
        return holdCancellations;
    }

    void hold(final String taskId) {
        this.holdTaskId = taskId;
    }

    void holdCanceled() {
        ++holdCancellations;
    }

    void check(final String taskId) {
        this.checkTaskId = taskId;
    }

}

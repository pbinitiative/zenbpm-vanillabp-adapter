/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.usertask;

import io.vanillabp.spi.service.NoSyncWithBPMS;
import io.vanillabp.spi.service.SyncWithBPMS;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The workflow aggregate of a review, a user task which the application completes or cancels: it
 * shares whether the paper was accepted, which the gateway behind the user task decides on, and
 * counts how often the application was told that the user task was created and that it was
 * cancelled.
 */
@NoSyncWithBPMS
@Entity
@Table(name = "USER_TASK_IT_REVIEW")
public class Review {

    @Id
    private String id;

    @SyncWithBPMS
    private boolean accepted;

    private String reviewTaskId;

    private int creations;

    private int cancellations;

    /**
     * For JPA.
     */
    protected Review() {
    }

    /**
     * A new review, of a paper not accepted yet.
     *
     * @param id The id
     */
    public Review(final String id) {
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
     * Whether the paper was accepted.
     *
     * @return <code>true</code> once accepted
     */
    public boolean isAccepted() {
        return accepted;
    }

    /**
     * Accepts the paper.
     */
    public void accept() {
        accepted = true;
    }

    /**
     * The task id of the user task, as the notification of its creation named it.
     *
     * @return The id, <code>null</code> before the notification
     */
    public String getReviewTaskId() {
        return reviewTaskId;
    }

    /**
     * How often the application was told that the user task was created.
     *
     * @return The number of notifications
     */
    public int getCreations() {
        return creations;
    }

    /**
     * How often the application was told that the user task was cancelled.
     *
     * @return The number of notifications
     */
    public int getCancellations() {
        return cancellations;
    }

    void created(final String taskId) {
        ++creations;
        this.reviewTaskId = taskId;
    }

    void canceled() {
        ++cancellations;
    }

}

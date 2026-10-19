/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.test;

import io.vanillabp.spi.service.NoSyncWithBPMS;
import io.vanillabp.spi.service.SyncWithBPMS;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The workflow aggregate of the process <code>Reviewing</code>: a review, a user task which the
 * application completes or cancels. It shares only whether the paper was accepted, which the gateway
 * behind the user task decides on, and counts how often the application was told that the user task
 * was created and that it was cancelled.
 */
@NoSyncWithBPMS
@Entity
@Table(name = "ZENBPM_E2E_REVIEW")
public class E2eReview {

    @Id
    private String id;

    @SyncWithBPMS
    private boolean accepted;

    private String reviewTaskId;

    private int creations;

    private int cancellations;

    /**
     * Built by JPA.
     */
    protected E2eReview() {
    }

    /**
     * A review which is not saved yet, of a paper not accepted yet.
     *
     * @param id The id, which becomes the business key of its workflow
     */
    public E2eReview(final String id) {
        this.id = id;
    }

    /**
     * @return The id
     */
    public String getId() {
        return id;
    }

    /**
     * @return Whether the paper was accepted
     */
    public boolean isAccepted() {
        return accepted;
    }

    /**
     * @return The user task, as the notification of its creation named it, <code>null</code> before
     */
    public String getReviewTaskId() {
        return reviewTaskId;
    }

    /**
     * @return How often the application was told that the user task was created
     */
    public int getCreations() {
        return creations;
    }

    /**
     * @return How often the application was told that the user task was cancelled
     */
    public int getCancellations() {
        return cancellations;
    }

    void accept() {
        accepted = true;
    }

    void created(final String taskId) {
        ++creations;
        reviewTaskId = taskId;
    }

    void canceled() {
        ++cancellations;
    }

}

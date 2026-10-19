/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.usertask;

import io.vanillabp.spi.service.NoSyncWithBPMS;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The workflow aggregate of a user task which a timer takes away while a service task of the same
 * workflow waits: it counts the notifications of the user task and whether the service task ran.
 */
@NoSyncWithBPMS
@Entity
@Table(name = "USER_TASK_IT_OFFER")
public class Offer {

    @Id
    private String id;

    private String acceptanceTaskId;

    private int creations;

    private int cancellations;

    private boolean archived;

    /**
     * For JPA.
     */
    protected Offer() {
    }

    /**
     * A new offer.
     *
     * @param id The id
     */
    public Offer(final String id) {
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
     * The task id of the user task, as the notification of its creation named it.
     *
     * @return The id, <code>null</code> before the notification
     */
    public String getAcceptanceTaskId() {
        return acceptanceTaskId;
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

    /**
     * Whether the service task behind the timer ran.
     *
     * @return Whether it ran
     */
    public boolean isArchived() {
        return archived;
    }

    void created(final String taskId) {
        ++creations;
        this.acceptanceTaskId = taskId;
    }

    void canceled() {
        ++cancellations;
    }

    void archive() {
        this.archived = true;
    }

}

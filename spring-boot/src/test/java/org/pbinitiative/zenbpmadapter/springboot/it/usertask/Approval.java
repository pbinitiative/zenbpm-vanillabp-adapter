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
 * The workflow aggregate of a user task which waits for a person: it counts how often the
 * application was told that the task was created and that it was cancelled.
 */
@NoSyncWithBPMS
@Entity
@Table(name = "USER_TASK_IT_APPROVAL")
public class Approval {

    @Id
    private String id;

    private String approvalTaskId;

    private int creations;

    private int cancellations;

    /**
     * For JPA.
     */
    protected Approval() {
    }

    /**
     * A new approval.
     *
     * @param id The id
     */
    public Approval(final String id) {
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
    public String getApprovalTaskId() {
        return approvalTaskId;
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
        this.approvalTaskId = taskId;
    }

    void canceled() {
        ++cancellations;
    }

}

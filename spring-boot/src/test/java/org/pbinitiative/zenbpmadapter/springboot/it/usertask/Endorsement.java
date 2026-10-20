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
 * The workflow aggregate of a request a person endorses unless it is withdrawn first: it counts how
 * often the application was told that the user task was created and that it was cancelled, and how
 * often the workflow ended.
 */
@NoSyncWithBPMS
@Entity
@Table(name = "USER_TASK_IT_ENDORSEMENT")
public class Endorsement {

    @Id
    private String id;

    private int creations;

    private int cancellations;

    private int ends;

    /**
     * For JPA.
     */
    protected Endorsement() {
    }

    /**
     * A new endorsement.
     *
     * @param id The id
     */
    public Endorsement(final String id) {
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
     * How often the application was told that the workflow ended.
     *
     * @return The number of reports
     */
    public int getEnds() {
        return ends;
    }

    void created() {
        ++creations;
    }

    void canceled() {
        ++cancellations;
    }

    void ended() {
        ++ends;
    }

}

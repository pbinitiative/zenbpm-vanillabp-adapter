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
 * The workflow aggregate of the process <code>Parceling</code>: a parcel, whose workflow waits for
 * an hour at a timer, so it runs throughout a test without a task the application would be handed.
 */
@NoSyncWithBPMS
@Entity
@Table(name = "ELECTION_IT_PARCEL")
public class Parcel {

    @Id
    private String id;

    /**
     * For JPA.
     */
    protected Parcel() {
    }

    /**
     * A new parcel.
     *
     * @param id The id
     */
    public Parcel(final String id) {
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

}

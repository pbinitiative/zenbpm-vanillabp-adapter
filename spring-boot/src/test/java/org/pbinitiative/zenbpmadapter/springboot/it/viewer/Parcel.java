/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.viewer;

import io.vanillabp.spi.service.NoSyncWithBPMS;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The workflow aggregate of the viewer IT: a parcel, and whether its first task is to fail, which
 * leaves an incident at the engine.
 */
@NoSyncWithBPMS
@Entity
@Table(name = "VIEWER_IT_PARCEL")
public class Parcel {

    @Id
    private String id;

    private boolean checkFails;

    /**
     * For JPA.
     */
    protected Parcel() {
    }

    /**
     * A parcel.
     *
     * @param id The id
     * @param checkFails Whether its first task is to fail
     */
    public Parcel(
        final String id,
        final boolean checkFails
    ) {
        this.id = id;
        this.checkFails = checkFails;
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
     * Whether its first task is to fail.
     *
     * @return <code>true</code> where it fails
     */
    public boolean isCheckFails() {
        return checkFails;
    }

}

/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.metrics;

import io.vanillabp.spi.service.NoSyncWithBPMS;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The second workflow aggregate of the meters: a pallet whose label the application prints later.
 */
@NoSyncWithBPMS
@Entity
@Table(name = "METRICS_IT_PALLET")
public class Pallet {

    @Id
    private String id;

    /**
     * For JPA.
     */
    protected Pallet() {
    }

    /**
     * A new pallet.
     *
     * @param id The id
     */
    public Pallet(final String id) {
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

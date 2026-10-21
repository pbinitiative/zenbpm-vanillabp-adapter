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
 * The workflow aggregate of the meters: a crate which is weighed and sealed.
 */
@NoSyncWithBPMS
@Entity
@Table(name = "METRICS_IT_CRATE")
public class Crate {

    @Id
    private String id;

    /**
     * For JPA.
     */
    protected Crate() {
    }

    /**
     * A new crate.
     *
     * @param id The id
     */
    public Crate(final String id) {
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

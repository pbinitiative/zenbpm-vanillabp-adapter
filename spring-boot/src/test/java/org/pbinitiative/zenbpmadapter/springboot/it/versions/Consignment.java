/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.versions;

import io.vanillabp.spi.service.NoSyncWithBPMS;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The workflow aggregate of the applications of <code>ZenBpmProcessVersionIT</code> and
 * <code>ZenBpmOldProcessVersionsIT</code>: a consignment,
 * which the task serving it marks with the method which ran, so a test reads which version
 * specification matched.
 */
@NoSyncWithBPMS
@Entity
@Table(name = "VERSIONS_IT_CONSIGNMENT")
public class Consignment {

    @Id
    private String id;

    private String servedBy;

    /**
     * For JPA.
     */
    protected Consignment() {
    }

    /**
     * A new consignment.
     *
     * @param id The id
     */
    public Consignment(final String id) {
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
     * The method which served the consignment's task.
     *
     * @return What the method calls itself, <code>null</code> before
     */
    public String getServedBy() {
        return servedBy;
    }

    /**
     * Marks the consignment as served.
     *
     * @param method What the method which served it calls itself
     */
    public void servedBy(final String method) {
        this.servedBy = method;
    }

}

/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.test;

import io.vanillabp.spi.service.NoSyncWithBPMS;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The workflow aggregate of the process <code>Sorting</code>, whose task is served by the method of the version tag of its workflow's version: the tag of the method which served it.
 */
@NoSyncWithBPMS
@Entity
@Table(name = "ZENBPM_E2E_SORTING")
public class E2eSorting {

    @Id
    private String id;

    private String servedBy;

    /**
     * Built by JPA.
     */
    protected E2eSorting() {
    }

    /**
     * An aggregate of the given id.
     *
     * @param id The id
     */
    public E2eSorting(final String id) {
        this.id = id;
    }

    /**
     * @return The id
     */
    public String getId() {
        return id;
    }

    /**
     * @return The tag of the method which served the task, <code>null</code> before one did
     */
    public String getServedBy() {
        return servedBy;
    }

    /**
     * Writes down which method served the task.
     *
     * @param method The tag of the method
     */
    public void servedBy(final String method) {
        servedBy = method;
    }

}

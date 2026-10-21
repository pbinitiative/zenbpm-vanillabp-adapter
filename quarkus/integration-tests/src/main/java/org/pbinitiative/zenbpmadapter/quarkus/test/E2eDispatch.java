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
 * The workflow aggregate of the process <code>Dispatching</code> and of <code>Boxing</code>, which it calls, whose definitions, models and history the viewer shows.
 */
@NoSyncWithBPMS
@Entity
@Table(name = "ZENBPM_E2E_DISPATCH")
public class E2eDispatch {

    @Id
    private String id;

    /**
     * Built by JPA.
     */
    protected E2eDispatch() {
    }

    /**
     * An aggregate of the given id.
     *
     * @param id The id
     */
    public E2eDispatch(final String id) {
        this.id = id;
    }

    /**
     * @return The id
     */
    public String getId() {
        return id;
    }

}

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
 * The workflow aggregate of the process <code>Storing</code>, of whose older version a workflow runs while the application starts.
 */
@NoSyncWithBPMS
@Entity
@Table(name = "ZENBPM_E2E_STOCK")
public class E2eStock {

    @Id
    private String id;

    /**
     * Built by JPA.
     */
    protected E2eStock() {
    }

    /**
     * An aggregate of the given id.
     *
     * @param id The id
     */
    public E2eStock(final String id) {
        this.id = id;
    }

    /**
     * @return The id
     */
    public String getId() {
        return id;
    }

}

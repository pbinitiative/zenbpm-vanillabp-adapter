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
 * The workflow aggregate of a user task which the application marks as served elsewhere.
 */
@NoSyncWithBPMS
@Entity
@Table(name = "USER_TASK_IT_SIGNATURE")
public class Signature {

    @Id
    private String id;

    /**
     * For JPA.
     */
    protected Signature() {
    }

    /**
     * A new signature.
     *
     * @param id The id
     */
    public Signature(final String id) {
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

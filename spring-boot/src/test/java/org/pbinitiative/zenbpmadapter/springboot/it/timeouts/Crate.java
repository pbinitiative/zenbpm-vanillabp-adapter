/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.timeouts;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * An aggregate of one of the two workflows, which never runs: the start ends before.
 */
@Entity
@Table(name = "TIMEOUTS_CRATE")
public class Crate {

    @Id
    private String id;

    /**
     * Built by JPA.
     */
    protected Crate() {
    }

    public String getId() {
        return id;
    }

}

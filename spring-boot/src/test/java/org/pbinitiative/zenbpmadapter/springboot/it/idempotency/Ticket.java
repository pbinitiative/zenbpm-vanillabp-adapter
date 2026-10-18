/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.idempotency;

import io.vanillabp.spi.service.NoSyncWithBPMS;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The workflow aggregate of the inbound idempotency: a ticket which is printed, and which counts
 * every print that was committed, so a test sees a handler which ran twice in the database.
 */
@NoSyncWithBPMS
@Entity
@Table(name = "IDEMPOTENCY_IT_TICKET")
public class Ticket {

    @Id
    private String id;

    private int prints;

    /**
     * For JPA.
     */
    protected Ticket() {
    }

    /**
     * A new ticket, printed never.
     *
     * @param id The id
     */
    public Ticket(final String id) {
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
     * How often a print of the ticket was committed.
     *
     * @return The number of committed prints
     */
    public int getPrints() {
        return prints;
    }

    /**
     * Records a print.
     */
    public void print() {
        ++prints;
    }

}

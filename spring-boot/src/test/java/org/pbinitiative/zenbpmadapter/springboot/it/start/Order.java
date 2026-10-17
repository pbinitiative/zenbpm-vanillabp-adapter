/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.start;

import io.vanillabp.spi.service.NoSyncWithBPMS;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The workflow aggregate of {@link OrderWorkflow}, a JPA entity whose id the test chooses, so a
 * test knows the business key of a workflow it never sees started. It shares its item with the
 * engine and keeps its note: the one attribute marked {@link NoSyncWithBPMS} puts the class into
 * the mode "share everything else", and an aggregate which shared everything would end the start
 * of the application.
 */
@Entity
@Table(name = "START_IT_ORDER")
public class Order {

    @Id
    private String id;

    private String item;

    @NoSyncWithBPMS
    private String note;

    /**
     * Built by JPA.
     */
    protected Order() {
    }

    /**
     * An order which is not saved yet.
     *
     * @param id The id, which becomes the business key of its workflow
     * @param item What the engine is told about the order
     * @param note What the engine is not told
     */
    public Order(
        final String id,
        final String item,
        final String note
    ) {
        this.id = id;
        this.item = item;
        this.note = note;
    }

    /**
     * @return The id, the business key of the order's workflow
     */
    public String getId() {
        return id;
    }

    /**
     * @return What the engine is told about the order
     */
    public String getItem() {
        return item;
    }

    /**
     * @return What the engine is not told
     */
    public String getNote() {
        return note;
    }

}

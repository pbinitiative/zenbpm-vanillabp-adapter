/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.test;

import java.util.ArrayList;
import java.util.List;

import io.vanillabp.spi.service.NoSyncWithBPMS;
import io.vanillabp.spi.service.SyncWithBPMS;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;

/**
 * The workflow aggregate of the process <code>Packing</code>: the items it shares with its
 * workflow, which iterates over them, and the end event its workflow reported.
 */
@NoSyncWithBPMS
@Entity
@Table(name = "ZENBPM_E2E_PACKING")
public class E2ePacking {

    @Id
    private String id;

    @SyncWithBPMS
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "ZENBPM_E2E_PACKING_ITEMS")
    @OrderColumn
    private List<String> items = new ArrayList<>();

    private String endEvent;

    /**
     * Built by JPA.
     */
    protected E2ePacking() {
    }

    /**
     * A new packing of some items.
     *
     * @param id The id, which becomes the business key of its workflow
     * @param items The items, in the order the workflow iterates over them
     */
    public E2ePacking(
        final String id,
        final List<String> items
    ) {
        this.id = id;
        this.items = new ArrayList<>(items);
    }

    /**
     * @return The id
     */
    public String getId() {
        return id;
    }

    /**
     * @return The items, which the workflow reads as its collection <code>items</code>
     */
    public List<String> getItems() {
        return List.copyOf(items);
    }

    /**
     * @return The end event its workflow reported, <code>null</code> before the end
     */
    public String getEndEvent() {
        return endEvent;
    }

    /**
     * Writes down the end event its workflow reported.
     *
     * @param endEvent The end event
     */
    public void ended(final String endEvent) {
        this.endEvent = endEvent;
    }

}

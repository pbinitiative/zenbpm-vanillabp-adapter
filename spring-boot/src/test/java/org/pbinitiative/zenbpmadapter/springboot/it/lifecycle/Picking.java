/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.lifecycle;

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
 * The workflow aggregate of the picking of an order: the parcels it shares with its workflow, which
 * iterates over them.
 */
@NoSyncWithBPMS
@Entity
@Table(name = "LIFECYCLE_IT_PICKING")
public class Picking {

    @Id
    private String id;

    @SyncWithBPMS
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "LIFECYCLE_IT_PICKING_PARCELS")
    @OrderColumn
    private List<String> parcels = new ArrayList<>();

    /**
     * For JPA.
     */
    protected Picking() {
    }

    /**
     * The picking of some parcels.
     *
     * @param id The id
     * @param parcels The parcels, in the order the workflow iterates over them
     */
    public Picking(
        final String id,
        final List<String> parcels
    ) {
        this.id = id;
        this.parcels = new ArrayList<>(parcels);
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
     * The parcels, which the workflow reads as its collection <code>parcels</code>.
     *
     * @return The parcels
     */
    public List<String> getParcels() {
        return List.copyOf(parcels);
    }

}

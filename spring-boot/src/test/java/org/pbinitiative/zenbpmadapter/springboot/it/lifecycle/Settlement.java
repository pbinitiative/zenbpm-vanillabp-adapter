/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.lifecycle;

import java.util.ArrayList;
import java.util.List;

import io.vanillabp.spi.service.NoSyncWithBPMS;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;

/**
 * The workflow aggregate of the settlement of a claim: the ends its workflow reported.
 */
@NoSyncWithBPMS
@Entity
@Table(name = "LIFECYCLE_IT_SETTLEMENT")
public class Settlement {

    @Id
    private String id;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "LIFECYCLE_IT_SETTLEMENT_ENDS")
    @OrderColumn
    private List<String> ends = new ArrayList<>();

    /**
     * For JPA.
     */
    protected Settlement() {
    }

    /**
     * A settlement whose workflow did not end yet.
     *
     * @param id The id
     */
    public Settlement(final String id) {
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
     * The end events its workflow reported, in the order they were reported.
     *
     * @return The end events
     */
    public List<String> getEnds() {
        return List.copyOf(ends);
    }

    /**
     * Writes down an end of its workflow.
     *
     * @param endEventId The end event
     */
    public void ended(final String endEventId) {
        ends.add(endEventId);
    }

}

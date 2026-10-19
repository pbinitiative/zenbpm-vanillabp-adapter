/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.push;

import io.vanillabp.spi.service.NoSyncWithBPMS;
import io.vanillabp.spi.service.SyncWithBPMS;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The workflow aggregate of the process <code>Ratings</code>, which <code>Shipments</code> calls: a
 * rating of a shipment, an aggregate of its own. Its id has another name than the shipment's, and
 * it shares a value of the same name as the shipment does, <code>approved</code>, which is never
 * set, so its gateway takes the approved branch only where a push of the shipment reached it.
 */
@NoSyncWithBPMS
@Entity
@Table(name = "PUSH_IT_RATING")
public class Rating {

    @Id
    private String ratingId;

    @SyncWithBPMS
    private boolean approved;

    /**
     * For JPA.
     */
    protected Rating() {
    }

    /**
     * A new rating, not approved.
     *
     * @param ratingId The id
     */
    public Rating(final String ratingId) {
        this.ratingId = ratingId;
    }

    /**
     * The id.
     *
     * @return The id
     */
    public String getRatingId() {
        return ratingId;
    }

    /**
     * Whether the rating was approved, which nothing does.
     *
     * @return Whether it was
     */
    public boolean isApproved() {
        return approved;
    }

}

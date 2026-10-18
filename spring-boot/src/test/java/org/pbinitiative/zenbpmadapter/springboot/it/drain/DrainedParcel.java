/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.drain;

import org.hibernate.annotations.DynamicUpdate;

import io.vanillabp.spi.service.NoSyncWithBPMS;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The workflow aggregate of the shutdown: a parcel which is packed and labelled, each by the node
 * which runs the task. The two run in parallel and each writes its own column only.
 */
@DynamicUpdate
@NoSyncWithBPMS
@Entity
@Table(name = "DRAIN_IT_PARCEL")
public class DrainedParcel {

    @Id
    private String id;

    private String packedBy;

    private String labelledBy;

    /**
     * For JPA.
     */
    protected DrainedParcel() {
    }

    /**
     * A new parcel.
     *
     * @param id The id
     */
    public DrainedParcel(final String id) {
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
     * The node which packed the parcel.
     *
     * @return The node, <code>null</code> before
     */
    public String getPackedBy() {
        return packedBy;
    }

    /**
     * The node which labelled the parcel.
     *
     * @return The node, <code>null</code> before
     */
    public String getLabelledBy() {
        return labelledBy;
    }

    /**
     * Records the packing.
     *
     * @param node The node which packed the parcel
     */
    public void packedBy(final String node) {
        packedBy = node;
    }

    /**
     * Records the label.
     *
     * @param node The node which labelled the parcel
     */
    public void labelledBy(final String node) {
        labelledBy = node;
    }

}

/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.test;

import io.vanillabp.spi.service.NoSyncWithBPMS;
import io.vanillabp.spi.service.SyncWithBPMS;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The workflow aggregate of the process <code>TaskMatrix</code>: a parcel which is weighed, for an
 * express parcel announced to the courier, rated and packed. It shares only what the gateways of
 * the model decide on.
 */
@NoSyncWithBPMS
@Entity
@Table(name = "ZENBPM_E2E_PARCEL")
public class E2eParcel {

    @Id
    private String id;

    @SyncWithBPMS
    private boolean express;

    @SyncWithBPMS
    private boolean packed;

    private String tariff;

    // how often each handler ran and committed, so that a test tells one run from two
    private int weighings;

    private int courierNotifications;

    private int ratings;

    private int packings;

    /**
     * Built by JPA.
     */
    protected E2eParcel() {
    }

    /**
     * A parcel which is not saved yet and not express.
     *
     * @param id The id, which becomes the business key of its workflow
     */
    public E2eParcel(final String id) {
        this.id = id;
    }

    /**
     * @return The id
     */
    public String getId() {
        return id;
    }

    /**
     * @return Whether the weighing made the parcel express
     */
    public boolean isExpress() {
        return express;
    }

    /**
     * @return Whether the parcel was packed
     */
    public boolean isPacked() {
        return packed;
    }

    /**
     * @return The tariff the model handed to the weighing
     */
    public String getTariff() {
        return tariff;
    }

    /**
     * @return How often the parcel was weighed
     */
    public int getWeighings() {
        return weighings;
    }

    /**
     * @return How often the courier was told
     */
    public int getCourierNotifications() {
        return courierNotifications;
    }

    /**
     * @return How often the parcel was rated
     */
    public int getRatings() {
        return ratings;
    }

    /**
     * @return How often the parcel was packed
     */
    public int getPackings() {
        return packings;
    }

    void weigh(final String tariff) {
        this.weighings++;
        this.tariff = tariff;
        // what the gateway behind the task decides on: the workflow started with false
        this.express = true;
    }

    void notifyCourier() {
        this.courierNotifications++;
    }

    void rate() {
        this.ratings++;
    }

    void pack() {
        this.packings++;
        // what the gateway behind the sub-process decides on
        this.packed = true;
    }

}

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

    private boolean weighed;

    private boolean courierNotified;

    private boolean rated;

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
     * @return Whether the parcel was weighed
     */
    public boolean isWeighed() {
        return weighed;
    }

    /**
     * @return Whether the courier was told
     */
    public boolean isCourierNotified() {
        return courierNotified;
    }

    /**
     * @return Whether the parcel was rated
     */
    public boolean isRated() {
        return rated;
    }

    void weigh(final String tariff) {
        this.weighed = true;
        this.tariff = tariff;
        // what the gateway behind the task decides on: the workflow started with false
        this.express = true;
    }

    void notifyCourier() {
        this.courierNotified = true;
    }

    void rate() {
        this.rated = true;
    }

    void pack() {
        // what the gateway behind the sub-process decides on
        this.packed = true;
    }

}

/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.task;

import io.vanillabp.spi.service.NoSyncWithBPMS;
import io.vanillabp.spi.service.SyncWithBPMS;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The aggregate of the task matrix: what each task of the workflow did to it, and the two values
 * the gateways of the model decide on, which the handlers set and which are all it shares with the
 * engine.
 */
@NoSyncWithBPMS
@Entity
@Table(name = "TASK_IT_PARCEL")
public class Parcel {

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
    protected Parcel() {
    }

    /**
     * A parcel nothing was done to yet, neither express nor packed.
     *
     * @param id The parcel's id
     */
    public Parcel(final String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    public boolean isExpress() {
        return express;
    }

    public boolean isPacked() {
        return packed;
    }

    public String getTariff() {
        return tariff;
    }

    public boolean isWeighed() {
        return weighed;
    }

    public boolean isCourierNotified() {
        return courierNotified;
    }

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

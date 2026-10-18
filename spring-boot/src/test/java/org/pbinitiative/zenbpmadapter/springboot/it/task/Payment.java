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
 * The workflow aggregate of the handlers which throw. It shares which task the workflow runs.
 */
@NoSyncWithBPMS
@Entity
@Table(name = "TASK_IT_PAYMENT")
public class Payment {

    /**
     * A charge whose handler always throws, of a model with two retries.
     */
    public static final String CHARGE = "charge";

    /**
     * A reservation whose handler throws at its first attempt only.
     */
    public static final String RESERVE_ONCE = "reserve-once";

    /**
     * A notification whose handler always throws, of a model which names no retries.
     */
    public static final String NOTIFY = "notify";

    /**
     * A hold whose handler waits until the test lets it throw.
     */
    public static final String HOLD = "hold";

    @Id
    private String id;

    @SyncWithBPMS
    private String kind;

    private boolean reserved;

    /**
     * For JPA.
     */
    protected Payment() {
    }

    /**
     * A new payment.
     *
     * @param id The id
     * @param kind {@link #CHARGE}, {@link #RESERVE_ONCE}, {@link #NOTIFY} or {@link #HOLD}
     */
    public Payment(
        final String id,
        final String kind
    ) {
        this.id = id;
        this.kind = kind;
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
     * Which task the workflow runs.
     *
     * @return {@link #CHARGE}, {@link #RESERVE_ONCE}, {@link #NOTIFY} or {@link #HOLD}
     */
    public String getKind() {
        return kind;
    }

    /**
     * Whether the reservation succeeded.
     *
     * @return <code>true</code> once the handler of the reservation returned
     */
    public boolean isReserved() {
        return reserved;
    }

    /**
     * Records the reservation.
     */
    public void reserve() {
        reserved = true;
    }

}

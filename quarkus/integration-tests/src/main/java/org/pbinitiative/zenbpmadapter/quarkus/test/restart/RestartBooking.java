/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.test.restart;

import io.vanillabp.spi.service.NoSyncWithBPMS;
import io.vanillabp.spi.service.SyncWithBPMS;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The workflow aggregate of the restart: a booking which waits for the application's confirmation,
 * or one which is packed. It counts the committed runs of the confirmation's handler, so the test
 * sees a handler which ran twice in the database, whichever run of the application ran it.
 */
@NoSyncWithBPMS
@Entity
@Table(name = "ZENBPM_RESTART_BOOKING")
public class RestartBooking {

    /**
     * The kind of a booking whose confirmation the application sends later.
     */
    public static final String CONFIRM = "confirm";

    /**
     * The kind of a booking which is packed.
     */
    public static final String PACK = "pack";

    @Id
    private String id;

    @SyncWithBPMS
    private String kind;

    private String confirmationTaskId;

    private int confirmationRequests;

    private boolean confirmed;

    private boolean packed;

    /**
     * Built by JPA.
     */
    protected RestartBooking() {
    }

    /**
     * A booking which is not saved yet.
     *
     * @param id The id, which becomes the business key of its workflow
     * @param kind {@link #CONFIRM} or {@link #PACK}
     */
    public RestartBooking(
        final String id,
        final String kind
    ) {
        this.id = id;
        this.kind = kind;
    }

    /**
     * @return The id
     */
    public String getId() {
        return id;
    }

    /**
     * @return {@link #CONFIRM} or {@link #PACK}
     */
    public String getKind() {
        return kind;
    }

    /**
     * @return The task the confirmation completes, <code>null</code> before it was delivered
     */
    public String getConfirmationTaskId() {
        return confirmationTaskId;
    }

    /**
     * @return How often the handler of the confirmation ran, as committed
     */
    public int getConfirmationRequests() {
        return confirmationRequests;
    }

    /**
     * @return Whether the application confirmed the booking
     */
    public boolean isConfirmed() {
        return confirmed;
    }

    /**
     * @return Whether the booking was packed
     */
    public boolean isPacked() {
        return packed;
    }

    /**
     * Confirms the booking, before the application completes the task.
     */
    public void confirm() {
        confirmed = true;
    }

    void requestConfirmation(final String taskId) {
        confirmationTaskId = taskId;
        ++confirmationRequests;
    }

    void pack() {
        packed = true;
    }

}

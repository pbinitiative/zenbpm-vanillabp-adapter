/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.nativeimage;

import io.vanillabp.spi.service.NoSyncWithBPMS;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The workflow aggregate of the native-image application. Its status says how far its workflow
 * got: each task of the workflow writes its own, so the application reads off the aggregate that
 * the engine handed a job to a handler inside the binary. The model reads none of its attributes,
 * so none is shared with the engine.
 */
@NoSyncWithBPMS
@Entity
@Table(name = "ZENBPM_NATIVE_AGGREGATE")
public class NativeImageAggregate {

    /**
     * The status of an aggregate whose workflow was started and no task ran yet.
     */
    public static final String STARTED = "started";

    @Id
    private String id;

    private String status;

    /**
     * Built by JPA.
     */
    protected NativeImageAggregate() {
    }

    /**
     * A new aggregate whose workflow is about to start.
     *
     * @param id The id, which becomes the business key of its workflow
     */
    public NativeImageAggregate(final String id) {
        this.id = id;
        this.status = STARTED;
    }

    /**
     * @return The id
     */
    public String getId() {
        return id;
    }

    /**
     * @return How far the workflow got
     */
    public String getStatus() {
        return status;
    }

    /**
     * Says how far the workflow got.
     *
     * @param status The status
     */
    public void setStatus(final String status) {
        this.status = status;
    }

}

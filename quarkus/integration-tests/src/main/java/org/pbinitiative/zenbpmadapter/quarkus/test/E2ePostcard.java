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
 * The workflow aggregate of the process <code>Postcards</code>, which a message starts: a postcard,
 * whose status travels with every message.
 */
@NoSyncWithBPMS
@Entity
@Table(name = "ZENBPM_E2E_POSTCARD")
public class E2ePostcard {

    @Id
    private String id;

    @SyncWithBPMS
    private String status;

    /**
     * Built by JPA.
     */
    protected E2ePostcard() {
    }

    /**
     * A new postcard, written and not posted yet.
     *
     * @param id The id, which the start message carries in the id variable
     */
    public E2ePostcard(final String id) {
        this.id = id;
        this.status = "written";
    }

    /**
     * @return The id
     */
    public String getId() {
        return id;
    }

    /**
     * @return Where the postcard stands
     */
    public String getStatus() {
        return status;
    }

    /**
     * Says where the postcard stands.
     *
     * @param status The status
     */
    public void setStatus(final String status) {
        this.status = status;
    }

}

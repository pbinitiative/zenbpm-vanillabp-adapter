/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.message;

import io.vanillabp.spi.service.NoSyncWithBPMS;
import io.vanillabp.spi.service.SyncWithBPMS;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The workflow aggregate of the starts by a message: a postcard, whose workflow a message starts and
 * whose status travels with every message.
 */
@NoSyncWithBPMS
@Entity
@Table(name = "MESSAGE_IT_POSTCARD")
public class Postcard {

    @Id
    private String id;

    @SyncWithBPMS
    private String status;

    /**
     * For JPA.
     */
    protected Postcard() {
    }

    /**
     * A new postcard, written and not posted yet.
     *
     * @param id The id
     */
    public Postcard(final String id) {
        this.id = id;
        this.status = "written";
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
     * Where the postcard stands.
     *
     * @return The status
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

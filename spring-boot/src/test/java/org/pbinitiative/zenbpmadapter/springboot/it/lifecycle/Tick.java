/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.lifecycle;

import io.vanillabp.spi.service.NoSyncWithBPMS;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The workflow aggregate of the process a timer starts: what started it, as the application's
 * method was told, and whether its task ran.
 */
@NoSyncWithBPMS
@Entity
@Table(name = "LIFECYCLE_IT_TICK")
public class Tick {

    @Id
    private String id;

    private String startedBy;

    private String startEvent;

    private boolean ticked;

    /**
     * For JPA.
     */
    protected Tick() {
    }

    /**
     * A tick the application's method built.
     *
     * @param id The id the method chose
     * @param startedBy What fired the start event
     * @param startEvent The start event
     */
    public Tick(
        final String id,
        final String startedBy,
        final String startEvent
    ) {
        this.id = id;
        this.startedBy = startedBy;
        this.startEvent = startEvent;
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
     * What fired the start event, as the method was told.
     *
     * @return The kind of the trigger
     */
    public String getStartedBy() {
        return startedBy;
    }

    /**
     * The start event, as the method was told.
     *
     * @return The id of the start event
     */
    public String getStartEvent() {
        return startEvent;
    }

    /**
     * Whether the task of the workflow ran.
     *
     * @return <code>true</code> once it ran
     */
    public boolean isTicked() {
        return ticked;
    }

    /**
     * Writes down that the task of the workflow ran.
     */
    public void tick() {
        ticked = true;
    }

}

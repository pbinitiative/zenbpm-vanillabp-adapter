/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.lifecycle;

import java.util.ArrayList;
import java.util.List;

import io.vanillabp.spi.service.NoSyncWithBPMS;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;

/**
 * The workflow aggregate of a return: what happened to it, in the order it happened, and the task
 * which inspects it while the inspection waits.
 */
@NoSyncWithBPMS
@Entity
@Table(name = "LIFECYCLE_IT_RETURN")
public class ReturnCase {

    @Id
    private String id;

    private String inspection;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "LIFECYCLE_IT_RETURN_EVENTS")
    @OrderColumn
    private List<String> events = new ArrayList<>();

    /**
     * For JPA.
     */
    protected ReturnCase() {
    }

    /**
     * A return which nothing happened to yet.
     *
     * @param id The id
     */
    public ReturnCase(final String id) {
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
     * The task which inspects the return.
     *
     * @return The task id, <code>null</code> before the inspection started
     */
    public String getInspection() {
        return inspection;
    }

    /**
     * What happened to the return, in the order it happened.
     *
     * @return The events
     */
    public List<String> getEvents() {
        return List.copyOf(events);
    }

    /**
     * Writes down that the inspection started and waits for its task to be completed.
     *
     * @param taskId The task
     */
    public void inspecting(final String taskId) {
        inspection = taskId;
        events.add("inspection started");
    }

    /**
     * Writes down that something happened.
     *
     * @param event What happened
     */
    public void happened(final String event) {
        events.add(event);
    }

}

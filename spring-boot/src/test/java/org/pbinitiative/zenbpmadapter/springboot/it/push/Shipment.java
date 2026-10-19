/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.push;

import io.vanillabp.spi.service.NoSyncWithBPMS;
import io.vanillabp.spi.service.SyncWithBPMS;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The workflow aggregate of the pushes: a shipment, whose scenario chooses where the gateway which
 * decides on its approval stands, and which shares whether it was approved. It is never approved
 * when its workflow starts, so a gateway takes the approved branch only where a push reached it.
 */
@NoSyncWithBPMS
@Entity
@Table(name = "PUSH_IT_SHIPMENT")
public class Shipment {

    /**
     * The path whose gateway follows a timer of the workflow's root.
     */
    public static final String GATEWAY = "gateway";

    /**
     * The path whose gateway follows a timer inside an embedded sub-process.
     */
    public static final String SUB_PROCESS = "subprocess";

    /**
     * The path whose gateway follows a timer inside each iteration of a parallel multi-instance
     * sub-process.
     */
    public static final String ITERATIONS = "iterations";

    /**
     * The path whose gateway follows a task inside an embedded sub-process, which the application
     * leaves open.
     */
    public static final String TASK = "task";

    /**
     * The path which calls the process <code>Ratings</code> on an aggregate of its own, whose
     * gateway follows a timer.
     */
    public static final String CALLED = "called";

    @Id
    private String id;

    @SyncWithBPMS
    private String scenario;

    @SyncWithBPMS
    private boolean approved;

    private String inspection;

    /**
     * For JPA.
     */
    protected Shipment() {
    }

    /**
     * A new shipment on a path, not approved.
     *
     * @param id The id
     * @param scenario The path the workflow takes
     */
    public Shipment(
        final String id,
        final String scenario
    ) {
        this.id = id;
        this.scenario = scenario;
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
     * The path the workflow takes.
     *
     * @return The scenario
     */
    public String getScenario() {
        return scenario;
    }

    /**
     * Whether the shipment was approved.
     *
     * @return Whether it was
     */
    public boolean isApproved() {
        return approved;
    }

    /**
     * Approves the shipment.
     */
    public void approve() {
        this.approved = true;
    }

    /**
     * The task of the inspection the application left open.
     *
     * @return The task id, <code>null</code> before the task was handed out
     */
    public String getInspection() {
        return inspection;
    }

    /**
     * Writes down the task of the inspection, which the application completes later.
     *
     * @param taskId The task id
     */
    public void inspectLater(final String taskId) {
        this.inspection = taskId;
    }

}

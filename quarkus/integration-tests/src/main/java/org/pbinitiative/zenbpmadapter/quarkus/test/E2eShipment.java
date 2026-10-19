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
 * The workflow aggregate of the process <code>Shipments</code>, a copy of the Spring Boot push
 * fixture: a shipment, whose scenario chooses where the gateway which decides on its approval
 * stands, and which shares whether it was approved. It is never approved when its workflow starts,
 * so a gateway takes the approved branch only where a push reached it.
 */
@NoSyncWithBPMS
@Entity
@Table(name = "ZENBPM_E2E_SHIPMENT")
public class E2eShipment {

    /**
     * The path whose gateway follows a timer of the workflow's root.
     */
    public static final String GATEWAY = "gateway";

    /**
     * The path whose gateway follows a task inside an embedded sub-process, which the application
     * leaves open.
     */
    public static final String TASK = "task";

    @Id
    private String id;

    @SyncWithBPMS
    private String scenario;

    @SyncWithBPMS
    private boolean approved;

    private String inspection;

    /**
     * Built by JPA.
     */
    protected E2eShipment() {
    }

    /**
     * A new shipment on a path, not approved.
     *
     * @param id The id, which becomes the business key of its workflow
     * @param scenario {@link #GATEWAY} or {@link #TASK}
     */
    public E2eShipment(
        final String id,
        final String scenario
    ) {
        this.id = id;
        this.scenario = scenario;
    }

    /**
     * @return The id
     */
    public String getId() {
        return id;
    }

    /**
     * @return The path the workflow takes
     */
    public String getScenario() {
        return scenario;
    }

    /**
     * @return Whether the shipment was approved
     */
    public boolean isApproved() {
        return approved;
    }

    /**
     * @return The task of the inspection the application left open, <code>null</code> before it was
     *         handed out
     */
    public String getInspection() {
        return inspection;
    }

    /**
     * Approves the shipment.
     */
    public void approve() {
        approved = true;
    }

    void inspectLater(final String taskId) {
        inspection = taskId;
    }

}

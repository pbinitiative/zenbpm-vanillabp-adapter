/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.test;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskId;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The workflow service of the process <code>Shipments</code>. Its one task, the inspection inside a
 * sub-process, is left open, so the application pushes into the scope of a task which waits.
 */
@ApplicationScoped
@WorkflowService(workflowAggregateClass = E2eShipment.class, bpmnProcess = @BpmnProcess(bpmnProcessId = "Shipments"))
public class E2eShipmentWorkflow {

    @Inject
    ProcessService<E2eShipment> processService;

    /**
     * Built by CDI.
     */
    public E2eShipmentWorkflow() {
    }

    /**
     * Starts the workflow of a new shipment, inside the caller's transaction.
     *
     * @param shipment The shipment, not saved yet
     */
    public void start(final E2eShipment shipment) {
        processService.startWorkflow(shipment);
    }

    /**
     * Pushes the changed shipment into its workflow, inside the caller's transaction.
     *
     * @param shipment The shipment
     */
    public void changed(final E2eShipment shipment) {
        processService.aggregateChanged(shipment);
    }

    /**
     * Pushes the changed shipment into the scope of its inspection, inside the caller's transaction.
     *
     * @param shipment The shipment
     */
    public void changedForTheInspection(final E2eShipment shipment) {
        processService.aggregateChanged(shipment, shipment.getInspection());
    }

    /**
     * Completes the inspection left open, inside the caller's transaction.
     *
     * @param shipment The shipment
     */
    public void completeTheInspection(final E2eShipment shipment) {
        processService.completeTask(shipment, shipment.getInspection());
    }

    /**
     * Leaves the inspection open: the application completes it after it pushed into its scope.
     *
     * @param shipment The shipment
     * @param taskId The task id
     */
    @WorkflowTask(taskDefinition = "inspect")
    public void inspect(
        final E2eShipment shipment,
        @TaskId final String taskId
    ) {
        shipment.inspectLater(taskId);
    }

}

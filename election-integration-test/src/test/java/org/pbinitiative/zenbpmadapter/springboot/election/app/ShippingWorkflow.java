/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.election.app;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskEvent;
import io.vanillabp.spi.service.TaskId;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow of a shipment: a user task asks a person to confirm it, and a service task ships it
 * once the application completed the user task.
 */
@Service
@WorkflowService(workflowAggregateClass = Shipment.class, bpmnProcess = @BpmnProcess(bpmnProcessId = "Shipping"))
public class ShippingWorkflow {

    /**
     * Built by Spring.
     */
    public ShippingWorkflow() {
    }

    /**
     * Writes down the user task which asks for the confirmation. Its cancellation changes nothing
     * here, because no test cancels it.
     *
     * @param shipment The shipment
     * @param taskId The user task
     * @param event What happened to it
     */
    @WorkflowTask(taskDefinition = "confirm")
    public void confirm(
        final Shipment shipment,
        @TaskId final String taskId,
        @TaskEvent final TaskEvent.Event event
    ) {
        if (event == TaskEvent.Event.CREATED) {
            shipment.confirmationAsked(taskId);
        }
    }

    /**
     * Ships the shipment.
     *
     * @param shipment The shipment
     */
    @WorkflowTask(taskDefinition = "ship")
    public void ship(final Shipment shipment) {
        shipment.ship();
    }

}

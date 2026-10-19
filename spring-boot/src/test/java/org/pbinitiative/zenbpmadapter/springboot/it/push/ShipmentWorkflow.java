/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.push;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskId;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow service of the process <code>Shipments</code>. Its one task, the inspection inside
 * a sub-process, is left open, so the application pushes into the scope of a task which waits.
 */
@Service
@WorkflowService(workflowAggregateClass = Shipment.class, bpmnProcess = @BpmnProcess(bpmnProcessId = "Shipments"))
public class ShipmentWorkflow {

    /**
     * Built by Spring.
     */
    public ShipmentWorkflow() {
    }

    /**
     * Leaves the inspection open: the test completes it after it pushed into its scope.
     *
     * @param shipment The shipment
     * @param taskId The task id
     */
    @WorkflowTask(taskDefinition = "inspect")
    public void inspect(
        final Shipment shipment,
        @TaskId final String taskId
    ) {
        shipment.inspectLater(taskId);
    }

}

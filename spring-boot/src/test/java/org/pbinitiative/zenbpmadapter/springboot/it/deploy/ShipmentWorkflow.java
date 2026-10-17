/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.deploy;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow service of the integration tests: it serves the one task of the process
 * <code>DeployIt</code>, so the adapter wires, deploys and subscribes a job type for it.
 */
@Service
@WorkflowService(workflowAggregateClass = Shipment.class, bpmnProcess = @BpmnProcess(bpmnProcessId = "DeployIt"))
public class ShipmentWorkflow {

    /**
     * Built by Spring.
     */
    public ShipmentWorkflow() {
    }

    /**
     * Serves the task <code>Ship</code>. No job reaches it before the adapter delivers tasks.
     *
     * @param shipment The workflow aggregate
     */
    @WorkflowTask(taskDefinition = "ship")
    public void ship(final Shipment shipment) {
        // nothing to do: the tests are about deploying and subscribing
    }

}

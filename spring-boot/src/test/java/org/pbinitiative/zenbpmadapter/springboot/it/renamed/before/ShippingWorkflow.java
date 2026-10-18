/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.renamed.before;

import org.pbinitiative.zenbpmadapter.springboot.it.renamed.Shipment;
import org.springframework.stereotype.Service;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskParam;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow of a shipment before the rename, under the process id <code>Shipping</code>.
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
     * Ships the shipment, marked as shipped by the version before the rename.
     *
     * @param shipment The shipment
     * @param destination The destination the job carries
     */
    @WorkflowTask(taskDefinition = "ship")
    public void ship(
        final Shipment shipment,
        @TaskParam("destination") final String destination
    ) {
        shipment.ship("before", destination);
    }

}

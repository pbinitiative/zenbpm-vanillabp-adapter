/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.renamed.after;

import org.pbinitiative.zenbpmadapter.springboot.it.renamed.Shipment;
import org.springframework.stereotype.Service;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskParam;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow of a shipment after the rename, under the process id <code>Dispatch</code>, which
 * declares the old id <code>Shipping</code> as well, so the workflows started before the rename keep
 * being served.
 */
@Service
@WorkflowService(workflowAggregateClass = Shipment.class, bpmnProcess = @BpmnProcess(bpmnProcessId = "Dispatch"),
        secondaryBpmnProcesses = @BpmnProcess(bpmnProcessId = "Shipping"))
public class DispatchWorkflow {

    /**
     * Built by Spring.
     */
    public DispatchWorkflow() {
    }

    /**
     * Ships the shipment, marked as shipped by the version after the rename.
     *
     * @param shipment The shipment
     * @param destination The destination the job carries
     */
    @WorkflowTask(taskDefinition = "ship")
    public void ship(
        final Shipment shipment,
        @TaskParam("destination") final String destination
    ) {
        shipment.ship("after", destination);
    }

}

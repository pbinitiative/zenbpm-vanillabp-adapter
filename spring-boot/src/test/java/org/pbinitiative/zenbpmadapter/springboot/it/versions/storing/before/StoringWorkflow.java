/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.versions.storing.before;

import org.pbinitiative.zenbpmadapter.springboot.it.versions.Consignment;
import org.springframework.stereotype.Service;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow of a consignment under the process id <code>Storing</code> before the change, which
 * weighs it.
 */
@Service
@WorkflowService(workflowAggregateClass = Consignment.class, bpmnProcess = @BpmnProcess(bpmnProcessId = "Storing"))
public class StoringWorkflow {

    /**
     * Built by Spring.
     */
    public StoringWorkflow() {
    }

    /**
     * Weighs the consignment.
     *
     * @param consignment The consignment
     */
    @WorkflowTask(taskDefinition = "weigh")
    public void weigh(final Consignment consignment) {
        consignment.servedBy("weigh");
    }

}

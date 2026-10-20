/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.versions.unserved;

import org.pbinitiative.zenbpmadapter.springboot.it.versions.Consignment;
import org.springframework.stereotype.Service;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow of a consignment under the process id <code>Sealing</code>, whose task only a method
 * of the versions after 1 serves, although the engine gives the model it deploys first version 1.
 */
@Service
@WorkflowService(workflowAggregateClass = Consignment.class, bpmnProcess = @BpmnProcess(bpmnProcessId = "Sealing"))
public class SealingWorkflow {

    /**
     * Built by Spring.
     */
    public SealingWorkflow() {
    }

    /**
     * Seals a consignment of a workflow which runs on a version after 1.
     *
     * @param consignment The consignment
     */
    @WorkflowTask(taskDefinition = "seal", version = ">1")
    public void sealOnLaterVersions(final Consignment consignment) {
        consignment.servedBy("a later version");
    }

}

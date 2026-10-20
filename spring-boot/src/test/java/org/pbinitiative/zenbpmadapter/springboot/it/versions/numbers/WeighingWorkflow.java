/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.versions.numbers;

import org.pbinitiative.zenbpmadapter.springboot.it.versions.Consignment;
import org.springframework.stereotype.Service;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow of a consignment under the process id <code>Weighing</code>, whose task one method
 * serves on version 1 and another on every later version.
 */
@Service
@WorkflowService(workflowAggregateClass = Consignment.class, bpmnProcess = @BpmnProcess(bpmnProcessId = "Weighing"))
public class WeighingWorkflow {

    /**
     * Built by Spring.
     */
    public WeighingWorkflow() {
    }

    /**
     * Weighs a consignment of a workflow which runs on version 1.
     *
     * @param consignment The consignment
     */
    @WorkflowTask(taskDefinition = "weigh", version = "1")
    public void weighOnVersionOne(final Consignment consignment) {
        consignment.servedBy("version 1");
    }

    /**
     * Weighs a consignment of a workflow which runs on a version after 1.
     *
     * @param consignment The consignment
     */
    @WorkflowTask(taskDefinition = "weigh", version = ">1")
    public void weighOnLaterVersions(final Consignment consignment) {
        consignment.servedBy("a later version");
    }

}

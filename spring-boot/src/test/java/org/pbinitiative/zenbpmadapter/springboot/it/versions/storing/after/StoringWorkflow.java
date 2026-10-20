/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.versions.storing.after;

import org.pbinitiative.zenbpmadapter.springboot.it.versions.Consignment;
import org.springframework.stereotype.Service;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowEnd;
import io.vanillabp.spi.service.WorkflowEnded;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow of a consignment under the process id <code>Storing</code> after the change, which
 * measures it in place of weighing it, and listens to its end, so the model it deploys carries end
 * markers.
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
     * Measures the consignment.
     *
     * @param consignment The consignment
     */
    @WorkflowTask(taskDefinition = "measure")
    public void measure(final Consignment consignment) {
        consignment.servedBy("measure");
    }

    /**
     * Hears of the end of the consignment's workflow.
     *
     * @param consignment The consignment
     * @param end How the workflow ended
     */
    @WorkflowEnded
    public void ended(
        final Consignment consignment,
        final WorkflowEnd end
    ) {
        consignment.servedBy("ended at %s".formatted(end.endEventId()));
    }

}

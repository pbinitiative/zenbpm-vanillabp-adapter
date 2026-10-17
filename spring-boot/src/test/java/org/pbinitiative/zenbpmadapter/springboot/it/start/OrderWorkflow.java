/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.start;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow service of the process <code>Ordering</code>, whose instance waits in its one task
 * once it is started. The test starts it through the platform's <code>ProcessService</code>.
 */
@Service
@WorkflowService(workflowAggregateClass = Order.class, bpmnProcess = @BpmnProcess(bpmnProcessId = "Ordering"))
public class OrderWorkflow {

    /**
     * Built by Spring.
     */
    public OrderWorkflow() {
    }

    /**
     * Serves the task <code>Pack</code>, so the adapter wires it and subscribes its job type. No
     * job reaches it before the adapter delivers tasks, so the instance stays in it.
     *
     * @param order The workflow aggregate
     */
    @WorkflowTask(taskDefinition = "pack")
    public void pack(final Order order) {
        // nothing to do: the tests are about starting the workflow
    }

}

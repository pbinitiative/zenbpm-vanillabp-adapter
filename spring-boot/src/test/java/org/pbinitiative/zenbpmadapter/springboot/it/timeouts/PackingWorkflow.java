/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.timeouts;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow of the process <code>Packing</code>, whose one task has the task definition
 * <code>pack</code>.
 */
@Service
@WorkflowService(workflowAggregateClass = Box.class, bpmnProcess = @BpmnProcess(bpmnProcessId = "Packing"))
public class PackingWorkflow {

    /**
     * Built by Spring.
     */
    public PackingWorkflow() {
    }

    /**
     * Packs, which never runs: the start ends before.
     *
     * @param aggregate The aggregate
     */
    @WorkflowTask(taskDefinition = "pack")
    public void pack(final Box aggregate) {
        // nothing to do: the test is about starting
    }

}

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
 * The workflow of the process <code>Returns</code>, whose one task has the task definition
 * <code>pack</code>.
 */
@Service
@WorkflowService(workflowAggregateClass = Crate.class, bpmnProcess = @BpmnProcess(bpmnProcessId = "Returns"))
public class ReturnsWorkflow {

    /**
     * Built by Spring.
     */
    public ReturnsWorkflow() {
    }

    /**
     * Packs, which never runs: the start ends before.
     *
     * @param aggregate The aggregate
     */
    @WorkflowTask(taskDefinition = "pack")
    public void pack(final Crate aggregate) {
        // nothing to do: the test is about starting
    }

}

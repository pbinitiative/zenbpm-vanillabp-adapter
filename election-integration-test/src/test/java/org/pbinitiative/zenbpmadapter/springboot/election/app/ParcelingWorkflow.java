/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.election.app;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;

/**
 * The workflow of a parcel, which hands the application no task.
 */
@Service
@WorkflowService(workflowAggregateClass = Parcel.class, bpmnProcess = @BpmnProcess(bpmnProcessId = "Parceling"))
public class ParcelingWorkflow {

    /**
     * Built by Spring.
     */
    public ParcelingWorkflow() {
    }

}

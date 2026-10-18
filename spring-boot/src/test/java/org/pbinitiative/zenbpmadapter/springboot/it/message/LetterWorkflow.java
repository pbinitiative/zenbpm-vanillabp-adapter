/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.message;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;

/**
 * The workflow service of the process <code>Letters</code>, whose every path waits for a message
 * and has no task. The test starts it and correlates its messages through the platform's
 * <code>ProcessService</code>.
 */
@Service
@WorkflowService(workflowAggregateClass = Letter.class, bpmnProcess = @BpmnProcess(bpmnProcessId = "Letters"))
public class LetterWorkflow {

    /**
     * Built by Spring.
     */
    public LetterWorkflow() {
    }

}

/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.message;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;

/**
 * The workflow service of the process <code>Postcards</code>, which the message
 * <code>postcardPosted</code> starts and which waits for the message <code>stamped</code>. It has no
 * task: the test starts it and correlates its message through the platform's
 * <code>ProcessService</code>.
 */
@Service
@WorkflowService(workflowAggregateClass = Postcard.class, bpmnProcess = @BpmnProcess(bpmnProcessId = "Postcards"))
public class PostcardWorkflow {

    /**
     * Built by Spring.
     */
    public PostcardWorkflow() {
    }

}

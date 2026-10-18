/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.task;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskException;
import io.vanillabp.spi.service.TaskId;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow of the outcomes other than a completion: an inspection which raises a BPMN error,
 * which an error boundary event of the model catches where its code is <code>damaged</code>, and an
 * approval which the application completes later, so its task stays open.
 */
@Service
@WorkflowService(workflowAggregateClass = Claim.class, bpmnProcess = @BpmnProcess(bpmnProcessId = "TaskOutcomes"))
public class ClaimWorkflow {

    /**
     * Built by Spring.
     */
    public ClaimWorkflow() {
    }

    /**
     * Inspects the claim, finds it refundable and raises the BPMN error the claim names.
     *
     * @param claim The claim
     */
    @WorkflowTask(taskDefinition = "inspect")
    public void inspect(final Claim claim) {
        claim.inspect();
        throw new TaskException(claim.getErrorCode());
    }

    /**
     * Asks for an approval, which the application completes later.
     *
     * @param claim The claim
     * @param taskId The task, which the completion names
     */
    @WorkflowTask(taskDefinition = "await-approval")
    public void awaitApproval(
        final Claim claim,
        @TaskId final String taskId
    ) {
        claim.requestApproval(taskId);
    }

}

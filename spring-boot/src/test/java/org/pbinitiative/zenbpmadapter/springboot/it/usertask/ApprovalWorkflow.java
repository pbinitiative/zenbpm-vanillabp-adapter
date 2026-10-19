/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.usertask;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskEvent;
import io.vanillabp.spi.service.TaskId;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow of a user task which waits for a person until the instance is cancelled.
 */
@Service
@WorkflowService(workflowAggregateClass = Approval.class, bpmnProcess = @BpmnProcess(bpmnProcessId = "Approving"))
public class ApprovalWorkflow {

    /**
     * Built by Spring.
     */
    public ApprovalWorkflow() {
    }

    /**
     * Writes down that the user task was created or cancelled.
     *
     * @param approval The approval
     * @param taskId The user task
     * @param event What happened to it
     */
    @WorkflowTask(taskDefinition = "approve")
    public void approve(
        final Approval approval,
        @TaskId final String taskId,
        @TaskEvent final TaskEvent.Event event
    ) {
        if (event == TaskEvent.Event.CANCELED) {
            approval.canceled();
            return;
        }
        approval.created(taskId);
    }

}

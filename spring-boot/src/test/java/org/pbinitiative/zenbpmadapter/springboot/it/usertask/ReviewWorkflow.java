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
 * The workflow of a review: a user task which the application completes, after which a gateway
 * decides on whether the paper was accepted, or cancels with a BPMN error, which an error boundary
 * event of the user task catches.
 */
@Service
@WorkflowService(workflowAggregateClass = Review.class, bpmnProcess = @BpmnProcess(bpmnProcessId = "Reviewing"))
public class ReviewWorkflow {

    /**
     * Built by Spring.
     */
    public ReviewWorkflow() {
    }

    /**
     * Writes down that the user task was created or cancelled.
     *
     * @param review The review
     * @param taskId The user task
     * @param event What happened to it
     */
    @WorkflowTask(taskDefinition = "review")
    public void review(
        final Review review,
        @TaskId final String taskId,
        @TaskEvent final TaskEvent.Event event
    ) {
        if (event == TaskEvent.Event.CANCELED) {
            review.canceled();
            return;
        }
        review.created(taskId);
    }

}

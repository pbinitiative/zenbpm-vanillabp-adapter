/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.test;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskEvent;
import io.vanillabp.spi.service.TaskId;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The workflow service of the process <code>Reviewing</code>: a user task, which the user-task
 * poller tells the application of, and which the application completes, after which a gateway
 * decides on whether the paper was accepted, or cancels with a BPMN error, which an error boundary
 * event of the user task catches.
 */
@ApplicationScoped
@WorkflowService(workflowAggregateClass = E2eReview.class, bpmnProcess = @BpmnProcess(bpmnProcessId = "Reviewing"))
public class E2eReviewWorkflow {

    @Inject
    ProcessService<E2eReview> processService;

    /**
     * Built by CDI.
     */
    public E2eReviewWorkflow() {
    }

    /**
     * Starts the workflow of a new review, inside the caller's transaction.
     *
     * @param review The review, not saved yet
     */
    public void start(final E2eReview review) {
        processService.startWorkflow(review);
    }

    /**
     * Accepts the paper and completes the user task, inside the caller's transaction.
     *
     * @param review The review
     */
    public void accept(final E2eReview review) {
        review.accept();
        processService.completeUserTask(review, review.getReviewTaskId());
    }

    /**
     * Cancels the user task with a BPMN error, inside the caller's transaction.
     *
     * @param review The review
     * @param errorCode The code of the BPMN error
     */
    public void withdraw(
        final E2eReview review,
        final String errorCode
    ) {
        processService.cancelUserTask(review, review.getReviewTaskId(), errorCode);
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
        final E2eReview review,
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

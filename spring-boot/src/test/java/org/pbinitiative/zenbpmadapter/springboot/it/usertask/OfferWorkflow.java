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
 * The workflow of an offer: a user task which a timer boundary event takes away, and in parallel a
 * service task which a later timer releases, so its delivery comes after the user task is gone.
 */
@Service
@WorkflowService(workflowAggregateClass = Offer.class, bpmnProcess = @BpmnProcess(bpmnProcessId = "Offering"))
public class OfferWorkflow {

    /**
     * Built by Spring.
     */
    public OfferWorkflow() {
    }

    /**
     * Writes down that the user task was created or cancelled.
     *
     * @param offer The offer
     * @param taskId The user task
     * @param event What happened to it
     */
    @WorkflowTask(taskDefinition = "accept")
    public void accept(
        final Offer offer,
        @TaskId final String taskId,
        @TaskEvent final TaskEvent.Event event
    ) {
        if (event == TaskEvent.Event.CANCELED) {
            offer.canceled();
            return;
        }
        offer.created(taskId);
    }

    /**
     * Archives the offer.
     *
     * @param offer The offer
     */
    @WorkflowTask(taskDefinition = "archive")
    public void archive(final Offer offer) {
        offer.archive();
    }

}

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
 * The workflow of a quote: a user task and a service task which the application leaves open, side
 * by side, until the instance is cancelled.
 */
@Service
@WorkflowService(workflowAggregateClass = Quote.class, bpmnProcess = @BpmnProcess(bpmnProcessId = "Quoting"))
public class QuoteWorkflow {

    /**
     * Built by Spring.
     */
    public QuoteWorkflow() {
    }

    /**
     * Writes down that the user task was created or cancelled.
     *
     * @param quote The quote
     * @param taskId The user task
     * @param event What happened to it
     */
    @WorkflowTask(taskDefinition = "quote")
    public void quote(
        final Quote quote,
        @TaskId final String taskId,
        @TaskEvent final TaskEvent.Event event
    ) {
        if (event == TaskEvent.Event.CANCELED) {
            quote.quotingCanceled();
            return;
        }
        quote.quotingCreated(taskId);
    }

    /**
     * Prices the quote, which the application completes later, and writes down its cancellation.
     *
     * @param quote The quote
     * @param taskId The task
     * @param event What happened to it
     */
    @WorkflowTask(taskDefinition = "price")
    public void price(
        final Quote quote,
        @TaskId final String taskId,
        @TaskEvent final TaskEvent.Event event
    ) {
        if (event == TaskEvent.Event.CANCELED) {
            quote.pricingCanceled();
            return;
        }
        quote.pricingDelivered(taskId);
    }

}

/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.test;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The workflow service of the process <code>Postcards</code>, which the message
 * <code>postcardPosted</code> starts and which waits for the message <code>stamped</code>. It has no
 * task.
 */
@ApplicationScoped
@WorkflowService(workflowAggregateClass = E2ePostcard.class, bpmnProcess = @BpmnProcess(bpmnProcessId = "Postcards"))
public class E2ePostcardWorkflow {

    @Inject
    ProcessService<E2ePostcard> processService;

    /**
     * Built by CDI.
     */
    public E2ePostcardWorkflow() {
    }

    /**
     * Starts the workflow of a new postcard by the message <code>postcardPosted</code>, inside the
     * caller's transaction.
     *
     * @param postcard The postcard, not saved yet
     */
    public void post(final E2ePostcard postcard) {
        processService.startWorkflowByMessage(postcard, "postcardPosted");
    }

    /**
     * Correlates the message <code>stamped</code>, inside the caller's transaction.
     *
     * @param postcard The postcard
     */
    public void stamp(final E2ePostcard postcard) {
        processService.correlateMessage(postcard, "stamped");
    }

}

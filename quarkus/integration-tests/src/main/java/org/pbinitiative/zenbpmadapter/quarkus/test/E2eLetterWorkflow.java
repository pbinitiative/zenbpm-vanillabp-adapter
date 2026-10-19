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
 * The workflow service of the process <code>Letters</code>, whose every path waits for a message and
 * has no task.
 */
@ApplicationScoped
@WorkflowService(workflowAggregateClass = E2eLetter.class, bpmnProcess = @BpmnProcess(bpmnProcessId = "Letters"))
public class E2eLetterWorkflow {

    @Inject
    ProcessService<E2eLetter> processService;

    /**
     * Built by CDI.
     */
    public E2eLetterWorkflow() {
    }

    /**
     * Starts the workflow of a new letter, inside the caller's transaction.
     *
     * @param letter The letter, not saved yet
     */
    public void start(final E2eLetter letter) {
        processService.startWorkflow(letter);
    }

    /**
     * Correlates the message <code>goAhead</code>, inside the caller's transaction.
     *
     * @param letter The letter
     */
    public void goAhead(final E2eLetter letter) {
        processService.correlateMessage(letter, "goAhead");
    }

}

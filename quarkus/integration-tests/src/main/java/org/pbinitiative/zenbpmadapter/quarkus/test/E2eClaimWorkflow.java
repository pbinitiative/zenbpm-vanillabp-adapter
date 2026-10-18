/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.test;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskException;
import io.vanillabp.spi.service.TaskId;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The workflow service of the process <code>TaskOutcomes</code>: an inspection which raises a BPMN
 * error, caught by an error boundary event with a gateway behind it, and an approval which the
 * application completes later, with a gateway behind it.
 */
@ApplicationScoped
@WorkflowService(workflowAggregateClass = E2eClaim.class, bpmnProcess = @BpmnProcess(bpmnProcessId = "TaskOutcomes"))
public class E2eClaimWorkflow {

    @Inject
    ProcessService<E2eClaim> processService;

    /**
     * Built by CDI.
     */
    public E2eClaimWorkflow() {
    }

    /**
     * Starts the workflow of a new claim, inside the caller's transaction.
     *
     * @param claim The claim, not saved yet
     */
    public void start(final E2eClaim claim) {
        processService.startWorkflow(claim);
    }

    /**
     * Completes the approval of a claim, inside the caller's transaction.
     *
     * @param claim The claim
     * @param taskId The approval's task
     */
    public void completeTheApproval(
        final E2eClaim claim,
        final String taskId
    ) {
        processService.completeTask(claim, taskId);
    }

    /**
     * Inspects the claim, finds it refundable and raises the BPMN error the claim names.
     *
     * @param claim The claim
     */
    @WorkflowTask(taskDefinition = "inspect")
    public void inspect(final E2eClaim claim) {
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
        final E2eClaim claim,
        @TaskId final String taskId
    ) {
        claim.requestApproval(taskId);
    }

}

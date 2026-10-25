/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.insuranceclaim;

import org.pbinitiative.zenbpm.examples.insuranceclaim.model.Claim;
import org.springframework.stereotype.Component;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskEvent;
import io.vanillabp.spi.service.TaskId;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * What the process tells the application. The business rule task "Route the claim" needs no method:
 * the engine evaluates the decision table itself, with the shared members of the claim as its
 * inputs, and the model maps the table's answer into the variable <code>route</code> the gateway
 * reads.
 */
@Component
@WorkflowService(workflowAggregateClass = Claim.class, bpmnProcess = @BpmnProcess(bpmnProcessId = "insurance_claim"))
public class WorkflowTaskHandler {

    private final Claims claims;

    /**
     * Built by Spring.
     *
     * @param claims The business service
     */
    public WorkflowTaskHandler(final Claims claims) {
        this.claims = claims;
    }

    /**
     * The user task "Review the claim" of the adjuster.
     *
     * @param claim The claim
     * @param taskId The id of the user task
     * @param event Whether it was created or taken away
     */
    @WorkflowTask
    public void reviewClaim(
        final Claim claim,
        @TaskId final String taskId,
        @TaskEvent final TaskEvent.Event event
    ) {
        if (event == TaskEvent.Event.CANCELED) {
            claims.reviewClosed(claim);
            return;
        }
        claims.reviewOpened(claim, taskId);
    }

    /**
     * The service task "Pay out the damage".
     *
     * @param claim The claim
     */
    @WorkflowTask
    public void payOut(final Claim claim) {
        claims.payOut(claim);
    }

    /**
     * The service task "Reject the claim".
     *
     * @param claim The claim
     */
    @WorkflowTask
    public void rejectClaim(final Claim claim) {
        claims.rejectClaim(claim);
    }

}

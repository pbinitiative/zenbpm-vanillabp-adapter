/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.purchaseapproval;

import org.pbinitiative.zenbpm.examples.purchaseapproval.model.PurchaseRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.vanillabp.spi.process.ProcessService;

/**
 * What the application tells the process. {@link PurchaseApproval} names what happened in business
 * terms, and this class turns it into what the process needs: a started workflow, a completed user
 * task. It is the only class which knows {@link ProcessService}.
 * <p>
 * Every method runs in the caller's transaction. The adapter sends the call to the engine only
 * after that transaction committed, so a rollback leaves the engine untouched.
 */
@Component
@Transactional
public class Workflow {

    private final ProcessService<PurchaseRequest> bpms;

    /**
     * Built by Spring.
     *
     * @param bpms The process service of the purchase requests
     */
    public Workflow(final ProcessService<PurchaseRequest> bpms) {
        this.bpms = bpms;
    }

    /**
     * A purchase was requested. VanillaBP saves the request and starts its workflow.
     *
     * @param purchaseRequest The request
     */
    public void purchaseRequested(final PurchaseRequest purchaseRequest) {
        bpms.startWorkflow(purchaseRequest);
    }

    /**
     * The clerk checked the request, which completes the clerk's user task.
     *
     * @param purchaseRequest The request
     * @param taskId The id of the check
     */
    public void requestChecked(
        final PurchaseRequest purchaseRequest,
        final String taskId
    ) {
        bpms.completeUserTask(purchaseRequest, taskId);
    }

    /**
     * The manager decided on a purchase above the limit, which completes the manager's user task.
     *
     * @param purchaseRequest The request
     * @param taskId The id of the approval
     */
    public void highValuePurchaseDecided(
        final PurchaseRequest purchaseRequest,
        final String taskId
    ) {
        bpms.completeUserTask(purchaseRequest, taskId);
    }

}

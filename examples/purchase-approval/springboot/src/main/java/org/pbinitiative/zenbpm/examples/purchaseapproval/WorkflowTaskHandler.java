/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.purchaseapproval;

import org.pbinitiative.zenbpm.examples.purchaseapproval.model.PurchaseRequest;
import org.springframework.stereotype.Component;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskEvent;
import io.vanillabp.spi.service.TaskId;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * What the process tells the application. Each method serves the task whose
 * <code>zenbpm:taskDefinition type</code> is the method's name, and only passes it on to
 * {@link PurchaseApproval}.
 * <p>
 * A user task is work for a person, so its method is a notification: it is called once when the
 * task is created and once more should the task be taken away, and returning from it completes
 * nothing. The adapter looks for new user tasks every <code>user-task-poll-interval</code>, so the
 * notification comes up to that long after the process reached the task.
 */
@Component
@WorkflowService(
        workflowAggregateClass = PurchaseRequest.class,
        bpmnProcess = @BpmnProcess(bpmnProcessId = "purchase_approval"))
public class WorkflowTaskHandler {

    private final PurchaseApproval purchaseApproval;

    /**
     * Built by Spring.
     *
     * @param purchaseApproval The business service
     */
    public WorkflowTaskHandler(final PurchaseApproval purchaseApproval) {
        this.purchaseApproval = purchaseApproval;
    }

    /**
     * The user task "Check purchase request" of the clerk.
     *
     * @param purchaseRequest The request
     * @param taskId The id of the user task
     * @param event Whether it was created or taken away
     */
    @WorkflowTask
    public void checkRequest(
        final PurchaseRequest purchaseRequest,
        @TaskId final String taskId,
        @TaskEvent final TaskEvent.Event event
    ) {
        if (event == TaskEvent.Event.CANCELED) {
            purchaseApproval.checkClosed(purchaseRequest);
            return;
        }
        purchaseApproval.checkOpened(purchaseRequest, taskId);
    }

    /**
     * The user task "Approve high-value purchase" of the manager.
     *
     * @param purchaseRequest The request
     * @param taskId The id of the user task
     * @param event Whether it was created or taken away
     */
    @WorkflowTask
    public void approveHighValue(
        final PurchaseRequest purchaseRequest,
        @TaskId final String taskId,
        @TaskEvent final TaskEvent.Event event
    ) {
        if (event == TaskEvent.Event.CANCELED) {
            purchaseApproval.highValueApprovalClosed(purchaseRequest);
            return;
        }
        purchaseApproval.highValueApprovalOpened(purchaseRequest, taskId);
    }

    /**
     * The service task "Place purchase order".
     *
     * @param purchaseRequest The request
     */
    @WorkflowTask
    public void placeOrder(final PurchaseRequest purchaseRequest) {
        purchaseApproval.placeOrder(purchaseRequest);
    }

    /**
     * The service task "Notify the requester".
     *
     * @param purchaseRequest The request
     */
    @WorkflowTask
    public void notifyRejection(final PurchaseRequest purchaseRequest) {
        purchaseApproval.notifyRejection(purchaseRequest);
    }

}

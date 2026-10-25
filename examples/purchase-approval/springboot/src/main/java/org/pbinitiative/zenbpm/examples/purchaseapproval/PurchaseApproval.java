/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.purchaseapproval;

import java.util.Optional;
import java.util.UUID;

import org.pbinitiative.zenbpm.examples.purchaseapproval.model.PurchaseRequest;
import org.pbinitiative.zenbpm.examples.purchaseapproval.model.PurchaseRequest.Status;
import org.pbinitiative.zenbpm.examples.purchaseapproval.model.PurchaseRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the company does with a purchase request, said without a word about processes. Whenever the
 * request moves on, this class tells {@link Workflow} what happened; when the process reaches a
 * task, {@link WorkflowTaskHandler} calls in here.
 * <p>
 * <code>@Transactional</code> sits on the methods the API calls only. VanillaBP runs a task handler
 * in a transaction of its own, which the methods it calls here join. A <code>@Transactional</code>
 * on them would mark that transaction rollback-only when a <code>TaskException</code> passes it,
 * so they carry none.
 * <p>
 * An answer loads the request with a write lock. Two answers to the same task then wait for each
 * other, and the second finds the task answered, instead of both deciding.
 */
@Service
public class PurchaseApproval {

    private static final Logger LOG = LoggerFactory.getLogger(PurchaseApproval.class);

    private final PurchaseRequestRepository purchaseRequests;

    private final Workflow workflow;

    private final PurchasingSystem purchasingSystem;

    /**
     * Built by Spring.
     *
     * @param purchaseRequests The purchase requests
     * @param workflow What this class tells the process
     * @param purchasingSystem Where purchase orders are placed
     */
    public PurchaseApproval(
        final PurchaseRequestRepository purchaseRequests,
        final Workflow workflow,
        final PurchasingSystem purchasingSystem
    ) {
        this.purchaseRequests = purchaseRequests;
        this.workflow = workflow;
        this.purchasingSystem = purchasingSystem;
    }

    /**
     * An employee asks to buy something.
     *
     * @param requester Who asks
     * @param item What is to be bought
     * @param amount What it costs
     * @return The request, submitted
     */
    @Transactional
    public PurchaseRequest submit(
        final String requester,
        final String item,
        final int amount
    ) {
        if (requester == null || requester.isBlank()) {
            throw new InvalidPurchaseRequest("A purchase request names its requester");
        }
        if (item == null || item.isBlank()) {
            throw new InvalidPurchaseRequest("A purchase request names the item to be bought");
        }
        if (amount <= 0) {
            throw new InvalidPurchaseRequest("The amount has to be positive, not %d".formatted(amount));
        }
        final var purchaseRequest = new PurchaseRequest(UUID.randomUUID().toString(), requester, item, amount);
        workflow.purchaseRequested(purchaseRequest);
        LOG.info("Purchase request {} submitted by {} for '{}' at {}", purchaseRequest.getId(), requester, item,
                amount);
        return purchaseRequest;
    }

    /**
     * The process asks the clerk to check the request. A real application would put the check on
     * the clerk's task list; this one keeps the task's id, which is all it takes to answer it later.
     *
     * @param purchaseRequest The request
     * @param taskId The id of the check
     */
    public void checkOpened(
        final PurchaseRequest purchaseRequest,
        final String taskId
    ) {
        purchaseRequest.setCheckTaskId(taskId);
        purchaseRequest.setStatus(Status.IN_CHECK);
        LOG.info("Purchase request {} waits for the clerk's check", purchaseRequest.getId());
    }

    /**
     * The check was taken away without an answer, so its id leads nowhere any more.
     *
     * @param purchaseRequest The request
     */
    public void checkClosed(final PurchaseRequest purchaseRequest) {
        purchaseRequest.setCheckTaskId(null);
    }

    /**
     * The clerk answers the check.
     *
     * @param id The id of the request
     * @param inOrder Whether the request is in order
     * @param comment Why, for the requester
     * @return The request, checked
     */
    @Transactional
    public PurchaseRequest check(
        final String id,
        final boolean inOrder,
        final String comment
    ) {
        final var purchaseRequest = purchaseRequestToAnswer(id);
        final var taskId = openTask(purchaseRequest.getCheckTaskId(), id, "check");
        purchaseRequest.setRequestInOrder(inOrder);
        purchaseRequest.setComment(comment);
        purchaseRequest.setCheckTaskId(null);
        purchaseRequest.setStatus(inOrder ? Status.CHECKED : Status.REJECTED);
        workflow.requestChecked(purchaseRequest, taskId);
        LOG.info("Purchase request {} checked: {}", id, inOrder ? "in order" : "rejected");
        return purchaseRequest;
    }

    /**
     * The process asks the manager to approve a purchase above the limit.
     *
     * @param purchaseRequest The request
     * @param taskId The id of the approval
     */
    public void highValueApprovalOpened(
        final PurchaseRequest purchaseRequest,
        final String taskId
    ) {
        purchaseRequest.setHighValueApprovalTaskId(taskId);
        purchaseRequest.setStatus(Status.IN_HIGH_VALUE_APPROVAL);
        LOG.info("Purchase request {} waits for the manager's approval", purchaseRequest.getId());
    }

    /**
     * The approval was taken away without an answer.
     *
     * @param purchaseRequest The request
     */
    public void highValueApprovalClosed(final PurchaseRequest purchaseRequest) {
        purchaseRequest.setHighValueApprovalTaskId(null);
    }

    /**
     * The manager decides on a purchase above the limit.
     *
     * @param id The id of the request
     * @param approved Whether the manager approves
     * @param comment Why, for the requester
     * @return The request, decided
     */
    @Transactional
    public PurchaseRequest decideHighValuePurchase(
        final String id,
        final boolean approved,
        final String comment
    ) {
        final var purchaseRequest = purchaseRequestToAnswer(id);
        final var taskId = openTask(purchaseRequest.getHighValueApprovalTaskId(), id, "high-value approval");
        purchaseRequest.setApprovedByManager(approved);
        purchaseRequest.setComment(comment);
        purchaseRequest.setHighValueApprovalTaskId(null);
        purchaseRequest.setStatus(approved ? Status.APPROVED : Status.REJECTED);
        workflow.highValuePurchaseDecided(purchaseRequest, taskId);
        LOG.info("Purchase request {} {} by the manager", id, approved ? "approved" : "rejected");
        return purchaseRequest;
    }

    /**
     * Turns the approved request into a purchase order. Should the purchasing system be down, the
     * exception makes the engine try again: the model gives the task three attempts.
     *
     * @param purchaseRequest The request
     */
    public void placeOrder(final PurchaseRequest purchaseRequest) {
        purchaseRequest.setPurchaseOrderNumber(purchasingSystem.placeOrder(purchaseRequest.getItem(),
                purchaseRequest.getAmount()));
        purchaseRequest.setStatus(Status.ORDERED);
    }

    /**
     * Tells the requester that the purchase was rejected, and why. A real application sends an
     * e-mail; this one logs it.
     *
     * @param purchaseRequest The request
     */
    public void notifyRejection(final PurchaseRequest purchaseRequest) {
        LOG.info("Mail to {}: your request for '{}' was rejected: {}", purchaseRequest.getRequester(),
                purchaseRequest.getItem(), purchaseRequest.getComment());
        purchaseRequest.requesterNotified();
    }

    /**
     * A purchase request as it is stored right now.
     *
     * @param id The id of the request
     * @return The request, if there is one
     */
    @Transactional(readOnly = true)
    public Optional<PurchaseRequest> find(final String id) {
        return purchaseRequests.findById(id);
    }

    private PurchaseRequest purchaseRequestToAnswer(final String id) {
        return purchaseRequests
                .findLockedById(id)
                .orElseThrow(() -> new UnknownPurchaseRequest(id));
    }

    private static String openTask(
        final String taskId,
        final String id,
        final String task
    ) {
        if (taskId == null) {
            throw new NoOpenTask("Purchase request %s has no open %s".formatted(id, task));
        }
        return taskId;
    }

    /**
     * A purchase request which cannot be submitted as it is.
     */
    public static class InvalidPurchaseRequest extends RuntimeException {

        private static final long serialVersionUID = 1L;

        InvalidPurchaseRequest(final String message) {
            super(message);
        }

    }

    /**
     * There is no purchase request of this id.
     */
    public static class UnknownPurchaseRequest extends RuntimeException {

        private static final long serialVersionUID = 1L;

        UnknownPurchaseRequest(final String id) {
            super("There is no purchase request %s".formatted(id));
        }

    }

    /**
     * The answer came for a task which is not open: not yet, not any more, or never for this
     * request.
     */
    public static class NoOpenTask extends RuntimeException {

        private static final long serialVersionUID = 1L;

        NoOpenTask(final String message) {
            super(message);
        }

    }

}

/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.purchaseapproval.model;

import io.vanillabp.spi.service.NoSyncWithBPMS;
import io.vanillabp.spi.service.SyncWithBPMS;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The workflow aggregate: one purchase request, and everything the process and the people working
 * on it know about it. There are no process variables; this entity is the only place the data
 * lives.
 * <p>
 * The class is <code>@NoSyncWithBPMS</code>, so nothing goes to the engine unless a member says
 * so. What is shared is what the gateways of the model decide on, and each of them is a boolean:
 * the answers of the clerk and the manager, and {@link #isAboveApprovalLimit()}. The limit is a
 * rule of the company, so it is written here in Java, and the model only asks the question. The
 * amount itself stays in the application: VanillaBP refuses to hand a number to a gateway, because
 * every expression language compares numbers in its own way.
 */
@Entity
@Table(name = "PURCHASE_REQUEST")
@NoSyncWithBPMS
public class PurchaseRequest {

    /**
     * Above this amount a manager has to approve the purchase as well.
     */
    public static final int APPROVAL_LIMIT = 50_000;

    /**
     * Where a purchase request stands, as the people asking about it want to know it.
     */
    public enum Status {
        /** Submitted; the clerk's check is not open yet. */
        SUBMITTED,
        /** The clerk has to check the request. */
        IN_CHECK,
        /** The clerk found the request in order. */
        CHECKED,
        /** The amount is above the limit, so the manager has to approve it. */
        IN_HIGH_VALUE_APPROVAL,
        /** The manager approved the request. */
        APPROVED,
        /** The purchase order is placed. */
        ORDERED,
        /** The clerk or the manager rejected the request. */
        REJECTED
    }

    @Id
    private String id;

    private String requester;

    private String item;

    private int amount;

    @Enumerated(EnumType.STRING)
    private Status status;

    private String checkTaskId;

    @SyncWithBPMS
    private boolean requestInOrder;

    private String highValueApprovalTaskId;

    @SyncWithBPMS
    private boolean approvedByManager;

    private String comment;

    private String purchaseOrderNumber;

    private boolean requesterNotified;

    /**
     * For JPA.
     */
    protected PurchaseRequest() {
    }

    /**
     * A purchase request just submitted.
     *
     * @param id The id of the request
     * @param requester Who asks for the purchase
     * @param item What is to be bought
     * @param amount What it costs, in whole currency units
     */
    public PurchaseRequest(
        final String id,
        final String requester,
        final String item,
        final int amount
    ) {
        this.id = id;
        this.requester = requester;
        this.item = item;
        this.amount = amount;
        this.status = Status.SUBMITTED;
    }

    /**
     * The id of the request, which is the id of its workflow as well.
     *
     * @return The id
     */
    public String getId() {
        return id;
    }

    /**
     * Who asks for the purchase.
     *
     * @return The requester
     */
    public String getRequester() {
        return requester;
    }

    /**
     * What is to be bought.
     *
     * @return The item
     */
    public String getItem() {
        return item;
    }

    /**
     * What it costs.
     *
     * @return The amount, in whole currency units
     */
    public int getAmount() {
        return amount;
    }

    /**
     * Whether a manager has to approve the purchase as well, which the gateway behind the clerk's
     * check reads.
     *
     * @return <code>true</code> above the {@link #APPROVAL_LIMIT}
     */
    @SyncWithBPMS
    public boolean isAboveApprovalLimit() {
        return amount > APPROVAL_LIMIT;
    }

    /**
     * Where the request stands.
     *
     * @return The status
     */
    public Status getStatus() {
        return status;
    }

    /**
     * Sets where the request stands.
     *
     * @param status The status
     */
    public void setStatus(final Status status) {
        this.status = status;
    }

    /**
     * The clerk's open check, the id the engine gave the user task. It is what completes the task
     * later, and it is <code>null</code> while no check is open.
     *
     * @return The task id, or <code>null</code>
     */
    public String getCheckTaskId() {
        return checkTaskId;
    }

    /**
     * Sets the clerk's open check.
     *
     * @param checkTaskId The task id, or <code>null</code> once the check is closed
     */
    public void setCheckTaskId(final String checkTaskId) {
        this.checkTaskId = checkTaskId;
    }

    /**
     * What the clerk found, read by the gateway behind the check.
     *
     * @return <code>true</code> if the request is in order, <code>false</code> before the check
     */
    public boolean isRequestInOrder() {
        return requestInOrder;
    }

    /**
     * Sets what the clerk found.
     *
     * @param requestInOrder Whether the request is in order
     */
    public void setRequestInOrder(final boolean requestInOrder) {
        this.requestInOrder = requestInOrder;
    }

    /**
     * The manager's open approval, the id the engine gave the user task.
     *
     * @return The task id, or <code>null</code>
     */
    public String getHighValueApprovalTaskId() {
        return highValueApprovalTaskId;
    }

    /**
     * Sets the manager's open approval.
     *
     * @param highValueApprovalTaskId The task id, or <code>null</code> once the approval is closed
     */
    public void setHighValueApprovalTaskId(final String highValueApprovalTaskId) {
        this.highValueApprovalTaskId = highValueApprovalTaskId;
    }

    /**
     * What the manager decided, read by the gateway behind the approval.
     *
     * @return <code>true</code> if approved, <code>false</code> before the approval
     */
    public boolean isApprovedByManager() {
        return approvedByManager;
    }

    /**
     * Sets what the manager decided.
     *
     * @param approvedByManager Whether the manager approved
     */
    public void setApprovedByManager(final boolean approvedByManager) {
        this.approvedByManager = approvedByManager;
    }

    /**
     * The comment of the last person who answered, which the requester reads on a rejection.
     *
     * @return The comment, or <code>null</code>
     */
    public String getComment() {
        return comment;
    }

    /**
     * Sets the comment of the person answering.
     *
     * @param comment The comment
     */
    public void setComment(final String comment) {
        this.comment = comment;
    }

    /**
     * The number of the purchase order the purchasing system placed.
     *
     * @return The number, or <code>null</code> while nothing is ordered
     */
    public String getPurchaseOrderNumber() {
        return purchaseOrderNumber;
    }

    /**
     * Sets the number of the placed purchase order.
     *
     * @param purchaseOrderNumber The number
     */
    public void setPurchaseOrderNumber(final String purchaseOrderNumber) {
        this.purchaseOrderNumber = purchaseOrderNumber;
    }

    /**
     * Whether the requester was told about the rejection.
     *
     * @return <code>true</code> once told
     */
    public boolean isRequesterNotified() {
        return requesterNotified;
    }

    /**
     * Notes that the requester was told about the rejection.
     */
    public void requesterNotified() {
        this.requesterNotified = true;
    }

}

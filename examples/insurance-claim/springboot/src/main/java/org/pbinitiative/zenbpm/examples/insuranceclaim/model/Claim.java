/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.insuranceclaim.model;

import java.math.BigDecimal;

import io.vanillabp.spi.service.NoSyncWithBPMS;
import io.vanillabp.spi.service.SyncWithBPMS;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The workflow aggregate: one claim a policyholder reported for their car.
 * <p>
 * The class is <code>@NoSyncWithBPMS</code>, and three members are shared, because the model decides
 * on them: the coverage and the damage amount are the inputs of the decision table, and the
 * adjuster's answer is read by the gateway behind the review. The amount is a number, which
 * VanillaBP hands to the engine only because <code>application.yaml</code> declares it: a decision
 * table is what decides on numbers.
 */
@Entity
@Table(name = "CLAIM")
@NoSyncWithBPMS
public class Claim {

    /**
     * Where a claim stands.
     */
    public enum Status {
        /** Reported; the decision table did not route it yet. */
        REPORTED,
        /** An adjuster has to look at the claim. */
        IN_REVIEW,
        /** The damage is paid out. */
        PAID,
        /** The claim is rejected, and the policyholder was told why. */
        REJECTED
    }

    @Id
    private String claimId;

    private String policyNumber;

    private String claimant;

    @SyncWithBPMS
    private String coverage;

    @SyncWithBPMS
    private BigDecimal damageAmount;

    private String description;

    @Enumerated(EnumType.STRING)
    private Status status;

    private String reviewTaskId;

    private boolean reviewed;

    @SyncWithBPMS
    private boolean approvedByAdjuster;

    private String adjusterComment;

    private String payoutReference;

    private String rejectionReason;

    /**
     * For JPA.
     */
    protected Claim() {
    }

    /**
     * A claim just reported.
     *
     * @param claimId The id of the claim
     * @param policyNumber The policy the claim is made under
     * @param claimant Who reports the damage
     * @param coverage What the policy covers: <code>comprehensive</code>, <code>partial</code> or
     *        <code>liability</code>
     * @param damageAmount What the repair costs
     * @param description What happened
     */
    public Claim(
        final String claimId,
        final String policyNumber,
        final String claimant,
        final String coverage,
        final BigDecimal damageAmount,
        final String description
    ) {
        this.claimId = claimId;
        this.policyNumber = policyNumber;
        this.claimant = claimant;
        this.coverage = coverage;
        this.damageAmount = damageAmount;
        this.description = description;
        this.status = Status.REPORTED;
    }

    /**
     * The id of the claim, which is the id of its workflow as well.
     *
     * @return The id
     */
    public String getClaimId() {
        return claimId;
    }

    /**
     * The policy the claim is made under.
     *
     * @return The policy's number
     */
    public String getPolicyNumber() {
        return policyNumber;
    }

    /**
     * Who reports the damage.
     *
     * @return The claimant
     */
    public String getClaimant() {
        return claimant;
    }

    /**
     * What the policy covers, an input of the decision table.
     *
     * @return <code>comprehensive</code>, <code>partial</code> or <code>liability</code>
     */
    public String getCoverage() {
        return coverage;
    }

    /**
     * What the repair costs, an input of the decision table.
     *
     * @return The amount
     */
    public BigDecimal getDamageAmount() {
        return damageAmount;
    }

    /**
     * What happened.
     *
     * @return The description
     */
    public String getDescription() {
        return description;
    }

    /**
     * Where the claim stands.
     *
     * @return The status
     */
    public Status getStatus() {
        return status;
    }

    /**
     * The adjuster's open review, the id the engine gave the user task.
     *
     * @return The task id, or <code>null</code> while no review is open
     */
    public String getReviewTaskId() {
        return reviewTaskId;
    }

    /**
     * Whether an adjuster looked at the claim.
     *
     * @return <code>true</code> once reviewed
     */
    public boolean isReviewed() {
        return reviewed;
    }

    /**
     * What the adjuster decided, read by the gateway behind the review.
     *
     * @return <code>true</code> if approved
     */
    public boolean isApprovedByAdjuster() {
        return approvedByAdjuster;
    }

    /**
     * Why the adjuster decided as they did.
     *
     * @return The comment, or <code>null</code>
     */
    public String getAdjusterComment() {
        return adjusterComment;
    }

    /**
     * The reference of the transfer which paid the damage.
     *
     * @return The reference, or <code>null</code> while nothing is paid
     */
    public String getPayoutReference() {
        return payoutReference;
    }

    /**
     * Why the claim was rejected.
     *
     * @return The reason, or <code>null</code>
     */
    public String getRejectionReason() {
        return rejectionReason;
    }

    /**
     * An adjuster has to look at the claim.
     *
     * @param taskId The id of the user task
     */
    public void reviewOpened(final String taskId) {
        this.reviewTaskId = taskId;
        this.status = Status.IN_REVIEW;
    }

    /**
     * The review is closed, answered or taken away.
     */
    public void reviewClosed() {
        this.reviewTaskId = null;
    }

    /**
     * The adjuster decided.
     *
     * @param approved Whether the damage is paid
     * @param comment Why
     */
    public void reviewed(
        final boolean approved,
        final String comment
    ) {
        this.reviewed = true;
        this.approvedByAdjuster = approved;
        this.adjusterComment = comment;
        this.reviewTaskId = null;
    }

    /**
     * The damage is paid out.
     *
     * @param payoutReference The reference of the transfer
     */
    public void paid(final String payoutReference) {
        this.payoutReference = payoutReference;
        this.status = Status.PAID;
    }

    /**
     * The claim is rejected.
     *
     * @param reason Why
     */
    public void rejected(final String reason) {
        this.rejectionReason = reason;
        this.status = Status.REJECTED;
    }

}

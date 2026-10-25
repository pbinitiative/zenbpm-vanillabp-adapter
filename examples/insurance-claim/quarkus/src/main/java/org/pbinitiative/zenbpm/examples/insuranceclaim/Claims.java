/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.insuranceclaim;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.pbinitiative.zenbpm.examples.insuranceclaim.model.Claim;
import org.pbinitiative.zenbpm.examples.insuranceclaim.model.ClaimRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

/**
 * What the insurer does with a motor claim, said without a word about processes. Which claims are
 * paid at once is not decided here: the decision table <code>claim_routing</code> decides it,
 * where the claims department can read and change the rules.
 */
@ApplicationScoped
public class Claims {

    private static final Logger LOG = LoggerFactory.getLogger(Claims.class);

    private static final Set<String> COVERAGES = Set.of("comprehensive", "partial", "liability");

    private final ClaimRepository claims;

    private final Workflow workflow;

    private final PayoutSystem payoutSystem;

    /**
     * Built by CDI.
     *
     * @param claims The claims
     * @param workflow What this class tells the process
     * @param payoutSystem Where the money is transferred
     */
    @Inject
    public Claims(
        final ClaimRepository claims,
        final Workflow workflow,
        final PayoutSystem payoutSystem
    ) {
        this.claims = claims;
        this.workflow = workflow;
        this.payoutSystem = payoutSystem;
    }

    /**
     * A policyholder reports damage to their car.
     *
     * @param policyNumber The policy
     * @param claimant Who reports
     * @param coverage What the policy covers
     * @param damageAmount What the repair costs
     * @param description What happened
     * @return The claim, reported
     */
    @Transactional
    public Claim report(
        final String policyNumber,
        final String claimant,
        final String coverage,
        final BigDecimal damageAmount,
        final String description
    ) {
        if (!COVERAGES.contains(coverage)) {
            throw new InvalidClaim("The coverage is one of %s, not '%s'".formatted(COVERAGES, coverage));
        }
        if (damageAmount == null || damageAmount.signum() <= 0) {
            throw new InvalidClaim("The damage amount has to be positive");
        }
        final var claim = new Claim("CLM-%s".formatted(UUID
                .randomUUID()
                .toString()
                .substring(0, 8)), policyNumber, claimant, coverage, damageAmount, description);
        workflow.claimReported(claim);
        LOG.info("Claim {} reported under policy {}: {} of damage", claim.getClaimId(), policyNumber, damageAmount);
        return claim;
    }

    /**
     * The decision table sends the claim to an adjuster.
     *
     * @param claim The claim
     * @param taskId The id of the review
     */
    public void reviewOpened(
        final Claim claim,
        final String taskId
    ) {
        claim.reviewOpened(taskId);
        LOG.info("Claim {} waits for an adjuster", claim.getClaimId());
    }

    /**
     * The review was taken away without an answer.
     *
     * @param claim The claim
     */
    public void reviewClosed(final Claim claim) {
        claim.reviewClosed();
    }

    /**
     * The adjuster decides on the claim.
     *
     * @param claimId The id of the claim
     * @param approved Whether the damage is paid
     * @param comment Why
     * @return The claim, reviewed
     */
    @Transactional
    public Claim review(
        final String claimId,
        final boolean approved,
        final String comment
    ) {
        final var claim = claims
                .findByIdOptional(claimId)
                .orElseThrow(() -> new UnknownClaim(claimId));
        final var taskId = claim.getReviewTaskId();
        if (taskId == null) {
            throw new NoOpenReview(claimId);
        }
        claim.reviewed(approved, comment);
        workflow.claimReviewed(claim, taskId);
        LOG.info("Claim {} {} by the adjuster", claimId, approved ? "approved" : "rejected");
        return claim;
    }

    /**
     * Pays out the damage.
     *
     * @param claim The claim
     */
    public void payOut(final Claim claim) {
        claim.paid(payoutSystem.pay(claim.getClaimId(), claim.getClaimant(), claim.getDamageAmount()));
    }

    /**
     * Rejects the claim and tells the policyholder why.
     *
     * @param claim The claim
     */
    public void rejectClaim(final Claim claim) {
        final var reason = claim.isReviewed()
                ? "rejected by the adjuster: %s".formatted(claim.getAdjusterComment())
                : "a %s policy does not cover damage to your own car".formatted(claim.getCoverage());
        claim.rejected(reason);
        LOG.info("Letter to {}: we cannot pay claim {}, {}", claim.getClaimant(), claim.getClaimId(), reason);
    }

    /**
     * A claim as it is stored right now.
     *
     * @param claimId The id of the claim
     * @return The claim, if there is one
     */
    @Transactional
    public Optional<Claim> find(final String claimId) {
        return claims.findByIdOptional(claimId);
    }

    /**
     * A claim which cannot be reported as it is.
     */
    public static class InvalidClaim extends RuntimeException {

        private static final long serialVersionUID = 1L;

        InvalidClaim(final String message) {
            super(message);
        }

    }

    /**
     * There is no claim of this id.
     */
    public static class UnknownClaim extends RuntimeException {

        private static final long serialVersionUID = 1L;

        UnknownClaim(final String claimId) {
            super("There is no claim %s".formatted(claimId));
        }

    }

    /**
     * The claim waits for no review: not yet, not any more, or never.
     */
    public static class NoOpenReview extends RuntimeException {

        private static final long serialVersionUID = 1L;

        NoOpenReview(final String claimId) {
            super("Claim %s has no open review".formatted(claimId));
        }

    }

}

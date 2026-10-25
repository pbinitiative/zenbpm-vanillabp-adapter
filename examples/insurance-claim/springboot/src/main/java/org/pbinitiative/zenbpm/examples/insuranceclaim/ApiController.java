/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.insuranceclaim;

import java.math.BigDecimal;
import java.net.URI;

import org.pbinitiative.zenbpm.examples.insuranceclaim.Claims.InvalidClaim;
import org.pbinitiative.zenbpm.examples.insuranceclaim.Claims.NoOpenReview;
import org.pbinitiative.zenbpm.examples.insuranceclaim.Claims.UnknownClaim;
import org.pbinitiative.zenbpm.examples.insuranceclaim.model.Claim;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The REST API of the claims: the policyholder reports, the adjuster reviews. It talks to
 * {@link Claims} only.
 */
@RestController
@RequestMapping("/claims")
public class ApiController {

    private final Claims claims;

    /**
     * Built by Spring.
     *
     * @param claims The business service
     */
    public ApiController(final Claims claims) {
        this.claims = claims;
    }

    /**
     * Reports a claim.
     *
     * @param report The policy, the claimant and the damage
     * @return The claim, reported
     */
    @PostMapping
    public ResponseEntity<ClaimView> report(@RequestBody final ClaimReport report) {
        final var claim = claims.report(report.policyNumber(), report.claimant(), report.coverage(), report
                .damageAmount(), report.description());
        return ResponseEntity
                .created(URI.create("/claims/%s".formatted(claim.getClaimId())))
                .body(ClaimView.of(claim));
    }

    /**
     * Shows where a claim stands.
     *
     * @param claimId The id of the claim
     * @return The claim
     */
    @GetMapping("/{claimId}")
    public ClaimView show(@PathVariable final String claimId) {
        return claims
                .find(claimId)
                .map(ClaimView::of)
                .orElseThrow(() -> new UnknownClaim(claimId));
    }

    /**
     * The adjuster's decision.
     *
     * @param claimId The id of the claim
     * @param review Whether the damage is paid, and why
     * @return The claim, reviewed
     */
    @PostMapping("/{claimId}/review")
    public ClaimView review(
        @PathVariable final String claimId,
        @RequestBody final Review review
    ) {
        return ClaimView.of(claims.review(claimId, review.approved(), review.comment()));
    }

    /**
     * A claim which cannot be reported as it is.
     *
     * @param exception What is wrong
     * @return 400
     */
    @ExceptionHandler
    public ProblemDetail invalid(final InvalidClaim exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    /**
     * A claim nobody reported.
     *
     * @param exception Which claim
     * @return 404
     */
    @ExceptionHandler
    public ProblemDetail unknown(final UnknownClaim exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    /**
     * A review of a claim which waits for none.
     *
     * @param exception Which claim
     * @return 409
     */
    @ExceptionHandler
    public ProblemDetail notOpen(final NoOpenReview exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
    }

    /**
     * A claim the policyholder reports.
     *
     * @param policyNumber The policy
     * @param claimant Who reports
     * @param coverage What the policy covers: <code>comprehensive</code>, <code>partial</code> or
     *        <code>liability</code>
     * @param damageAmount What the repair costs
     * @param description What happened
     */
    public record ClaimReport(
        String policyNumber,
        String claimant,
        String coverage,
        BigDecimal damageAmount,
        String description
    ) {
    }

    /**
     * The adjuster's decision.
     *
     * @param approved Whether the damage is paid
     * @param comment Why
     */
    public record Review(
        boolean approved,
        String comment
    ) {
    }

    /**
     * A claim as the API shows it.
     *
     * @param claimId The id
     * @param policyNumber The policy
     * @param coverage What the policy covers
     * @param damageAmount What the repair costs
     * @param status Where the claim stands
     * @param reviewed Whether an adjuster looked at it
     * @param payoutReference The reference of the transfer, once paid
     * @param rejectionReason Why it was rejected, if it was
     */
    public record ClaimView(
        String claimId,
        String policyNumber,
        String coverage,
        BigDecimal damageAmount,
        Claim.Status status,
        boolean reviewed,
        String payoutReference,
        String rejectionReason
    ) {

        static ClaimView of(final Claim claim) {
            return new ClaimView(claim.getClaimId(), claim.getPolicyNumber(), claim.getCoverage(), claim
                    .getDamageAmount(), claim.getStatus(), claim.isReviewed(), claim.getPayoutReference(), claim
                            .getRejectionReason());
        }

    }

}

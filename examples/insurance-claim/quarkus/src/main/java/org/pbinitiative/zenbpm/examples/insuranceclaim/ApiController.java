/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.insuranceclaim;

import java.math.BigDecimal;
import java.net.URI;

import org.jboss.resteasy.reactive.server.ServerExceptionMapper;
import org.pbinitiative.zenbpm.examples.insuranceclaim.Claims.InvalidClaim;
import org.pbinitiative.zenbpm.examples.insuranceclaim.Claims.NoOpenReview;
import org.pbinitiative.zenbpm.examples.insuranceclaim.Claims.UnknownClaim;
import org.pbinitiative.zenbpm.examples.insuranceclaim.model.Claim;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * The REST API of the claims: the policyholder reports, the adjuster reviews. It talks to
 * {@link Claims} only.
 */
@Path("/claims")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class ApiController {

    private final Claims claims;

    /**
     * Built by CDI.
     *
     * @param claims The business service
     */
    @Inject
    public ApiController(final Claims claims) {
        this.claims = claims;
    }

    /**
     * Reports a claim.
     *
     * @param report The policy, the claimant and the damage
     * @return The claim, reported
     */
    @POST
    public Response report(final ClaimReport report) {
        final var claim = claims.report(report.policyNumber(), report.claimant(), report.coverage(), report
                .damageAmount(), report.description());
        return Response
                .created(URI.create("/claims/%s".formatted(claim.getClaimId())))
                .entity(ClaimView.of(claim))
                .build();
    }

    /**
     * Shows where a claim stands.
     *
     * @param claimId The id of the claim
     * @return The claim
     */
    @GET
    @Path("/{claimId}")
    public ClaimView show(@PathParam("claimId") final String claimId) {
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
    @POST
    @Path("/{claimId}/review")
    public ClaimView review(
        @PathParam("claimId") final String claimId,
        final Review review
    ) {
        return ClaimView.of(claims.review(claimId, review.approved(), review.comment()));
    }

    /**
     * A claim which cannot be reported as it is.
     *
     * @param exception What is wrong
     * @return 400
     */
    @ServerExceptionMapper
    public Response invalid(final InvalidClaim exception) {
        return problem(Response.Status.BAD_REQUEST, exception);
    }

    /**
     * A claim nobody reported.
     *
     * @param exception Which claim
     * @return 404
     */
    @ServerExceptionMapper
    public Response unknown(final UnknownClaim exception) {
        return problem(Response.Status.NOT_FOUND, exception);
    }

    /**
     * A review of a claim which waits for none.
     *
     * @param exception Which claim
     * @return 409
     */
    @ServerExceptionMapper
    public Response notOpen(final NoOpenReview exception) {
        return problem(Response.Status.CONFLICT, exception);
    }

    private static Response problem(
        final Response.Status status,
        final RuntimeException exception
    ) {
        return Response
                .status(status)
                .type("application/problem+json")
                .entity(new Problem(status.getReasonPhrase(), status.getStatusCode(), exception.getMessage()))
                .build();
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

    /**
     * What went wrong, in the fields of RFC 9457 which Spring Boot's twin of this example writes.
     *
     * @param title The reason phrase of the status
     * @param status The HTTP status
     * @param detail What went wrong
     */
    public record Problem(
        String title,
        int status,
        String detail
    ) {
    }

}

/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.purchaseapproval;

import java.net.URI;

import org.jboss.resteasy.reactive.server.ServerExceptionMapper;
import org.pbinitiative.zenbpm.examples.purchaseapproval.PurchaseApproval.NoOpenTask;
import org.pbinitiative.zenbpm.examples.purchaseapproval.PurchaseApproval.UnknownPurchaseRequest;
import org.pbinitiative.zenbpm.examples.purchaseapproval.model.PurchaseRequest;

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
 * The REST API of the purchase requests, the one the README walks through with curl. It talks to
 * {@link PurchaseApproval} only; that a process runs behind it is none of its business.
 */
@Path("/purchase-requests")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class ApiController {

    private final PurchaseApproval purchaseApproval;

    /**
     * Built by CDI.
     *
     * @param purchaseApproval The business service
     */
    @Inject
    public ApiController(final PurchaseApproval purchaseApproval) {
        this.purchaseApproval = purchaseApproval;
    }

    /**
     * Submits a purchase request.
     *
     * @param request Who asks for what at which amount
     * @return The request, submitted
     */
    @POST
    public Response submit(final NewPurchaseRequest request) {
        final var purchaseRequest = purchaseApproval.submit(request.requester(), request.item(), request.amount());
        return Response
                .created(URI.create("/purchase-requests/%s".formatted(purchaseRequest.getId())))
                .entity(PurchaseRequestView.of(purchaseRequest))
                .build();
    }

    /**
     * Shows where a purchase request stands.
     *
     * @param id The id of the request
     * @return The request
     */
    @GET
    @Path("/{id}")
    public PurchaseRequestView show(@PathParam("id") final String id) {
        return purchaseApproval
                .find(id)
                .map(PurchaseRequestView::of)
                .orElseThrow(() -> new UnknownPurchaseRequest(id));
    }

    /**
     * The clerk's answer to the check.
     *
     * @param id The id of the request
     * @param answer Whether the request is in order, and why
     * @return The request, checked
     */
    @POST
    @Path("/{id}/check")
    public PurchaseRequestView check(
        @PathParam("id") final String id,
        final Answer answer
    ) {
        return PurchaseRequestView.of(purchaseApproval.check(id, answer.approved(), answer.comment()));
    }

    /**
     * The manager's answer to the approval of a purchase above the limit.
     *
     * @param id The id of the request
     * @param answer Whether the manager approves, and why
     * @return The request, decided
     */
    @POST
    @Path("/{id}/high-value-approval")
    public PurchaseRequestView approveHighValue(
        @PathParam("id") final String id,
        final Answer answer
    ) {
        return PurchaseRequestView.of(purchaseApproval.decideHighValuePurchase(id, answer.approved(), answer
                .comment()));
    }

    /**
     * A request of an id nobody submitted.
     *
     * @param exception What was not found
     * @return 404
     */
    @ServerExceptionMapper
    public Response unknown(final UnknownPurchaseRequest exception) {
        return problem(Response.Status.NOT_FOUND, exception);
    }

    /**
     * An answer for a task which is not open.
     *
     * @param exception Which task is not open
     * @return 409
     */
    @ServerExceptionMapper
    public Response notOpen(final NoOpenTask exception) {
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
     * A purchase request to submit.
     *
     * @param requester Who asks
     * @param item What is to be bought
     * @param amount What it costs, in whole currency units
     */
    public record NewPurchaseRequest(
        String requester,
        String item,
        int amount
    ) {
    }

    /**
     * The answer of the clerk or the manager.
     *
     * @param approved Whether they approve
     * @param comment Why, for the requester
     */
    public record Answer(
        boolean approved,
        String comment
    ) {
    }

    /**
     * A purchase request as the API shows it.
     *
     * @param id The id
     * @param requester Who asks
     * @param item What is to be bought
     * @param amount What it costs
     * @param status Where the request stands
     * @param comment The comment of the last answer
     * @param purchaseOrderNumber The number of the purchase order, once placed
     * @param requesterNotified Whether the requester was told about a rejection
     */
    public record PurchaseRequestView(
        String id,
        String requester,
        String item,
        int amount,
        PurchaseRequest.Status status,
        String comment,
        String purchaseOrderNumber,
        boolean requesterNotified
    ) {

        static PurchaseRequestView of(final PurchaseRequest purchaseRequest) {
            return new PurchaseRequestView(purchaseRequest.getId(), purchaseRequest.getRequester(), purchaseRequest
                    .getItem(), purchaseRequest.getAmount(), purchaseRequest.getStatus(), purchaseRequest
                            .getComment(), purchaseRequest.getPurchaseOrderNumber(), purchaseRequest
                                    .isRequesterNotified());
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

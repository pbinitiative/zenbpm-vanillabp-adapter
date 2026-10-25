/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.purchaseapproval;

import java.net.URI;

import org.pbinitiative.zenbpm.examples.purchaseapproval.PurchaseApproval.NoOpenTask;
import org.pbinitiative.zenbpm.examples.purchaseapproval.PurchaseApproval.UnknownPurchaseRequest;
import org.pbinitiative.zenbpm.examples.purchaseapproval.model.PurchaseRequest;
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
 * The REST API of the purchase requests, the one the README walks through with curl. It talks to
 * {@link PurchaseApproval} only; that a process runs behind it is none of its business.
 */
@RestController
@RequestMapping("/purchase-requests")
public class ApiController {

    private final PurchaseApproval purchaseApproval;

    /**
     * Built by Spring.
     *
     * @param purchaseApproval The business service
     */
    public ApiController(final PurchaseApproval purchaseApproval) {
        this.purchaseApproval = purchaseApproval;
    }

    /**
     * Submits a purchase request.
     *
     * @param request Who asks for what at which amount
     * @return The request, submitted
     */
    @PostMapping
    public ResponseEntity<PurchaseRequestView> submit(@RequestBody final NewPurchaseRequest request) {
        final var purchaseRequest = purchaseApproval.submit(request.requester(), request.item(), request.amount());
        return ResponseEntity
                .created(URI.create("/purchase-requests/%s".formatted(purchaseRequest.getId())))
                .body(PurchaseRequestView.of(purchaseRequest));
    }

    /**
     * Shows where a purchase request stands.
     *
     * @param id The id of the request
     * @return The request
     */
    @GetMapping("/{id}")
    public PurchaseRequestView show(@PathVariable final String id) {
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
    @PostMapping("/{id}/check")
    public PurchaseRequestView check(
        @PathVariable final String id,
        @RequestBody final Answer answer
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
    @PostMapping("/{id}/high-value-approval")
    public PurchaseRequestView approveHighValue(
        @PathVariable final String id,
        @RequestBody final Answer answer
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
    @ExceptionHandler
    public ProblemDetail unknown(final UnknownPurchaseRequest exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    /**
     * An answer for a task which is not open.
     *
     * @param exception Which task is not open
     * @return 409
     */
    @ExceptionHandler
    public ProblemDetail notOpen(final NoOpenTask exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
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

}

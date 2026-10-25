/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.cardpayment;

import java.math.BigDecimal;
import java.net.URI;

import org.jboss.resteasy.reactive.server.ServerExceptionMapper;
import org.pbinitiative.zenbpm.examples.cardpayment.CardPayment.PaymentExists;
import org.pbinitiative.zenbpm.examples.cardpayment.model.Payment;

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
 * The REST API of the payments, which the checkout of the web shop calls. It talks to
 * {@link CardPayment} only.
 */
@Path("/payments")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class ApiController {

    private final CardPayment cardPayment;

    /**
     * Built by CDI.
     *
     * @param cardPayment The business service
     */
    @Inject
    public ApiController(final CardPayment cardPayment) {
        this.cardPayment = cardPayment;
    }

    /**
     * Pays an order by card. The answer comes before the card is charged: the payment is pending
     * until the process charged it.
     *
     * @param request The order, the amount and the card
     * @return The payment, pending
     */
    @POST
    public Response pay(final PaymentRequest request) {
        final var payment = cardPayment.pay(request.orderId(), request.customerEmail(), request.amount(), request
                .cardToken());
        return Response
                .created(URI.create("/payments/%s".formatted(payment.getOrderId())))
                .entity(PaymentView.of(payment))
                .build();
    }

    /**
     * Shows where the payment of an order stands.
     *
     * @param orderId The number of the order
     * @return The payment
     */
    @GET
    @Path("/{orderId}")
    public Response show(@PathParam("orderId") final String orderId) {
        return cardPayment
                .find(orderId)
                .map(payment -> Response
                        .ok(PaymentView.of(payment))
                        .build())
                .orElseGet(() -> Response
                        .status(Response.Status.NOT_FOUND)
                        .build());
    }

    /**
     * A second payment of the same order.
     *
     * @param exception Which order
     * @return 409
     */
    @ServerExceptionMapper
    public Response paidAlready(final PaymentExists exception) {
        return Response
                .status(Response.Status.CONFLICT)
                .type("application/problem+json")
                .entity(new Problem(Response.Status.CONFLICT.getReasonPhrase(), Response.Status.CONFLICT
                        .getStatusCode(), exception.getMessage()))
                .build();
    }

    /**
     * A payment the checkout asks for.
     *
     * @param orderId The number of the order
     * @param customerEmail Where the customer is told about the payment
     * @param amount What the order costs
     * @param cardToken The card, as the payment form of the provider handed it over
     */
    public record PaymentRequest(
        String orderId,
        String customerEmail,
        BigDecimal amount,
        String cardToken
    ) {
    }

    /**
     * A payment as the API shows it, without the card.
     *
     * @param orderId The number of the order
     * @param amount What the order costs
     * @param status Where the payment stands
     * @param transactionId The provider's id of the charge, once charged
     * @param declineReason Why the provider declined the card, if it did
     * @param orderConfirmed Whether the order is confirmed
     * @param customerAskedForAnotherCard Whether the customer was asked for another card
     */
    public record PaymentView(
        String orderId,
        BigDecimal amount,
        Payment.Status status,
        String transactionId,
        String declineReason,
        boolean orderConfirmed,
        boolean customerAskedForAnotherCard
    ) {

        static PaymentView of(final Payment payment) {
            return new PaymentView(payment.getOrderId(), payment.getAmount(), payment.getStatus(), payment
                    .getTransactionId(), payment.getDeclineReason(), payment.isOrderConfirmed(), payment
                            .isCustomerAskedForAnotherCard());
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

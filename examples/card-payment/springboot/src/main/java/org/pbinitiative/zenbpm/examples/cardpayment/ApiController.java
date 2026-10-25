/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.cardpayment;

import java.math.BigDecimal;

import org.pbinitiative.zenbpm.examples.cardpayment.CardPayment.InvalidPayment;
import org.pbinitiative.zenbpm.examples.cardpayment.CardPayment.PaymentExists;
import org.pbinitiative.zenbpm.examples.cardpayment.model.Payment;
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
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The REST API of the payments, which the checkout of the web shop calls. It talks to
 * {@link CardPayment} only.
 */
@RestController
@RequestMapping("/payments")
public class ApiController {

    private final CardPayment cardPayment;

    /**
     * Built by Spring.
     *
     * @param cardPayment The business service
     */
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
    @PostMapping
    public ResponseEntity<PaymentView> pay(@RequestBody final PaymentRequest request) {
        final var payment = cardPayment.pay(request.orderId(), request.customerEmail(), request.amount(), request
                .cardToken());
        return ResponseEntity
                .created(UriComponentsBuilder
                        .fromPath("/payments/{orderId}")
                        .buildAndExpand(payment.getOrderId())
                        .encode()
                        .toUri())
                .body(PaymentView.of(payment));
    }

    /**
     * Shows where the payment of an order stands.
     *
     * @param orderId The number of the order
     * @return The payment
     */
    @GetMapping("/{orderId}")
    public ResponseEntity<PaymentView> show(@PathVariable final String orderId) {
        return ResponseEntity.of(cardPayment
                .find(orderId)
                .map(PaymentView::of));
    }

    /**
     * A payment which cannot be charged as it is.
     *
     * @param exception What is wrong with it
     * @return 400
     */
    @ExceptionHandler
    public ProblemDetail invalid(final InvalidPayment exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    /**
     * A second payment of the same order.
     *
     * @param exception Which order
     * @return 409
     */
    @ExceptionHandler
    public ProblemDetail paidAlready(final PaymentExists exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
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

}

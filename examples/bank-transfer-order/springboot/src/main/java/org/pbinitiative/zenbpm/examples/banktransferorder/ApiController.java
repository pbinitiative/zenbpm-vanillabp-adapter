/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.banktransferorder;

import java.math.BigDecimal;

import org.pbinitiative.zenbpm.examples.banktransferorder.BankTransferOrders.InvalidOrder;
import org.pbinitiative.zenbpm.examples.banktransferorder.BankTransferOrders.InvalidTransfer;
import org.pbinitiative.zenbpm.examples.banktransferorder.BankTransferOrders.OrderExists;
import org.pbinitiative.zenbpm.examples.banktransferorder.BankTransferOrders.TransferNotMatched;
import org.pbinitiative.zenbpm.examples.banktransferorder.BankTransferOrders.TransferTooLate;
import org.pbinitiative.zenbpm.examples.banktransferorder.model.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The REST API: the shop places and shows orders, and the bank reports the transfers to the
 * shop's account through its webhook. Both talk to {@link BankTransferOrders} only.
 */
@RestController
public class ApiController {

    private final BankTransferOrders orders;

    /**
     * Built by Spring.
     *
     * @param orders The business service
     */
    public ApiController(final BankTransferOrders orders) {
        this.orders = orders;
    }

    /**
     * Places an order paid by bank transfer.
     *
     * @param request The order
     * @return The order, waiting for the payment
     */
    @PostMapping("/orders")
    public ResponseEntity<OrderView> place(@RequestBody final OrderRequest request) {
        final var order = orders.place(request.orderId(), request.customerEmail(), request.item(), request
                .total());
        return ResponseEntity
                .created(UriComponentsBuilder
                        .fromPath("/orders/{orderId}")
                        .buildAndExpand(order.getOrderId())
                        .encode()
                        .toUri())
                .body(OrderView.of(order));
    }

    /**
     * Shows where an order stands.
     *
     * @param orderId The number of the order
     * @return The order
     */
    @GetMapping("/orders/{orderId}")
    public ResponseEntity<OrderView> show(@PathVariable final String orderId) {
        return ResponseEntity.of(orders
                .find(orderId)
                .map(OrderView::of));
    }

    /**
     * The bank's webhook: a transfer arrived on the shop's account.
     *
     * @param transfer The reference and the amount of the transfer
     * @return The order it pays
     */
    @PostMapping("/bank/transfers")
    public OrderView transferReceived(@RequestBody final Transfer transfer) {
        return OrderView.of(orders.transferReceived(transfer.reference(), transfer.amount()));
    }

    /**
     * An order which cannot be placed as it is.
     *
     * @param exception What is wrong with it
     * @return 400
     */
    @ExceptionHandler
    public ProblemDetail invalid(final InvalidOrder exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    /**
     * A report of the bank without the reference or the amount of the transfer.
     *
     * @param exception What is missing
     * @return 400
     */
    @ExceptionHandler
    public ProblemDetail incomplete(final InvalidTransfer exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    /**
     * An order of a number placed before.
     *
     * @param exception Which order
     * @return 409
     */
    @ExceptionHandler
    public ProblemDetail exists(final OrderExists exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
    }

    /**
     * A transfer which pays no order waiting for it.
     *
     * @param exception Why
     * @return 422
     */
    @ExceptionHandler
    public ProblemDetail notMatched(final TransferNotMatched exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, exception.getMessage());
    }

    /**
     * A transfer for a cancelled order, paid back.
     *
     * @param exception Which order
     * @return 409
     */
    @ExceptionHandler
    public ProblemDetail tooLate(final TransferTooLate exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
    }

    /**
     * An order the shop places.
     *
     * @param orderId The number of the order
     * @param customerEmail Where the customer is written to
     * @param item What is ordered
     * @param total What the customer has to transfer
     */
    public record OrderRequest(
        String orderId,
        String customerEmail,
        String item,
        BigDecimal total
    ) {
    }

    /**
     * A transfer as the bank reports it.
     *
     * @param reference The reference the customer gave
     * @param amount What arrived
     */
    public record Transfer(
        String reference,
        BigDecimal amount
    ) {
    }

    /**
     * An order as the API shows it.
     *
     * @param orderId The number of the order
     * @param item What is ordered
     * @param total What the customer has to transfer
     * @param status Where the order stands
     * @param paymentInstructionsSent Whether the customer got the payment instructions
     * @param reminderSent Whether the customer was reminded
     * @param trackingNumber The tracking number, once shipped
     * @param stockReleased Whether the item went back on the shelf
     */
    public record OrderView(
        String orderId,
        String item,
        BigDecimal total,
        Order.Status status,
        boolean paymentInstructionsSent,
        boolean reminderSent,
        String trackingNumber,
        boolean stockReleased
    ) {

        static OrderView of(final Order order) {
            return new OrderView(order.getOrderId(), order.getItem(), order.getTotal(), order.getStatus(), order
                    .isPaymentInstructionsSent(), order.isReminderSent(), order.getTrackingNumber(), order
                            .isStockReleased());
        }

    }

}

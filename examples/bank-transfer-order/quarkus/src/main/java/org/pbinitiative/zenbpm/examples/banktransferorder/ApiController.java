/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.banktransferorder;

import java.math.BigDecimal;

import org.jboss.resteasy.reactive.server.ServerExceptionMapper;
import org.pbinitiative.zenbpm.examples.banktransferorder.BankTransferOrders.InvalidOrder;
import org.pbinitiative.zenbpm.examples.banktransferorder.BankTransferOrders.InvalidTransfer;
import org.pbinitiative.zenbpm.examples.banktransferorder.BankTransferOrders.OrderExists;
import org.pbinitiative.zenbpm.examples.banktransferorder.BankTransferOrders.TransferNotMatched;
import org.pbinitiative.zenbpm.examples.banktransferorder.BankTransferOrders.TransferTooLate;
import org.pbinitiative.zenbpm.examples.banktransferorder.model.Order;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;

/**
 * The REST API: the shop places and shows orders, and the bank reports the transfers to the
 * shop's account through its webhook. Both talk to {@link BankTransferOrders} only.
 */
@Path("/")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class ApiController {

    private final BankTransferOrders orders;

    /**
     * Built by CDI.
     *
     * @param orders The business service
     */
    @Inject
    public ApiController(final BankTransferOrders orders) {
        this.orders = orders;
    }

    /**
     * Places an order paid by bank transfer.
     *
     * @param request The order
     * @return The order, waiting for the payment
     */
    @POST
    @Path("/orders")
    public Response place(final OrderRequest request) {
        final var order = orders.place(request.orderId(), request.customerEmail(), request.item(), request
                .total());
        return Response
                .created(UriBuilder
                        .fromPath("/orders/{orderId}")
                        .build(order.getOrderId()))
                .entity(OrderView.of(order))
                .build();
    }

    /**
     * Shows where an order stands.
     *
     * @param orderId The number of the order
     * @return The order
     */
    @GET
    @Path("/orders/{orderId}")
    public Response show(@PathParam("orderId") final String orderId) {
        return orders
                .find(orderId)
                .map(order -> Response
                        .ok(OrderView.of(order))
                        .build())
                .orElseGet(() -> Response
                        .status(Response.Status.NOT_FOUND)
                        .build());
    }

    /**
     * The bank's webhook: a transfer arrived on the shop's account.
     *
     * @param transfer The reference and the amount of the transfer
     * @return The order it pays
     */
    @POST
    @Path("/bank/transfers")
    public OrderView transferReceived(final Transfer transfer) {
        return OrderView.of(orders.transferReceived(transfer.reference(), transfer.amount()));
    }

    /**
     * An order which cannot be placed as it is.
     *
     * @param exception What is wrong with it
     * @return 400
     */
    @ServerExceptionMapper
    public Response invalid(final InvalidOrder exception) {
        return problem(400, "Bad Request", exception);
    }

    /**
     * A report of the bank without the reference or the amount of the transfer.
     *
     * @param exception What is missing
     * @return 400
     */
    @ServerExceptionMapper
    public Response incomplete(final InvalidTransfer exception) {
        return problem(400, "Bad Request", exception);
    }

    /**
     * An order of a number placed before.
     *
     * @param exception Which order
     * @return 409
     */
    @ServerExceptionMapper
    public Response exists(final OrderExists exception) {
        return problem(409, "Conflict", exception);
    }

    /**
     * A transfer which pays no order waiting for it.
     *
     * @param exception Why
     * @return 422
     */
    @ServerExceptionMapper
    public Response notMatched(final TransferNotMatched exception) {
        return problem(422, "Unprocessable Content", exception);
    }

    /**
     * A transfer for a cancelled order, paid back.
     *
     * @param exception Which order
     * @return 409
     */
    @ServerExceptionMapper
    public Response tooLate(final TransferTooLate exception) {
        return problem(409, "Conflict", exception);
    }

    private static Response problem(
        final int status,
        final String title,
        final RuntimeException exception
    ) {
        return Response
                .status(status)
                .type("application/problem+json")
                .entity(new Problem(title, status, exception.getMessage()))
                .build();
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

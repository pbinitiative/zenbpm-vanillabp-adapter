/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.banktransferorder;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.regex.Pattern;

import org.hibernate.exception.ConstraintViolationException;
import org.pbinitiative.zenbpm.examples.banktransferorder.model.Order;
import org.pbinitiative.zenbpm.examples.banktransferorder.model.Order.Status;
import org.pbinitiative.zenbpm.examples.banktransferorder.model.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;

/**
 * What the web shop does with an order paid by bank transfer, said without a word about
 * processes. The bank reports every transfer to {@link #transferReceived}, and only a transfer
 * which matches an order waiting for it moves the process on.
 */
@ApplicationScoped
public class BankTransferOrders {

    private static final Logger LOG = LoggerFactory.getLogger(BankTransferOrders.class);

    /** The account the customers transfer to. */
    private static final String IBAN = "DE02 1203 0000 0000 2020 51";

    /** An order's number is a single segment of the order's address, and the transfer's reference. */
    private static final Pattern ORDER_NUMBER = Pattern.compile("[A-Za-z0-9-]{1,40}");

    private final OrderRepository orders;

    private final Workflow workflow;

    private final Warehouse warehouse;

    /**
     * Built by CDI.
     *
     * @param orders The orders
     * @param workflow What this class tells the process
     * @param warehouse Where the stock is
     */
    @Inject
    public BankTransferOrders(
        final OrderRepository orders,
        final Workflow workflow,
        final Warehouse warehouse
    ) {
        this.orders = orders;
        this.workflow = workflow;
        this.warehouse = warehouse;
    }

    /**
     * The customer orders and chooses to pay by bank transfer. The item is kept aside at once.
     *
     * @param orderId The number of the order
     * @param customerEmail Where the customer is written to
     * @param item What is ordered
     * @param total What the customer has to transfer
     * @return The order, waiting for the payment
     */
    @Transactional
    public Order place(
        final String orderId,
        final String customerEmail,
        final String item,
        final BigDecimal total
    ) {
        if (orderId == null || !ORDER_NUMBER
                .matcher(orderId)
                .matches()) {
            throw new InvalidOrder("The order's number is 1 to 40 letters, digits and dashes, not '%s'".formatted(
                    orderId));
        }
        if (blank(customerEmail) || blank(item)) {
            throw new InvalidOrder("An order names the customer's e-mail address and the item");
        }
        if (total == null || total.signum() <= 0 || total
                .stripTrailingZeros()
                .scale() > 2) {
            throw new InvalidOrder("The total is a positive amount with at most two decimals, not %s".formatted(
                    total));
        }
        if (orders.findByIdOptional(orderId).isPresent()) {
            throw new OrderExists(orderId);
        }
        final var order = new Order(orderId, customerEmail, item, total);
        try {
            // flushed at once: an order of the same number placed at the same moment fails on the
            // primary key here, and is answered as one placed before
            orders.persistAndFlush(order);
        } catch (final ConstraintViolationException exception) {
            throw new OrderExists(orderId);
        }
        warehouse.reserve(orderId, item);
        workflow.orderPlaced(order);
        return order;
    }

    /**
     * Tells the customer where to transfer the money to, and which reference to give.
     *
     * @param order The order
     */
    public void sendPaymentInstructions(final Order order) {
        LOG.info("Mail to {}: please transfer {} to {} with the reference {}", order.getCustomerEmail(), order
                .getTotal(), IBAN, order.getOrderId());
        order.paymentInstructionsSent();
    }

    /**
     * Reminds the customer who has not paid yet. A customer whose transfer arrived meanwhile is not
     * reminded.
     *
     * @param order The order
     */
    public void sendReminder(final Order order) {
        if (order.getStatus() != Status.AWAITING_PAYMENT) {
            // the transfer arrived just before the reminder was due
            LOG.info("Order {} waits for no transfer any more, so its customer is not reminded", order
                    .getOrderId());
            return;
        }
        LOG.info("Mail to {}: we have not received your transfer of {} for order {} yet", order.getCustomerEmail(),
                order.getTotal(), order.getOrderId());
        order.reminderSent();
    }

    /**
     * The bank reports a transfer to the shop's account.
     *
     * @param reference The reference the customer gave, an order's number if they followed the
     *        instructions
     * @param amount What arrived
     * @return The order the transfer pays
     */
    @Transactional
    public Order transferReceived(
        final String reference,
        final BigDecimal amount
    ) {
        if (blank(reference) || amount == null || amount.signum() <= 0) {
            throw new InvalidTransfer("A transfer names its reference and a positive amount");
        }
        // locked until the transaction ends: the bank may deliver the same transfer twice at once,
        // and only one delivery may find the order waiting for its money
        final var order = orders
                .findByIdOptional(reference, LockModeType.PESSIMISTIC_WRITE)
                .orElseThrow(() -> new TransferNotMatched("No order has the reference %s".formatted(reference)));
        if (order.isCancelled()) {
            throw new TransferTooLate(reference);
        }
        if (order.getStatus() != Status.AWAITING_PAYMENT) {
            throw new TransferNotMatched("Order %s is paid already".formatted(reference));
        }
        if (order
                .getTotal()
                .compareTo(amount) != 0) {
            throw new TransferNotMatched("Order %s costs %s, but %s arrived".formatted(reference, order.getTotal(),
                    amount));
        }
        order.paid();
        workflow.paymentReceived(order);
        LOG.info("The transfer of {} for order {} arrived", amount, reference);
        return order;
    }

    /**
     * Ships the paid order.
     *
     * @param order The order
     */
    public void shipOrder(final Order order) {
        order.shipped(warehouse.ship(order.getOrderId()));
    }

    /**
     * Cancels an order which was not paid by the deadline, and puts its item back on the shelf. A
     * transfer accepted while the deadline passed came too late to ship the order, which the
     * workflow left the wait for; the money is paid back.
     *
     * @param order The order
     */
    public void cancelOrder(final Order order) {
        warehouse.release(order.getOrderId());
        order.cancelled();
        if (order.getStatus() == Status.REFUND_DUE) {
            LOG.info("Mail to {}: order {} is cancelled, because your transfer came after the deadline; we pay it back",
                    order.getCustomerEmail(), order.getOrderId());
            return;
        }
        LOG.info("Mail to {}: order {} is cancelled, because no transfer arrived", order.getCustomerEmail(), order
                .getOrderId());
    }

    /**
     * An order as it is stored right now.
     *
     * @param orderId The number of the order
     * @return The order, if there is one
     */
    @Transactional
    public Optional<Order> find(final String orderId) {
        return orders.findByIdOptional(orderId);
    }

    private static boolean blank(final String value) {
        return value == null || value.isBlank();
    }

    /**
     * An order which cannot be placed as it is.
     */
    public static class InvalidOrder extends RuntimeException {

        private static final long serialVersionUID = 1L;

        InvalidOrder(final String message) {
            super(message);
        }

    }

    /**
     * A report of the bank which lacks what identifies a transfer.
     */
    public static class InvalidTransfer extends RuntimeException {

        private static final long serialVersionUID = 1L;

        InvalidTransfer(final String message) {
            super(message);
        }

    }

    /**
     * There is an order of this number already.
     */
    public static class OrderExists extends RuntimeException {

        private static final long serialVersionUID = 1L;

        OrderExists(final String orderId) {
            super("There is an order %s already".formatted(orderId));
        }

    }

    /**
     * A transfer which pays no order waiting for it. Accounting clears it by hand.
     */
    public static class TransferNotMatched extends RuntimeException {

        private static final long serialVersionUID = 1L;

        TransferNotMatched(final String message) {
            super(message);
        }

    }

    /**
     * A transfer for an order cancelled already. Accounting pays it back.
     */
    public static class TransferTooLate extends RuntimeException {

        private static final long serialVersionUID = 1L;

        TransferTooLate(final String orderId) {
            super("Order %s was cancelled before the transfer arrived; it is to be paid back".formatted(orderId));
        }

    }

}

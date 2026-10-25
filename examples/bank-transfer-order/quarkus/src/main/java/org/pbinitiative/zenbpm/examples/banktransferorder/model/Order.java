/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.banktransferorder.model;

import java.math.BigDecimal;

import io.vanillabp.spi.service.NoSyncWithBPMS;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The workflow aggregate: one order of the web shop, paid by bank transfer.
 * <p>
 * The model waits and times out, but decides nothing on data, so nothing of the order goes to the
 * engine: the class is <code>@NoSyncWithBPMS</code> without a shared member. The message the bank's
 * transfer correlates finds the workflow by the order's number all the same, because the adapter
 * gives every message the workflow waits for the aggregate's id as its correlation key.
 */
@Entity
@Table(name = "CUSTOMER_ORDER")
@NoSyncWithBPMS
public class Order {

    /**
     * Where an order stands.
     */
    public enum Status {
        /** The shop waits for the transfer. */
        AWAITING_PAYMENT,
        /** The transfer arrived; the order is not shipped yet. */
        PAID,
        /** The order is on its way. */
        SHIPPED,
        /** The deadline passed without a transfer. */
        CANCELLED
    }

    @Id
    private String orderId;

    private String customerEmail;

    private String item;

    private BigDecimal total;

    @Enumerated(EnumType.STRING)
    private Status status;

    private boolean paymentInstructionsSent;

    private boolean reminderSent;

    private String trackingNumber;

    private boolean stockReleased;

    /**
     * For JPA.
     */
    protected Order() {
    }

    /**
     * An order just placed.
     *
     * @param orderId The number of the order, which the customer names as the transfer's reference
     * @param customerEmail Where the customer is written to
     * @param item What is ordered
     * @param total What the customer has to transfer
     */
    public Order(
        final String orderId,
        final String customerEmail,
        final String item,
        final BigDecimal total
    ) {
        this.orderId = orderId;
        this.customerEmail = customerEmail;
        this.item = item;
        this.total = total;
        this.status = Status.AWAITING_PAYMENT;
    }

    /**
     * The number of the order, which is the id of its workflow and the reference of the transfer.
     *
     * @return The number
     */
    public String getOrderId() {
        return orderId;
    }

    /**
     * Where the customer is written to.
     *
     * @return The e-mail address
     */
    public String getCustomerEmail() {
        return customerEmail;
    }

    /**
     * What is ordered.
     *
     * @return The item
     */
    public String getItem() {
        return item;
    }

    /**
     * What the customer has to transfer.
     *
     * @return The total
     */
    public BigDecimal getTotal() {
        return total;
    }

    /**
     * Where the order stands.
     *
     * @return The status
     */
    public Status getStatus() {
        return status;
    }

    /**
     * Whether the customer got the payment instructions.
     *
     * @return <code>true</code> once sent
     */
    public boolean isPaymentInstructionsSent() {
        return paymentInstructionsSent;
    }

    /**
     * Whether the customer was reminded to pay.
     *
     * @return <code>true</code> once reminded
     */
    public boolean isReminderSent() {
        return reminderSent;
    }

    /**
     * The tracking number of the parcel.
     *
     * @return The number, or <code>null</code> while the order is not shipped
     */
    public String getTrackingNumber() {
        return trackingNumber;
    }

    /**
     * Whether the stock reserved for the order went back to the warehouse.
     *
     * @return <code>true</code> once released
     */
    public boolean isStockReleased() {
        return stockReleased;
    }

    /**
     * The customer got the payment instructions.
     */
    public void paymentInstructionsSent() {
        this.paymentInstructionsSent = true;
    }

    /**
     * The customer was reminded to pay.
     */
    public void reminderSent() {
        this.reminderSent = true;
    }

    /**
     * The transfer arrived.
     */
    public void paid() {
        this.status = Status.PAID;
    }

    /**
     * The order is on its way.
     *
     * @param trackingNumber The tracking number of the parcel
     */
    public void shipped(final String trackingNumber) {
        this.trackingNumber = trackingNumber;
        this.status = Status.SHIPPED;
    }

    /**
     * The order is cancelled, and its stock is back in the warehouse.
     */
    public void cancelled() {
        this.stockReleased = true;
        this.status = Status.CANCELLED;
    }

}

/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.cardpayment.model;

import java.math.BigDecimal;

import io.vanillabp.spi.service.NoSyncWithBPMS;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The workflow aggregate: the payment of one order of the web shop, by the order's number.
 * <p>
 * The model decides nothing on data, so nothing of the payment goes to the engine. The class is
 * <code>@NoSyncWithBPMS</code> without a single shared member; the engine gets the order's
 * number only, which VanillaBP always shares to find the workflow again. The amount and the card
 * stay in the application.
 */
@Entity
@Table(name = "PAYMENT")
@NoSyncWithBPMS
public class Payment {

    /**
     * Where a payment stands.
     */
    public enum Status {
        /** The card is not charged yet. */
        PENDING,
        /** The card is charged and the order confirmed. */
        PAID,
        /** The provider declined the card. */
        DECLINED
    }

    @Id
    private String orderId;

    private String customerEmail;

    private BigDecimal amount;

    private String cardToken;

    @Enumerated(EnumType.STRING)
    private Status status;

    private String transactionId;

    private String declineReason;

    private boolean orderConfirmed;

    private boolean customerAskedForAnotherCard;

    /**
     * For JPA.
     */
    protected Payment() {
    }

    /**
     * A payment of an order just placed.
     *
     * @param orderId The number of the order
     * @param customerEmail Where the customer is told about the payment
     * @param amount What the order costs
     * @param cardToken The card, as the payment form of the provider handed it over
     */
    public Payment(
        final String orderId,
        final String customerEmail,
        final BigDecimal amount,
        final String cardToken
    ) {
        this.orderId = orderId;
        this.customerEmail = customerEmail;
        this.amount = amount;
        this.cardToken = cardToken;
        this.status = Status.PENDING;
    }

    /**
     * The number of the order, which is the id of the workflow as well.
     *
     * @return The order's number
     */
    public String getOrderId() {
        return orderId;
    }

    /**
     * Where the customer is told about the payment.
     *
     * @return The e-mail address
     */
    public String getCustomerEmail() {
        return customerEmail;
    }

    /**
     * What the order costs.
     *
     * @return The amount
     */
    public BigDecimal getAmount() {
        return amount;
    }

    /**
     * The card. A shop never sees the card number: the payment form of the provider turns it into
     * a token, which only the provider can charge.
     *
     * @return The token
     */
    public String getCardToken() {
        return cardToken;
    }

    /**
     * Where the payment stands.
     *
     * @return The status
     */
    public Status getStatus() {
        return status;
    }

    /**
     * The provider's id of the charge.
     *
     * @return The id, or <code>null</code> while the card is not charged
     */
    public String getTransactionId() {
        return transactionId;
    }

    /**
     * Why the provider declined the card.
     *
     * @return The reason, or <code>null</code>
     */
    public String getDeclineReason() {
        return declineReason;
    }

    /**
     * Whether the order is confirmed to the customer and handed to the warehouse.
     *
     * @return <code>true</code> once confirmed
     */
    public boolean isOrderConfirmed() {
        return orderConfirmed;
    }

    /**
     * Whether the customer was asked to pay with another card.
     *
     * @return <code>true</code> once asked
     */
    public boolean isCustomerAskedForAnotherCard() {
        return customerAskedForAnotherCard;
    }

    /**
     * The provider charged the card.
     *
     * @param transactionId The provider's id of the charge
     */
    public void charged(final String transactionId) {
        this.transactionId = transactionId;
    }

    /**
     * The provider declined the card.
     *
     * @param reason Why
     */
    public void declined(final String reason) {
        this.declineReason = reason;
        this.status = Status.DECLINED;
    }

    /**
     * The order is confirmed, which makes the payment complete.
     */
    public void orderConfirmed() {
        this.orderConfirmed = true;
        this.status = Status.PAID;
    }

    /**
     * The customer was asked to pay with another card.
     */
    public void customerAskedForAnotherCard() {
        this.customerAskedForAnotherCard = true;
    }

}

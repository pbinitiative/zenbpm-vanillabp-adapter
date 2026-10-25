/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.cardpayment;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.regex.Pattern;

import org.hibernate.exception.ConstraintViolationException;
import org.pbinitiative.zenbpm.examples.cardpayment.PaymentProvider.CardDeclined;
import org.pbinitiative.zenbpm.examples.cardpayment.model.Payment;
import org.pbinitiative.zenbpm.examples.cardpayment.model.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityExistsException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;

/**
 * What the web shop does to get paid for an order, said without a word about processes.
 * <p>
 * Two things can go wrong when a card is charged, and they are not the same. A declined card is an
 * answer: the provider looked at the card and said no, and asking again changes nothing, so
 * {@link #charge(Payment)} records it and reports it. A provider which does not answer says nothing
 * about the card; its exception leaves this class, the engine tries again, and the process never
 * sees it unless the provider stays away longer than the attempts last.
 */
@Service
public class CardPayment {

    private static final Logger LOG = LoggerFactory.getLogger(CardPayment.class);

    /** What an order number may look like: it names the payment in the address of the API. */
    private static final Pattern ORDER_NUMBER = Pattern.compile("[A-Za-z0-9-]{1,40}");

    private final PaymentRepository payments;

    private final Workflow workflow;

    private final PaymentProvider provider;

    private final EntityManager entityManager;

    /**
     * Built by Spring.
     *
     * @param payments The payments
     * @param workflow What this class tells the process
     * @param provider Who charges the card
     * @param entityManager Where a new payment is inserted
     */
    public CardPayment(
        final PaymentRepository payments,
        final Workflow workflow,
        final PaymentProvider provider,
        final EntityManager entityManager
    ) {
        this.payments = payments;
        this.workflow = workflow;
        this.provider = provider;
        this.entityManager = entityManager;
    }

    /**
     * The customer placed an order and pays it by card. A payment which cannot be charged as it is
     * is refused before anything is stored. The payment is inserted before its workflow starts, so
     * of two payments of the same order sent at the same time only one gets in: the order's number
     * is the primary key, and the database refuses the second row.
     *
     * @param orderId The number of the order
     * @param customerEmail Where the customer is told about the payment
     * @param amount What the order costs
     * @param cardToken The card, as the payment form handed it over
     * @return The payment, pending
     */
    @Transactional
    public Payment pay(
        final String orderId,
        final String customerEmail,
        final BigDecimal amount,
        final String cardToken
    ) {
        validate(orderId, customerEmail, amount, cardToken);
        if (payments.existsById(orderId)) {
            throw new PaymentExists(orderId);
        }
        final var payment = new Payment(orderId, customerEmail, amount, cardToken);
        insert(payment);
        workflow.paymentRequested(payment);
        LOG.info("Payment of order {} requested: {}", orderId, amount);
        return payment;
    }

    /**
     * Charges the card.
     *
     * @param payment The payment
     * @return <code>true</code> once charged, <code>false</code> if the provider declined the card
     */
    public boolean charge(final Payment payment) {
        try {
            payment.charged(provider.charge(payment.getOrderId(), payment.getCardToken(), payment.getAmount()));
            return true;
        } catch (final CardDeclined declined) {
            payment.declined(declined.getMessage());
            LOG.info("The card of order {} was declined: {}", payment.getOrderId(), declined.getMessage());
            return false;
        }
    }

    /**
     * Confirms the paid order to the customer and hands it to the warehouse.
     *
     * @param payment The payment
     */
    public void confirmOrder(final Payment payment) {
        LOG.info("Mail to {}: your order {} is paid and on its way", payment.getCustomerEmail(), payment
                .getOrderId());
        payment.orderConfirmed();
    }

    /**
     * Asks the customer to pay with another card.
     *
     * @param payment The payment
     */
    public void askForAnotherCard(final Payment payment) {
        LOG.info("Mail to {}: your card was declined ({}), please pay order {} with another one", payment
                .getCustomerEmail(), payment.getDeclineReason(), payment.getOrderId());
        payment.customerAskedForAnotherCard();
    }

    /**
     * A payment as it is stored right now.
     *
     * @param orderId The number of the order
     * @return The payment, if there is one
     */
    @Transactional(readOnly = true)
    public Optional<Payment> find(final String orderId) {
        return payments.findById(orderId);
    }

    private static void validate(
        final String orderId,
        final String customerEmail,
        final BigDecimal amount,
        final String cardToken
    ) {
        if (orderId == null || !ORDER_NUMBER
                .matcher(orderId)
                .matches()) {
            throw new InvalidPayment("The order number is 1 to 40 letters, digits and dashes, not '%s'".formatted(
                    orderId));
        }
        if (customerEmail == null || customerEmail.isBlank()) {
            throw new InvalidPayment("The customer's e-mail address is missing");
        }
        if (cardToken == null || cardToken.isBlank()) {
            throw new InvalidPayment("The card token is missing");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new InvalidPayment("The amount has to be positive");
        }
        if (amount
                .stripTrailingZeros()
                .scale() > 2) {
            throw new InvalidPayment("The amount has at most two decimals, not %s".formatted(amount));
        }
    }

    private void insert(final Payment payment) {
        try {
            entityManager.persist(payment);
            entityManager.flush();
        } catch (final PersistenceException failure) {
            if (aSecondRowOfTheSameOrder(failure)) {
                throw new PaymentExists(payment.getOrderId());
            }
            throw failure;
        }
    }

    private static boolean aSecondRowOfTheSameOrder(final Throwable failure) {
        for (var cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof EntityExistsException || cause instanceof ConstraintViolationException) {
                return true;
            }
        }
        return false;
    }

    /**
     * A payment which cannot be charged as it is: something is missing, or not what a payment
     * needs.
     */
    public static class InvalidPayment extends RuntimeException {

        private static final long serialVersionUID = 1L;

        InvalidPayment(final String message) {
            super(message);
        }

    }

    /**
     * The order is paid already, or being paid: a second payment would charge the customer twice.
     */
    public static class PaymentExists extends RuntimeException {

        private static final long serialVersionUID = 1L;

        PaymentExists(final String orderId) {
            super("Order %s is paid already, or being paid".formatted(orderId));
        }

    }

}

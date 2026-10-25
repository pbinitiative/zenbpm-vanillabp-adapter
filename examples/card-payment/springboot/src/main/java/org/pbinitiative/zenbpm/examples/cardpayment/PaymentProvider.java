/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.cardpayment;

import java.math.BigDecimal;

/**
 * The payment provider, such as Stripe or Adyen, which charges a card. A real shop calls it over
 * its API; this example simulates it with {@link LocalPaymentProvider}.
 */
public interface PaymentProvider {

    /**
     * Charges a card. Calling it again for the same order charges nothing twice: a real provider
     * takes the order's number as the idempotency key of the charge.
     *
     * @param orderId The number of the order, the idempotency key
     * @param cardToken The card
     * @param amount What to charge
     * @return The provider's id of the charge
     * @throws CardDeclined Where the provider declines the card, which is an answer about the
     *         card and not a failure
     * @throws ProviderUnavailable Where the provider does not answer, which says nothing about the
     *         card
     */
    String charge(
        String orderId,
        String cardToken,
        BigDecimal amount
    );

    /**
     * The provider declined the card: no money, a blocked card, a suspicion of fraud.
     */
    class CardDeclined extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /**
         * The provider declined the card.
         *
         * @param reason Why, as the provider says it
         */
        public CardDeclined(final String reason) {
            super(reason);
        }

    }

    /**
     * The provider did not answer.
     */
    class ProviderUnavailable extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /**
         * The provider did not answer.
         *
         * @param message What happened
         */
        public ProviderUnavailable(final String message) {
            super(message);
        }

    }

}

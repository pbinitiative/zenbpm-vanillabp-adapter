/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.cardpayment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * A payment provider simulated in memory. It knows the test cards of the big providers, so a demo
 * picks its outcome by the card it pays with:
 * <ul>
 * <li><code>tok_visa</code> is charged,</li>
 * <li><code>tok_chargeDeclined</code> is declined,</li>
 * <li><code>tok_processingError</code> fails the first attempt of an order, as a provider which
 * did not answer in time, and is charged on the next one.</li>
 * </ul>
 * Switched off with {@link #setAvailable(boolean)}, it answers no charge at all, as in an outage.
 */
@Component
public class LocalPaymentProvider implements PaymentProvider {

    private static final Logger LOG = LoggerFactory.getLogger(LocalPaymentProvider.class);

    private final Map<String, Integer> attempts = new ConcurrentHashMap<>();

    private final Map<String, List<Instant>> attemptTimes = new ConcurrentHashMap<>();

    private final Map<String, String> charges = new ConcurrentHashMap<>();

    private volatile boolean available = true;

    @Override
    public String charge(
        final String orderId,
        final String cardToken,
        final BigDecimal amount
    ) {
        final var attempt = attempts.merge(orderId, 1, Integer::sum);
        attemptTimes
                .computeIfAbsent(orderId, id -> new CopyOnWriteArrayList<>())
                .add(Instant.now());
        LOG.info("Attempt {} to charge {} for order {}", attempt, amount, orderId);
        if (!available) {
            throw new ProviderUnavailable("The payment provider is down");
        }
        return switch (cardToken) {
            case "tok_chargeDeclined" -> throw new CardDeclined("insufficient funds");
            case "tok_processingError" -> {
                if (attempt == 1) {
                    throw new ProviderUnavailable("The payment provider did not answer in time");
                }
                yield charged(orderId);
            }
            default -> charged(orderId);
        };
    }

    /**
     * Whether the provider answers.
     *
     * @return <code>false</code> during an outage
     */
    public boolean isAvailable() {
        return available;
    }

    /**
     * Starts or ends an outage.
     *
     * @param available <code>false</code> for an outage
     */
    public void setAvailable(final boolean available) {
        this.available = available;
        LOG.info("The payment provider is {}", available ? "back" : "down");
    }

    /**
     * How often the shop tried to charge the card of an order.
     *
     * @param orderId The number of the order
     * @return The attempts
     */
    public int attemptsFor(final String orderId) {
        return attempts.getOrDefault(orderId, 0);
    }

    /**
     * When the shop tried to charge the card of an order.
     *
     * @param orderId The number of the order
     * @return The instant of each attempt, the first one first
     */
    public List<Instant> attemptTimesFor(final String orderId) {
        return List.copyOf(attemptTimes.getOrDefault(orderId, List.of()));
    }

    private String charged(final String orderId) {
        return charges.computeIfAbsent(orderId, id -> "ch_%s".formatted(UUID
                .randomUUID()
                .toString()
                .replace("-", "")
                .substring(0, 16)));
    }

}

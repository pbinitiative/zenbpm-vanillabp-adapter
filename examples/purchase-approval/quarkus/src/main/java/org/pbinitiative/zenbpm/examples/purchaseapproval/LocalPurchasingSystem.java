/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.purchaseapproval;

import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * A purchasing system simulated in memory: it numbers the orders it is given.
 */
@ApplicationScoped
public class LocalPurchasingSystem implements PurchasingSystem {

    private static final Logger LOG = LoggerFactory.getLogger(LocalPurchasingSystem.class);

    private final AtomicInteger lastOrderNumber = new AtomicInteger(4500);

    @Override
    public String placeOrder(
        final String item,
        final int amount
    ) {
        final var orderNumber = "PO-%d".formatted(lastOrderNumber.incrementAndGet());
        LOG.info("Purchase order {} placed for '{}' at {}", orderNumber, item, amount);
        return orderNumber;
    }

}

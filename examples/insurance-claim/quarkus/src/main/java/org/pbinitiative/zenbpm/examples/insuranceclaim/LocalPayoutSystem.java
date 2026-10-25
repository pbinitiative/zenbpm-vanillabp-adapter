/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.insuranceclaim;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * A payment system simulated in memory: it numbers the transfers, one per claim.
 */
@ApplicationScoped
public class LocalPayoutSystem implements PayoutSystem {

    private static final Logger LOG = LoggerFactory.getLogger(LocalPayoutSystem.class);

    private final Map<String, String> transfers = new ConcurrentHashMap<>();

    private final AtomicInteger lastTransfer = new AtomicInteger(7000);

    @Override
    public String pay(
        final String claimId,
        final String claimant,
        final BigDecimal amount
    ) {
        return transfers.computeIfAbsent(claimId, id -> {
            final var reference = "PAY-%d".formatted(lastTransfer.incrementAndGet());
            LOG.info("{} transferred to {} for claim {} as {}", amount, claimant, claimId, reference);
            return reference;
        });
    }

}

/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.employeeonboarding;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * The IT department simulated in memory.
 */
@ApplicationScoped
public class LocalItDepartment implements ItDepartment {

    private static final Logger LOG = LoggerFactory.getLogger(LocalItDepartment.class);

    private final Map<String, String> laptops = new ConcurrentHashMap<>();

    private final AtomicInteger lastSerialNumber = new AtomicInteger(30_000);

    @Override
    public String createAccounts(
        final String employeeName,
        final String position
    ) {
        final var emailAddress = "%s@example.com".formatted(employeeName
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z]+", "."));
        LOG.info("Accounts created for {} ({}): {}", employeeName, position, emailAddress);
        return emailAddress;
    }

    @Override
    public String orderLaptop(final String onboardingId) {
        return laptops.computeIfAbsent(onboardingId, id -> {
            final var serialNumber = "NB-%d".formatted(lastSerialNumber.incrementAndGet());
            LOG.info("Laptop {} ordered for onboarding {}", serialNumber, onboardingId);
            return serialNumber;
        });
    }

}

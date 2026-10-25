/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.employeeonboarding;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Facility management simulated in memory.
 */
@ApplicationScoped
public class LocalFacilityManagement implements FacilityManagement {

    private static final Logger LOG = LoggerFactory.getLogger(LocalFacilityManagement.class);

    private final Map<String, String> badges = new ConcurrentHashMap<>();

    private final AtomicInteger lastBadge = new AtomicInteger(500);

    @Override
    public String issueBadge(
        final String onboardingId,
        final String employeeName
    ) {
        return badges.computeIfAbsent(onboardingId, id -> {
            final var badgeNumber = "BADGE-%d".formatted(lastBadge.incrementAndGet());
            LOG.info("Badge {} issued for {}", badgeNumber, employeeName);
            return badgeNumber;
        });
    }

}

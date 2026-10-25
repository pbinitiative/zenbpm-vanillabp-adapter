/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.employeeonboarding;

/**
 * Facility management, which issues the badges that open the doors. A real company calls its
 * access control; this example simulates it with {@link LocalFacilityManagement}.
 */
public interface FacilityManagement {

    /**
     * Issues the badge of a new employee.
     *
     * @param onboardingId The onboarding, the idempotency key
     * @param employeeName Whose badge it is
     * @return The badge's number
     */
    String issueBadge(
        String onboardingId,
        String employeeName
    );

}

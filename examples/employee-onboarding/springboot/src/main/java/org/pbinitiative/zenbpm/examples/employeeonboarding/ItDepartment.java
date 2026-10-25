/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.employeeonboarding;

/**
 * The IT department's systems: the directory which holds the accounts, and the procurement of
 * the hardware. A real company calls them over their APIs; this example simulates them with
 * {@link LocalItDepartment}.
 */
public interface ItDepartment {

    /**
     * Creates the accounts of a new employee: e-mail, directory, the tools of the position.
     *
     * @param employeeName Who joins
     * @param position As what
     * @return The e-mail address
     */
    String createAccounts(
        String employeeName,
        String position
    );

    /**
     * Orders a laptop for a new employee.
     *
     * @param onboardingId The onboarding, the idempotency key of the order
     * @return The laptop's serial number
     */
    String orderLaptop(String onboardingId);

}

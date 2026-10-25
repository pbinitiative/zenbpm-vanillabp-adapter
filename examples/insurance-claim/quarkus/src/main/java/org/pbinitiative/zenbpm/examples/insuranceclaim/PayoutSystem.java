/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.insuranceclaim;

import java.math.BigDecimal;

/**
 * The insurer's payment system, which transfers the money for a claim. A real insurer calls its
 * accounting; this example simulates it with {@link LocalPayoutSystem}.
 */
public interface PayoutSystem {

    /**
     * Transfers the money for a claim. Calling it again for the same claim pays nothing twice.
     *
     * @param claimId The claim, the idempotency key of the transfer
     * @param claimant Who receives the money
     * @param amount How much
     * @return The reference of the transfer
     */
    String pay(
        String claimId,
        String claimant,
        BigDecimal amount
    );

}

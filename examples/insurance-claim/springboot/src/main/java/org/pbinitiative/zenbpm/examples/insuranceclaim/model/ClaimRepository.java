/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.insuranceclaim.model;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import jakarta.persistence.LockModeType;

/**
 * The claims. VanillaBP loads and saves the workflow aggregate through this repository whenever the
 * process calls a task.
 */
public interface ClaimRepository extends JpaRepository<Claim, String> {

    /**
     * Loads a claim and keeps it locked until the transaction ends. Two adjusters who answer the
     * same review at once would otherwise both find it open, and both answers would be saved.
     *
     * @param claimId The id of the claim
     * @return The claim, if there is one
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Claim> findLockedByClaimId(String claimId);

}

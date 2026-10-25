/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.employeeonboarding.model;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import jakarta.persistence.LockModeType;

/**
 * The onboardings. VanillaBP loads and saves the workflow aggregate through this repository
 * whenever one of the two processes calls a task.
 */
public interface OnboardingRepository extends JpaRepository<Onboarding, String> {

    /**
     * An onboarding, locked until the transaction ends. HR's answer to the welcome meeting reads it
     * so: a second answer at the same time waits, and then finds the meeting held.
     *
     * @param onboardingId The id of the onboarding
     * @return The onboarding, if there is one
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Onboarding> findLockedByOnboardingId(String onboardingId);

}

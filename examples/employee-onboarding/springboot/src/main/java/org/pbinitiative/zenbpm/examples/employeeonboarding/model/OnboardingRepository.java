/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.employeeonboarding.model;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The onboardings. VanillaBP loads and saves the workflow aggregate through this repository
 * whenever one of the two processes calls a task.
 */
public interface OnboardingRepository extends JpaRepository<Onboarding, String> {
}

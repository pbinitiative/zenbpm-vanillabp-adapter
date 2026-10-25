/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.employeeonboarding.model;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The onboardings. VanillaBP loads and saves the workflow aggregate through this repository
 * whenever one of the two processes calls a task.
 */
@ApplicationScoped
public class OnboardingRepository implements PanacheRepositoryBase<Onboarding, String> {
}

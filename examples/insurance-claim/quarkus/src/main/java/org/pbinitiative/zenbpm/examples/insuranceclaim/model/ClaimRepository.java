/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.insuranceclaim.model;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The claims. VanillaBP loads and saves the workflow aggregate through this repository whenever the
 * process calls a task.
 */
@ApplicationScoped
public class ClaimRepository implements PanacheRepositoryBase<Claim, String> {
}

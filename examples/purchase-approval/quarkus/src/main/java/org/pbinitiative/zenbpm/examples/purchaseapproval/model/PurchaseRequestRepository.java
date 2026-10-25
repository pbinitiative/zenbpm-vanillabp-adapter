/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.purchaseapproval.model;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The purchase requests. VanillaBP loads and saves the workflow aggregate through this repository
 * whenever the process calls a task.
 */
@ApplicationScoped
public class PurchaseRequestRepository implements PanacheRepositoryBase<PurchaseRequest, String> {
}

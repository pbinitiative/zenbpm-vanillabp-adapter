/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.banktransferorder.model;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The orders. VanillaBP loads and saves the workflow aggregate through this repository whenever the
 * process calls a task.
 */
public interface OrderRepository extends JpaRepository<Order, String> {
}

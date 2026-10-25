/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.cardpayment.model;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The payments. VanillaBP loads and saves the workflow aggregate through this repository whenever
 * the process calls a task.
 */
public interface PaymentRepository extends JpaRepository<Payment, String> {
}

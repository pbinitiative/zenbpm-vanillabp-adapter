/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.task;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The payments of the task IT.
 */
public interface PaymentRepository extends JpaRepository<Payment, String> {
}

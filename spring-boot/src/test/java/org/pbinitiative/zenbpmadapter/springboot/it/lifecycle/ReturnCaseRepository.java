/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.lifecycle;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The returns of the lifecycle IT.
 */
public interface ReturnCaseRepository extends JpaRepository<ReturnCase, String> {
}

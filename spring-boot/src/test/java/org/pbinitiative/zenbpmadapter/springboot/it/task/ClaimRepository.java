/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.task;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The claims of the task IT.
 */
public interface ClaimRepository extends JpaRepository<Claim, String> {
}

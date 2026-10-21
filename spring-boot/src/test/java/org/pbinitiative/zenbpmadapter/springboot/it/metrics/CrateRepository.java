/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.metrics;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The crates of the meters IT.
 */
public interface CrateRepository extends JpaRepository<Crate, String> {
}

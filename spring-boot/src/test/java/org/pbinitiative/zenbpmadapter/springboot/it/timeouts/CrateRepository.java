/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.timeouts;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The Spring Data repository of {@link Crate}, from which the platform builds the aggregate's
 * persistence.
 */
public interface CrateRepository extends JpaRepository<Crate, String> {
}

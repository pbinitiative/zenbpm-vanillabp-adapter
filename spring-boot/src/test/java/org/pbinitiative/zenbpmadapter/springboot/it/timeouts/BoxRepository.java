/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.timeouts;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The Spring Data repository of {@link Box}, from which the platform builds the aggregate's
 * persistence.
 */
public interface BoxRepository extends JpaRepository<Box, String> {
}

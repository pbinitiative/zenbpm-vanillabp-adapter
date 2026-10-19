/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.push;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The ratings of the push IT.
 */
public interface RatingRepository extends JpaRepository<Rating, String> {
}

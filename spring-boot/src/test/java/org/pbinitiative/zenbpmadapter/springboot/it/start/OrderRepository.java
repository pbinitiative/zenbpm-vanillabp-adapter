/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.start;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The Spring Data repository of {@link Order}, from which the platform builds the aggregate's
 * persistence.
 */
public interface OrderRepository extends JpaRepository<Order, String> {
}

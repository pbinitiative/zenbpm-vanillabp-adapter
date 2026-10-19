/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.push;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The shipments of the push IT.
 */
public interface ShipmentRepository extends JpaRepository<Shipment, String> {
}

/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.renamed;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The shipments of both versions of the application of the rename.
 */
public interface ShipmentRepository extends JpaRepository<Shipment, String> {
}

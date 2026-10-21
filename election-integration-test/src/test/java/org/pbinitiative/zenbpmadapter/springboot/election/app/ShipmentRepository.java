/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.election.app;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The shipments of the application.
 */
public interface ShipmentRepository extends JpaRepository<Shipment, String> {
}

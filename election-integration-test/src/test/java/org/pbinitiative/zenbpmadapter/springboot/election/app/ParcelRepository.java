/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.election.app;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The parcels of the application.
 */
public interface ParcelRepository extends JpaRepository<Parcel, String> {
}

/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.viewer;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The parcels of the viewer IT.
 */
public interface ParcelRepository extends JpaRepository<Parcel, String> {
}

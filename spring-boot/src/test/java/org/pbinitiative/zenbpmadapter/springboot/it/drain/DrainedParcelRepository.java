/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.drain;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The parcels of the shutdown IT.
 */
public interface DrainedParcelRepository extends JpaRepository<DrainedParcel, String> {
}

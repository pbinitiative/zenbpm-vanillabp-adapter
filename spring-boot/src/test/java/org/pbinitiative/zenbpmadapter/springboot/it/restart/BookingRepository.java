/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.restart;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The bookings of the restart IT.
 */
public interface BookingRepository extends JpaRepository<Booking, String> {
}

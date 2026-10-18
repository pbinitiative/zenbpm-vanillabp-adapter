/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The tickets of the inbound idempotency IT.
 */
public interface TicketRepository extends JpaRepository<Ticket, String> {
}

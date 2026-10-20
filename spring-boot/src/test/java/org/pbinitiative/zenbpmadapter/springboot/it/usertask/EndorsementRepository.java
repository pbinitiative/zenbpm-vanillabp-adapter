/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.usertask;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The endorsements of the user-task IT.
 */
public interface EndorsementRepository extends JpaRepository<Endorsement, String> {
}

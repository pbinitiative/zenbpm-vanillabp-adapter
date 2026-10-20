/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.versions;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The consignments of the applications of <code>ZenBpmProcessVersionIT</code> and
 * <code>ZenBpmOldProcessVersionsIT</code>.
 */
public interface ConsignmentRepository extends JpaRepository<Consignment, String> {
}

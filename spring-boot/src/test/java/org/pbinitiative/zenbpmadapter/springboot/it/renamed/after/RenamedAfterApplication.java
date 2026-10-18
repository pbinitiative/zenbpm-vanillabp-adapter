/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.renamed.after;

import org.pbinitiative.zenbpmadapter.springboot.it.renamed.Shipment;
import org.pbinitiative.zenbpmadapter.springboot.it.renamed.ShipmentRepository;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * The version of the application after the rename, which <code>ZenBpmRenamedProcessIT</code> boots
 * second: it deploys the process <code>Dispatch</code> of <code>renamed-it/after/dispatch.bpmn</code>
 * and serves it with {@link DispatchWorkflow}, which declares the old id <code>Shipping</code> beside
 * it. It scans this package only, so the version before the rename stays out of it, and shares the
 * aggregate of the parent package with that version.
 */
@SpringBootApplication
@EntityScan(basePackageClasses = Shipment.class)
@EnableJpaRepositories(basePackageClasses = ShipmentRepository.class)
public class RenamedAfterApplication {

    /**
     * Built by Spring.
     */
    public RenamedAfterApplication() {
    }

}

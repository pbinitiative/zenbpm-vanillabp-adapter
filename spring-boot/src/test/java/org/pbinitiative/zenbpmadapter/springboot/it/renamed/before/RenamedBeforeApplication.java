/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.renamed.before;

import org.pbinitiative.zenbpmadapter.springboot.it.renamed.Shipment;
import org.pbinitiative.zenbpmadapter.springboot.it.renamed.ShipmentRepository;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * The version of the application before the rename, which <code>ZenBpmRenamedProcessIT</code> boots
 * first: it deploys the process <code>Shipping</code> of <code>renamed-it/before/shipping.bpmn</code>
 * and serves it with {@link ShippingWorkflow}. It scans this package only, so the version after the
 * rename stays out of it, and shares the aggregate of the parent package with that version.
 */
@SpringBootApplication
@EntityScan(basePackageClasses = Shipment.class)
@EnableJpaRepositories(basePackageClasses = ShipmentRepository.class)
public class RenamedBeforeApplication {

    /**
     * Built by Spring.
     */
    public RenamedBeforeApplication() {
    }

}

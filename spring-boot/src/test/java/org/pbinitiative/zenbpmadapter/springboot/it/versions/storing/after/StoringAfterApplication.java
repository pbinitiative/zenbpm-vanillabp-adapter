/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.versions.storing.after;

import org.pbinitiative.zenbpmadapter.springboot.it.versions.Consignment;
import org.pbinitiative.zenbpmadapter.springboot.it.versions.ConsignmentRepository;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * The application of <code>ZenBpmOldProcessVersionsIT</code> in the version after the change: it deploys the process <code>Storing</code> of <code>versions-it/storing/v2</code>, which has the task <code>measure</code> in place of <code>weigh</code>, serves only that, and listens to the end of the workflows. It scans this package only,
 * and shares the aggregate of the versions package with the other applications.
 */
@SpringBootApplication
@EntityScan(basePackageClasses = Consignment.class)
@EnableJpaRepositories(basePackageClasses = ConsignmentRepository.class)
public class StoringAfterApplication {

    /**
     * Built by Spring.
     */
    public StoringAfterApplication() {
    }

}

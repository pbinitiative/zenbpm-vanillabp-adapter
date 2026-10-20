/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.versions.tags;

import org.pbinitiative.zenbpmadapter.springboot.it.versions.Consignment;
import org.pbinitiative.zenbpmadapter.springboot.it.versions.ConsignmentRepository;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * An application <code>ZenBpmProcessVersionIT</code> boots: it deploys the process <code>Labelling</code>, whose task methods serve by version tag. It scans this package only,
 * so the other applications of the test stay out of it, and shares the aggregate of the parent
 * package with them.
 */
@SpringBootApplication
@EntityScan(basePackageClasses = Consignment.class)
@EnableJpaRepositories(basePackageClasses = ConsignmentRepository.class)
public class TagsApplication {

    /**
     * Built by Spring.
     */
    public TagsApplication() {
    }

}

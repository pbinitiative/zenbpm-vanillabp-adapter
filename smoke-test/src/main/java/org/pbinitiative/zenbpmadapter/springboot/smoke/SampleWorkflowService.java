/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.smoke;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.WorkflowService;

/**
 * A workflow service, so the platform builds a {@link ProcessService} and wires the adapter's
 * services into it. It has no task, and there is no BPMN file, so the platform deploys nothing to
 * the adapter.
 */
@Service
@WorkflowService(workflowAggregateClass = Aggregate.class)
public class SampleWorkflowService {

    private final ProcessService<Aggregate> processService;

    /**
     * Built by Spring.
     *
     * @param processService The process service of {@link Aggregate}
     */
    public SampleWorkflowService(final ProcessService<Aggregate> processService) {
        this.processService = processService;
    }

    /**
     * The process service of {@link Aggregate} as the application gets it.
     *
     * @return The process service
     */
    public ProcessService<Aggregate> processService() {
        return processService;
    }

}

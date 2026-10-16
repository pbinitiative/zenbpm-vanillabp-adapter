/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.smoke;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.WorkflowService;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * A workflow service, so the platform builds a {@link ProcessService} and wires the adapter's
 * services into it. It has no task, and there is no BPMN file, so the platform deploys nothing to
 * the adapter. It says itself who persists {@link Aggregate}, which is no entity, and every method
 * of that persistence fails loudly, so a test which starts to persist hears about it.
 */
@Singleton
@WorkflowService(workflowAggregateClass = Aggregate.class)
public class SampleWorkflowService implements AggregatePersistenceAware<Aggregate> {

    @Inject
    ProcessService<Aggregate> processService;

    /**
     * Built by CDI.
     */
    public SampleWorkflowService() {
    }

    /**
     * The process service of {@link Aggregate} as the application gets it.
     *
     * @return The process service
     */
    public ProcessService<Aggregate> processService() {
        return processService;
    }

    @Override
    public Class<Aggregate> getAggregateClass() {
        return Aggregate.class;
    }

    @Override
    public Aggregate save(final Aggregate aggregate) {
        throw new UnsupportedOperationException("no persistence in the smoke tests");
    }

    @Override
    public Object getAggregateId(final Aggregate aggregate) {
        throw new UnsupportedOperationException("no persistence in the smoke tests");
    }

}

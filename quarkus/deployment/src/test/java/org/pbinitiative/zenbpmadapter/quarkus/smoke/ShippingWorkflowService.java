/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.smoke;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.inject.Singleton;

/**
 * The workflow service of the application {@link SmokeApplication#withAModuleWhichSubscribes()}
 * boots instead of the sample workflow service: it serves the one task of the process
 * <code>Shipping</code>, so the workflow module subscribes a job type when it starts. It owns the
 * persistence of {@link Aggregate}, which fails, because no test persists.
 */
@Singleton
@WorkflowService(workflowAggregateClass = Aggregate.class, bpmnProcess = @BpmnProcess(bpmnProcessId = "Shipping"))
public class ShippingWorkflowService implements AggregatePersistenceAware<Aggregate> {

    /**
     * Built by CDI.
     */
    public ShippingWorkflowService() {
    }

    /**
     * Serves the task <code>Ship</code>. No job reaches it: the job stream of the tests addresses a
     * port nothing listens on.
     *
     * @param aggregate The workflow aggregate
     */
    @WorkflowTask(taskDefinition = "ship")
    public void ship(final Aggregate aggregate) {
        // nothing to do: the tests are about starting and stopping
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

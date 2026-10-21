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
 * The workflow service of {@link ZenBpmDeclaredProcessFindingTest}: it serves the process
 * <code>Shipping</code>, whose model the application brings, and declares the old id
 * <code>Parcels</code> as well, as an application does after it renamed a process, so its workflow
 * module asks the engine for the models it holds under that id when its processing starts. It owns
 * the persistence of {@link Aggregate}, which fails, because no test persists.
 */
@Singleton
@WorkflowService(workflowAggregateClass = Aggregate.class, bpmnProcess = @BpmnProcess(bpmnProcessId = "Shipping"),
        secondaryBpmnProcesses = @BpmnProcess(bpmnProcessId = "Parcels"))
public class RenamedShippingWorkflowService implements AggregatePersistenceAware<Aggregate> {

    /**
     * Built by CDI.
     */
    public RenamedShippingWorkflowService() {
    }

    /**
     * Serves the task <code>Ship</code>. No job reaches it: the job stream of the test addresses a
     * port nothing listens on.
     *
     * @param aggregate The workflow aggregate
     */
    @WorkflowTask(taskDefinition = "ship")
    public void ship(final Aggregate aggregate) {
        // nothing to do: the test is about the start
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

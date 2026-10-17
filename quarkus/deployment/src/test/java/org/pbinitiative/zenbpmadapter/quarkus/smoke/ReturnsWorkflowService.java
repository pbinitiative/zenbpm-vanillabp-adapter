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
 * The workflow service of the process <code>Returns</code>, which
 * {@link SmokeApplication#withTwoProcessesSharingATaskDefinition()} boots beside
 * {@link ShippingWorkflowService}: its one task has the task definition of the task of
 * <code>Shipping</code>. It owns the persistence of {@link ReturnedParcel}, which fails, because no
 * test persists.
 */
@Singleton
@WorkflowService(workflowAggregateClass = ReturnedParcel.class, bpmnProcess = @BpmnProcess(bpmnProcessId = "Returns"))
public class ReturnsWorkflowService implements AggregatePersistenceAware<ReturnedParcel> {

    /**
     * Built by CDI.
     */
    public ReturnsWorkflowService() {
    }

    /**
     * Serves the task <code>Ship</code>, which never runs: the start ends before.
     *
     * @param aggregate The workflow aggregate
     */
    @WorkflowTask(taskDefinition = "ship")
    public void ship(final ReturnedParcel aggregate) {
        // nothing to do: the tests are about starting and stopping
    }

    @Override
    public Class<ReturnedParcel> getAggregateClass() {
        return ReturnedParcel.class;
    }

    @Override
    public ReturnedParcel save(final ReturnedParcel aggregate) {
        throw new UnsupportedOperationException("no persistence in the smoke tests");
    }

    @Override
    public Object getAggregateId(final ReturnedParcel aggregate) {
        throw new UnsupportedOperationException("no persistence in the smoke tests");
    }

}

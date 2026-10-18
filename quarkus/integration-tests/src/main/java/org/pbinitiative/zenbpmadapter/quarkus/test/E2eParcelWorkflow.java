/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.test;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskParam;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The workflow service of the process <code>TaskMatrix</code>: a service task whose input mapping
 * hands over a tariff, a gateway on what its handler computed, a send task, a business rule task
 * served by a job, and a service task inside an embedded sub-process with a gateway behind it.
 */
@ApplicationScoped
@WorkflowService(workflowAggregateClass = E2eParcel.class, bpmnProcess = @BpmnProcess(bpmnProcessId = "TaskMatrix"))
public class E2eParcelWorkflow {

    @Inject
    ProcessService<E2eParcel> processService;

    /**
     * Built by CDI.
     */
    public E2eParcelWorkflow() {
    }

    /**
     * Starts the workflow of a new parcel, inside the caller's transaction.
     *
     * @param parcel The parcel, not saved yet
     */
    public void start(final E2eParcel parcel) {
        processService.startWorkflow(parcel);
    }

    /**
     * Weighs the parcel with the tariff the model maps into the task, and makes it express.
     *
     * @param parcel The parcel
     * @param tariff What the input mapping of the task hands over
     */
    @WorkflowTask(taskDefinition = "weigh")
    public void weigh(
        final E2eParcel parcel,
        @TaskParam("tariff") final String tariff
    ) {
        parcel.weigh(tariff);
    }

    /**
     * Tells the courier, which the model does for an express parcel only.
     *
     * @param parcel The parcel
     */
    @WorkflowTask(taskDefinition = "notify-courier")
    public void notifyCourier(final E2eParcel parcel) {
        parcel.notifyCourier();
    }

    /**
     * Rates the parcel.
     *
     * @param parcel The parcel
     */
    @WorkflowTask(taskDefinition = "rate")
    public void rate(final E2eParcel parcel) {
        parcel.rate();
    }

    /**
     * Packs the parcel, inside the sub-process of the model.
     *
     * @param parcel The parcel
     */
    @WorkflowTask(taskDefinition = "pack")
    public void pack(final E2eParcel parcel) {
        parcel.pack();
    }

}

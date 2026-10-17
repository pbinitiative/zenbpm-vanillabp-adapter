/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.task;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskParam;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow of the task matrix: a service task, a send task and a business rule task served by a
 * job, and a service task inside an embedded sub-process. Each handler writes down on the parcel
 * that it ran, and two of them set what a gateway behind them decides on.
 */
@Service
@WorkflowService(workflowAggregateClass = Parcel.class, bpmnProcess = @BpmnProcess(bpmnProcessId = "TaskMatrix"))
public class ParcelWorkflow {

    /**
     * Built by Spring.
     */
    public ParcelWorkflow() {
    }

    /**
     * Weighs the parcel with the tariff the model maps into the task, and makes it express.
     *
     * @param parcel The parcel
     * @param tariff What the input mapping of the task hands over
     */
    @WorkflowTask(taskDefinition = "weigh")
    public void weigh(
        final Parcel parcel,
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
    public void notifyCourier(final Parcel parcel) {
        parcel.notifyCourier();
    }

    /**
     * Rates the parcel.
     *
     * @param parcel The parcel
     */
    @WorkflowTask(taskDefinition = "rate")
    public void rate(final Parcel parcel) {
        parcel.rate();
    }

    /**
     * Packs the parcel, inside the sub-process of the model.
     *
     * @param parcel The parcel
     */
    @WorkflowTask(taskDefinition = "pack")
    public void pack(final Parcel parcel) {
        parcel.pack();
    }

}

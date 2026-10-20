/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.viewer;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowEnded;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow service of the process <code>Shipping</code>, which the application starts, and of
 * <code>Packing</code>, which it calls on the same aggregate. The tasks do nothing but let the
 * workflow go on, except the first one of a parcel whose check is to fail, which throws, so the
 * engine keeps an incident at it. The application listens to the end of the workflow, so the
 * deployment writes an end marker into the model.
 */
@Service
@WorkflowService(workflowAggregateClass = Parcel.class, bpmnProcess = @BpmnProcess(bpmnProcessId = "Shipping"),
        secondaryBpmnProcesses = @BpmnProcess(bpmnProcessId = "Packing"))
public class ShippingWorkflow {

    /**
     * The message of the check which fails.
     */
    public static final String THE_CHECK_FAILED = "the parcel is too heavy";

    /**
     * Built by Spring.
     */
    public ShippingWorkflow() {
    }

    /**
     * The task in front of everything, which fails where the parcel says so.
     *
     * @param parcel The aggregate
     */
    @WorkflowTask(taskDefinition = "check")
    public void check(final Parcel parcel) {
        if (parcel.isCheckFails()) {
            throw new IllegalStateException(THE_CHECK_FAILED);
        }
    }

    /**
     * The task inside the embedded sub-process.
     *
     * @param parcel The aggregate
     */
    @WorkflowTask(taskDefinition = "wrap")
    public void wrap(final Parcel parcel) {
        // nothing to do: the workflow goes on
    }

    /**
     * The task of the called process.
     *
     * @param parcel The aggregate
     */
    @WorkflowTask(taskDefinition = "pack")
    public void pack(final Parcel parcel) {
        // nothing to do: the workflow goes on
    }

    /**
     * The multi-instance task, once per size of label.
     *
     * @param parcel The aggregate
     */
    @WorkflowTask(taskDefinition = "label")
    public void label(final Parcel parcel) {
        // nothing to do: the workflow goes on
    }

    /**
     * Hears the end of a workflow.
     *
     * @param parcel The aggregate
     */
    @WorkflowEnded
    public void shipped(final Parcel parcel) {
        // nothing to do: the marker in front of the end event is what this method is there for
    }

}

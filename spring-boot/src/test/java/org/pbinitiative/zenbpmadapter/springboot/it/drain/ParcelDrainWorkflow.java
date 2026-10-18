/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.drain;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow of the shutdown: a parcel is packed and labelled in parallel, and each task records
 * the node which ran it. The packing, and where the test asks so the labelling, waits where the
 * test holds it on this node.
 */
@Service
@WorkflowService(workflowAggregateClass = DrainedParcel.class, bpmnProcess = @BpmnProcess(bpmnProcessId = "Drain"))
public class ParcelDrainWorkflow {

    private final String node;

    /**
     * Built by Spring.
     *
     * @param node The name of this node, as the test boots it
     */
    public ParcelDrainWorkflow(@Value("${drain-it.node}") final String node) {
        this.node = node;
    }

    /**
     * Packs the parcel, after the test let it go on where it holds the packing on this node.
     *
     * @param parcel The parcel
     */
    @WorkflowTask(taskDefinition = "pack")
    public void pack(final DrainedParcel parcel) {
        if (PackingGate.passOn(parcel.getId(), node)) {
            throw new IllegalStateException("the packing table was taken away");
        }
        parcel.packedBy(node);
    }

    /**
     * Labels the parcel, after the test let it go on where it holds the labelling on this node.
     *
     * @param parcel The parcel
     */
    @WorkflowTask(taskDefinition = "label")
    public void label(final DrainedParcel parcel) {
        if (PackingGate.labellingPassesOn(parcel.getId(), node)) {
            throw new IllegalStateException("the label printer was taken away");
        }
        parcel.labelledBy(node);
    }

}

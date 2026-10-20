/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.lifecycle;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.MultiInstanceElement;
import io.vanillabp.spi.service.MultiInstanceIndex;
import io.vanillabp.spi.service.MultiInstanceTotal;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow service of the process <code>Picking</code>, which the application starts, and of
 * <code>Labelling</code>, which it calls on the same aggregate. <code>Picking</code> iterates over
 * the parcels three times: a parallel multi-instance sub-process picks them, a sequential
 * multi-instance task weighs them, and a parallel multi-instance call activity has
 * <code>Labelling</code> label each. Every task writes down the iteration it was told, as
 * <code>&lt;aggregate&gt;:&lt;element&gt;:&lt;index&gt;/&lt;total&gt;</code>; the parallel
 * iterations run at once, so they write beside the aggregate and not into it.
 */
@Service
@WorkflowService(workflowAggregateClass = Picking.class, bpmnProcess = @BpmnProcess(bpmnProcessId = "Picking"),
        secondaryBpmnProcesses = @BpmnProcess(bpmnProcessId = "Labelling"))
public class PickingWorkflow {

    private final List<String> picked = new CopyOnWriteArrayList<>();

    private final List<String> weighed = new CopyOnWriteArrayList<>();

    private final List<String> labelled = new CopyOnWriteArrayList<>();

    /**
     * Built by Spring.
     */
    public PickingWorkflow() {
    }

    /**
     * Picks one parcel, inside the parallel multi-instance sub-process <code>Parcels</code>.
     *
     * @param picking The aggregate
     * @param parcel The item of the iteration
     * @param index The index of the iteration
     * @param total How many iterations there are
     */
    @WorkflowTask(taskDefinition = "pickParcel")
    public void pick(
        final Picking picking,
        @MultiInstanceElement("Parcels") final String parcel,
        @MultiInstanceIndex("Parcels") final int index,
        @MultiInstanceTotal("Parcels") final int total
    ) {
        picked.add(iteration(picking, parcel, index, total));
    }

    /**
     * Weighs one parcel, as an iteration of the sequential multi-instance task
     * <code>WeighParcel</code>.
     *
     * @param picking The aggregate
     * @param parcel The item of the iteration
     * @param index The index of the iteration
     * @param total How many iterations there are
     */
    @WorkflowTask(taskDefinition = "weighParcel")
    public void weigh(
        final Picking picking,
        @MultiInstanceElement("WeighParcel") final String parcel,
        @MultiInstanceIndex("WeighParcel") final int index,
        @MultiInstanceTotal("WeighParcel") final int total
    ) {
        weighed.add(iteration(picking, parcel, index, total));
    }

    /**
     * Prints the label of one parcel, in the process <code>Labelling</code>, which an iteration of
     * the multi-instance call activity <code>LabelParcels</code> of <code>Picking</code> started.
     *
     * @param picking The aggregate
     * @param parcel The item of the caller's iteration
     * @param index The index of the caller's iteration
     * @param total How many iterations the caller has
     */
    @WorkflowTask(taskDefinition = "printLabel")
    public void printLabel(
        final Picking picking,
        @MultiInstanceElement("LabelParcels") final String parcel,
        @MultiInstanceIndex("LabelParcels") final int index,
        @MultiInstanceTotal("LabelParcels") final int total
    ) {
        labelled.add(iteration(picking, parcel, index, total));
    }

    /**
     * The iterations the tasks picking a parcel were told, in the order they ran.
     *
     * @return The iterations
     */
    public List<String> picked() {
        return List.copyOf(picked);
    }

    /**
     * The iterations the task weighing a parcel was told, in the order it ran.
     *
     * @return The iterations
     */
    public List<String> weighed() {
        return List.copyOf(weighed);
    }

    /**
     * The iterations the tasks printing a label were told, in the order they ran.
     *
     * @return The iterations
     */
    public List<String> labelled() {
        return List.copyOf(labelled);
    }

    private static String iteration(
        final Picking picking,
        final String parcel,
        final int index,
        final int total
    ) {
        return "%s:%s:%d/%d".formatted(picking.getId(), parcel, index, total);
    }

}

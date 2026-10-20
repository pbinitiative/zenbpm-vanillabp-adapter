/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.MultiInstanceElement;
import io.vanillabp.spi.service.MultiInstanceIndex;
import io.vanillabp.spi.service.MultiInstanceTotal;
import io.vanillabp.spi.service.WorkflowEnd;
import io.vanillabp.spi.service.WorkflowEnded;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The workflow service of the process <code>Packing</code>, a copy of the Spring Boot fixture of
 * multi-instance elements: a parallel multi-instance sub-process packs the items, and a sequential
 * multi-instance task weighs them. Every task writes down the iteration it was told, as
 * <code>&lt;aggregate&gt;:&lt;element&gt;:&lt;index&gt;/&lt;total&gt;</code>, beside the aggregate,
 * because the parallel iterations run at once; the end of the workflow is written into the
 * aggregate.
 */
@ApplicationScoped
@WorkflowService(workflowAggregateClass = E2ePacking.class, bpmnProcess = @BpmnProcess(bpmnProcessId = "Packing"))
public class E2ePackingWorkflow {

    @Inject
    ProcessService<E2ePacking> processService;

    private final List<String> packed = new CopyOnWriteArrayList<>();

    private final List<String> weighed = new CopyOnWriteArrayList<>();

    /**
     * Built by CDI.
     */
    public E2ePackingWorkflow() {
    }

    /**
     * Starts the workflow of a new packing, inside the caller's transaction.
     *
     * @param packing The packing, not saved yet
     */
    public void start(final E2ePacking packing) {
        processService.startWorkflow(packing);
    }

    /**
     * Packs one item, inside the parallel multi-instance sub-process <code>Items</code>.
     *
     * @param packing The aggregate
     * @param item The item of the iteration
     * @param index The index of the iteration
     * @param total How many iterations there are
     */
    @WorkflowTask(taskDefinition = "packItem")
    public void pack(
        final E2ePacking packing,
        @MultiInstanceElement("Items") final String item,
        @MultiInstanceIndex("Items") final int index,
        @MultiInstanceTotal("Items") final int total
    ) {
        packed.add(iteration(packing, item, index, total));
    }

    /**
     * Weighs one item, as an iteration of the sequential multi-instance task
     * <code>WeighItem</code>.
     *
     * @param packing The aggregate
     * @param item The item of the iteration
     * @param index The index of the iteration
     * @param total How many iterations there are
     */
    @WorkflowTask(taskDefinition = "weighItem")
    public void weigh(
        final E2ePacking packing,
        @MultiInstanceElement("WeighItem") final String item,
        @MultiInstanceIndex("WeighItem") final int index,
        @MultiInstanceTotal("WeighItem") final int total
    ) {
        weighed.add(iteration(packing, item, index, total));
    }

    /**
     * Writes down the end event the workflow ended at.
     *
     * @param packing The aggregate
     * @param end How the workflow ended
     */
    @WorkflowEnded
    public void ended(
        final E2ePacking packing,
        final WorkflowEnd end
    ) {
        packing.ended(end.endEventId());
    }

    /**
     * @return The iterations the tasks packing an item were told, in the order they ran
     */
    public List<String> packed() {
        return List.copyOf(packed);
    }

    /**
     * @return The iterations the task weighing an item was told, in the order it ran
     */
    public List<String> weighed() {
        return List.copyOf(weighed);
    }

    private static String iteration(
        final E2ePacking packing,
        final String item,
        final int index,
        final int total
    ) {
        return "%s:%s:%d/%d".formatted(packing.getId(), item, index, total);
    }

}

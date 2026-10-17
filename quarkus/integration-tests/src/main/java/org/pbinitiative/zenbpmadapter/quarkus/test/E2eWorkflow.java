/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.test;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The workflow service of the Quarkus end-to-end application. Its process <code>StartProcess</code>
 * evaluates the decision <code>rating</code> of the workflow module and then reaches the task
 * <code>Prepare</code>, whose job the adapter delivers to {@link #prepare(E2eAggregate)}; once that
 * completed, the workflow ends. So a started instance shows that the engine holds both files under
 * the ids the adapter deployed them with, and that its task reaches the application.
 */
@ApplicationScoped
@WorkflowService(workflowAggregateClass = E2eAggregate.class,
        bpmnProcess = @BpmnProcess(bpmnProcessId = "StartProcess"))
public class E2eWorkflow {

    @Inject
    ProcessService<E2eAggregate> processService;

    /**
     * The aggregates whose task <code>Prepare</code> ran, by id.
     */
    private final Set<String> prepared = ConcurrentHashMap.newKeySet();

    /**
     * Built by CDI.
     */
    public E2eWorkflow() {
    }

    /**
     * Starts the workflow of a new aggregate, inside the caller's transaction.
     *
     * @param aggregate The aggregate, not saved yet
     * @return The saved aggregate
     */
    public E2eAggregate start(final E2eAggregate aggregate) {
        return processService.startWorkflow(aggregate);
    }

    /**
     * Serves the task <code>Prepare</code>, and writes down that it ran for the aggregate.
     *
     * @param aggregate The workflow aggregate
     */
    @WorkflowTask(taskDefinition = "prepare")
    public void prepare(final E2eAggregate aggregate) {
        prepared.add(aggregate.getId());
    }

    /**
     * Whether the task <code>Prepare</code> ran for an aggregate.
     *
     * @param id The aggregate's id
     * @return <code>true</code> once it ran
     */
    public boolean hasPrepared(final String id) {
        return prepared.contains(id);
    }

}

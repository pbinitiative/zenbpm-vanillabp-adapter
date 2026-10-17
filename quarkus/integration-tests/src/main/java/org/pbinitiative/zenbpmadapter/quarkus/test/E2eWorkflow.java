/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.test;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The workflow service of the Quarkus end-to-end application. Its process <code>StartProcess</code>
 * evaluates the decision <code>rating</code> of the workflow module and then waits in the task
 * <code>Prepare</code>, so a started instance shows that the engine holds both files under the ids
 * the adapter deployed them with.
 */
@ApplicationScoped
@WorkflowService(workflowAggregateClass = E2eAggregate.class,
        bpmnProcess = @BpmnProcess(bpmnProcessId = "StartProcess"))
public class E2eWorkflow {

    @Inject
    ProcessService<E2eAggregate> processService;

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
     * Serves the task <code>Prepare</code>, so the adapter wires it and subscribes its job type. No
     * job reaches it before the adapter delivers tasks, so the instance stays in it.
     *
     * @param aggregate The workflow aggregate
     */
    @WorkflowTask(taskDefinition = "prepare")
    public void prepare(final E2eAggregate aggregate) {
        // nothing to do: the tests are about deploying and starting the workflow
    }

}

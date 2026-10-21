/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.nativeimage;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The workflow of the native-image application, the process <code>Native</code>: a task, a message
 * the workflow waits for, and a second task. Each step reaches the engine on another way out of
 * the binary: the deployment and the start over REST, the jobs of both tasks over the gRPC job
 * stream and back over REST, the message over REST.
 */
@ApplicationScoped
@WorkflowService(workflowAggregateClass = NativeImageAggregate.class,
        bpmnProcess = @BpmnProcess(bpmnProcessId = "Native"))
public class NativeImageWorkflow {

    /**
     * The status the first task writes; after it the workflow waits for the message.
     */
    public static final String SERVED = "served";

    /**
     * The status the second task writes, after the message reached the workflow.
     */
    public static final String FINISHED = "finished";

    @Inject
    ProcessService<NativeImageAggregate> processService;

    /**
     * Built by CDI.
     */
    public NativeImageWorkflow() {
    }

    /**
     * Starts the workflow of a new aggregate, inside the caller's transaction.
     *
     * @param aggregate The aggregate, not saved yet
     */
    public void start(final NativeImageAggregate aggregate) {
        processService.startWorkflow(aggregate);
    }

    /**
     * Correlates the message <code>goAhead</code>, inside the caller's transaction.
     *
     * @param aggregate The aggregate whose workflow waits for it
     */
    public void goAhead(final NativeImageAggregate aggregate) {
        processService.correlateMessage(aggregate, "goAhead");
    }

    /**
     * The task <code>Serve</code>, whose job reaches the binary over the job stream.
     *
     * @param aggregate The workflow aggregate
     */
    @WorkflowTask(taskDefinition = "serve")
    public void serve(final NativeImageAggregate aggregate) {
        aggregate.setStatus(SERVED);
    }

    /**
     * The task <code>Finish</code>, which the workflow reaches only after the message.
     *
     * @param aggregate The workflow aggregate
     */
    @WorkflowTask(taskDefinition = "finish")
    public void finish(final NativeImageAggregate aggregate) {
        aggregate.setStatus(FINISHED);
    }

}

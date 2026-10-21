/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.test;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowEnded;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The workflow service of the process <code>Dispatching</code>, a copy of the Spring Boot viewer
 * fixture: a task, an embedded sub-process, a call activity of the process <code>Boxing</code> on
 * the same aggregate and a multi-instance task, whose tasks do nothing but let the workflow go on.
 * The application listens to the end of the workflow, so the deployment writes an end marker into
 * the model.
 */
@ApplicationScoped
@WorkflowService(workflowAggregateClass = E2eDispatch.class, bpmnProcess = @BpmnProcess(bpmnProcessId = "Dispatching"),
        secondaryBpmnProcesses = @BpmnProcess(bpmnProcessId = "Boxing"))
public class E2eDispatchWorkflow {

    @Inject
    ProcessService<E2eDispatch> processService;

    /**
     * Built by CDI.
     */
    public E2eDispatchWorkflow() {
    }

    /**
     * Starts the workflow of a dispatch, inside the caller's transaction.
     *
     * @param dispatch The dispatch
     */
    public void start(final E2eDispatch dispatch) {
        processService.startWorkflow(dispatch);
    }

    /**
     * The process service, which the viewer endpoints ask.
     *
     * @return The process service
     */
    public ProcessService<E2eDispatch> processService() {
        return processService;
    }

    /**
     * The task in front of everything.
     *
     * @param dispatch The aggregate
     */
    @WorkflowTask(taskDefinition = "inspect")
    public void inspect(final E2eDispatch dispatch) {
        // nothing to do: the workflow goes on
    }

    /**
     * The task inside the embedded sub-process.
     *
     * @param dispatch The aggregate
     */
    @WorkflowTask(taskDefinition = "wrap")
    public void wrap(final E2eDispatch dispatch) {
        // nothing to do: the workflow goes on
    }

    /**
     * The task of the called process.
     *
     * @param dispatch The aggregate
     */
    @WorkflowTask(taskDefinition = "box")
    public void box(final E2eDispatch dispatch) {
        // nothing to do: the workflow goes on
    }

    /**
     * The multi-instance task, once per size of label.
     *
     * @param dispatch The aggregate
     */
    @WorkflowTask(taskDefinition = "label")
    public void label(final E2eDispatch dispatch) {
        // nothing to do: the workflow goes on
    }

    /**
     * Hears the end of a workflow.
     *
     * @param dispatch The aggregate
     */
    @WorkflowEnded
    public void dispatched(final E2eDispatch dispatch) {
        // nothing to do: the marker in front of the end event is what this method is there for
    }

}

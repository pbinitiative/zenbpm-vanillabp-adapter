/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.test;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowEnded;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The workflow service of the process <code>Storing</code>, a copy of the Spring Boot fixture of the
 * older versions after the change: the test deploys version 1 with the task <code>weigh</code> and
 * starts a workflow on it before the application starts; the application deploys version 2 with
 * the task <code>measure</code> in its place, serves only that, and listens to the end of the
 * workflows, so its model carries an end marker which version 1 lacks.
 */
@ApplicationScoped
@WorkflowService(workflowAggregateClass = E2eStock.class, bpmnProcess = @BpmnProcess(bpmnProcessId = "Storing"))
public class E2eStoringWorkflow {

    /**
     * Built by CDI.
     */
    public E2eStoringWorkflow() {
    }

    /**
     * Serves the task of the version the application deploys.
     *
     * @param stock The aggregate
     */
    @WorkflowTask(taskDefinition = "measure")
    public void measure(final E2eStock stock) {
        // nothing to do: no workflow of this version is started
    }

    /**
     * Hears the end of a workflow.
     *
     * @param stock The aggregate
     */
    @WorkflowEnded
    public void stored(final E2eStock stock) {
        // nothing to do: the marker in front of the end event is what this method is there for
    }

}

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
 * The workflow service of the process <code>Sorting</code>, a copy of the Spring Boot fixture of
 * version tags: the test deploys version 1, tagged <code>old-belt</code>, before the application
 * starts, the application deploys version 2, tagged <code>new-belt</code>, and the task
 * <code>sort</code> is served by the method of each tag.
 */
@ApplicationScoped
@WorkflowService(workflowAggregateClass = E2eSorting.class, bpmnProcess = @BpmnProcess(bpmnProcessId = "Sorting"))
public class E2eSortingWorkflow {

    @Inject
    ProcessService<E2eSorting> processService;

    /**
     * Built by CDI.
     */
    public E2eSortingWorkflow() {
    }

    /**
     * Starts the workflow of a sorting on the latest version, inside the caller's transaction.
     *
     * @param sorting The sorting
     */
    public void start(final E2eSorting sorting) {
        processService.startWorkflow(sorting);
    }

    /**
     * Serves the task of the version tagged <code>old-belt</code>.
     *
     * @param sorting The aggregate
     */
    @WorkflowTask(taskDefinition = "sort", version = "old-belt")
    public void sortOnTheOldBelt(final E2eSorting sorting) {
        sorting.servedBy("old-belt");
    }

    /**
     * Serves the task of the version tagged <code>new-belt</code>.
     *
     * @param sorting The aggregate
     */
    @WorkflowTask(taskDefinition = "sort", version = "new-belt")
    public void sortOnTheNewBelt(final E2eSorting sorting) {
        sorting.servedBy("new-belt");
    }

}

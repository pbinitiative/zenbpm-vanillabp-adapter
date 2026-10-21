/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.metrics;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskId;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow of a task left open: the labelling of a pallet is completed by the application
 * later, through the process service.
 */
@Service
@WorkflowService(workflowAggregateClass = Pallet.class, bpmnProcess = @BpmnProcess(bpmnProcessId = "Labelling"))
public class PalletWorkflow {

    // the task ids by pallet, kept outside the aggregate, which the test reads
    private static final Map<String, String> LABEL_TASKS = new ConcurrentHashMap<>();

    /**
     * Built by Spring.
     */
    public PalletWorkflow() {
    }

    /**
     * The task id of the labelling of a pallet, once its handler ran.
     *
     * @param palletId The pallet
     * @return The task id
     */
    public static Optional<String> labelTaskOf(final String palletId) {
        return Optional.ofNullable(LABEL_TASKS.get(palletId));
    }

    /**
     * Leaves the labelling open: the application prints the label and completes the task later.
     *
     * @param pallet The pallet
     * @param taskId The task, which the application completes
     */
    @WorkflowTask(taskDefinition = "label")
    public void label(
        final Pallet pallet,
        @TaskId final String taskId
    ) {
        LABEL_TASKS.put(pallet.getId(), taskId);
    }

}

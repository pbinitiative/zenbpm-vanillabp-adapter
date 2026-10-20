/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.lifecycle;

import java.util.function.Consumer;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskEvent;
import io.vanillabp.spi.service.TaskId;
import io.vanillabp.spi.service.WorkflowEnd;
import io.vanillabp.spi.service.WorkflowEnded;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow service of the process <code>Returning</code>, which the application starts: it
 * inspects the goods in an embedded sub-process, a task which stays open until the application
 * completes it, while a parallel branch waits for a refund, which ends the whole workflow at a
 * terminate end event, or for a credit, which ends its branch only. It writes down into the
 * aggregate what happened to the inspection and how the workflow ended.
 */
@Service
@WorkflowService(workflowAggregateClass = ReturnCase.class, bpmnProcess = @BpmnProcess(bpmnProcessId = "Returning"))
public class ReturnWorkflow {

    private volatile Consumer<ReturnCase> whileEnding = returnCase -> {
    };

    /**
     * Built by Spring.
     */
    public ReturnWorkflow() {
    }

    /**
     * Starts the inspection, which waits until the application completes its task, or writes down
     * that the inspection was cancelled.
     *
     * @param returnCase The aggregate
     * @param taskId The task
     * @param event What happened to it
     */
    @WorkflowTask(taskDefinition = "inspect")
    public void inspect(
        final ReturnCase returnCase,
        @TaskId final String taskId,
        @TaskEvent final TaskEvent.Event event
    ) {
        if (event == TaskEvent.Event.CANCELED) {
            returnCase.happened("inspection cancelled");
            return;
        }
        returnCase.inspecting(taskId);
    }

    /**
     * Writes down how the workflow ended, after what a test hooked in ran.
     *
     * @param returnCase The aggregate
     * @param end How the workflow ended
     */
    @WorkflowEnded
    public void ended(
        final ReturnCase returnCase,
        final WorkflowEnd end
    ) {
        whileEnding.accept(returnCase);
        returnCase.happened("%s at %s".formatted(end.kind(), end.endEventId()));
    }

    /**
     * Has something run at the start of every report of an end, which may throw.
     *
     * @param hook What runs
     */
    public void whileEnding(final Consumer<ReturnCase> hook) {
        whileEnding = hook;
    }

}

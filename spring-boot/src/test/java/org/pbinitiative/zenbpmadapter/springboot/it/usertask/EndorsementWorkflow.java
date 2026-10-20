/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.usertask;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskEvent;
import io.vanillabp.spi.service.TaskId;
import io.vanillabp.spi.service.WorkflowEnded;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow of a user task which waits for a person while a parallel user task, which something
 * else serves, may withdraw the request at a terminate end event, which takes the first user task
 * away. It counts the notifications of the first user task and the ends of the workflow.
 */
@Service
@WorkflowService(workflowAggregateClass = Endorsement.class, bpmnProcess = @BpmnProcess(bpmnProcessId = "Endorsing"))
public class EndorsementWorkflow {

    /**
     * Built by Spring.
     */
    public EndorsementWorkflow() {
    }

    /**
     * Counts that the user task was created or cancelled.
     *
     * @param endorsement The endorsement
     * @param taskId The user task
     * @param event What happened to it
     */
    @WorkflowTask(taskDefinition = "endorse")
    public void endorse(
        final Endorsement endorsement,
        @TaskId final String taskId,
        @TaskEvent final TaskEvent.Event event
    ) {
        if (event == TaskEvent.Event.CANCELED) {
            endorsement.canceled();
            return;
        }
        endorsement.created();
    }

    /**
     * Counts that the workflow ended.
     *
     * @param endorsement The endorsement
     */
    @WorkflowEnded
    public void ended(final Endorsement endorsement) {
        endorsement.ended();
    }

}

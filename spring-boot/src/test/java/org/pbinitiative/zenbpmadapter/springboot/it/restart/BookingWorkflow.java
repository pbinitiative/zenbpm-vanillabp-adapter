/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.restart;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskId;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow of the restart: a booking waits for its confirmation, which the application sends
 * later by completing the task.
 */
@Service
@WorkflowService(workflowAggregateClass = Booking.class, bpmnProcess = @BpmnProcess(bpmnProcessId = "Restart"))
public class BookingWorkflow {

    /**
     * Built by Spring.
     */
    public BookingWorkflow() {
    }

    /**
     * Asks for the confirmation, and leaves the task open until the application completes it.
     *
     * @param booking The booking
     * @param taskId The task, which the completion names
     */
    @WorkflowTask(taskDefinition = "await-confirmation")
    public void awaitConfirmation(
        final Booking booking,
        @TaskId final String taskId
    ) {
        booking.requestConfirmation(taskId);
    }

}

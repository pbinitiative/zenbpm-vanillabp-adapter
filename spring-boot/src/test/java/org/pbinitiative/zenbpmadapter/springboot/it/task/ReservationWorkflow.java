/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.task;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskEvent;
import io.vanillabp.spi.service.TaskId;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow of two tasks left open side by side: a hold, which an interrupting timer boundary
 * event takes away after two seconds, and a check, whose job the engine hands out again every
 * <code>async-task-lock-renewal</code>. Each delivery of the check lets the platform ask about the
 * hold, and the hold is reported cancelled once the engine no longer has it.
 */
@Service
@WorkflowService(workflowAggregateClass = Reservation.class,
        bpmnProcess = @BpmnProcess(bpmnProcessId = "TaskTakenAway"))
public class ReservationWorkflow {

    /**
     * Built by Spring.
     */
    public ReservationWorkflow() {
    }

    /**
     * Holds the room until the application completes the task, and writes down its cancellation.
     *
     * @param reservation The reservation
     * @param taskId The task
     * @param event What happened to the task
     */
    @WorkflowTask(taskDefinition = "hold")
    public void hold(
        final Reservation reservation,
        @TaskId final String taskId,
        @TaskEvent final TaskEvent.Event event
    ) {
        if (event == TaskEvent.Event.CANCELED) {
            reservation.holdCanceled();
            return;
        }
        reservation.hold(taskId);
    }

    /**
     * Checks the payment, which the application completes later.
     *
     * @param reservation The reservation
     * @param taskId The task
     */
    @WorkflowTask(taskDefinition = "check")
    public void check(
        final Reservation reservation,
        @TaskId final String taskId
    ) {
        reservation.check(taskId);
    }

}

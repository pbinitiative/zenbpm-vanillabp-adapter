/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.test;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskEvent;
import io.vanillabp.spi.service.TaskId;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The workflow service of the process <code>TaskTakenAway</code>: two tasks left open in parallel,
 * the hold of a room, which an interrupting timer boundary event takes away, and the check of the
 * payment, which the engine hands out again every <code>async-task-lock-renewal</code>.
 */
@ApplicationScoped
@WorkflowService(workflowAggregateClass = E2eReservation.class,
        bpmnProcess = @BpmnProcess(bpmnProcessId = "TaskTakenAway"))
public class E2eReservationWorkflow {

    @Inject
    ProcessService<E2eReservation> processService;

    /**
     * Built by CDI.
     */
    public E2eReservationWorkflow() {
    }

    /**
     * Starts the workflow of a new reservation, inside the caller's transaction.
     *
     * @param reservation The reservation, not saved yet
     */
    public void start(final E2eReservation reservation) {
        processService.startWorkflow(reservation);
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
        final E2eReservation reservation,
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
        final E2eReservation reservation,
        @TaskId final String taskId
    ) {
        reservation.check(taskId);
    }

}

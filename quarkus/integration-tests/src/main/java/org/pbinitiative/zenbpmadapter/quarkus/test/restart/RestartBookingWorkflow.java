/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.test.restart;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskId;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The workflow service of the restart: a booking either waits for the confirmation the application
 * sends later, or is packed. The packing waits while the test holds it, by a file named after the
 * booking in the directory <code>zenbpm-restart.holds</code>, because the test runs in a JVM of its
 * own and has to let the packing go on while the application stops, when no endpoint answers any
 * more; a packing whose hold was taken away throws, as one which the shutdown cut off.
 */
@ApplicationScoped
@WorkflowService(workflowAggregateClass = RestartBooking.class, bpmnProcess = @BpmnProcess(bpmnProcessId = "Restart"))
public class RestartBookingWorkflow {

    /**
     * How long a held packing waits at most, so a test which fails before it lets the handler go
     * does not keep a handler thread for the rest of the run.
     */
    private static final Duration LONGEST_HOLD = Duration.ofSeconds(60);

    @Inject
    ProcessService<RestartBooking> processService;

    @ConfigProperty(name = "zenbpm-restart.holds")
    String holds;

    private final Set<String> waiting = ConcurrentHashMap.newKeySet();

    /**
     * Built by CDI.
     */
    public RestartBookingWorkflow() {
    }

    /**
     * Starts the workflow of a new booking, inside the caller's transaction.
     *
     * @param booking The booking, not saved yet
     */
    public void start(final RestartBooking booking) {
        processService.startWorkflow(booking);
    }

    /**
     * Completes the task of a booking left open, inside the caller's transaction.
     *
     * @param booking The booking
     * @param taskId The task
     */
    public void completeTheConfirmation(
        final RestartBooking booking,
        final String taskId
    ) {
        processService.completeTask(booking, taskId);
    }

    /**
     * Whether the packing of a booking waits in its handler right now.
     *
     * @param bookingId The booking
     * @return <code>true</code> while the handler waits
     */
    public boolean isPackingHeld(final String bookingId) {
        return waiting.contains(bookingId);
    }

    /**
     * Asks for the confirmation, and leaves the task open until the application completes it.
     *
     * @param booking The booking
     * @param taskId The task, which the completion names
     */
    @WorkflowTask(taskDefinition = "await-confirmation")
    public void awaitConfirmation(
        final RestartBooking booking,
        @TaskId final String taskId
    ) {
        booking.requestConfirmation(taskId);
    }

    /**
     * Packs the booking, or, where the test holds the packing, waits until the hold is taken away
     * and then throws.
     *
     * @param booking The booking
     */
    @WorkflowTask(taskDefinition = "pack")
    public void pack(final RestartBooking booking) {
        final var hold = Path.of(holds, booking.getId());
        if (!Files.exists(hold)) {
            booking.pack();
            return;
        }
        waiting.add(booking.getId());
        try {
            final var deadline = Instant
                    .now()
                    .plus(LONGEST_HOLD);
            while (Files.exists(hold) && Instant
                    .now()
                    .isBefore(deadline)) {
                Thread.sleep(Duration.ofMillis(20));
            }
        } catch (final InterruptedException e) {
            Thread
                    .currentThread()
                    .interrupt();
        } finally {
            waiting.remove(booking.getId());
        }
        throw new IllegalStateException("the packing table was taken away");
    }

}

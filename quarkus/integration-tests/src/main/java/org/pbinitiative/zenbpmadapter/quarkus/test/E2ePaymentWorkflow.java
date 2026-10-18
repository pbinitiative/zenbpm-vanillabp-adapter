/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The workflow service of the process <code>TaskFailures</code>: a charge which throws every time,
 * on a model which gives the task two retries. The attempts are written down outside the handler's
 * transaction, which the throw rolls back.
 */
@ApplicationScoped
@WorkflowService(workflowAggregateClass = E2ePayment.class, bpmnProcess = @BpmnProcess(bpmnProcessId = "TaskFailures"))
public class E2ePaymentWorkflow {

    @Inject
    ProcessService<E2ePayment> processService;

    /**
     * When the charge of a payment was attempted, by payment.
     */
    private final Map<String, List<Instant>> attempts = new ConcurrentHashMap<>();

    /**
     * Built by CDI.
     */
    public E2ePaymentWorkflow() {
    }

    /**
     * Starts the workflow of a new payment, inside the caller's transaction.
     *
     * @param payment The payment, not saved yet
     */
    public void start(final E2ePayment payment) {
        processService.startWorkflow(payment);
    }

    /**
     * Charges the payment, which the card provider declines every time.
     *
     * @param payment The payment
     */
    @WorkflowTask(taskDefinition = "charge")
    public void charge(final E2ePayment payment) {
        attempts
                .computeIfAbsent(payment.getId(), id -> new CopyOnWriteArrayList<>())
                .add(Instant.now());
        throw new IllegalStateException("the card was declined");
    }

    /**
     * When the charge of a payment was attempted.
     *
     * @param paymentId The payment
     * @return The moments, in the order of the attempts
     */
    public List<Instant> attemptsOf(final String paymentId) {
        return List.copyOf(attempts.getOrDefault(paymentId, List.of()));
    }

}

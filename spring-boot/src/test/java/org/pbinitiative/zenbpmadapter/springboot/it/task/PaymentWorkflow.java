/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.task;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow of the handlers which throw: a charge which always fails, a reservation which fails
 * once, a notification of a task whose model names no retries, and a hold which fails once the
 * test lets it.
 */
@Service
@WorkflowService(workflowAggregateClass = Payment.class, bpmnProcess = @BpmnProcess(bpmnProcessId = "TaskFailures"))
public class PaymentWorkflow {

    private final PaymentAttempts attempts;

    /**
     * Built by Spring.
     *
     * @param attempts Where the attempts are recorded
     */
    public PaymentWorkflow(final PaymentAttempts attempts) {
        this.attempts = attempts;
    }

    /**
     * Charges the payment, which the card provider declines every time.
     *
     * @param payment The payment
     */
    @WorkflowTask(taskDefinition = "charge")
    public void charge(final Payment payment) {
        attempts.record(payment.getId());
        throw new IllegalStateException("the card was declined");
    }

    /**
     * Reserves the amount, which fails at the first attempt and succeeds at the second.
     *
     * @param payment The payment
     */
    @WorkflowTask(taskDefinition = "reserve")
    public void reserve(final Payment payment) {
        if (attempts.record(payment.getId()) == 1) {
            throw new IllegalStateException("the bank is busy");
        }
        payment.reserve();
    }

    /**
     * Notifies the customer, which fails every time.
     *
     * @param payment The payment
     */
    @WorkflowTask(taskDefinition = "notify")
    public void notifyTheCustomer(final Payment payment) {
        attempts.record(payment.getId());
        throw new IllegalStateException("the mail server is down");
    }

    /**
     * Holds the payment until the test lets it go on, then fails.
     *
     * @param payment The payment
     */
    @WorkflowTask(taskDefinition = "hold")
    public void hold(final Payment payment) {
        attempts.record(payment.getId());
        attempts.hold(payment.getId());
        throw new IllegalStateException("the hold was lifted");
    }

}

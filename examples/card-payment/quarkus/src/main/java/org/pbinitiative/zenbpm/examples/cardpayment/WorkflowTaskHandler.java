/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.cardpayment;

import org.pbinitiative.zenbpm.examples.cardpayment.model.Payment;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskException;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * What the process tells the application. Each method serves the service task whose
 * <code>zenbpm:taskDefinition type</code> is its name, and passes it on to {@link CardPayment}.
 * <p>
 * The two outcomes of a failed charge leave here in two ways. A declined card becomes a
 * {@link TaskException}, a BPMN error the boundary event of the task catches by its code, and the
 * process goes on along the path for a declined card; what the method wrote to the payment before
 * is kept. Any other exception is a failure: VanillaBP rolls the payment back, the engine spends one
 * of the task's three attempts, hands the task out again after the <code>retry-backoff</code> of
 * <code>application.yaml</code>, and raises an incident after the last one.
 */
@ApplicationScoped
@WorkflowService(workflowAggregateClass = Payment.class, bpmnProcess = @BpmnProcess(bpmnProcessId = "card_payment"))
public class WorkflowTaskHandler {

    /**
     * The code of the BPMN error for a declined card, the <code>errorCode</code> of the model's
     * error <code>Error_CardDeclined</code>.
     */
    static final String CARD_DECLINED = "card-declined";

    private final CardPayment cardPayment;

    /**
     * Built by CDI.
     *
     * @param cardPayment The business service
     */
    @Inject
    public WorkflowTaskHandler(final CardPayment cardPayment) {
        this.cardPayment = cardPayment;
    }

    /**
     * The service task "Charge the card".
     *
     * @param payment The payment
     */
    @WorkflowTask
    public void chargeCard(final Payment payment) {
        if (!cardPayment.charge(payment)) {
            throw new TaskException(CARD_DECLINED);
        }
    }

    /**
     * The service task "Confirm the order".
     *
     * @param payment The payment
     */
    @WorkflowTask
    public void confirmOrder(final Payment payment) {
        cardPayment.confirmOrder(payment);
    }

    /**
     * The service task "Ask the customer for another card".
     *
     * @param payment The payment
     */
    @WorkflowTask
    public void askForAnotherCard(final Payment payment) {
        cardPayment.askForAnotherCard(payment);
    }

}

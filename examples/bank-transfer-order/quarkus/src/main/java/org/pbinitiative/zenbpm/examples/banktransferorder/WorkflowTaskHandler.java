/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.banktransferorder;

import org.pbinitiative.zenbpm.examples.banktransferorder.model.Order;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * What the process tells the application. Each method serves the service task whose
 * <code>zenbpm:taskDefinition type</code> is its name, and passes it on to
 * {@link BankTransferOrders}. Waiting for the transfer and the two timers need no code: the engine
 * waits, and calls a task when there is something to do.
 */
@ApplicationScoped
@WorkflowService(workflowAggregateClass = Order.class,
        bpmnProcess = @BpmnProcess(bpmnProcessId = "bank_transfer_order"))
public class WorkflowTaskHandler {

    private final BankTransferOrders orders;

    /**
     * Built by CDI.
     *
     * @param orders The business service
     */
    @Inject
    public WorkflowTaskHandler(final BankTransferOrders orders) {
        this.orders = orders;
    }

    /**
     * The service task "Send the payment instructions".
     *
     * @param order The order
     */
    @WorkflowTask
    public void sendPaymentInstructions(final Order order) {
        orders.sendPaymentInstructions(order);
    }

    /**
     * The service task "Send a payment reminder", behind the timer which does not interrupt the
     * waiting.
     *
     * @param order The order
     */
    @WorkflowTask
    public void sendReminder(final Order order) {
        orders.sendReminder(order);
    }

    /**
     * The service task "Ship the order", once the transfer arrived.
     *
     * @param order The order
     */
    @WorkflowTask
    public void shipOrder(final Order order) {
        orders.shipOrder(order);
    }

    /**
     * The service task "Cancel the order", behind the timer of the deadline, which ends the waiting.
     *
     * @param order The order
     */
    @WorkflowTask
    public void cancelOrder(final Order order) {
        orders.cancelOrder(order);
    }

}

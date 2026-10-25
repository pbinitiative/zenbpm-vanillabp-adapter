/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.banktransferorder;

import org.pbinitiative.zenbpm.examples.banktransferorder.model.Order;

import io.vanillabp.spi.process.ProcessService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

/**
 * What the application tells the process: an order was placed, and its money arrived. It is the
 * only class which knows {@link ProcessService}.
 */
@ApplicationScoped
@Transactional
public class Workflow {

    /**
     * The name of the message the process waits for, the <code>name</code> of the model's message
     * <code>Message_PaymentReceived</code>.
     */
    static final String PAYMENT_RECEIVED = "PaymentReceived";

    private final ProcessService<Order> bpms;

    /**
     * Built by CDI.
     *
     * @param bpms The process service of the orders
     */
    @Inject
    public Workflow(final ProcessService<Order> bpms) {
        this.bpms = bpms;
    }

    /**
     * An order was placed. VanillaBP saves it and starts its workflow.
     *
     * @param order The order
     */
    public void orderPlaced(final Order order) {
        bpms.startWorkflow(order);
    }

    /**
     * The transfer for an order arrived, which is the message the process waits for. The adapter
     * publishes it with the order's number as the correlation key once the transaction committed,
     * and the engine hands it to the one workflow waiting under that key.
     *
     * @param order The order
     */
    public void paymentReceived(final Order order) {
        bpms.correlateMessage(order, PAYMENT_RECEIVED);
    }

}

/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.cardpayment;

import org.pbinitiative.zenbpm.examples.cardpayment.model.Payment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.vanillabp.spi.process.ProcessService;

/**
 * What the application tells the process: here only that a payment was requested. It is the only
 * class which knows {@link ProcessService}.
 */
@Component
@Transactional
public class Workflow {

    private final ProcessService<Payment> bpms;

    /**
     * Built by Spring.
     *
     * @param bpms The process service of the payments
     */
    public Workflow(final ProcessService<Payment> bpms) {
        this.bpms = bpms;
    }

    /**
     * A payment was requested. VanillaBP saves it and starts its workflow once the transaction
     * committed.
     *
     * @param payment The payment
     */
    public void paymentRequested(final Payment payment) {
        bpms.startWorkflow(payment);
    }

}

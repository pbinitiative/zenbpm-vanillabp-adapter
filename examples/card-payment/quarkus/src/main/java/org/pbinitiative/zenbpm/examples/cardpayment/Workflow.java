/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.cardpayment;

import org.pbinitiative.zenbpm.examples.cardpayment.model.Payment;

import io.vanillabp.spi.process.ProcessService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

/**
 * What the application tells the process: here only that a payment was requested. It is the only
 * class which knows {@link ProcessService}.
 */
@ApplicationScoped
@Transactional
public class Workflow {

    private final ProcessService<Payment> bpms;

    /**
     * Built by CDI.
     *
     * @param bpms The process service of the payments
     */
    @Inject
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

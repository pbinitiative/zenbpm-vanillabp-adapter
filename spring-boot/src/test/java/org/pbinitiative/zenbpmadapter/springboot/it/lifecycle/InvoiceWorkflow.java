/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.lifecycle;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskParam;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowStartedByBpms;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow service of the process <code>Invoicing</code>, which the call activity of
 * <code>Delivering</code> starts. It is a workflow of its own, on an aggregate of its own, so its
 * start builds an invoice named after the order the caller's variables carry.
 */
@Service
@WorkflowService(workflowAggregateClass = Invoice.class, bpmnProcess = @BpmnProcess(bpmnProcessId = "Invoicing"))
public class InvoiceWorkflow {

    /**
     * Built by Spring.
     */
    public InvoiceWorkflow() {
    }

    /**
     * Builds the aggregate of a workflow the call activity started.
     *
     * @param orderRef The order, a variable the called instance takes over from its caller
     * @return The aggregate
     */
    @WorkflowStartedByBpms
    public Invoice started(@TaskParam("orderRef") final String orderRef) {
        return new Invoice("invoice-%s".formatted(orderRef));
    }

    /**
     * The task behind the start.
     *
     * @param invoice The aggregate
     */
    @WorkflowTask(taskDefinition = "issue")
    public void issue(final Invoice invoice) {
        invoice.issue();
    }

}

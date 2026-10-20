/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.test;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskParam;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowStartedByBpms;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The workflow service of the process <code>Receiving</code>, which somebody starts past VanillaBP,
 * over the engine's REST API and without a business key: the application's method builds the
 * aggregate and names it after the order the start carried. The workflow then waits for the message
 * <code>unloaded</code>, which the application can only send because the start row names the
 * instance.
 */
@ApplicationScoped
@WorkflowService(workflowAggregateClass = E2eReceipt.class, bpmnProcess = @BpmnProcess(bpmnProcessId = "Receiving"))
public class E2eReceiptWorkflow {

    @Inject
    ProcessService<E2eReceipt> processService;

    /**
     * Built by CDI.
     */
    public E2eReceiptWorkflow() {
    }

    /**
     * Builds the aggregate of a workflow somebody started past VanillaBP.
     *
     * @param orderRef The order, a variable of the start
     * @return The aggregate
     */
    @WorkflowStartedByBpms
    public E2eReceipt started(@TaskParam("orderRef") final String orderRef) {
        return new E2eReceipt("receipt-%s".formatted(orderRef), orderRef);
    }

    /**
     * Correlates the message <code>unloaded</code>, inside the caller's transaction.
     *
     * @param receipt The receipt
     */
    public void unloaded(final E2eReceipt receipt) {
        processService.correlateMessage(receipt, "unloaded");
    }

}

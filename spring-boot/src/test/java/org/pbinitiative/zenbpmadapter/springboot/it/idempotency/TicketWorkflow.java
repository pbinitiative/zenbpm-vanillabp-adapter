/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.idempotency;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow of the inbound idempotency: a ticket is printed, and the print waits where the test
 * holds it.
 */
@Service
@WorkflowService(workflowAggregateClass = Ticket.class, bpmnProcess = @BpmnProcess(bpmnProcessId = "Idempotency"))
public class TicketWorkflow {

    /**
     * Built by Spring.
     */
    public TicketWorkflow() {
    }

    /**
     * Prints the ticket, after the test let the print go on where it holds it.
     *
     * @param ticket The ticket
     */
    @WorkflowTask(taskDefinition = "print")
    public void print(final Ticket ticket) {
        PrintingGate.passOn(ticket.getId());
        ticket.print();
    }

}

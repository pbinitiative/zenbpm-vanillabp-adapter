/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.lifecycle;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowEnd;
import io.vanillabp.spi.service.WorkflowEnded;
import io.vanillabp.spi.service.WorkflowService;

/**
 * The workflow service of the process <code>Settling</code>, which the application starts: it waits
 * for the claim to be closed, while a non-interrupting event sub-process, which a timer starts a
 * second after the opening, waits for an agreement. It writes down every end its workflow reports.
 */
@Service
@WorkflowService(workflowAggregateClass = Settlement.class, bpmnProcess = @BpmnProcess(bpmnProcessId = "Settling"))
public class SettlementWorkflow {

    /**
     * Built by Spring.
     */
    public SettlementWorkflow() {
    }

    /**
     * Writes down the end event the workflow ended at.
     *
     * @param settlement The aggregate
     * @param end How the workflow ended
     */
    @WorkflowEnded
    public void ended(
        final Settlement settlement,
        final WorkflowEnd end
    ) {
        settlement.ended(end.endEventId());
    }

}

/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.push;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskParam;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowStartedByBpms;

/**
 * The workflow service of the process <code>Ratings</code>, which a call activity of
 * <code>Shipments</code> starts on an aggregate of its own.
 */
@Service
@WorkflowService(workflowAggregateClass = Rating.class, bpmnProcess = @BpmnProcess(bpmnProcessId = "Ratings"))
public class RatingWorkflow {

    /**
     * Built by Spring.
     */
    public RatingWorkflow() {
    }

    /**
     * Builds the aggregate of a workflow the call activity started.
     *
     * @param shipmentId The id of the shipment, a variable the called instance takes over from its
     *            caller
     * @return The aggregate
     */
    @WorkflowStartedByBpms
    public Rating started(@TaskParam("id") final String shipmentId) {
        return new Rating("rating-%s".formatted(shipmentId));
    }

}

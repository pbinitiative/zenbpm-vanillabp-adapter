/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.lifecycle;

import java.util.UUID;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.BpmsStartTrigger;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowStartedByBpms;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow service of the process <code>Ticking</code>, which a timer starts once, two seconds
 * after it was deployed, and which waits for the message <code>proceed</code> behind its task.
 */
@Service
@WorkflowService(workflowAggregateClass = Tick.class, bpmnProcess = @BpmnProcess(bpmnProcessId = "Ticking"))
public class TickWorkflow {

    /**
     * Built by Spring.
     */
    public TickWorkflow() {
    }

    /**
     * Builds the aggregate of a workflow the timer started, under an id of the application's
     * choosing.
     *
     * @param trigger What started the workflow
     * @return The aggregate
     */
    @WorkflowStartedByBpms
    public Tick started(final BpmsStartTrigger trigger) {
        return new Tick("tick-%s".formatted(UUID.randomUUID()), trigger
                .kind()
                .name(), trigger.startEventId());
    }

    /**
     * The task behind the start.
     *
     * @param tick The aggregate
     */
    @WorkflowTask(taskDefinition = "tick")
    public void tick(final Tick tick) {
        tick.tick();
    }

}

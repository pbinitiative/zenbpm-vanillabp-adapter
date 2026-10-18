/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it.lifecycle;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskParam;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowStartedByBpms;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow service of the process <code>Delivering</code>, which the application starts, or
 * somebody past VanillaBP with the variable <code>orderRef</code>, and which calls the process
 * <code>Invoicing</code> behind its task. It writes down every order it built an aggregate for.
 */
@Service
@WorkflowService(workflowAggregateClass = Delivery.class, bpmnProcess = @BpmnProcess(bpmnProcessId = "Delivering"))
public class DeliveryWorkflow {

    private final List<String> builtFor = new CopyOnWriteArrayList<>();

    /**
     * Built by Spring.
     */
    public DeliveryWorkflow() {
    }

    /**
     * Builds the aggregate of a workflow somebody started past VanillaBP, named after its order.
     *
     * @param orderRef The order, a variable of the start
     * @return The aggregate
     */
    @WorkflowStartedByBpms
    public Delivery started(@TaskParam("orderRef") final String orderRef) {
        builtFor.add(orderRef);
        return new Delivery("delivery-%s".formatted(orderRef), orderRef);
    }

    /**
     * The task behind the start.
     *
     * @param delivery The aggregate
     */
    @WorkflowTask(taskDefinition = "deliver")
    public void deliver(final Delivery delivery) {
        delivery.deliver();
    }

    /**
     * The orders the method above built an aggregate for, in the order it was called.
     *
     * @return The orders
     */
    public List<String> builtFor() {
        return List.copyOf(builtFor);
    }

}

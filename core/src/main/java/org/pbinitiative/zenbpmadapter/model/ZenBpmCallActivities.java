/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.model;

import java.util.List;

import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring;

/**
 * Gives a process called on the same workflow aggregate the variable which names that aggregate,
 * where the engine would not hand it over by itself.
 * <p>
 * The engine starts a called instance with a copy of the variables around the call activity and
 * with the business key of the calling instance, so a call activity of the process itself hands
 * over the aggregate id without anything written into the model. Inside a multi-instance body it
 * does not: the body runs in a scope which starts with the item alone, so the called instance gets
 * the business key and nothing else. A called process reads the aggregate id where it waits for a
 * message with the key the adapter writes, so the id is carried into the body by an input mapping
 * on every multi-instance element the call activity runs in, see {@link ZenBpmInputMappings}.
 * <p>
 * A process called on an aggregate of its own must not get the caller's id, so nothing is written
 * for it. The platform is asked only about a call activity inside a multi-instance body, because
 * nowhere else does its answer change the model.
 */
public final class ZenBpmCallActivities {

    private ZenBpmCallActivities() {
    }

    /**
     * Carries the aggregate id to the processes a claimed process calls on the same aggregate from
     * inside a multi-instance body.
     *
     * @param model The model of the calling process's file
     * @param workflowModuleId The workflow module of the file
     * @param wiring The platform's wiring, which knows which processes share an aggregate
     */
    public static void carryTheAggregateId(
        final ZenBpmBpmnModel model,
        final String workflowModuleId,
        final WorkflowTaskWiring wiring
    ) {
        final var process = model.bpmnProcessId();
        final var elements = ZenBpmInputMappings.elementsById(model.document());
        String aggregateIdName = null;
        for (final var callActivity : model.callActivities()) {
            final var chain = model.multiInstanceChainOf(callActivity.elementId());
            if (chain.isEmpty() || callActivity.calledProcessId() == null || !wiring
                    .workflowsShareTheWorkflowAggregate(workflowModuleId, process, callActivity.calledProcessId())) {
                continue;
            }
            if (aggregateIdName == null) {
                aggregateIdName = wiring.resolveWorkflowAggregateIdName(workflowModuleId, process);
            }
            for (final var multiInstance : chain) {
                ZenBpmInputMappings.carry(elements.get(multiInstance.elementId()), List.of(aggregateIdName));
            }
        }
    }

}

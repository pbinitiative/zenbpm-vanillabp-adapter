/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.model;

import java.util.List;

import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartInvoker;
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
 * for it there. The platform is asked about the aggregate here only for a call activity inside a
 * multi-instance body, because nowhere else does its answer add the id.
 * <p>
 * The other way round, the engine hands the caller's business key and variables to a process
 * called on an aggregate of its own as well, whose start marker would then hand the core the
 * caller's name. So a call activity which starts such a process starts it with an empty business
 * key and an empty variable of the called process's aggregate id, where the modeller wrote no
 * <code>in</code> of their own, see decision 45 in the repository's DECISIONS.md.
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

    /**
     * Lets every call activity of a claimed process which starts a workflow of its own, on an
     * aggregate of its own, start it without the caller's name: an empty business key and an empty
     * variable of the called process's aggregate id. A call activity whose process the platform
     * does not know, one which names its process by nothing or by an expression, and one with an
     * <code>in</code> of the modeller's are left as they are.
     *
     * @param model The model of the calling process's file
     * @param workflowModuleId The workflow module of the file
     * @param wiring The platform's wiring, which knows the processes and their aggregates
     * @param starts The platform's starts, which know which processes start a workflow of their own
     */
    public static void cutTheCallersName(
        final ZenBpmBpmnModel model,
        final String workflowModuleId,
        final WorkflowTaskWiring wiring,
        final BpmsInitiatedStartInvoker starts
    ) {
        final var process = model.bpmnProcessId();
        final var elements = ZenBpmInputMappings.elementsById(model.document());
        for (final var callActivity : model.callActivities()) {
            final var called = callActivity.calledProcessId();
            if (!startsAWorkflowOfItsOwn(called, process, workflowModuleId, wiring, starts)) {
                continue;
            }
            final var element = elements.get(callActivity.elementId());
            if (ZenBpmModelReader
                    .extension(element, "in")
                    .isPresent()) {
                continue;
            }
            ZenBpmInputMappings
                    .addExtension(element, "in")
                    .setAttribute("businessKey", "");
            ZenBpmInputMappings.empty(element, wiring.resolveWorkflowAggregateIdName(workflowModuleId, called));
        }
    }

    private static boolean startsAWorkflowOfItsOwn(
        final String called,
        final String process,
        final String workflowModuleId,
        final WorkflowTaskWiring wiring,
        final BpmsInitiatedStartInvoker starts
    ) {
        if ((called == null) || !wiring.isClaimedByAWorkflowService(workflowModuleId, called)) {
            return false;
        }
        return !wiring.workflowsShareTheWorkflowAggregate(workflowModuleId, process, called) && starts
                .startsAWorkflowOfItsOwn(workflowModuleId, called);
    }

}

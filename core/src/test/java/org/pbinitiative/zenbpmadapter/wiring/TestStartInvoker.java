/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.wiring;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartContext;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartInvoker;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartResult;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartSpec;

/**
 * The platform's core as the handler of a start marker calls it: it writes down what it was told
 * and answers each start the way a test chose, by default as for a workflow which carries no name,
 * whose aggregate <code>order-1</code> the application built. Every other call fails the test.
 */
final class TestStartInvoker implements BpmsInitiatedStartInvoker {

    private final List<Start> starts = new CopyOnWriteArrayList<>();

    private volatile Function<BpmsInitiatedStartContext, BpmsInitiatedStartResult> answer = context -> built(
            "order-1");

    /**
     * Answers every start from now on the way the function does, which may throw.
     *
     * @param answer The answer
     */
    void answers(final Function<BpmsInitiatedStartContext, BpmsInitiatedStartResult> answer) {
        this.answer = answer;
    }

    /**
     * What the core was told, in the order it was told.
     *
     * @return The starts
     */
    List<Start> starts() {
        return List.copyOf(starts);
    }

    /**
     * The answer for a workflow which carried no name: the application built the aggregate, which
     * shares one value.
     *
     * @param aggregateId The id the application chose
     * @return The answer
     */
    static BpmsInitiatedStartResult built(final String aggregateId) {
        return new BpmsInitiatedStartResult(aggregateId, "id", Map.of("id", aggregateId, "status", "new"), true);
    }

    /**
     * The answer for a workflow which carried the name of an aggregate which exists.
     *
     * @param aggregateId The name it carried
     * @return The answer
     */
    static BpmsInitiatedStartResult found(final String aggregateId) {
        return new BpmsInitiatedStartResult(aggregateId, "id", Map.of("id", aggregateId, "status", "new"), false);
    }

    @Override
    public BpmsInitiatedStartResult startWorkflowByBpms(
        final String workflowModuleId,
        final String bpmnProcessId,
        final BpmsInitiatedStartContext context
    ) {
        starts.add(new Start(workflowModuleId, bpmnProcessId, context));
        return answer.apply(context);
    }

    @Override
    public void validateBpmsInitiatedStarts(
        final String workflowModuleId,
        final String bpmnProcessId,
        final Collection<BpmsInitiatedStartSpec> startEvents
    ) {
        throw new AssertionError("no start is validated while a job runs");
    }

    @Override
    public void reportStartMessages(
        final String adapterId,
        final String workflowModuleId,
        final String bpmnProcessId,
        final Collection<String> messageNames
    ) {
        throw new AssertionError("no start message is reported while a job runs");
    }

    /**
     * One start the core was told about.
     *
     * @param workflowModuleId The workflow module
     * @param bpmnProcessId The plain process id
     * @param context What the handler said about the start
     */
    record Start(
        String workflowModuleId,
        String bpmnProcessId,
        BpmsInitiatedStartContext context
    ) {
    }

}

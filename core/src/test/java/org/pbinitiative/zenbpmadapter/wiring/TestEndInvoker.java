/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.wiring;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedContext;
import io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedInvoker;

/**
 * The platform's core as the handler of an end marker calls it: it writes down every end it was
 * told and does what a test chose with it, by default nothing, as for a workflow whose
 * <code>&#64;WorkflowEnded</code> method returned. Asking whether an end is heard fails the test:
 * nothing is wired while a job runs.
 */
final class TestEndInvoker implements WorkflowEndedInvoker {

    private final List<End> ends = new CopyOnWriteArrayList<>();

    private volatile Consumer<WorkflowEndedContext> answer = context -> {
    };

    /**
     * Answers every end from now on the way the consumer does, which may throw.
     *
     * @param answer The answer
     */
    void answers(final Consumer<WorkflowEndedContext> answer) {
        this.answer = answer;
    }

    /**
     * What the core was told, in the order it was told.
     *
     * @return The ends
     */
    List<End> ends() {
        return List.copyOf(ends);
    }

    @Override
    public boolean workflowEndedHandlerExists(
        final String workflowModuleId,
        final String bpmnProcessId
    ) {
        throw new AssertionError("no end is wired while a job runs");
    }

    @Override
    public void workflowEnded(
        final String workflowModuleId,
        final String bpmnProcessId,
        final WorkflowEndedContext context
    ) {
        ends.add(new End(workflowModuleId, bpmnProcessId, context));
        answer.accept(context);
    }

    /**
     * One end the core was told.
     *
     * @param workflowModuleId The workflow module
     * @param bpmnProcessId The process as the application knows it
     * @param context What the core was told
     */
    record End(
        String workflowModuleId,
        String bpmnProcessId,
        WorkflowEndedContext context
    ) {
    }

}

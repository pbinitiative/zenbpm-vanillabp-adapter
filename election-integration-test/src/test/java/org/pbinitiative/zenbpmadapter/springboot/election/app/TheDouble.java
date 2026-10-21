/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.election.app;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.beans.factory.annotation.Value;

import io.vanillabp.bpmsdouble.DummyPhaseTwoListener;
import io.vanillabp.bpmsdouble.DummyTaskAwarenessSource;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;

/**
 * What the platform's BPMS double holds, as a test says it: the workflows of the aggregates a test
 * hands to {@link #holds(String)}, and no task. It writes down every aggregate whose change the
 * double was asked to push. It is no component, so only the application of a test which names it
 * as a source has it.
 * <p>
 * With <code>election-it.the-double-guesses: true</code> the double says that it cannot ask its
 * BPMS whether it holds a workflow.
 */
public class TheDouble implements DummyTaskAwarenessSource, DummyPhaseTwoListener {

    private final Set<String> heldWorkflows = ConcurrentHashMap.newKeySet();

    private final List<Object> pushedAggregates = new CopyOnWriteArrayList<>();

    private final boolean guesses;

    /**
     * Built by Spring.
     *
     * @param guesses Whether the double says it cannot ask its BPMS whether it holds a workflow
     */
    public TheDouble(@Value("${election-it.the-double-guesses:false}") final boolean guesses) {
        this.guesses = guesses;
    }

    /**
     * Makes the double hold the workflow of an aggregate from now on.
     *
     * @param aggregateId The id of the aggregate
     */
    public void holds(final String aggregateId) {
        heldWorkflows.add(aggregateId);
    }

    /**
     * The aggregates whose change the double was asked to push, in the order of the requests.
     *
     * @return The ids of the aggregates
     */
    public List<Object> pushedAggregates() {
        return List.copyOf(pushedAggregates);
    }

    /**
     * No task: the double's own answer, unknown, is left to it.
     */
    @Override
    public WorkflowAwareness awarenessOfTask(
        final String adapterId,
        final Object workflowAggregateId,
        final String taskId
    ) {
        return null;
    }

    /**
     * The workflows a test made the double hold.
     */
    @Override
    public WorkflowAwareness awarenessOfWorkflow(
        final String adapterId,
        final Object workflowAggregateId
    ) {
        return heldWorkflows.contains(String.valueOf(workflowAggregateId)) ? WorkflowAwareness.ACTIVE : null;
    }

    @Override
    public boolean canLocateWorkflows(final String adapterId) {
        return !guesses;
    }

    /**
     * No test starts a workflow with the double.
     */
    @Override
    public void startedWorkflowPhaseTwo(final Object workflowAggregateId) {
    }

    @Override
    public void aggregateChanged(
        final Object workflowAggregateId,
        final String taskId,
        final boolean phaseTwo
    ) {
        pushedAggregates.add(workflowAggregateId);
    }

}

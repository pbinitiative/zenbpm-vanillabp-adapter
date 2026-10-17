/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.wiring;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import io.vanillabp.integration.adapter.spi.AggregateSyncMode;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;

/**
 * The platform's core as a job handler meets it, for a test: it answers each invocation the way the
 * test says, remembers what it was handed, names the aggregate's id variable <code>id</code>, and
 * shares the values the test gives it.
 */
final class TestInvoker implements WorkflowTaskInvoker {

    private final List<Invocation> invocations = new CopyOnWriteArrayList<>();

    private final List<String> syncedReads = new CopyOnWriteArrayList<>();

    private volatile Function<TaskInvocationContext, WorkflowTaskOutcome> answer = context -> WorkflowTaskOutcome
            .completed();

    private volatile Map<String, Object> sharedValues = Map.of();

    /**
     * Answers every invocation from now on the way the function does, which may throw.
     *
     * @param answer The answer
     */
    void answers(final Function<TaskInvocationContext, WorkflowTaskOutcome> answer) {
        this.answer = answer;
    }

    /**
     * Shares these values of every aggregate from now on.
     *
     * @param sharedValues The values
     */
    void shares(final Map<String, Object> sharedValues) {
        this.sharedValues = sharedValues;
    }

    /**
     * What the core was handed, in the order it was handed.
     *
     * @return The invocations
     */
    List<Invocation> invocations() {
        return List.copyOf(invocations);
    }

    /**
     * The aggregates whose shared values were read, as <code>module/process/id</code>.
     *
     * @return The reads
     */
    List<String> syncedReads() {
        return List.copyOf(syncedReads);
    }

    @Override
    public WorkflowTaskOutcome invokeWorkflowTask(
        final String workflowModuleId,
        final String bpmnProcessId,
        final TaskInvocationContext context
    ) {
        invocations.add(new Invocation(workflowModuleId, bpmnProcessId, context));
        return answer.apply(context);
    }

    @Override
    public Map<String, Object> syncedWorkflowAggregateValues(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String workflowAggregateId,
        final AggregateSyncMode adapterDefault
    ) {
        syncedReads.add("%s/%s/%s/%s".formatted(workflowModuleId, bpmnProcessId, workflowAggregateId,
                adapterDefault));
        return sharedValues;
    }

    @Override
    public String resolveWorkflowAggregateIdName(
        final String workflowModuleId,
        final String bpmnProcessId
    ) {
        return "id";
    }

    @Override
    public boolean workflowTaskHandlerExists(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String taskDefinitionOrActivityId
    ) {
        throw new AssertionError("a job handler does not ask whether a handler exists");
    }

    @Override
    @SuppressWarnings("removal")
    public boolean workflowAggregateHasProperty(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String propertyName
    ) {
        throw new AssertionError("a job handler reads no property of an aggregate");
    }

    @Override
    @SuppressWarnings("removal")
    public Object resolveWorkflowAggregateProperty(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String workflowAggregateId,
        final String propertyName
    ) {
        throw new AssertionError("a job handler reads no property of an aggregate");
    }

    /**
     * One invocation as the core was handed it.
     *
     * @param workflowModuleId The workflow module
     * @param bpmnProcessId The process
     * @param context The invocation context
     */
    record Invocation(
        String workflowModuleId,
        String bpmnProcessId,
        TaskInvocationContext context
    ) {
    }

}

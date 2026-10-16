/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedInvoker;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartInvoker;

/**
 * The platform's two invokers of the lifecycle of a workflow, as far as wiring a file asks them, for
 * a test which builds a service of this adapter by hand: which processes are called by others and
 * so start no workflow of their own, and whose ends the application wants to hear about. It writes
 * down every call, so a test can read what the adapter reported; any other call fails the test.
 */
public final class TestStarts {

    private final Set<String> calledProcesses = new HashSet<>();

    private final Set<String> processesWhoseEndIsHeard = new HashSet<>();

    private final List<String> calls = new ArrayList<>();

    private TestStarts() {
    }

    /**
     * Invokers under which every process starts a workflow of its own and nobody listens to an end.
     *
     * @return The invokers
     */
    public static TestStarts everyProcessStartingAWorkflow() {
        return new TestStarts();
    }

    /**
     * Lets the given processes be called by other processes, so their start is no workflow of its
     * own.
     *
     * @param bpmnProcessIds The called processes
     * @return These invokers
     */
    public TestStarts called(final String... bpmnProcessIds) {
        calledProcesses.addAll(List.of(bpmnProcessIds));
        return this;
    }

    /**
     * Lets the application want to hear about the end of the workflows of the given processes.
     *
     * @param bpmnProcessIds The processes
     * @return These invokers
     */
    public TestStarts listeningToTheEndOf(final String... bpmnProcessIds) {
        processesWhoseEndIsHeard.addAll(List.of(bpmnProcessIds));
        return this;
    }

    /**
     * What the adapter asked and reported, one line per call, in the order of the calls.
     *
     * @return The calls
     */
    public List<String> calls() {
        return List.copyOf(calls);
    }

    /**
     * The invoker of starts as the adapter sees it.
     *
     * @return The invoker
     */
    public BpmsInitiatedStartInvoker startInvoker() {
        return (BpmsInitiatedStartInvoker) proxy(BpmsInitiatedStartInvoker.class);
    }

    /**
     * The invoker of ends as the adapter sees it.
     *
     * @return The invoker
     */
    public WorkflowEndedInvoker endedInvoker() {
        return (WorkflowEndedInvoker) proxy(WorkflowEndedInvoker.class);
    }

    private Object proxy(final Class<?> type) {
        return Proxy.newProxyInstance(TestStarts.class.getClassLoader(), new Class<?>[]{
                type
        }, (
            proxy,
            method,
            arguments) -> {
            calls.add("%s%s".formatted(method.getName(), Arrays.toString(arguments)));
            return switch (method.getName()) {
                case "startsAWorkflowOfItsOwn" -> !calledProcesses.contains((String) arguments[1]);
                case "workflowEndedHandlerExists" -> processesWhoseEndIsHeard.contains((String) arguments[1]);
                case "reportStartMessages", "validateBpmsInitiatedStarts" -> null;
                default -> throw new AssertionError("%s.%s is not expected to be called".formatted(type
                        .getSimpleName(), method.getName()));
            };
        });
    }

}

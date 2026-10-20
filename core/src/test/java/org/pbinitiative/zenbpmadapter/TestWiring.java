/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring;

/**
 * The platform's wiring, as far as preparing, wiring and deploying a file ask it, for a test which
 * builds a service of this adapter by hand: which processes a workflow service claims, the name of
 * the aggregate id, the names and the items the methods serving a task read, which tasks something
 * else serves, which called processes share the aggregate, which processes a workflow service
 * declares without a model, and whether the check of the tasks refuses. It writes down every call, so a test can read what the adapter asked and reported; any
 * other call fails the test.
 */
public final class TestWiring {

    private final Set<String> claimedProcesses;

    private final Map<String, List<String>> taskParameters = new HashMap<>();

    private final Map<String, List<String>> extensionTaskParameters = new HashMap<>();

    private final Map<String, List<String>> multiInstanceElementNames = new HashMap<>();

    private final Set<String> tasksServedElsewhere = new HashSet<>();

    private final Set<String> processesSharingTheAggregate = new HashSet<>();

    private final Map<String, Collection<String>> declaredWithoutAModel = new LinkedHashMap<>();

    private String taskWiringRefusal;

    private final List<String> calls = new ArrayList<>();

    private TestWiring(final Set<String> claimedProcesses) {
        this.claimedProcesses = claimedProcesses;
    }

    /**
     * A wiring under which workflow services claim the given processes and no other.
     *
     * @param bpmnProcessIds The processes claimed
     * @return The wiring
     */
    public static TestWiring claiming(final String... bpmnProcessIds) {
        return new TestWiring(Set.of(bpmnProcessIds));
    }

    /**
     * Lets the methods serving a task by the given key read the given names.
     *
     * @param taskDefinitionOrActivityId The key a method serves a task by
     * @param names The names its <code>&#64;TaskParam</code>s read
     * @return This wiring
     */
    public TestWiring withTaskParameters(
        final String taskDefinitionOrActivityId,
        final String... names
    ) {
        taskParameters.put(taskDefinitionOrActivityId, List.of(names));
        return this;
    }

    /**
     * Lets the methods of the extensions serving a task by one of its keys read the given names.
     *
     * @param lookupKey One of the keys the extensions look a task up by
     * @param names The names their <code>&#64;TaskParam</code>s read
     * @return This wiring
     */
    public TestWiring withExtensionTaskParameters(
        final String lookupKey,
        final String... names
    ) {
        extensionTaskParameters.put(lookupKey, List.of(names));
        return this;
    }

    /**
     * Lets the methods serving a task by the given key want the items of the given multi-instance
     * elements.
     *
     * @param taskDefinitionOrActivityId The key a method serves a task by
     * @param elementIds The multi-instance elements its <code>&#64;MultiInstanceElement</code>s name
     * @return This wiring
     */
    public TestWiring withMultiInstanceElementNames(
        final String taskDefinitionOrActivityId,
        final String... elementIds
    ) {
        multiInstanceElementNames.put(taskDefinitionOrActivityId, List.of(elementIds));
        return this;
    }

    /**
     * Lets the application say that something else serves the tasks of the given element ids.
     *
     * @param activityIds The element ids of the tasks
     * @return This wiring
     */
    public TestWiring servedElsewhere(final String... activityIds) {
        tasksServedElsewhere.addAll(List.of(activityIds));
        return this;
    }

    /**
     * Lets the given called processes work on the aggregate of the process which calls them.
     *
     * @param bpmnProcessIds The called processes
     * @return This wiring
     */
    public TestWiring sharingTheAggregateWith(final String... bpmnProcessIds) {
        processesSharingTheAggregate.addAll(List.of(bpmnProcessIds));
        return this;
    }

    /**
     * Lets a workflow service declare a process without bringing a model for it, such as the old id
     * of a renamed process, with what its methods serve for it.
     *
     * @param bpmnProcessId The process the workflow service declares
     * @param taskDefinitions The task definitions its methods name for the process; none where
     *            every method is wired by element id
     * @return This wiring
     */
    public TestWiring declaringWithoutAModel(
        final String bpmnProcessId,
        final String... taskDefinitions
    ) {
        declaredWithoutAModel.put(bpmnProcessId, List.of(taskDefinitions));
        return this;
    }

    /**
     * Lets the check of the tasks refuse, as the platform does where a task has no method.
     *
     * @param message The message of the refusal
     * @return This wiring
     */
    public TestWiring refusingTheTasks(final String message) {
        taskWiringRefusal = message;
        return this;
    }

    /**
     * The calls of one method, each with its arguments, in the order of the calls.
     *
     * @param methodName The name of the method
     * @return The calls, as {@link #calls()} writes them
     */
    public List<String> callsOf(final String methodName) {
        return calls
                .stream()
                .filter(call -> call.startsWith("%s[".formatted(methodName)))
                .toList();
    }

    /**
     * What the adapter asked, one line per call, in the order of the calls.
     *
     * @return The calls
     */
    public List<String> calls() {
        return List.copyOf(calls);
    }

    /**
     * The wiring as the adapter sees it.
     *
     * @return The wiring
     */
    public WorkflowTaskWiring wiring() {
        return (WorkflowTaskWiring) Proxy.newProxyInstance(TestWiring.class.getClassLoader(), new Class<?>[]{
                WorkflowTaskWiring.class
        }, (
            proxy,
            method,
            arguments) -> {
            calls.add("%s%s".formatted(method.getName(), Arrays.toString(arguments)));
            return switch (method.getName()) {
                case "isClaimedByAWorkflowService" -> claimedProcesses.contains((String) arguments[1]);
                case "resolveWorkflowAggregateIdName" -> "orderId";
                case "taskParameterNames" -> taskParameters.getOrDefault((String) arguments[2], List.of());
                case "extensionTaskParameterNames" -> extensionNamesOf((List<?>) arguments[2]);
                case "multiInstanceElementNames" -> multiInstanceElementNames.getOrDefault((String) arguments[2],
                        List.of());
                case "isImplementedExternally" -> tasksServedElsewhere.contains(((BpmnTaskSpec) arguments[3])
                        .activityId());
                case "workflowsShareTheWorkflowAggregate" -> processesSharingTheAggregate.contains(
                        (String) arguments[2]);
                case "validateTaskWiring" -> refuseTheTasksWhereAsked();
                case "taskWiringOfProcessesNobodyDeployed" -> Map.copyOf(declaredWithoutAModel);
                case "reportModelExpressions", "reportConcurrentTokenElements", "registerDeployedVersion",
                        "registerProcessVersions" ->
                    null;
                default -> throw new AssertionError("WorkflowTaskWiring.%s is not expected to be called".formatted(
                        method.getName()));
            };
        });
    }

    private Object refuseTheTasksWhereAsked() {
        if (taskWiringRefusal != null) {
            throw new IllegalStateException(taskWiringRefusal);
        }
        return null;
    }

    private List<String> extensionNamesOf(final List<?> lookupKeys) {
        final var names = new TreeSet<String>();
        lookupKeys.forEach(key -> names.addAll(extensionTaskParameters.getOrDefault((String) key, List.of())));
        return List.copyOf(names);
    }

}

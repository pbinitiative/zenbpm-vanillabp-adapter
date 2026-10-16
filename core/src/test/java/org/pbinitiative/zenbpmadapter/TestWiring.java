/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring;

/**
 * The platform's wiring, as far as preparing a file asks it, for a test which builds a service of
 * this adapter by hand: which processes a workflow service claims, the name of the aggregate id,
 * and the names the methods serving a task read. It writes down every call, so a test can read
 * what the adapter asked; any other call fails the test.
 */
public final class TestWiring {

    private final Set<String> claimedProcesses;

    private final Map<String, List<String>> taskParameters = new HashMap<>();

    private final Map<String, List<String>> extensionTaskParameters = new HashMap<>();

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
                default -> throw new AssertionError("WorkflowTaskWiring.%s is not expected to be called".formatted(
                        method.getName()));
            };
        });
    }

    private List<String> extensionNamesOf(final List<?> lookupKeys) {
        final var names = new TreeSet<String>();
        lookupKeys.forEach(key -> names.addAll(extensionTaskParameters.getOrDefault((String) key, List.of())));
        return List.copyOf(names);
    }

}

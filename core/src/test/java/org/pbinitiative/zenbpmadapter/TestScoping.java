/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;

/**
 * The platform's name-clash scoping, for a test which builds a service of this adapter by hand. It
 * answers a mode per adapter id, workflow module and workflow, builds the scoped identifiers the way
 * the platform does (the parts joined by {@link #SEPARATOR}, task definitions per process), refuses
 * <code>by-adapter</code> as the platform does for a BPMS without isolation, and writes down every
 * call which reports or validates, so a test can read what the adapter asked.
 */
public final class TestScoping implements NameClashAvoidanceSupport {

    private final NameClashAvoidance adapterMode;

    private final Map<String, NameClashAvoidance> modes = new HashMap<>();

    private final List<String> calls = new ArrayList<>();

    private TestScoping(final NameClashAvoidance adapterMode) {
        this.adapterMode = adapterMode;
    }

    /**
     * Scoping which answers one mode wherever nothing more specific is set.
     *
     * @param adapterMode The mode of the adapter id
     * @return The scoping
     */
    public static TestScoping of(final NameClashAvoidance adapterMode) {
        return new TestScoping(adapterMode);
    }

    /**
     * Sets the mode of a workflow module.
     *
     * @param workflowModuleId The workflow module
     * @param mode Its mode
     * @return This scoping
     */
    public TestScoping withModule(
        final String workflowModuleId,
        final NameClashAvoidance mode
    ) {
        modes.put(workflowModuleId, mode);
        return this;
    }

    /**
     * Sets the mode of a workflow.
     *
     * @param workflowModuleId The workflow module
     * @param bpmnProcessId The workflow's process
     * @param mode Its mode
     * @return This scoping
     */
    public TestScoping withWorkflow(
        final String workflowModuleId,
        final String bpmnProcessId,
        final NameClashAvoidance mode
    ) {
        modes.put("%s/%s".formatted(workflowModuleId, bpmnProcessId), mode);
        return this;
    }

    /**
     * What the adapter reported or had validated, one line per call, in the order of the calls.
     *
     * @return The calls
     */
    public List<String> calls() {
        return List.copyOf(calls);
    }

    @Override
    public NameClashAvoidance modeFor(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String adapterId
    ) {
        final var ofTheWorkflow = modes.get("%s/%s".formatted(workflowModuleId, bpmnProcessId));
        if (ofTheWorkflow != null) {
            return ofTheWorkflow;
        }
        return modes.getOrDefault(workflowModuleId, adapterMode);
    }

    @Override
    public String scopedProcessId(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String adapterId
    ) {
        return prefixes(workflowModuleId, bpmnProcessId, adapterId) ? String.join(SEPARATOR, workflowModuleId,
                bpmnProcessId) : bpmnProcessId;
    }

    @Override
    public String scopedIdentifier(
        final String workflowModuleId,
        final String identifier,
        final String adapterId
    ) {
        return prefixes(workflowModuleId, null, adapterId) ? String.join(SEPARATOR, workflowModuleId, identifier)
                : identifier;
    }

    @Override
    public String scopedTaskDefinition(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String taskDefinition,
        final String adapterId
    ) {
        return prefixes(workflowModuleId, bpmnProcessId, adapterId) ? String.join(SEPARATOR, workflowModuleId,
                bpmnProcessId, taskDefinition) : taskDefinition;
    }

    @Override
    public String plainProcessId(
        final String workflowModuleId,
        final String scopedBpmnProcessId,
        final String adapterId
    ) {
        return withoutPrefix(scopedBpmnProcessId, workflowModuleId + SEPARATOR);
    }

    @Override
    public String plainIdentifier(
        final String workflowModuleId,
        final String scopedIdentifier,
        final String adapterId
    ) {
        return withoutPrefix(scopedIdentifier, workflowModuleId + SEPARATOR);
    }

    @Override
    public String plainTaskDefinition(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String scopedTaskDefinition,
        final String adapterId
    ) {
        return withoutPrefix(scopedTaskDefinition, String.join(SEPARATOR, workflowModuleId, bpmnProcessId, ""));
    }

    @Override
    public void validateNoneNameClashStrategy(
        final String adapterId,
        final String byAdapterOnlyPropertyKey
    ) {
        calls.add("validateNoneNameClashStrategy(%s, %s)".formatted(adapterId, byAdapterOnlyPropertyKey));
    }

    @Override
    public void validateNativeIsolationSupported(
        final String adapterId,
        final String workflowModuleId,
        final String bpmsDescription
    ) {
        calls.add("validateNativeIsolationSupported(%s, %s, %s)".formatted(adapterId, workflowModuleId,
                bpmsDescription));
        if (modeFor(workflowModuleId, null, adapterId) == NameClashAvoidance.BY_ADAPTER) {
            throw new IllegalStateException("""
                    %s has no isolation mechanism of its own, so the name-clash-avoidance mode 'by-adapter' \
                    cannot be served by adapter '%s' - but it applies to workflow module '%s'. Choose explicitly:
                      vanillabp.adapters.%s.name-clash-avoidance: use-prefix
                      vanillabp.adapters.%s.name-clash-avoidance: none"""
                    .formatted(bpmsDescription, adapterId, workflowModuleId, adapterId, adapterId));
        }
    }

    @Override
    public void validateNoCollidingProcessIds(
        final String adapterId,
        final Collection<DeployedProcess> deployedProcesses
    ) {
        calls.add("validateNoCollidingProcessIds(%s, %s)".formatted(adapterId, deployedProcesses));
    }

    @Override
    public void reportIdentifiersTheBpmsAlreadyHolds(
        final String adapterId,
        final String workflowModuleId,
        final Collection<IdentifierHeldElsewhere> found
    ) {
        calls.add("reportIdentifiersTheBpmsAlreadyHolds(%s, %s, %s)".formatted(adapterId, workflowModuleId, found));
    }

    @Override
    public void reportIdentifiersTheModelsDeclare(
        final String adapterId,
        final String workflowModuleId,
        final Collection<ModelIdentifier> declared
    ) {
        calls.add("reportIdentifiersTheModelsDeclare(%s, %s, %s)".formatted(adapterId, workflowModuleId,
                declared));
    }

    @Override
    public void reportIdentifiersOfHeldVersion(
        final String adapterId,
        final String workflowModuleId,
        final String bpmnProcessId,
        final String processVersion,
        final Long processDefinitionKey,
        final Collection<ModelIdentifier> identifiers
    ) {
        calls.add("reportIdentifiersOfHeldVersion(%s, %s, %s)".formatted(adapterId, workflowModuleId,
                bpmnProcessId));
    }

    private boolean prefixes(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String adapterId
    ) {
        return modeFor(workflowModuleId, bpmnProcessId, adapterId) == NameClashAvoidance.USE_PREFIX;
    }

    private static String withoutPrefix(
        final String identifier,
        final String prefix
    ) {
        return identifier.startsWith(prefix) ? identifier.substring(prefix.length()) : identifier;
    }

}

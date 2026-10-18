/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the deployments of this start brought to the engine of one adapter id: per process the
 * definition the engine ended up with, which it created or already held. The deployment service
 * writes it, and the services which later name a process by its definition read it, so they do
 * not have to ask the engine for what the start learned already.
 * <p>
 * It also knows which workflow module serves the instances of a process, by the id the engine
 * knows the process under: every process a workflow service claims, and every process id a
 * workflow service declares without bringing a model for it, such as the old id of a renamed
 * process. That is what decides whose task a job is, wherever a job is met.
 * <p>
 * The client factory of the adapter id keeps it, because every service of the adapter id holds
 * that factory. It is filled while the application starts and read from any thread afterwards.
 */
public final class ZenBpmDeployedProcesses {

    private final Map<ProcessOfModule, DeployedProcess> byProcess = new ConcurrentHashMap<>();

    private final Map<Long, DeployedProcess> byKey = new ConcurrentHashMap<>();

    /**
     * The workflow module which serves the instances of a process, by the id the engine knows the
     * process under.
     */
    private final Map<String, String> servingModules = new ConcurrentHashMap<>();

    /**
     * Nothing deployed yet: the client factory of an adapter id builds one.
     */
    public ZenBpmDeployedProcesses() {
    }

    /**
     * Remembers the definition a deployment of this start ended up with, replacing what was
     * remembered for the same process of the same workflow module.
     *
     * @param deployed The definition
     */
    public void record(final DeployedProcess deployed) {
        byProcess.put(new ProcessOfModule(deployed.workflowModuleId(), deployed.bpmnProcessId()), deployed);
        byKey.put(deployed.key(), deployed);
    }

    /**
     * The ids under which the engine holds the processes a workflow module deployed in this start.
     *
     * @param workflowModuleId The workflow module
     * @return The scoped process ids, empty where the module deployed nothing
     */
    public List<String> scopedIdsOf(final String workflowModuleId) {
        return byProcess
                .values()
                .stream()
                .filter(deployed -> deployed
                        .workflowModuleId()
                        .equals(workflowModuleId))
                .map(DeployedProcess::scopedBpmnProcessId)
                .sorted()
                .toList();
    }

    /**
     * The definition key of a process a workflow module deployed in this start.
     *
     * @param workflowModuleId The workflow module
     * @param bpmnProcessId The plain id of the process
     * @return The key, empty where the module deployed no such process
     */
    public Optional<Long> keyOf(
        final String workflowModuleId,
        final String bpmnProcessId
    ) {
        return deployed(workflowModuleId, bpmnProcessId).map(DeployedProcess::key);
    }

    /**
     * The version the engine gave a process a workflow module deployed in this start.
     *
     * @param workflowModuleId The workflow module
     * @param bpmnProcessId The plain id of the process
     * @return The version, empty where the module deployed no such process
     */
    public Optional<Integer> versionOf(
        final String workflowModuleId,
        final String bpmnProcessId
    ) {
        return deployed(workflowModuleId, bpmnProcessId).map(DeployedProcess::version);
    }

    /**
     * The BPMN of a definition deployed in this start, as the engine holds it.
     *
     * @param key The definition key
     * @return The BPMN, empty where no deployment of this start ended up with that definition
     */
    public Optional<String> xmlOf(final long key) {
        return Optional
                .ofNullable(byKey.get(key))
                .map(DeployedProcess::xml);
    }

    /**
     * Whether a workflow module deployed a process under the given id in this start, the id as the
     * engine knows it.
     *
     * @param workflowModuleId The workflow module
     * @param scopedBpmnProcessId The process id as the engine knows it
     * @return <code>true</code> where it did
     */
    public boolean ownsScopedProcessId(
        final String workflowModuleId,
        final String scopedBpmnProcessId
    ) {
        return scopedIdsOf(workflowModuleId).contains(scopedBpmnProcessId);
    }

    /**
     * Remembers that a workflow module serves the instances of a process: one a workflow service
     * claims, or one it declares without bringing a model for it. A process another workflow
     * module serves already stays that module's, and the caller learns which one it is.
     *
     * @param workflowModuleId The workflow module
     * @param scopedBpmnProcessId The process id as the engine knows it
     * @return The other workflow module which serves the process already, empty where none does
     */
    public Optional<String> recordServed(
        final String workflowModuleId,
        final String scopedBpmnProcessId
    ) {
        return Optional
                .ofNullable(servingModules.putIfAbsent(scopedBpmnProcessId, workflowModuleId))
                .filter(before -> !before.equals(workflowModuleId));
    }

    /**
     * The workflow module which serves the instances of a process.
     *
     * @param scopedBpmnProcessId The process id as the engine knows it
     * @return The workflow module, empty where no workflow module of this application serves the
     *         process
     */
    public Optional<String> moduleServing(final String scopedBpmnProcessId) {
        return Optional.ofNullable(servingModules.get(scopedBpmnProcessId));
    }

    private Optional<DeployedProcess> deployed(
        final String workflowModuleId,
        final String bpmnProcessId
    ) {
        return Optional.ofNullable(byProcess.get(new ProcessOfModule(workflowModuleId, bpmnProcessId)));
    }

    /**
     * The definition one deployment of this start ended up with.
     *
     * @param workflowModuleId The workflow module which deployed it
     * @param bpmnProcessId The id of the process as the modeller wrote it
     * @param scopedBpmnProcessId The id under which the engine holds it
     * @param key The definition key
     * @param version The version the engine gave the definition
     * @param versionTag The version tag of the model, <code>null</code> where it has none
     * @param xml The BPMN as the engine holds it
     */
    public record DeployedProcess(
        String workflowModuleId,
        String bpmnProcessId,
        String scopedBpmnProcessId,
        long key,
        int version,
        String versionTag,
        String xml
    ) {
    }

    private record ProcessOfModule(
        String workflowModuleId,
        String bpmnProcessId
    ) {
    }

}

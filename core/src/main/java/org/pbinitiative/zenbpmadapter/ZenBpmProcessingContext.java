/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the deployment pipeline of this adapter collects about one workflow module, from its first
 * BPMN file to the start of its workflow processing. It names the type the platform and the
 * extensions match the pipeline by.
 * <p>
 * It holds the files it prepared, the processes of the workflow module, the messages they start
 * with and wait for, and its DMN files as they are deployed. The adapter SPI prepares every process of a file, and all processes of a file share
 * one model, so what is done to a file is done once: the context remembers the files it prepared. This
 * adapter reads one process per file, because the engine deploys no more, so today every file is
 * prepared once anyway; the guard keeps the SPI's contract should that ever change.
 * <p>
 * Each field has an accessor of its own and no Lombok getter on the class, see decision 13 in the
 * repository's DECISIONS.md.
 */
@SuppressWarnings("LombokGetterMayBeUsed")
public final class ZenBpmProcessingContext {

    private final String workflowModuleId;

    private final Set<String> preparedFiles = new HashSet<>();

    private final Set<String> processes = new LinkedHashSet<>();

    private final Map<String, byte[]> decisions = new LinkedHashMap<>();

    private final Map<String, String> startingMessages = new LinkedHashMap<>();

    private final Map<String, String> awaitedMessages = new LinkedHashMap<>();

    /**
     * The context of one workflow module, built for its first BPMN process.
     *
     * @param workflowModuleId The workflow module
     */
    public ZenBpmProcessingContext(final String workflowModuleId) {
        this.workflowModuleId = workflowModuleId;
    }

    /**
     * The workflow module this context collects about.
     *
     * @return The workflow module id
     */
    public String workflowModuleId() {
        return workflowModuleId;
    }

    /**
     * Remembers that a file is prepared, and says whether it is the first time.
     *
     * @param filename The name of the BPMN file
     * @return <code>true</code> the first time a file is named, <code>false</code> afterwards
     */
    public boolean firstPreparationOf(final String filename) {
        return preparedFiles.add(filename);
    }

    /**
     * Remembers a process of the workflow module, by its plain id.
     *
     * @param bpmnProcessId The id of the process as the modeller wrote it
     */
    public void recordProcess(final String bpmnProcessId) {
        processes.add(bpmnProcessId);
    }

    /**
     * The processes of the workflow module, by their plain ids, in the order they were prepared.
     *
     * @return The ids, each once
     */
    public List<String> processes() {
        return List.copyOf(processes);
    }

    /**
     * Keeps a DMN file of the workflow module as it is deployed.
     *
     * @param filename The name of the file
     * @param dmn The content of the file, with its decision ids scoped where the mode asks for it
     */
    public void addDecisions(
        final String filename,
        final byte[] dmn
    ) {
        decisions.put(filename, dmn);
    }

    /**
     * The DMN files of the workflow module as they are deployed, in the order they were read.
     *
     * @return The content of each file, by its name
     */
    public Map<String, byte[]> decisions() {
        return Collections.unmodifiableMap(decisions);
    }

    /**
     * The names of the messages which start a workflow of the module, each with the first start
     * event naming it as <code>process/element</code>. The preparation of each file adds its own.
     *
     * @return The messages, changed by the caller
     */
    public Map<String, String> startingMessages() {
        return startingMessages;
    }

    /**
     * The names of the messages the claimed workflows of the module wait for, each with the first
     * element waiting for it as <code>process/element</code>. The preparation of each file adds
     * its own.
     *
     * @return The messages, changed by the caller
     */
    public Map<String, String> awaitedMessages() {
        return awaitedMessages;
    }

}

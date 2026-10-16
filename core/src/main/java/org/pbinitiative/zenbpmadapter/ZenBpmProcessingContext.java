/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.pbinitiative.zenbpmadapter.model.ZenBpmBpmnModel;
import org.pbinitiative.zenbpmadapter.model.ZenBpmCorrelationKeys;

/**
 * What the deployment pipeline of this adapter collects about one workflow module, from its first
 * BPMN file to the start of its workflow processing. It names the type the platform and the
 * extensions match the pipeline by.
 * <p>
 * It holds the files it prepared, the processes of the workflow module, the messages they start
 * with and wait for, and its DMN files as they are deployed; what it hands out cannot be changed.
 * The adapter SPI prepares every process of a file, and all processes of a file share one model,
 * so what is done to a file is done once: the context remembers the files it prepared. This
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

    // the processes of the workflow module, each with the file declaring it
    private final Map<String, String> processes = new LinkedHashMap<>();

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
     * Remembers a process of the workflow module, by its plain id, with the file declaring it.
     * The engine keeps one definition per process id and deploys every file on its own, so a
     * second file declaring the same process would replace the first as a new version of it,
     * and the platform's check for colliding process ids takes one process of one module handed
     * over twice for the same process: two files declaring one process id end the deployment
     * here.
     *
     * @param bpmnProcessId The id of the process as the modeller wrote it
     * @param filename The name of the file declaring it
     * @throws IllegalStateException Where another file of the workflow module declares the same
     *             process id, naming both files
     */
    public void recordProcess(
        final String bpmnProcessId,
        final String filename
    ) {
        final var declaredBefore = processes.putIfAbsent(bpmnProcessId, filename);
        if (declaredBefore == null || declaredBefore.equals(filename)) {
            return;
        }
        throw new IllegalStateException("""
                Workflow module '%s' declares BPMN process '%s' in two files, '%s' and '%s'. ZenBPM keeps \
                one definition per process id, so the later file would replace the earlier one as a new \
                version of it. Keep the process in one file, or give one of the two processes another id."""
                .formatted(workflowModuleId, bpmnProcessId, declaredBefore, filename));
    }

    /**
     * The processes of the workflow module, by their plain ids, in the order they were prepared.
     *
     * @return The ids, each once
     */
    public List<String> processes() {
        return List.copyOf(processes.keySet());
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
     * Adds the messages of a file to those of the workflow module, and ends the deployment where a
     * message then both starts a workflow of the module and is one a claimed workflow waits for, see
     * {@link ZenBpmCorrelationKeys#refuseMessagesWhichStartAndAreAwaited}.
     *
     * @param model The model of the file
     * @param claimed Whether a workflow service claims the process of the file
     * @throws IllegalStateException Naming every message used both ways, and where
     */
    public void addMessagesOf(
        final ZenBpmBpmnModel model,
        final boolean claimed
    ) {
        ZenBpmCorrelationKeys.refuseMessagesWhichStartAndAreAwaited(workflowModuleId, startingMessages,
                awaitedMessages, model, claimed);
    }

    /**
     * The names of the messages which start a workflow of the module, each with the first start
     * event naming it as <code>process/element</code>.
     *
     * @return The messages, as far as the files were prepared
     */
    public Map<String, String> startingMessages() {
        return Collections.unmodifiableMap(startingMessages);
    }

    /**
     * The names of the messages the claimed workflows of the module wait for, each with the first
     * element waiting for it as <code>process/element</code>.
     *
     * @return The messages, as far as the files were prepared
     */
    public Map<String, String> awaitedMessages() {
        return Collections.unmodifiableMap(awaitedMessages);
    }

}

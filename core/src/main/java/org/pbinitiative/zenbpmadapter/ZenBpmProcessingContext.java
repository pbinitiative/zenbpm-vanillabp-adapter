/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter;

import java.util.HashSet;
import java.util.Set;

/**
 * What the deployment pipeline of this adapter collects about one workflow module, from its first
 * BPMN file to the start of its workflow processing. It names the type the platform and the
 * extensions match the pipeline by.
 * <p>
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

}

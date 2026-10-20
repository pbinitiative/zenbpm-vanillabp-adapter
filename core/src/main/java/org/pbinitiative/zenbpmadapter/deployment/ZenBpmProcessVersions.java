/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.deployment;

import java.util.Comparator;
import java.util.List;

import org.pbinitiative.zenbpmadapter.ZenBpmDeployedProcesses;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientFactory;
import org.pbinitiative.zenbpmadapter.client.api.ProcessDefinitionRef;

import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;
import io.vanillabp.integration.adapter.spi.version.CachingProcessVersionCatalog;
import io.vanillabp.integration.adapter.spi.version.DeployedProcessVersion;

/**
 * The versions of the processes the engine of ONE adapter id holds, against which the platform
 * matches <code>&#64;WorkflowTask(version = ...)</code> and its siblings.
 * <p>
 * A job carries the version of its definition, so a version specification made of numbers needs
 * nothing from here. A version TAG is different: which version carries which tag is read from the
 * engine's list of the definitions of a process, one request per process, which answers every
 * version with its tag, see decision 14 in the repository's DECISIONS.md. The version a start
 * deployed is recorded from the deployment's answer without any request. The engine reads the tag
 * of a model from its process's <code>versionTag</code> extension element, in any namespace
 * (<code>&lt;zenbpm:versionTag value="..." /&gt;</code>, or <code>zeebe:</code> from the Camunda
 * Modeler), and gives a tag to one version of a process only, see GAPS 32 in the repository's
 * GAPS.md. How the catalog is made and where it is registered is decision 55 in the repository's
 * DECISIONS.md.
 */
public class ZenBpmProcessVersions extends CachingProcessVersionCatalog {

    private final String adapterId;

    private final ZenBpmClientFactory factory;

    private final NameClashAvoidanceSupport scoping;

    /**
     * The catalog of one adapter id.
     *
     * @param adapterId The adapter id
     * @param factory The factory of the adapter id, whose engine holds the versions
     * @param scoping The platform's name-clash scoping, which names a process as the engine knows
     *            it
     */
    public ZenBpmProcessVersions(
        final String adapterId,
        final ZenBpmClientFactory factory,
        final NameClashAvoidanceSupport scoping
    ) {
        this.adapterId = adapterId;
        this.factory = factory;
        this.scoping = scoping;
    }

    /**
     * Records the version a deployment of this start ended up with, with its version tag, so neither
     * needs a request.
     *
     * @param deployed The definition the deployment ended up with
     */
    public void recordDeployed(final ZenBpmDeployedProcesses.DeployedProcess deployed) {
        record(deployed.workflowModuleId(), deployed.bpmnProcessId(), DeployedProcessVersion.of(String.valueOf(
                deployed.version()), deployed.versionTag()));
    }

    /**
     * Every version the engine holds under the process's id, oldest first, with its tag, read with
     * one request for the list.
     *
     * @param workflowModuleId The workflow module
     * @param bpmnProcessId The plain id of the process
     * @return The versions, empty where the engine holds none
     * @throws RuntimeException Where the engine could not be asked; the platform logs it
     */
    @Override
    protected List<DeployedProcessVersion> fetchDeployedVersions(
        final String workflowModuleId,
        final String bpmnProcessId
    ) {
        return factory
                .rest()
                .processDefinitions(scoping.scopedProcessId(workflowModuleId, bpmnProcessId, adapterId), false)
                .stream()
                .sorted(Comparator.comparingInt(ProcessDefinitionRef::version))
                .map(definition -> DeployedProcessVersion.of(String.valueOf(definition.version()), definition
                        .versionTag()))
                .toList();
    }

}

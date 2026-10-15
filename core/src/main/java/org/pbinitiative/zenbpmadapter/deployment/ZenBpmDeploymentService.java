/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.deployment;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import org.pbinitiative.zenbpmadapter.ZenBpmAdapter;
import org.pbinitiative.zenbpmadapter.ZenBpmProcessingContext;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientFactory;
import org.pbinitiative.zenbpmadapter.health.ZenBpmHealth;
import org.pbinitiative.zenbpmadapter.model.ZenBpmBpmnModel;

import io.vanillabp.integration.adapter.spi.AdapterCollaborators;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.adapter.spi.health.AdapterHealth;
import io.vanillabp.integration.spi.parts.VanillaBpParts;
import io.vanillabp.integration.spi.startup.StartupReport;

/**
 * The deployment pipeline of one ZenBPM adapter id: one instance per configured adapter id, built
 * by the platform modules.
 * <p>
 * This version of the adapter registers, validates its configuration and reports health, and
 * deploys nothing yet. Every step of the pipeline throws: where the adapter id has no clients,
 * the message of its validation, which names the keys to set; otherwise an
 * {@link UnsupportedOperationException} which says that the step is not built yet. Nothing
 * pretends to have done the work. A workflow module without BPMN files reaches none of the steps,
 * because the platform skips the adapter for it.
 * <p>
 * Each field has an accessor of its own and no Lombok getter on the class, see decision 13 in the
 * repository's DECISIONS.md.
 */
@SuppressWarnings("LombokGetterMayBeUsed")
public class ZenBpmDeploymentService implements AdapterDeploymentService<ZenBpmBpmnModel, ZenBpmProcessingContext> {

    private final String adapterId;

    private final ZenBpmClientFactory factory;

    private final AdapterCollaborators collaborators;

    private StartupReport startupReport;

    /**
     * Builds the deployment service of one adapter id. Fails first where this adapter and the
     * VanillaBP platform integration on the classpath do not belong together.
     *
     * @param adapterId The adapter id
     * @param factory The factory of the adapter id, from the registry
     * @param collaborators What the platform hands to the adapter id
     * @throws IllegalStateException Where adapter and platform do not belong together
     * @throws IllegalArgumentException Where the factory or the collaborators belong to another
     *             adapter id, a defect of the platform module's wiring
     */
    public ZenBpmDeploymentService(
        final String adapterId,
        final ZenBpmClientFactory factory,
        final AdapterCollaborators collaborators
    ) {
        VanillaBpParts.requireAdapterFitsPlatform(ZenBpmAdapter.ADAPTER_TYPE, ZenBpmDeploymentService.class);
        if (!adapterId.equals(factory.getAdapterId()) || !adapterId.equals(collaborators.adapterId())) {
            throw new IllegalArgumentException(
                    "The deployment service of ZenBPM adapter id '%s' was given the factory of adapter id '%s' and the collaborators of adapter id '%s'; the platform module wires the three of one id."
                            .formatted(adapterId, factory.getAdapterId(), collaborators.adapterId()));
        }
        this.adapterId = adapterId;
        this.factory = factory;
        this.collaborators = collaborators;
    }

    @Override
    public String getAdapterId() {
        return adapterId;
    }

    @Override
    public String getAdapterType() {
        return ZenBpmAdapter.ADAPTER_TYPE;
    }

    @Override
    public Class<ZenBpmBpmnModel> getModelType() {
        return ZenBpmBpmnModel.class;
    }

    @Override
    public Class<ZenBpmProcessingContext> getProcessContextType() {
        return ZenBpmProcessingContext.class;
    }

    /**
     * Asks the engine whether it is ready, see {@link ZenBpmHealth}.
     *
     * @return What was found, never <code>null</code>
     */
    @Override
    public AdapterHealth checkHealth() {
        return ZenBpmHealth.check(factory);
    }

    /**
     * What the platform hands to this adapter id.
     *
     * @return The collaborators
     */
    public AdapterCollaborators collaborators() {
        return collaborators;
    }

    /**
     * Where this adapter id reports what it finds while the application starts.
     *
     * @return The platform's startup report, or <code>null</code> where the platform gave none
     */
    public StartupReport getStartupReport() {
        return startupReport;
    }

    /**
     * Where this adapter id reports what it finds while the application starts: the block the
     * platform writes at the end of a start.
     *
     * @param startupReport The platform's startup report
     */
    public void setStartupReport(final StartupReport startupReport) {
        this.startupReport = startupReport;
    }

    @Override
    public List<Map.Entry<String, ZenBpmBpmnModel>> readBpmn(
        final String workflowModuleId,
        final String filename,
        final InputStream bpmn,
        final boolean isVanillaBpBpmn
    ) {
        throw notBuiltYet("reading BPMN file '%s' of workflow module '%s'".formatted(filename, workflowModuleId));
    }

    @Override
    public ZenBpmProcessingContext prepareBpmn(
        final String workflowModuleId,
        final ZenBpmProcessingContext existingContext,
        final String filename,
        final String bpmnProcessId,
        final ZenBpmBpmnModel model
    ) {
        throw notBuiltYet("preparing BPMN process '%s' of workflow module '%s'".formatted(bpmnProcessId,
                workflowModuleId));
    }

    @Override
    public void wireBpmn(
        final String workflowModuleId,
        final String filename,
        final String bpmnProcessId,
        final ZenBpmBpmnModel model,
        final ZenBpmProcessingContext context
    ) {
        throw notBuiltYet("wiring BPMN process '%s' of workflow module '%s'".formatted(bpmnProcessId,
                workflowModuleId));
    }

    @Override
    public void deployResources(
        final String workflowModuleId,
        final ZenBpmProcessingContext context
    ) {
        throw notBuiltYet("deploying workflow module '%s'".formatted(workflowModuleId));
    }

    @Override
    public void startWorkflowProcessing(
        final String workflowModuleId,
        final ZenBpmProcessingContext context
    ) {
        throw notBuiltYet("starting the workflow processing of workflow module '%s'".formatted(workflowModuleId));
    }

    // an adapter id without clients answers with the message of its validation, which names the
    // keys to set, before it says what this version cannot do
    private RuntimeException notBuiltYet(final String what) {
        final var unconfigured = factory.unconfiguredMessage();
        if (unconfigured.isPresent()) {
            return new IllegalStateException(unconfigured.get());
        }
        return new UnsupportedOperationException(
                "ZenBPM adapter '%s': %s is implemented in a later story".formatted(adapterId, what));
    }

}

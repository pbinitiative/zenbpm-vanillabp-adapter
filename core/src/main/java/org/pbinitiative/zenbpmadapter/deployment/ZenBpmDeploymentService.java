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
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientRegistry;
import org.pbinitiative.zenbpmadapter.client.ZenBpmInstanceIdentity;
import org.pbinitiative.zenbpmadapter.health.ZenBpmHealth;
import org.pbinitiative.zenbpmadapter.model.ZenBpmBpmnModel;
import org.pbinitiative.zenbpmadapter.model.ZenBpmModelReader;
import org.pbinitiative.zenbpmadapter.model.ZenBpmUnsupportedConstructs;

import io.vanillabp.integration.adapter.spi.AdapterCollaborators;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.adapter.spi.BpmnParseException;
import io.vanillabp.integration.adapter.spi.health.AdapterHealth;
import io.vanillabp.integration.spi.parts.VanillaBpParts;
import io.vanillabp.integration.spi.startup.StartupReport;

/**
 * The deployment pipeline of one ZenBPM adapter id: one instance per configured adapter id, built
 * by the platform modules.
 * <p>
 * This version of the adapter registers, validates its configuration, reports health, reads BPMN
 * files and refuses what ZenBPM cannot run, and deploys nothing yet. Reading and preparing need no
 * engine, so an adapter id without clients reads and prepares its files as well, like the Camunda
 * 8 adapter, and the checks done meanwhile speak before the configuration does. Every later step
 * of the pipeline throws: where the adapter id has no clients, the message of its validation,
 * which names the keys to set, so a workflow module with BPMN files ends the start of an
 * application which configured no engine for it; otherwise an
 * {@link UnsupportedOperationException} which says that the step is not built yet. Nothing
 * pretends to have done the work. A workflow module without BPMN files reaches none of the
 * steps, because the platform skips the adapter for it. It also answers what the platform asks
 * before the deployment: whether two adapter ids of this type are one instance.
 * <p>
 * Each field has an accessor of its own and no Lombok getter on the class, see decision 13 in the
 * repository's DECISIONS.md.
 */
@SuppressWarnings("LombokGetterMayBeUsed")
public class ZenBpmDeploymentService implements AdapterDeploymentService<ZenBpmBpmnModel, ZenBpmProcessingContext> {

    private final String adapterId;

    private final ZenBpmClientRegistry registry;

    private final ZenBpmClientFactory factory;

    private final AdapterCollaborators collaborators;

    private StartupReport startupReport;

    /**
     * Builds the deployment service of one adapter id. Fails first where this adapter and the
     * VanillaBP platform integration on the classpath do not belong together.
     *
     * @param adapterId The adapter id
     * @param registry The factories of every adapter id of this type: the one of this id, and the
     *            others, which the check for distinct instances compares
     * @param collaborators What the platform hands to the adapter id
     * @throws IllegalStateException Where adapter and platform do not belong together, or where the
     *             registry has no factory of the adapter id
     * @throws IllegalArgumentException Where the collaborators belong to another adapter id, a
     *             defect of the platform module's wiring
     */
    public ZenBpmDeploymentService(
        final String adapterId,
        final ZenBpmClientRegistry registry,
        final AdapterCollaborators collaborators
    ) {
        VanillaBpParts.requireAdapterFitsPlatform(ZenBpmAdapter.ADAPTER_TYPE, ZenBpmDeploymentService.class);
        if (!adapterId.equals(collaborators.adapterId())) {
            throw new IllegalArgumentException(
                    "The deployment service of ZenBPM adapter id '%s' was given the collaborators of adapter id '%s'; the platform module wires the collaborators of one id."
                            .formatted(adapterId, collaborators.adapterId()));
        }
        this.adapterId = adapterId;
        this.registry = registry;
        this.factory = registry.getFactory(adapterId);
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
     * Ends the start where two adapter ids of this type are one instance: the same
     * <code>rest-address</code> with the same name-clash-avoidance mode, see
     * {@link ZenBpmInstanceIdentity}. The platform asks the
     * first deployment service of the type, before anything is deployed.
     *
     * @param adapterIdsOfThisType The adapter ids of type <code>zenbpm</code>, in the order of
     *            their priority where all of them are prioritized
     * @throws IllegalStateException Where two of them are one instance
     */
    @Override
    public void validateDistinctAdapterInstances(final List<String> adapterIdsOfThisType) {
        ZenBpmInstanceIdentity.validateDistinct(adapterIdsOfThisType, registry, collaborators.scoping());
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

    /**
     * Reads one BPMN file, see {@link ZenBpmModelReader}. It needs no engine, so it reads for an
     * adapter id without clients as well.
     *
     * @param workflowModuleId The workflow module the file belongs to
     * @param filename The name of the file
     * @param bpmn The content of the file, which is not closed
     * @param isVanillaBpBpmn Not read: every file is read alike
     * @return The executable process of the file under its id, or nothing where it has none
     * @throws BpmnParseException Where the file cannot be deployed as it is, naming file and
     *             workflow module
     */
    @Override
    public List<Map.Entry<String, ZenBpmBpmnModel>> readBpmn(
        final String workflowModuleId,
        final String filename,
        final InputStream bpmn,
        final boolean isVanillaBpBpmn
    ) {
        return ZenBpmModelReader
                .read(workflowModuleId, filename, bpmn)
                .stream()
                .map(model -> Map.entry(model.bpmnProcessId(), model))
                .toList();
    }

    /**
     * Prepares one process for the engine. This version refuses a file which uses what ZenBPM cannot
     * run, see {@link ZenBpmUnsupportedConstructs}, once per file and before anything is sent to
     * the engine; it rewrites nothing yet. It needs no engine, so it prepares for an adapter id
     * without clients as well.
     *
     * @param workflowModuleId The workflow module of the process
     * @param existingContext The context of the module's earlier processes, <code>null</code> for
     *            its first
     * @param filename The name of the process's file
     * @param bpmnProcessId The id of the process
     * @param model The model of the process's file
     * @return The context of the workflow module, never <code>null</code>
     * @throws IllegalStateException Where the file uses what ZenBPM cannot run, naming every element
     */
    @Override
    public ZenBpmProcessingContext prepareBpmn(
        final String workflowModuleId,
        final ZenBpmProcessingContext existingContext,
        final String filename,
        final String bpmnProcessId,
        final ZenBpmBpmnModel model
    ) {
        final var context = existingContext != null ? existingContext
                : new ZenBpmProcessingContext(workflowModuleId);
        if (context.firstPreparationOf(filename)) {
            ZenBpmUnsupportedConstructs.check(workflowModuleId, model, collaborators
                    .workflowTaskWiring()
                    .isClaimedByAWorkflowService(workflowModuleId, bpmnProcessId));
        }
        return context;
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

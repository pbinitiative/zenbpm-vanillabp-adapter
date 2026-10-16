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
import org.pbinitiative.zenbpmadapter.model.ZenBpmScoping;
import org.pbinitiative.zenbpmadapter.model.ZenBpmUnsupportedConstructs;

import io.vanillabp.integration.adapter.spi.AdapterCollaborators;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.adapter.spi.BpmnParseException;
import io.vanillabp.integration.adapter.spi.DmnDecisionIds;
import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport.DeployedProcess;
import io.vanillabp.integration.adapter.spi.health.AdapterHealth;
import io.vanillabp.integration.spi.parts.VanillaBpParts;
import io.vanillabp.integration.spi.startup.StartupReport;
import lombok.extern.slf4j.Slf4j;

/**
 * The deployment pipeline of one ZenBPM adapter id: one instance per configured adapter id, built
 * by the platform modules.
 * <p>
 * This version of the adapter registers, validates its configuration, reports health, reads BPMN
 * files, refuses what ZenBPM cannot run and the name-clash-avoidance mode <code>by-adapter</code>,
 * scopes the identifiers of the files and of the DMN files under <code>use-prefix</code>, checks
 * that no two processes reach the engine under one id, and deploys nothing yet. Reading, preparing
 * and that check need no engine, so an adapter id without clients does them as well, like the
 * Camunda 8 adapter, and the checks done meanwhile speak before the configuration does. Every later
 * step of the pipeline throws: where the adapter id has no clients, the message of its validation,
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
@Slf4j
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
     * The mode which applies where the application configures none: <code>by-adapter</code>, the
     * default of the adapter SPI, which ZenBPM cannot serve and which {@link #prepareBpmn} refuses.
     * So an application chooses <code>use-prefix</code> or <code>none</code>, one line per adapter
     * id, see decision 24 in the repository's DECISIONS.md.
     *
     * @return {@link NameClashAvoidance#BY_ADAPTER}
     */
    @Override
    public NameClashAvoidance defaultNameClashAvoidance() {
        return NameClashAvoidance.BY_ADAPTER;
    }

    /**
     * Warns that a workflow module reaches the engine with plain identifiers, naming what ZenBPM
     * offers instead: prefixing, or an engine per workflow module. Where
     * <code>accept-unscoped-identifiers</code> says that the identifiers are unique, a DEBUG line
     * names the key which said so, because the warning asks for a decision which is on record then.
     *
     * @param workflowModuleId The workflow module whose identifiers are not scoped
     * @param fromDefault Not read: the default of this adapter is <code>by-adapter</code>, so
     *            <code>none</code> is always configured
     */
    @Override
    public void warnAboutUnscopedIdentifiers(
        final String workflowModuleId,
        final boolean fromDefault
    ) {
        final var accepted = factory
                .configuration()
                .keyAcceptingUnscopedIdentifiers(adapterId, workflowModuleId);
        if (accepted.isPresent()) {
            log.debug("ZenBPM[{}]: workflow module '{}' is deployed with name-clash-avoidance 'none', accepted by '{}'",
                    adapterId, workflowModuleId, accepted.get());
            return;
        }
        log.warn(
                """
                        Workflow module '{}' is deployed to ZenBPM (adapter '{}') with name-clash-avoidance 'none'. Its \
                        identifiers reach the engine as they are - BPMN process ids, message names, error codes, \
                        decision ids and job types - so a second workflow module using the same identifier addresses \
                        the very same processes and jobs, and neither VanillaBP nor the engine can tell. Keep 'none' \
                        only as long as your identifiers are unique across ALL workflow modules of this application. \
                        ZenBPM has no tenants, so 'by-adapter' is no choice; choose instead:
                          vanillabp.adapters.{}.name-clash-avoidance: use-prefix   # VanillaBP prefixes the identifiers with the workflow module id
                        or give the workflow module an engine of its own: an adapter id of its own with its own \
                        vanillabp.adapters.<id>.rest-address. The mode may be set per workflow module as well \
                        (vanillabp.workflow-modules.{}.adapters.{}.name-clash-avoidance). It is not a runtime switch - \
                        changing it once workflows are running is a BPMS migration. If the identifiers ARE unique, \
                        say so once and this warning is gone:
                          vanillabp.adapters.{}.accept-unscoped-identifiers: true
                        or for this workflow module only:
                          vanillabp.workflow-modules.{}.adapters.{}.accept-unscoped-identifiers: true""",
                workflowModuleId,
                adapterId, adapterId, workflowModuleId, adapterId, adapterId, workflowModuleId, adapterId);
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
     * Prepares one process for the engine, without asking the engine anything, so an adapter id
     * without clients prepares as well. Once per file, it refuses a file which uses what ZenBPM
     * cannot run, see {@link ZenBpmUnsupportedConstructs}. Then it refuses the name-clash-avoidance
     * mode <code>by-adapter</code>, for the workflow module and for the workflow, because ZenBPM
     * has no isolation of its own (see decision 24 in the repository's DECISIONS.md). Once per
     * file again, it scopes the identifiers of the file, see {@link ZenBpmScoping}.
     *
     * @param workflowModuleId The workflow module of the process
     * @param existingContext The context of the module's earlier processes, <code>null</code> for
     *            its first
     * @param filename The name of the process's file
     * @param bpmnProcessId The id of the process
     * @param model The model of the process's file
     * @return The context of the workflow module, never <code>null</code>
     * @throws IllegalStateException Where the file uses what ZenBPM cannot run, naming every
     *             element, or where <code>by-adapter</code> applies to the module or the workflow
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
        final var firstPreparation = context.firstPreparationOf(filename);
        if (firstPreparation) {
            ZenBpmUnsupportedConstructs.check(workflowModuleId, model, collaborators
                    .workflowTaskWiring()
                    .isClaimedByAWorkflowService(workflowModuleId, bpmnProcessId));
        }
        final var scoping = collaborators.scoping();
        scoping.validateNativeIsolationSupported(adapterId, workflowModuleId, ZenBpmAdapter.BPMS_NAME);
        refuseByAdapterForTheWorkflow(workflowModuleId, bpmnProcessId);
        if (firstPreparation) {
            ZenBpmScoping.apply(model.document(), workflowModuleId, bpmnProcessId, scoping, adapterId);
        }
        context.recordProcess(bpmnProcessId);
        return context;
    }

    /**
     * Keeps one DMN file of the workflow module for its deployment. Under <code>use-prefix</code>
     * the ids of the decisions it declares are scoped by the workflow module, as the
     * <code>calledDecision</code> of a business rule task is (see {@link ZenBpmScoping}); otherwise
     * the file is kept as it was read. It needs no engine.
     *
     * @param workflowModuleId The workflow module of the file
     * @param existingContext The context the module's BPMN files produced
     * @param filename The name of the file
     * @param dmn The content of the file, which is not closed
     * @return The context, with the file added
     * @throws IllegalStateException Where the decision ids are to be scoped and the file cannot be
     *             read as DMN, naming file and workflow module
     */
    @Override
    public ZenBpmProcessingContext readDmn(
        final String workflowModuleId,
        final ZenBpmProcessingContext existingContext,
        final String filename,
        final InputStream dmn
    ) {
        final var file = DmnDecisionIds.bytesOf(dmn);
        final var scoping = collaborators.scoping();
        if (scoping.modeFor(workflowModuleId, null, adapterId) != NameClashAvoidance.USE_PREFIX) {
            existingContext.addDecisions(filename, file);
            return existingContext;
        }
        try {
            existingContext.addDecisions(filename, DmnDecisionIds.rewrite(file, decisionId -> scoping
                    .scopedIdentifier(workflowModuleId, decisionId, adapterId)));
        } catch (final IllegalArgumentException e) {
            throw new IllegalStateException("DMN file '%s' of workflow module '%s' cannot be read: %s"
                    .formatted(filename, workflowModuleId, e.getMessage()), e);
        }
        return existingContext;
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

    /**
     * Ends the start where two processes of this application would reach the engine under one id,
     * before anything is sent to it. It needs no engine. The deployment itself is not built yet.
     *
     * @param workflowModuleId The workflow module
     * @param context The context its files were prepared into
     */
    @Override
    public void deployResources(
        final String workflowModuleId,
        final ZenBpmProcessingContext context
    ) {
        collaborators
                .scoping()
                .validateNoCollidingProcessIds(adapterId, context
                        .processes()
                        .stream()
                        .map(bpmnProcessId -> new DeployedProcess(workflowModuleId, bpmnProcessId))
                        .toList());
        throw notBuiltYet("deploying workflow module '%s'".formatted(workflowModuleId));
    }

    @Override
    public void startWorkflowProcessing(
        final String workflowModuleId,
        final ZenBpmProcessingContext context
    ) {
        throw notBuiltYet("starting the workflow processing of workflow module '%s'".formatted(workflowModuleId));
    }

    // the platform checks the mode of the workflow module, but a workflow may set a mode of its
    // own, and by-adapter there would deploy the workflow with plain identifiers
    private void refuseByAdapterForTheWorkflow(
        final String workflowModuleId,
        final String bpmnProcessId
    ) {
        if (collaborators
                .scoping()
                .modeFor(workflowModuleId, bpmnProcessId, adapterId) != NameClashAvoidance.BY_ADAPTER) {
            return;
        }
        final var key = "vanillabp.workflow-modules.%s.workflows.%s.adapters.%s.name-clash-avoidance".formatted(
                workflowModuleId, bpmnProcessId, adapterId);
        throw new IllegalStateException("""
                ZenBPM has no isolation mechanism of its own, so the name-clash-avoidance mode 'by-adapter' \
                cannot be served by adapter '%s' - but it is configured for workflow '%s' of workflow module \
                '%s'. Choose explicitly:
                  %s: use-prefix   # VanillaBP prefixes the identifiers
                  %s: none         # your identifiers are unique already
                or remove the key, so the mode of the workflow module applies.""".formatted(adapterId,
                bpmnProcessId, workflowModuleId, key, key));
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

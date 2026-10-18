/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.deployment;

import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pbinitiative.zenbpmadapter.ZenBpmAdapter;
import org.pbinitiative.zenbpmadapter.ZenBpmDeployedProcesses;
import org.pbinitiative.zenbpmadapter.ZenBpmProcessingContext;
import org.pbinitiative.zenbpmadapter.ZenBpmProcessingContext.StartedProcessing;
import org.pbinitiative.zenbpmadapter.ZenBpmProcessingContext.WiredProcess;
import org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientFactory;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientRegistry;
import org.pbinitiative.zenbpmadapter.client.ZenBpmEngineWait;
import org.pbinitiative.zenbpmadapter.client.ZenBpmEnvironmentInfo;
import org.pbinitiative.zenbpmadapter.client.ZenBpmErrors;
import org.pbinitiative.zenbpmadapter.client.ZenBpmInstanceIdentity;
import org.pbinitiative.zenbpmadapter.client.api.DeployedResource;
import org.pbinitiative.zenbpmadapter.health.ZenBpmHealth;
import org.pbinitiative.zenbpmadapter.model.ZenBpmBpmnModel;
import org.pbinitiative.zenbpmadapter.model.ZenBpmCallActivities;
import org.pbinitiative.zenbpmadapter.model.ZenBpmCorrelationKeys;
import org.pbinitiative.zenbpmadapter.model.ZenBpmModelReader;
import org.pbinitiative.zenbpmadapter.model.ZenBpmModelWriter;
import org.pbinitiative.zenbpmadapter.model.ZenBpmMultiInstanceItems;
import org.pbinitiative.zenbpmadapter.model.ZenBpmScoping;
import org.pbinitiative.zenbpmadapter.model.ZenBpmTaskParameters;
import org.pbinitiative.zenbpmadapter.model.ZenBpmUnsupportedConstructs;
import org.pbinitiative.zenbpmadapter.wiring.ZenBpmDrain;
import org.pbinitiative.zenbpmadapter.wiring.ZenBpmJobDispatcher;
import org.pbinitiative.zenbpmadapter.wiring.ZenBpmJobHandler;
import org.pbinitiative.zenbpmadapter.wiring.ZenBpmJobTimeoutResolver;
import org.pbinitiative.zenbpmadapter.wiring.ZenBpmRetryBackoffResolver;

import io.vanillabp.integration.adapter.spi.AdapterCollaborators;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.adapter.spi.BpmnParseException;
import io.vanillabp.integration.adapter.spi.DmnDecisionIds;
import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport.DeployedProcess;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport.ModelIdentifier;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport.ScopedIdentifierKind;
import io.vanillabp.integration.adapter.spi.health.AdapterHealth;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartInvoker;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartSpec;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring;
import io.vanillabp.integration.spi.parts.VanillaBpParts;
import io.vanillabp.integration.spi.startup.StartupReport;
import lombok.extern.slf4j.Slf4j;

/**
 * The deployment pipeline of one ZenBPM adapter id: one instance per configured adapter id, built
 * by the platform modules.
 * <p>
 * This version of the adapter registers, validates its configuration, reports health, reads BPMN
 * files, refuses what ZenBPM cannot run and the name-clash-avoidance mode <code>by-adapter</code>,
 * scopes the identifiers of the files and of the DMN files under <code>use-prefix</code>, wires the
 * processes with the platform, deploys the files once the engine is ready and reports the version
 * of each process, and starts and stops the workflow processing of a module by subscribing and
 * unsubscribing the job types of its claimed processes, whose jobs the {@link ZenBpmJobDispatcher}
 * of the adapter id hands to the module's {@link ZenBpmJobHandler}. Reading, preparing and wiring
 * need no engine, so an adapter id
 * without clients does them as well, like the Camunda 8 adapter, and the checks done meanwhile speak
 * before the configuration does; its deployment then ends with the message of its validation, which
 * names the keys to set, so a workflow module with BPMN files ends the start of an application which
 * configured no engine for it. A workflow module without BPMN files reaches none of the steps,
 * because the platform skips the adapter for it. It also answers what the platform asks before the
 * deployment: whether two adapter ids of this type are one instance.
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

    private final ZenBpmJobTimeoutResolver jobTimeouts;

    private final ZenBpmRetryBackoffResolver retryBackoffs;

    private StartupReport startupReport;

    /**
     * The dispatcher of the jobs of this adapter id, built when the first workflow module starts
     * its processing. Guarded by <code>this</code>.
     */
    private ZenBpmJobDispatcher dispatcher;

    /**
     * The <code>job-timeout</code> of every job type the workflow modules of this adapter id wired,
     * and which module, process and task set it first: modules which share a job type share its
     * subscription.
     */
    private final Map<String, ModuleJobTimeout> jobTimeoutsOfTheModules = new ConcurrentHashMap<>();

    /**
     * The drains of the workflow modules whose processing runs, by workflow module id.
     */
    private final Map<String, ZenBpmDrain> drainsOfRunningModules = new ConcurrentHashMap<>();

    /**
     * The drains of the workflow modules which stopped while another module of this adapter id
     * still ran, and which the stop of the last one waits for: the grace is one budget for all of
     * them, see decision 39 in the repository's DECISIONS.md. Guarded by itself.
     */
    private final List<ZenBpmDrain> drainsToWaitFor = new ArrayList<>();

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
        this(adapterId, registry, collaborators, ZenBpmJobTimeoutResolver.DEFAULT, ZenBpmRetryBackoffResolver.NONE);
    }

    /**
     * Builds the deployment service of one adapter id, which subscribes the job type of a task with
     * the <code>job-timeout</code> the configuration resolves for it, and fails the job of a handler
     * which threw with the <code>retry-backoff</code> it resolves. Fails first where this adapter
     * and the VanillaBP platform integration on the classpath do not belong together.
     *
     * @param adapterId The adapter id
     * @param registry The factories of every adapter id of this type: the one of this id, and the
     *            others, which the check for distinct instances compares
     * @param collaborators What the platform hands to the adapter id
     * @param jobTimeouts The <code>job-timeout</code> of each task, resolved over the four levels of
     *            the configuration
     * @param retryBackoffs The <code>retry-backoff</code> of each task, resolved over the four
     *            levels of the configuration
     * @throws IllegalStateException Where adapter and platform do not belong together, or where the
     *             registry has no factory of the adapter id
     * @throws IllegalArgumentException Where the collaborators belong to another adapter id, a
     *             defect of the platform module's wiring
     */
    public ZenBpmDeploymentService(
        final String adapterId,
        final ZenBpmClientRegistry registry,
        final AdapterCollaborators collaborators,
        final ZenBpmJobTimeoutResolver jobTimeouts,
        final ZenBpmRetryBackoffResolver retryBackoffs
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
        this.jobTimeouts = jobTimeouts;
        this.retryBackoffs = retryBackoffs;
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
     * The <code>job-timeout</code> of a task as the configuration resolves it over its four levels:
     * the lock the task's job type is subscribed with.
     *
     * @param workflowModuleId The workflow module of the task
     * @param bpmnProcessId The plain id of the task's process
     * @param taskDefinition The plain task definition of the task
     * @return The duration, never <code>null</code>
     */
    public Duration jobTimeoutOf(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String taskDefinition
    ) {
        return jobTimeouts.jobTimeoutFor(workflowModuleId, bpmnProcessId, taskDefinition);
    }

    /**
     * The <code>retry-backoff</code> of a task as the configuration resolves it over its four
     * levels: the wait the failure of a handler which threw asks the engine for.
     *
     * @param workflowModuleId The workflow module of the task
     * @param bpmnProcessId The plain id of the task's process
     * @param taskDefinition The plain task definition of the task
     * @return The duration; empty where no level sets it, so the failure carries none
     */
    public Optional<Duration> retryBackoffOf(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String taskDefinition
    ) {
        return retryBackoffs.retryBackoffFor(workflowModuleId, bpmnProcessId, taskDefinition);
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
     * has no isolation of its own (see decision 24 in the repository's DECISIONS.md). It refuses a
     * process id another file of the workflow module declares as well. Once per file again, it
     * refuses a message used both to start a workflow of the module and as one a
     * claimed workflow waits for, scopes the identifiers of the file, see {@link ZenBpmScoping}, and,
     * in a process a workflow service claims, writes the correlation keys, see
     * {@link ZenBpmCorrelationKeys}, and the input mappings which carry what the methods read into
     * multi-instance bodies, see {@link ZenBpmTaskParameters}.
     *
     * @param workflowModuleId The workflow module of the process
     * @param existingContext The context of the module's earlier processes, <code>null</code> for
     *            its first
     * @param filename The name of the process's file
     * @param bpmnProcessId The id of the process
     * @param model The model of the process's file
     * @return The context of the workflow module, never <code>null</code>
     * @throws IllegalStateException Where the file uses what ZenBPM cannot run, naming every
     *             element, where <code>by-adapter</code> applies to the module or the workflow,
     *             where another file declares the same process, or where a message starts a
     *             workflow and a workflow waits for it
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
        // asked once per file, because only the first preparation of a file reads it
        final var claimed = firstPreparation && collaborators
                .workflowTaskWiring()
                .isClaimedByAWorkflowService(workflowModuleId, bpmnProcessId);
        if (firstPreparation) {
            ZenBpmUnsupportedConstructs.check(workflowModuleId, model, claimed);
        }
        collaborators
                .scoping()
                .validateNativeIsolationSupported(adapterId, workflowModuleId, ZenBpmAdapter.BPMS_NAME);
        refuseByAdapterForTheWorkflow(workflowModuleId, bpmnProcessId);
        context.recordProcess(bpmnProcessId, filename);
        if (firstPreparation) {
            rewrite(workflowModuleId, context, bpmnProcessId, model, claimed);
        }
        return context;
    }

    /**
     * Keeps one DMN file of the workflow module for its deployment, and the ids of the decisions it
     * declares, which the platform's check of the identifiers two workflow modules share needs.
     * Under <code>use-prefix</code> those ids are scoped by the workflow module, as the
     * <code>calledDecision</code> of a business rule task is (see {@link ZenBpmScoping}); otherwise
     * the file is kept as it was read. It needs no engine.
     *
     * @param workflowModuleId The workflow module of the file
     * @param existingContext The context the module's BPMN files produced
     * @param filename The name of the file
     * @param dmn The content of the file, which is not closed
     * @return The context, with the file added
     * @throws IllegalStateException Where the file cannot be read as XML, naming file and workflow
     *             module
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
        final Set<String> decisionIds;
        final byte[] deployable;
        try {
            decisionIds = DmnDecisionIds.of(file);
            deployable = scoping.modeFor(workflowModuleId, null, adapterId) == NameClashAvoidance.USE_PREFIX
                    ? DmnDecisionIds.rewrite(file, decisionId -> scoping.scopedIdentifier(workflowModuleId, decisionId,
                            adapterId))
                    : file;
        } catch (final IllegalArgumentException e) {
            throw new IllegalStateException("DMN file '%s' of workflow module '%s' cannot be read: %s"
                    .formatted(filename, workflowModuleId, e.getMessage()), e);
        }
        existingContext.addDecisions(filename, deployable);
        existingContext.addDeclaredIdentifiers(decisionIds
                .stream()
                .map(decisionId -> new ModelIdentifier(ScopedIdentifierKind.DMN_DECISION_ID, decisionId, null))
                .toList());
        return existingContext;
    }

    /**
     * Makes the platform's wiring calls for one process, so the start ends over a task nothing
     * serves, a start event a method names but the model does not hold, or an item no element hands
     * over, before anything reaches the engine. It needs no engine. A process nobody claims belongs
     * to somebody else: nothing is asked or written for it.
     * <p>
     * For a claimed process it reports the expressions the modeller wrote, read before preparing
     * added keys and mappings; refuses an element which creates a job and names no task
     * definition, because the engine gives it a job of the type '' which nothing subscribes, unless
     * the task is served elsewhere; has every task and user task checked against the methods, with
     * their names; reports the names of its message start events, where every one can be read, and
     * every start event of the process itself; reports the elements which give a workflow a second
     * token; refuses a method which wants the item of an iteration whose element names none, see
     * {@link ZenBpmMultiInstanceItems}; carries the aggregate id to a process called on the same
     * aggregate from inside a multi-instance body, see {@link ZenBpmCallActivities}; and remembers
     * what the start of the workflow processing and the lifecycle of a workflow need, the
     * <code>job-timeout</code> of each job type included. No version
     * catalog is registered yet, which the platform reads as "not asked yet".
     *
     * @param workflowModuleId The workflow module of the process
     * @param filename The name of the process's file
     * @param bpmnProcessId The id of the process
     * @param model The model of the process's file, as preparing left it
     * @param context The context of the workflow module
     * @throws IllegalStateException Where an element which creates a job names no task definition,
     *             the platform refuses the wiring, a method wants an item no element hands over, or
     *             two tasks of one job type have different <code>job-timeout</code>s
     */
    @Override
    public void wireBpmn(
        final String workflowModuleId,
        final String filename,
        final String bpmnProcessId,
        final ZenBpmBpmnModel model,
        final ZenBpmProcessingContext context
    ) {
        final var wiring = collaborators.workflowTaskWiring();
        if (!wiring.isClaimedByAWorkflowService(workflowModuleId, bpmnProcessId)) {
            log.debug(
                    "ZenBPM[{}]: BPMN process '{}' of file '{}' (workflow module '{}') is claimed by no @WorkflowService and is deployed as it was modelled",
                    adapterId, bpmnProcessId, filename, workflowModuleId);
            return;
        }
        wiring.reportModelExpressions(workflowModuleId, bpmnProcessId, model.expressions());
        refuseJobsOfNoType(wiring, workflowModuleId, filename, bpmnProcessId, model);
        wiring.validateTaskWiring(adapterId, workflowModuleId, bpmnProcessId, Stream
                .concat(model
                        .tasks()
                        .stream(),
                        model
                                .userTasks()
                                .stream())
                .toList());
        collaborators
                .bpmsInitiatedStartInvoker()
                .ifPresent(invoker -> reportTheStarts(invoker, workflowModuleId, bpmnProcessId, model));
        wiring.reportConcurrentTokenElements(workflowModuleId, bpmnProcessId, model.concurrentTokenElements());
        ZenBpmMultiInstanceItems.refuseItemsNoElementHandsOver(model, workflowModuleId, wiring);
        ZenBpmCallActivities.carryTheAggregateId(model, workflowModuleId, wiring);
        final var scoping = collaborators.scoping();
        final var jobTypes = new LinkedHashSet<String>();
        model
                .tasks()
                .stream()
                .filter(task -> !wiring.isImplementedExternally(adapterId, workflowModuleId, bpmnProcessId, task))
                .forEach(task -> {
                    final var jobType = scoping.scopedTaskDefinition(workflowModuleId, bpmnProcessId, task
                            .taskDefinition(), adapterId);
                    jobTypes.add(jobType);
                    final var jobTimeout = jobTimeouts.jobTimeoutFor(workflowModuleId, bpmnProcessId, task
                            .taskDefinition());
                    context.recordJobTimeout(jobType, jobTimeout, bpmnProcessId, task.taskDefinition(), adapterId);
                    refuseAnotherJobTimeoutOfASharedType(jobType,
                            new ModuleJobTimeout(workflowModuleId, bpmnProcessId, task.taskDefinition(), jobTimeout));
                });
        final var scopedProcessId = scoping.scopedProcessId(workflowModuleId, bpmnProcessId, adapterId);
        context.recordWiring(new WiredProcess(bpmnProcessId, scopedProcessId, jobTypes, reportsItsStart(
                workflowModuleId, bpmnProcessId), reportsItsEnd(workflowModuleId, bpmnProcessId)));
        recordServed(workflowModuleId, bpmnProcessId, scopedProcessId);
        log.info(
                "ZenBPM[{}]: wired {} task(s) and {} user task(s) of BPMN process '{}' (file '{}', workflow module '{}')",
                adapterId, model
                        .tasks()
                        .size(),
                model
                        .userTasks()
                        .size(),
                bpmnProcessId, filename, workflowModuleId);
    }

    /**
     * Deploys the BPMN and DMN files of a workflow module, one request per file, and tells the
     * platform which version of each process the engine ended up with, also where a file was
     * unchanged and the engine kept the version it held.
     * <p>
     * First it ends the start where two processes of this application would reach the engine under
     * one id, before anything is sent to it. Then it waits until the engine is ready, once per
     * adapter id (see {@link ZenBpmEngineWait}; an adapter id without clients ends the start there
     * with the message of its validation), and says which engine it reached, see
     * {@link ZenBpmEnvironmentInfo}. The DMN files go first, so no new version of a process calls a
     * decision the engine does not hold yet. A file reaches the engine as it was read where the
     * pipeline changed nothing, see {@link ZenBpmModelWriter}. A file the engine refuses ends the
     * deployment with the engine's reason, naming file, workflow module and process; any other
     * failure names file and engine address. The engine keeps the files it took before, because it
     * deploys one file per request. Only where it took every file are the versions registered with
     * the platform and remembered in {@link ZenBpmClientFactory#deployedProcesses()}; the
     * identifiers the files declare go to the platform, which warns where two workflow modules
     * share one. Which of them the engine held before is not reported: a definition carries no
     * owner, so this application's own earlier deployments could not be told from those of another
     * application.
     *
     * @param workflowModuleId The workflow module
     * @param context The context its files were prepared into
     * @throws IllegalStateException Where two processes would share an id, where the adapter id has
     *             no clients or the engine is not ready, or where a file cannot be deployed
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
        ZenBpmEngineWait.awaitReady(factory);
        ZenBpmEnvironmentInfo.logOnce(factory, startupReport);
        final var rest = factory.rest();
        final var wiring = collaborators.workflowTaskWiring();
        final var deployed = new ArrayList<String>();
        // decisions first, so no new version of a process calls a decision the engine does not
        // hold yet
        for (final var decisions : context
                .decisions()
                .entrySet()) {
            final var file = "DMN file '%s' of workflow module '%s'".formatted(decisions.getKey(), workflowModuleId);
            final var resource = answerTo(file, () -> rest.deployDmn(decisions.getValue()));
            deployed.add("%s as DMN resource definition key %d%s".formatted(decisions.getKey(), resource.key(),
                    unchangedOrNot(resource)));
        }
        final var versions = new ArrayList<ZenBpmDeployedProcesses.DeployedProcess>();
        for (final var model : context
                .bpmnFiles()
                .values()) {
            final var bpmnProcessId = model.bpmnProcessId();
            final var file = "BPMN file '%s' of workflow module '%s' (process '%s')".formatted(model.filename(),
                    workflowModuleId, bpmnProcessId);
            final var resource = answerTo(file, () -> rest.deployBpmn(ZenBpmModelWriter.bytesOf(model)));
            final var definition = answerTo(file, () -> rest.processDefinition(resource.key()));
            versions.add(new ZenBpmDeployedProcesses.DeployedProcess(workflowModuleId, bpmnProcessId, definition
                    .bpmnProcessId(), definition.key(), definition.version(), definition.versionTag(), definition
                            .bpmnData()));
            deployed.add("%s as process '%s', version %d, definition key %d%s".formatted(model.filename(), definition
                    .bpmnProcessId(), definition.version(), definition.key(), unchangedOrNot(resource)));
        }
        // only a deployment the engine took as a whole tells the platform its versions
        versions.forEach(version -> {
            wiring.registerDeployedVersion(adapterId, workflowModuleId, version.bpmnProcessId(), String.valueOf(
                    version.version()));
            factory
                    .deployedProcesses()
                    .record(version);
        });
        log.info("ZenBPM[{}]: workflow module '{}' is deployed to the engine at {}:\n  {}", adapterId,
                workflowModuleId, factory
                        .configuration()
                        .engineAddress(),
                String.join("\n  ", deployed));
        collaborators
                .scoping()
                .reportIdentifiersTheModelsDeclare(adapterId, workflowModuleId, List.copyOf(context
                        .declaredIdentifiers()));
    }

    /**
     * Starts the workflow processing of a workflow module: subscribes every job type of its claimed
     * processes on the job stream of this adapter id, a process nobody claims left to whoever serves
     * it. The hook which stops the module where the client factory closes first is registered
     * before the first subscription, so a factory which closes already refuses the module before
     * anything is subscribed. A job type two workflow modules share stays subscribed until both
     * stopped. A module without a job type subscribes nothing.
     * <p>
     * The workflows of a process id the module declares without bringing a model for it, such as
     * the old id of a renamed process, are served as well, by the job types the methods serving
     * that id name where a model the engine holds under the id gives them to a task, see
     * {@link ZenBpmDeclaredProcessWorkers} and decision 41 in the repository's DECISIONS.md; the
     * engine is asked for those models only where the module declares such an id.
     * <p>
     * The module's {@link ZenBpmJobHandler} is registered with the {@link ZenBpmJobDispatcher} of
     * this adapter id before the first subscription, so the first job finds it. Every type is
     * subscribed with its <code>job-timeout</code> as the lock and the number of handler slots as
     * the cap of active jobs, see decision 9 in the repository's DECISIONS.md.
     *
     * @param workflowModuleId The workflow module
     * @param context What its deployment collected
     * @throws IllegalStateException Where the client factory is closing, or a job type cannot be
     *             subscribed; then nothing of the module stays subscribed
     */
    @Override
    public void startWorkflowProcessing(
        final String workflowModuleId,
        final ZenBpmProcessingContext context
    ) {
        // an adapter id without clients has no engine to read the models of a declared process from,
        // and refuses below where the module subscribes anything
        if (factory.isConfigured()) {
            serveTheProcessesNobodyDeployed(workflowModuleId, context);
        }
        final var jobTypes = context.jobTypes();
        if (jobTypes.isEmpty()) {
            log.info("ZenBPM[{}]: workflow module '{}' has no job type to subscribe", adapterId, workflowModuleId);
            return;
        }
        final var stream = factory.stream();
        final var dispatcher = dispatcher();
        final var handler = new ZenBpmJobHandler(adapterId, factory, collaborators, context, retryBackoffs);
        final var slots = factory
                .executor()
                .getSlots();
        final var jobTimeoutsOfTheTypes = context.jobTimeouts();
        // the closing factory runs the module's hook on its own thread, which may be at once; the
        // hook's stop asks the context what started, so holding the context until the last
        // subscription lets that stop find all of the start or nothing of it
        synchronized (context) {
            context.processingStarted(new StartedProcessing(jobTypes, factory.workflowModuleStarted(
                    workflowModuleId, () -> stopWorkflowProcessing(workflowModuleId, context))));
            // only a module the closing factory did not refuse counts as running, or the stop of
            // the others would wait for it forever and so never wait for any handler
            drainsOfRunningModules.put(workflowModuleId, handler.drain());
            dispatcher.register(handler);
            try {
                jobTimeoutsOfTheTypes.forEach((
                    jobType,
                    jobTimeout) -> stream.subscribe(workflowModuleId, jobType, jobTimeout, slots));
            } catch (final RuntimeException e) {
                stopWorkflowProcessing(workflowModuleId, context);
                throw e;
            }
        }
        log.info("ZenBPM[{}]: workflow module '{}' subscribed the job type(s) {}, each with at most {} active job(s)",
                adapterId, workflowModuleId, jobTimeoutsOfTheTypes
                        .entrySet()
                        .stream()
                        .map(jobType -> "%s (job-timeout %s)".formatted(jobType.getKey(), jobType.getValue()))
                        .collect(Collectors.joining(", ")),
                slots);
    }

    /**
     * Stops the workflow processing of a workflow module: unsubscribes its job types, where no other
     * module of this adapter id subscribes them, hands no further job to the module's handler, which
     * from now on repeats no failed command, starts no job and fails no job whose handler threw, and
     * removes the hook of the client factory after the last of them. It is idempotent, because the
     * platform and the closing client factory may both stop a module. Jobs delivered before keep
     * their locks, which the closing job stream releases, and a handler which runs goes on.
     * <p>
     * The stop of the last running module of this adapter id waits for the handlers of every module
     * which stopped, all together, at most <code>shutdown-grace</code>, and names each handler which
     * still runs then: the platform stops the modules one after another, and a wait per module would
     * spend the grace once per module, see decision 39 in the repository's DECISIONS.md.
     *
     * @param workflowModuleId The workflow module
     * @param context What its deployment collected
     */
    @Override
    public void stopWorkflowProcessing(
        final String workflowModuleId,
        final ZenBpmProcessingContext context
    ) {
        final var started = context.processingStopped();
        if (started.isEmpty()) {
            return;
        }
        final var stream = factory.stream();
        started
                .get()
                .jobTypes()
                .forEach(jobType -> stream.unsubscribe(workflowModuleId, jobType));
        dispatcher().unregister(workflowModuleId);
        started
                .get()
                .registration()
                .close();
        log.info("ZenBPM[{}]: workflow module '{}' unsubscribed the job type(s) {}", adapterId, workflowModuleId,
                String.join(", ", started
                        .get()
                        .jobTypes()));
        final List<ZenBpmDrain> stopped;
        synchronized (drainsToWaitFor) {
            drainsToWaitFor.add(drainsOfRunningModules.remove(workflowModuleId));
            if (!drainsOfRunningModules.isEmpty()) {
                log.debug(
                        "ZenBPM[{}]: workflow module '{}' stopped while {} further module(s) of this adapter id run, so its handlers are waited for with theirs",
                        adapterId, workflowModuleId, drainsOfRunningModules.size());
                return;
            }
            stopped = List.copyOf(drainsToWaitFor);
            drainsToWaitFor.clear();
        }
        final var grace = factory
                .configuration()
                .getShutdownGrace();
        if (stopped.size() > 1) {
            log.info(
                    "ZenBPM[{}]: the handlers of {} workflow modules are waited for together, within one {} of {}",
                    adapterId, stopped.size(), ZenBpmAdapterConfiguration.propertyKey(adapterId, "shutdown-grace"),
                    grace);
        }
        ZenBpmDrain.awaitEveryModuleQuiet(stopped, grace);
    }

    // a job goes to the workflow module which serves the process of its instance, so a process the
    // engine knows under one id is served by one module: under none a module which declares a
    // process without a model may name the process another module claims, which the check of the
    // deployed ids does not see
    private void recordServed(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String scopedProcessId
    ) {
        factory
                .deployedProcesses()
                .recordServed(workflowModuleId, scopedProcessId)
                .ifPresent(other -> {
                    throw new IllegalStateException("""
                            Workflow modules '%s' and '%s' both serve the workflows of BPMN process '%s', which \
                            ZenBPM (adapter '%s') knows as '%s', so whose task a job of them is cannot be said. One \
                            of them declares the process without bringing a model for it, under name-clash-avoidance \
                            'none'. Let one workflow module only serve that process, or keep the modules' ids apart \
                            with vanillabp.adapters.%s.name-clash-avoidance: use-prefix (or \
                            vanillabp.workflow-modules.%s.adapters.%s.name-clash-avoidance) before any workflow of \
                            them runs: changing it later is a BPMS migration."""
                            .formatted(other, workflowModuleId, bpmnProcessId, adapterId, scopedProcessId, adapterId,
                                    workflowModuleId, adapterId));
                });
    }

    // the job types are recorded like those of a claimed process, so they are subscribed, routed to
    // the module and unsubscribed with the others
    private void serveTheProcessesNobodyDeployed(
        final String workflowModuleId,
        final ZenBpmProcessingContext context
    ) {
        final var declared = collaborators
                .workflowTaskWiring()
                .taskWiringOfProcessesNobodyDeployed(workflowModuleId);
        if (declared.isEmpty()) {
            return;
        }
        final var subscribedBefore = new LinkedHashSet<String>();
        context
                .wiredProcesses()
                .values()
                .stream()
                .filter(process -> !declared.containsKey(process.bpmnProcessId()))
                .forEach(process -> subscribedBefore.addAll(process.jobTypes()));
        new ZenBpmDeclaredProcessWorkers(adapterId, factory, collaborators)
                .compose(workflowModuleId, declared, subscribedBefore)
                .forEach(process -> serve(workflowModuleId, process, context));
    }

    private void serve(
        final String workflowModuleId,
        final ZenBpmDeclaredProcessWorkers.DeclaredProcess declared,
        final ZenBpmProcessingContext context
    ) {
        final var bpmnProcessId = declared.bpmnProcessId();
        recordServed(workflowModuleId, bpmnProcessId, declared.scopedBpmnProcessId());
        if (declared
                .jobTypes()
                .isEmpty()) {
            return;
        }
        declared
                .jobTypes()
                .forEach((
                    taskDefinition,
                    jobType) -> {
                    final var jobTimeout = jobTimeouts.jobTimeoutFor(workflowModuleId, bpmnProcessId, taskDefinition);
                    context.recordJobTimeout(jobType, jobTimeout, bpmnProcessId, taskDefinition, adapterId);
                    refuseAnotherJobTimeoutOfASharedType(jobType,
                            new ModuleJobTimeout(workflowModuleId, bpmnProcessId, taskDefinition, jobTimeout));
                });
        context.recordWiring(
                new WiredProcess(bpmnProcessId, declared.scopedBpmnProcessId(), new LinkedHashSet<>(declared
                        .jobTypes()
                        .values()), reportsItsStart(workflowModuleId, bpmnProcessId), reportsItsEnd(workflowModuleId,
                                bpmnProcessId)));
    }

    private boolean reportsItsStart(
        final String workflowModuleId,
        final String bpmnProcessId
    ) {
        return collaborators
                .bpmsInitiatedStartInvoker()
                .map(invoker -> invoker.startsAWorkflowOfItsOwn(workflowModuleId, bpmnProcessId))
                .orElse(false);
    }

    private boolean reportsItsEnd(
        final String workflowModuleId,
        final String bpmnProcessId
    ) {
        return collaborators
                .workflowEndedInvoker()
                .map(invoker -> invoker.workflowEndedHandlerExists(workflowModuleId, bpmnProcessId))
                .orElse(false);
    }

    // two workflow modules share a job type where its name is not scoped, and the job stream keeps
    // one lock duration per job type, so the second module's subscription would be refused when
    // its processing starts; refused here instead, before anything reaches the engine
    private void refuseAnotherJobTimeoutOfASharedType(
        final String jobType,
        final ModuleJobTimeout wired
    ) {
        final var recorded = jobTimeoutsOfTheModules.putIfAbsent(jobType, wired);
        if ((recorded == null) || recorded.workflowModuleId.equals(wired.workflowModuleId) || recorded.duration
                .equals(wired.duration)) {
            return;
        }
        throw new IllegalStateException("""
                Job type '%s' is served by two workflow modules with different job-timeouts: %s for task '%s' of \
                process '%s' in workflow module '%s' and %s for task '%s' of process '%s' in workflow module '%s'. \
                The engine locks the jobs of a type for one duration per subscription, and both modules share the \
                subscription of adapter id '%s', so give both tasks the same job-timeout, for example:
                  vanillabp.workflow-modules.%s.workflows.%s.tasks.%s.adapters.%s.job-timeout
                  vanillabp.workflow-modules.%s.workflows.%s.tasks.%s.adapters.%s.job-timeout
                or keep the job types apart with name-clash-avoidance 'use-prefix'."""
                .formatted(jobType, recorded.duration, recorded.taskDefinition, recorded.bpmnProcessId,
                        recorded.workflowModuleId, wired.duration, wired.taskDefinition, wired.bpmnProcessId,
                        wired.workflowModuleId, adapterId, recorded.workflowModuleId, recorded.bpmnProcessId,
                        recorded.taskDefinition, adapterId, wired.workflowModuleId, wired.bpmnProcessId,
                        wired.taskDefinition, adapterId));
    }

    private record ModuleJobTimeout(
        String workflowModuleId,
        String bpmnProcessId,
        String taskDefinition,
        Duration duration
    ) {
    }

    // built once per adapter id, by the first module which starts, and the job stream's consumer
    // from then on
    private synchronized ZenBpmJobDispatcher dispatcher() {
        if (dispatcher == null) {
            dispatcher = new ZenBpmJobDispatcher(adapterId, factory);
            factory
                    .stream()
                    .onJob(dispatcher);
        }
        return dispatcher;
    }

    // what is done to a file once, the refusals before anything is written: a process nobody
    // claims belongs to somebody else and gets nothing but the scoping of the whole file
    private void rewrite(
        final String workflowModuleId,
        final ZenBpmProcessingContext context,
        final String bpmnProcessId,
        final ZenBpmBpmnModel model,
        final boolean claimed
    ) {
        context.addMessagesOf(model, claimed);
        context.addDeclaredIdentifiers(ZenBpmScoping.apply(model.document(), workflowModuleId, bpmnProcessId,
                collaborators.scoping(), adapterId));
        context.addBpmnFile(model);
        if (!claimed) {
            return;
        }
        final var wiring = collaborators.workflowTaskWiring();
        ZenBpmCorrelationKeys.apply(model, () -> wiring.resolveWorkflowAggregateIdName(workflowModuleId,
                bpmnProcessId));
        ZenBpmTaskParameters.apply(model, workflowModuleId, wiring);
    }

    // the engine gives an element which creates a job and names no task definition a job of the
    // type '', which nothing of this application subscribes, so a workflow would wait there for
    // good; a task served elsewhere is left to whoever serves it
    private void refuseJobsOfNoType(
        final WorkflowTaskWiring wiring,
        final String workflowModuleId,
        final String filename,
        final String bpmnProcessId,
        final ZenBpmBpmnModel model
    ) {
        final var untyped = model
                .tasks()
                .stream()
                .filter(task -> task.taskDefinition() == null)
                .filter(task -> !wiring.isImplementedExternally(adapterId, workflowModuleId, bpmnProcessId, task))
                .map(task -> "  - %s".formatted(task.activityId()))
                .toList();
        if (untyped.isEmpty()) {
            return;
        }
        throw new IllegalStateException("""
                BPMN file '%s' of workflow module '%s' holds elements of process '%s' which create a job and \
                name no task definition:
                %s
                ZenBPM gives each of them a job of the type '', which no worker of this application subscribes, \
                so a workflow would wait there for good. Give each of them a task definition, \
                <zenbpm:taskDefinition type="..." />, and serve that type with a @WorkflowTask method."""
                .formatted(filename, workflowModuleId, bpmnProcessId, String.join("\n", untyped)));
    }

    // every start event of the process itself, NONE and MESSAGE included, so the platform can judge
    // the methods which name one; the message names only where every one of them can be read,
    // because the platform checks a start by message against a complete list or not at all
    private void reportTheStarts(
        final BpmsInitiatedStartInvoker starts,
        final String workflowModuleId,
        final String bpmnProcessId,
        final ZenBpmBpmnModel model
    ) {
        if (model.startMessagesAreKnown()) {
            starts.reportStartMessages(adapterId, workflowModuleId, bpmnProcessId, model.startMessages());
        }
        starts.validateBpmsInitiatedStarts(workflowModuleId, bpmnProcessId, model
                .startEvents()
                .stream()
                .map(startEvent -> BpmsInitiatedStartSpec.of(startEvent.elementId(), startEvent.kind()))
                .toList());
    }

    // what the engine answered about one file: a file it refuses as it is ends the deployment with
    // its reason, every other failure with the address the file did not reach
    private <T> T answerTo(
        final String file,
        final Supplier<T> call
    ) {
        try {
            return call.get();
        } catch (final RuntimeException e) {
            if (ZenBpmErrors.permanentFailure(e)) {
                throw new IllegalStateException("""
                        ZenBPM refuses %s: %s. Correct the model where the engine's reason points, or \
                        take the file out of the workflow module.""".formatted(file, ZenBpmErrors.withoutAdapterId(
                        adapterId, e)), e);
            }
            throw new IllegalStateException("ZenBPM adapter '%s' could not deploy %s to the engine at %s: %s"
                    .formatted(adapterId, file, factory
                            .configuration()
                            .engineAddress(), ZenBpmErrors.withoutAdapterId(adapterId, e)), e);
        }
    }

    private static String unchangedOrNot(final DeployedResource resource) {
        return resource.created() ? "" : " (unchanged)";
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

}

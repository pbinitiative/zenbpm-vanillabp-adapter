/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.processservice;

import java.io.InputStream;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration;
import org.pbinitiative.zenbpmadapter.client.ZenBpmApiException;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientFactory;
import org.pbinitiative.zenbpmadapter.client.ZenBpmErrors;
import org.pbinitiative.zenbpmadapter.client.ZenBpmTimeToLive;
import org.pbinitiative.zenbpmadapter.client.api.CreateInstanceRequest;
import org.pbinitiative.zenbpmadapter.client.api.ProcessInstanceRef;

import io.vanillabp.integration.adapter.spi.AdapterCollaborators;
import io.vanillabp.integration.adapter.spi.AggregateSyncMode;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.adapter.spi.PhaseOneRequest;
import io.vanillabp.integration.adapter.spi.PhaseOperationHandler;
import io.vanillabp.integration.adapter.spi.PhaseTwoRequest;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.adapter.spi.WorkflowScope;
import io.vanillabp.integration.adapter.spi.values.ValueDirection;
import io.vanillabp.integration.adapter.spi.values.ValueTypeVerdict;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.spi.process.ProcessDefinition;
import io.vanillabp.spi.process.WorkflowHistory;
import lombok.extern.slf4j.Slf4j;

/**
 * What the core asks of one ZenBPM adapter id at runtime: one instance per configured adapter
 * id, built by the platform modules.
 * <p>
 * This version of the adapter starts workflows and says what the engine does with the values a
 * workflow carries. It serves every operation the core requires of each adapter, so the core
 * accepts it while the application starts. Every other handler, every probe and every method of
 * the viewer/history API throws: where the adapter id has no clients, the message of its
 * validation, which names the keys to set; otherwise an {@link UnsupportedOperationException}
 * which says that the call is not built yet. Nothing pretends to have done the work. The
 * operations a BPMS may lack, sending a signal and telling the BPMS about a changed aggregate, are
 * left out, so the core answers them as not supported.
 * <p>
 * A start asks the engine nothing in phase one, because there is nothing to ask before an
 * instance exists. Phase two starts the latest version of the process under the id the engine
 * knows it by, with the aggregate's id as the business key and as a variable beside the values the
 * aggregate shares (see {@link ZenBpmSharedValues}), and reports the key and the version of the
 * new instance. A start the engine answers with <code>404</code>, because it holds no such
 * process, is permanent, see decision 30 in the repository's DECISIONS.md.
 * <p>
 * Each field has an accessor of its own and no Lombok getter on the class, see decision 13 in the
 * repository's DECISIONS.md.
 *
 * @param <A> The workflow aggregate type
 */
@Slf4j
@SuppressWarnings("LombokGetterMayBeUsed")
public class ZenBpmProcessService<A> implements MigratableProcessService<A> {

    private final String adapterId;

    private final ZenBpmClientFactory factory;

    private final AdapterCollaborators collaborators;

    private final HistoryTimeToLive historyTimeToLive;

    private final Map<PhaseOperation, PhaseOperationHandler<A>> phaseOperations;

    /**
     * Builds the process service of one adapter id.
     *
     * @param adapterId The adapter id
     * @param factory The factory of the adapter id, from the registry
     * @param collaborators What the platform hands to the adapter id
     * @param historyTimeToLive The <code>history-time-to-live</code> of a workflow, as the platform
     *            module resolves it
     * @throws IllegalArgumentException Where the factory or the collaborators belong to another
     *             adapter id, a defect of the platform module's wiring
     */
    public ZenBpmProcessService(
        final String adapterId,
        final ZenBpmClientFactory factory,
        final AdapterCollaborators collaborators,
        final HistoryTimeToLive historyTimeToLive
    ) {
        if (!adapterId.equals(factory.getAdapterId()) || !adapterId.equals(collaborators.adapterId())) {
            throw new IllegalArgumentException(
                    "The process service of ZenBPM adapter id '%s' was given the factory of adapter id '%s' and the collaborators of adapter id '%s'; the platform module wires the three of one id."
                            .formatted(adapterId, factory.getAdapterId(), collaborators.adapterId()));
        }
        this.adapterId = adapterId;
        this.factory = factory;
        this.collaborators = collaborators;
        this.historyTimeToLive = historyTimeToLive;
        final var handlers = new LinkedHashMap<PhaseOperation, PhaseOperationHandler<A>>();
        PhaseOperation.CORE_OPERATIONS
                .stream()
                .filter(PhaseOperation::requiredOfEveryAdapter)
                .forEach(operation -> handlers.put(operation, PhaseOperationHandler.of(request -> {
                    throw notBuiltYet("phase one of %s".formatted(operation.name()));
                }, request -> {
                    throw notBuiltYet("phase two of %s".formatted(operation.name()));
                })));
        // replaces the handler which is not built yet, in its place
        handlers.put(PhaseOperation.START_WORKFLOW, PhaseOperationHandler.of(this::askNothingBeforeAStart,
                this::startWorkflow));
        this.phaseOperations = Collections.unmodifiableMap(handlers);
    }

    @Override
    public String getAdapterId() {
        return adapterId;
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
     * How long the engine keeps a workflow of a process after it ended, as a start of it sends it:
     * the workflow's <code>history-time-to-live</code>, else the workflow module's, else the
     * adapter's.
     *
     * @param workflowModuleId The workflow module
     * @param bpmnProcessId The BPMN process, as the workflow module names it
     * @return The time, empty where no level sets it and the engine's own default applies
     */
    public Optional<Duration> historyTimeToLiveOf(
        final String workflowModuleId,
        final String bpmnProcessId
    ) {
        return Optional.ofNullable(historyTimeToLive.of(workflowModuleId, bpmnProcessId));
    }

    /**
     * A handler for every operation the core requires of each adapter: the start of a workflow,
     * and a handler which throws for every other.
     *
     * @return The handlers, which cannot be changed
     */
    @Override
    public Map<PhaseOperation, PhaseOperationHandler<A>> phaseOperations() {
        return phaseOperations;
    }

    /**
     * The engine learns the outcome of a task after the application committed, so a crash in
     * between hands the task out again.
     *
     * @return <code>true</code>
     */
    @Override
    public boolean deliversTasksAtLeastOnce() {
        return true;
    }

    /**
     * What the engine does with that type, see {@link ZenBpmValueTypes}.
     *
     * @param valueType The declared type of the value
     * @param direction Which way the value travels
     * @return The verdict of this adapter
     */
    @Override
    public ValueTypeVerdict whatThisBpmsDoesWith(
        final Class<?> valueType,
        final ValueDirection direction
    ) {
        return ZenBpmValueTypes.verdictFor(valueType, direction);
    }

    /**
     * Whether a failed phase two is worth repeating: everything but what the engine refuses the
     * same way every time, see {@link ZenBpmErrors#permanentFailure(Throwable)}.
     *
     * @param failure What phase two failed with
     * @return Whether another attempt may change the answer
     */
    @Override
    public boolean isPhaseTwoFailureRepeatable(final Throwable failure) {
        return ZenBpmErrors.isRepeatable(failure);
    }

    @Override
    public WorkflowAwareness awarenessOfTask(
        final WorkflowScope scope,
        final Object workflowAggregateId,
        final String taskId
    ) {
        throw notBuiltYet("asking for task '%s'".formatted(taskId));
    }

    @Override
    public WorkflowAwareness awarenessOfWorkflow(
        final WorkflowScope scope,
        final AggregatePersistenceAware<A> aggregatePersistence,
        final Object workflowAggregateId
    ) {
        throw notBuiltYet("asking for the workflow of an aggregate");
    }

    @Override
    public WorkflowAwareness awarenessOfUserTask(
        final WorkflowScope scope,
        final Object workflowAggregateId,
        final String taskId
    ) {
        throw notBuiltYet("asking for user task '%s'".formatted(taskId));
    }

    // the aggregate id is the application's business key, so no message of the three viewer
    // methods repeats it, like the probes
    @Override
    public List<ProcessDefinition> getProcessDefinitions(
        final String workflowModuleId,
        final String bpmnProcessId,
        final AggregatePersistenceAware<A> aggregatePersistence,
        final Object workflowAggregateId,
        final String historyContext
    ) {
        throw notBuiltYet("listing the process definitions of workflow '%s' of workflow module '%s'".formatted(
                bpmnProcessId, workflowModuleId));
    }

    @Override
    public InputStream getBpmnXml(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String processDefinitionId
    ) {
        throw notBuiltYet("reading the BPMN XML of process definition '%s' of workflow module '%s'".formatted(
                processDefinitionId, workflowModuleId));
    }

    @Override
    public WorkflowHistory getWorkflowHistory(
        final String workflowModuleId,
        final String bpmnProcessId,
        final AggregatePersistenceAware<A> aggregatePersistence,
        final Object workflowAggregateId,
        final String historyContext
    ) {
        throw notBuiltYet("reading the history of workflow '%s' of workflow module '%s'".formatted(bpmnProcessId,
                workflowModuleId));
    }

    // the core checked the aggregate's id before phase one. Phase one runs inside the caller's
    // transaction, and an engine which is away only makes phase two wait in the outbox, so only an
    // adapter id without clients is refused here, with the message of its validation
    private void askNothingBeforeAStart(final PhaseOneRequest<A> request) {
        final var unconfigured = factory.unconfiguredMessage();
        if (unconfigured.isPresent()) {
            throw new IllegalStateException(unconfigured.get());
        }
    }

    private void startWorkflow(final PhaseTwoRequest<A> request) {
        final var workflowModuleId = request.workflowModuleId();
        final var bpmnProcessId = request.bpmnProcessId();
        final var aggregateId = request.workflowAggregateId();
        final var scopedProcessId = collaborators
                .scoping()
                .scopedProcessId(workflowModuleId, bpmnProcessId, adapterId);
        final var variables = ZenBpmSharedValues.forCommand(request
                .aggregatePersistence()
                .getAggregateIdName(), aggregateId, sharedValuesOf(request));
        final var start = CreateInstanceRequest
                .latestVersionOf(scopedProcessId)
                .withBusinessKey(String.valueOf(aggregateId))
                .withVariables(variables);
        final var timeToLive = historyTimeToLiveOf(workflowModuleId, bpmnProcessId).map(ZenBpmTimeToLive::engineForm);

        final var started = createInstance(timeToLive
                .map(start::withHistoryTimeToLive)
                .orElse(start), request);
        log.debug("ZenBPM[{}]: started workflow '{}' of workflow module '{}' for aggregate '{}' as instance {}",
                adapterId, bpmnProcessId, workflowModuleId, aggregateId, started.key());

        // a start creates a root instance, so its key is the workflow's id, and the version is
        // written the way a delivered task reports the version of its process
        request.reportStartedWorkflow(String.valueOf(started.key()), String.valueOf(started.version()));
    }

    // the aggregate is loaded here, after the caller committed, because the request carries its
    // id only. One which is gone by now starts its workflow with the id alone, which is all the
    // workflow needs to be found
    private Map<String, Object> sharedValuesOf(final PhaseTwoRequest<A> request) {
        final var aggregate = request
                .aggregatePersistence()
                .loadById(request.workflowAggregateId());
        if (aggregate == null) {
            log.warn(
                    "ZenBPM[{}]: the workflow aggregate '{}' could not be loaded, so workflow '{}' of workflow module '{}' is started with its id alone and no shared value",
                    adapterId, request.workflowAggregateId(), request.bpmnProcessId(), request.workflowModuleId());
            return Map.of();
        }
        return collaborators
                .workflowAggregateSync()
                .syncedValues(aggregate, AggregateSyncMode.FULL);
    }

    private ProcessInstanceRef createInstance(
        final CreateInstanceRequest start,
        final PhaseTwoRequest<A> request
    ) {
        try {
            return factory
                    .rest()
                    .createInstance(start);
        } catch (final ZenBpmApiException refusal) {
            if (refusal.getStatus() != 404) {
                throw refusal;
            }
            // the engine's answer stays the cause, so the classification still reads it as permanent
            throw new IllegalStateException("""
                    ZenBPM adapter '%s' cannot start workflow '%s' of workflow module '%s' for aggregate '%s': \
                    the engine holds no process '%s'. Every further attempt is answered the same way, so the \
                    start is not repeated and its outbox entry is blocked. Deploy workflow module '%s' to the \
                    engine of this adapter id. The application deploys it while it boots, and a boot whose \
                    deployment failed goes on only where '%s' is 'warn'; the log of that boot says why."""
                    .formatted(adapterId, request.bpmnProcessId(), request.workflowModuleId(), request
                            .workflowAggregateId(), start.bpmnProcessId(), request.workflowModuleId(),
                            ZenBpmAdapterConfiguration.propertyKey(adapterId, "deployment-failure")), refusal);
        }
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

    /**
     * The <code>history-time-to-live</code> of the workflows of one process, resolved by the
     * platform module from the levels it is set at.
     */
    @FunctionalInterface
    public interface HistoryTimeToLive {

        /**
         * The time a start of the process sends.
         *
         * @param workflowModuleId The workflow module
         * @param bpmnProcessId The BPMN process, as the workflow module names it
         * @return The time, or <code>null</code> where no level sets it
         */
        Duration of(
            String workflowModuleId,
            String bpmnProcessId
        );

    }

}

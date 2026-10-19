/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.processservice;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration;
import org.pbinitiative.zenbpmadapter.client.ZenBpmApiException;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientFactory;
import org.pbinitiative.zenbpmadapter.client.ZenBpmErrors;
import org.pbinitiative.zenbpmadapter.client.ZenBpmRestClient;
import org.pbinitiative.zenbpmadapter.client.ZenBpmTimeToLive;
import org.pbinitiative.zenbpmadapter.client.api.CreateInstanceRequest;
import org.pbinitiative.zenbpmadapter.client.api.InstanceFilter;
import org.pbinitiative.zenbpmadapter.client.api.JobRef;
import org.pbinitiative.zenbpmadapter.client.api.ProcessDefinitionRef;
import org.pbinitiative.zenbpmadapter.client.api.ProcessInstanceRef;
import org.pbinitiative.zenbpmadapter.model.ZenBpmModelReader;

import io.vanillabp.integration.adapter.spi.AdapterCollaborators;
import io.vanillabp.integration.adapter.spi.AggregateSyncMode;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.adapter.spi.PhaseOneRequest;
import io.vanillabp.integration.adapter.spi.PhaseOperationHandler;
import io.vanillabp.integration.adapter.spi.PhaseTwoRequest;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.adapter.spi.WorkflowScope;
import io.vanillabp.integration.adapter.spi.WorkflowVisibilityDelay;
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
 * This version of the adapter starts workflows, also by a message, completes and cancels the tasks
 * the application left open, correlates messages, answers every probe, counts the open tasks of a
 * process and says what the engine does with the values a workflow carries. It serves every
 * operation the core requires of each adapter, so the core accepts it while the application
 * starts. Every other handler and every method of the viewer/history API throw: where the adapter
 * id has no clients, the message of its validation, which names the keys to set; otherwise an
 * {@link UnsupportedOperationException} which says that the call is not built yet. Nothing
 * pretends to have done the work. The operations a BPMS may lack, sending a signal and telling the
 * BPMS about a changed aggregate, are left out, so the core answers them as not supported. A probe
 * of an adapter id without clients throws the message of its validation as well.
 * <p>
 * A start asks the engine nothing in phase one, because there is nothing to ask before an
 * instance exists. Phase two starts the latest version of the process under the id the engine
 * knows it by, with the aggregate's id as the business key and as a variable beside the values the
 * aggregate shares (see {@link ZenBpmSharedValues}), and reports the key and the version of the
 * new instance. A start the engine answers with <code>404</code>, because it holds no such
 * process, is permanent, see decision 30 in the repository's DECISIONS.md.
 * <p>
 * A task the application completes or cancels is checked right before the caller's transaction
 * commits, and phase two writes the shared values into the engine before it completes the task's
 * job or fails it with the BPMN error the application named, see {@link ZenBpmTaskOutcomes}.
 * <p>
 * A message is checked against the models of the workflow module in phase one, without asking the
 * engine, and published in phase two, where a publication nothing waits for yet is handed back to
 * the outbox, see {@link ZenBpmMessageCorrelation}. A start by a message is checked the same way
 * and published without a correlation key, and the engine names no instance it started, so its
 * start marker reports the workflow, see {@link ZenBpmMessageStart}. Under
 * <code>message-start-lookup: scan</code> the probes for a workflow read the instances of the
 * processes a message starts where nothing else finds the workflow, see
 * {@link ZenBpmMessageStartScan}.
 * <p>
 * Each field has an accessor of its own and no Lombok getter on the class, see decision 13 in the
 * repository's DECISIONS.md.
 *
 * @param <A> The workflow aggregate type
 */
@Slf4j
@SuppressWarnings("LombokGetterMayBeUsed")
public class ZenBpmProcessService<A> implements MigratableProcessService<A> {

    /**
     * How often the core asks again while it waits for a workflow to become visible. Not
     * configurable: an operator may have to raise the window, not the rate.
     */
    private static final Duration VISIBILITY_PROBE_INTERVAL = Duration.ofMillis(250);

    private final String adapterId;

    private final ZenBpmClientFactory factory;

    private final AdapterCollaborators collaborators;

    private final HistoryTimeToLive historyTimeToLive;

    private final Map<PhaseOperation, PhaseOperationHandler<A>> phaseOperations;

    private final ZenBpmMessageStartScan messageStartScan;

    /**
     * Whether the last probe which asked the engine failed.
     */
    private final AtomicBoolean probesFail = new AtomicBoolean();

    /**
     * Whether the last lookup of a workflow started by a message reached
     * <code>message-start-lookup-max-instances</code>.
     */
    private final AtomicBoolean lookupsReachTheBound = new AtomicBoolean();

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
        // each replaces the handler which is not built yet, in its place
        handlers.put(PhaseOperation.START_WORKFLOW, PhaseOperationHandler.of(this::askNothingBeforeAStart,
                this::startWorkflow));
        final var tasks = new ZenBpmTaskOutcomes<A>(adapterId, factory, collaborators, this::variablesOf);
        handlers.put(PhaseOperation.COMPLETE_TASK, PhaseOperationHandler.of(tasks::checkBeforeTheCompletion,
                tasks::complete));
        handlers.put(PhaseOperation.CANCEL_TASK, PhaseOperationHandler.of(tasks::checkBeforeTheCancellation,
                tasks::cancel));
        final var messages = new ZenBpmMessageCorrelation<A>(adapterId, factory, collaborators, this::variablesOf);
        handlers.put(PhaseOperation.CORRELATE_MESSAGE, PhaseOperationHandler.of(messages::checkTheMessage,
                messages::correlate));
        final var messageStarts = new ZenBpmMessageStart<A>(adapterId, factory, collaborators, this::variablesOf);
        handlers.put(PhaseOperation.START_WORKFLOW_BY_MESSAGE, PhaseOperationHandler.of(messageStarts::checkTheStart,
                messageStarts::start));
        this.phaseOperations = Collections.unmodifiableMap(handlers);
        this.messageStartScan = new ZenBpmMessageStartScan(adapterId, factory);
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
     * also by a message, the completion and the cancellation of a task, the correlation of a
     * message, and a handler which throws for every other.
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

    /**
     * Whether the engine holds a task of the given scope: its job, read by key, and the process of
     * the instance the job waits in, compared with the scope. Asking moves nothing: both are reads.
     * <p>
     * A task id which is no positive number is no key of this engine, such as a key of another
     * BPMS of the same list, and is {@link WorkflowAwareness#UNKNOWN_TO_BPMS} without a request. So
     * is a job the engine answers <code>404</code> for, and a job of a process outside the scope. A
     * job which waits for a worker, or carries an incident and so stays open, is
     * {@link WorkflowAwareness#ACTIVE}; a completed or terminated one
     * {@link WorkflowAwareness#COMPLETED}. The engine answers <code>502</code> for a key of a
     * partition it does not have, which the core may hand over because it asks every adapter of a
     * workflow's list with the same task id. So after a <code>502</code> the engine's partitions are
     * read: a key of none of them is {@link WorkflowAwareness#UNKNOWN_TO_BPMS}, see decision 32 in
     * the repository's DECISIONS.md. Every other failure is {@link WorkflowAwareness#BPMS_UNAVAILABLE},
     * logged as the workflow probes log it.
     *
     * @param scope The workflow module and the processes asked about
     * @param workflowAggregateId The id of the aggregate, which the engine's answer does not need
     * @param taskId The key of the job
     * @return The engine's awareness of the task within the scope
     */
    @Override
    public WorkflowAwareness awarenessOfTask(
        final WorkflowScope scope,
        final Object workflowAggregateId,
        final String taskId
    ) {
        return awarenessOfJob(scope, taskId, "task");
    }

    /**
     * Whether the engine holds a user task of the given scope. A user task is a job of the engine,
     * so the probe is the one of {@link #awarenessOfTask(WorkflowScope, Object, String)}; the core
     * knows from the record of a delivery which kind a task id is.
     *
     * @param scope The workflow module and the processes asked about
     * @param workflowAggregateId The id of the aggregate, which the engine's answer does not need
     * @param taskId The key of the user task's job
     * @return The engine's awareness of the user task within the scope
     */
    @Override
    public WorkflowAwareness awarenessOfUserTask(
        final WorkflowScope scope,
        final Object workflowAggregateId,
        final String taskId
    ) {
        return awarenessOfJob(scope, taskId, "user task");
    }

    /**
     * How many tasks of a process the engine holds open, asked once while the application starts:
     * the element instances which are active now at the elements which give a worker a job (service,
     * send and business rule tasks, message throw and end events, user tasks), summed over every
     * version the engine holds under the process's id. Tasks opened before this application ran wait
     * mostly on an older version, so every version counts, and each is read with its own model.
     * <p>
     * The engine counts, the adapter adds up: one request for the list of versions, and per version
     * one for its model and one for its statistics. The platform asks before the application
     * deployed anything, so every model is read from the engine. A task with an incident is not
     * counted. Where the engine does not answer, the number is lost and nothing else, so the
     * answer is <code>null</code> and the failure a DEBUG line. An adapter id without clients has
     * no engine to ask and answers <code>null</code> the same way.
     *
     * @param workflowModuleId The workflow module
     * @param bpmnProcessId The process, as the workflow module names it
     * @return The number of open tasks, <code>0</code> where the engine holds no version of the
     *         process, <code>null</code> where it could not be asked
     */
    @Override
    public Long openTaskCount(
        final String workflowModuleId,
        final String bpmnProcessId
    ) {
        final var scopedProcessId = collaborators
                .scoping()
                .scopedProcessId(workflowModuleId, bpmnProcessId, adapterId);
        try {
            final var rest = factory.rest();
            var open = 0L;
            for (final var version : rest.processDefinitions(scopedProcessId, false)) {
                final var jobElements = jobElementsOf(workflowModuleId, version, rest);
                open += rest
                        .processDefinitionStatistics(version.key())
                        .elements()
                        .entrySet()
                        .stream()
                        .filter(element -> jobElements.contains(element.getKey()))
                        .mapToLong(element -> element
                                .getValue()
                                .active())
                        .sum();
            }
            return open;
        } catch (final RuntimeException failure) {
            log.debug(
                    "ZenBPM[{}]: the engine did not say how many tasks of process '{}' of workflow module '{}' are open",
                    adapterId, bpmnProcessId, workflowModuleId, failure);
            return null;
        }
    }

    /**
     * Whether the engine holds the workflow of an aggregate in the given scope, where VanillaBP
     * holds no id of the workflow: the search of
     * {@link #awarenessOfWorkflow(WorkflowScope, AggregatePersistenceAware, Object, String)}.
     *
     * @param scope The workflow module and the processes asked about
     * @param aggregatePersistence The persistence of the aggregate
     * @param workflowAggregateId The id of the aggregate
     * @return {@link WorkflowAwareness#ACTIVE} where a workflow of the scope runs,
     *         {@link WorkflowAwareness#COMPLETED} where every one of them ended,
     *         {@link WorkflowAwareness#UNKNOWN_TO_BPMS} where the engine holds none, and
     *         {@link WorkflowAwareness#BPMS_UNAVAILABLE} where it could not be asked
     */
    @Override
    public WorkflowAwareness awarenessOfWorkflow(
        final WorkflowScope scope,
        final AggregatePersistenceAware<A> aggregatePersistence,
        final Object workflowAggregateId
    ) {
        return awarenessOfWorkflow(scope, aggregatePersistence, workflowAggregateId, null);
    }

    /**
     * Whether the engine holds the workflow of an aggregate in the given scope.
     * <p>
     * Where VanillaBP holds the workflow's id, the engine is asked for that instance first, because
     * it answers by key at once while a node which lags behind may not find a young workflow by
     * search yet. The key may only make a YES shorter: an instance which runs, of a process of the
     * scope, whose business key or id variable is the aggregate's id, is
     * {@link WorkflowAwareness#ACTIVE}. Everything else the key leads to falls through to the
     * search and is never the answer: another state, because an aggregate may carry a second
     * workflow once its first ended and the key may name the first; another process or another
     * aggregate, because the core hands the same key to every adapter it asks; a <code>404</code>,
     * which cannot tell an ended workflow from one the engine never held; a <code>502</code>, which
     * the engine answers for a key of a partition it does not have; and any failure. A workflow the
     * engine started has no business key, so the key is the only way to find it, see decision 25
     * in the repository's DECISIONS.md.
     * <p>
     * The search asks for the instances of the aggregate's business key once per process of the
     * scope, and asks no more once one of them runs. One which runs or carries an incident is
     * {@link WorkflowAwareness#ACTIVE}; where all of them ended, completed or terminated, the
     * answer is {@link WorkflowAwareness#COMPLETED}. So no probe sends more requests than the scope
     * has processes and one, except where an aggregate has more instances of one process than a
     * page holds and none of those read runs: then the engine counts the running ones in two more.
     * A search which fails is {@link WorkflowAwareness#BPMS_UNAVAILABLE}, never
     * {@link WorkflowAwareness#UNKNOWN_TO_BPMS}; the first failure after an answer is logged at
     * WARN, the next ones at DEBUG until the engine answers a probe again.
     * <p>
     * Under <code>message-start-lookup: scan</code>, where the search finds no workflow which
     * runs, the instances which run of the processes of the scope a message starts are read, and
     * one whose id variable names the aggregate is {@link WorkflowAwareness#ACTIVE}, see
     * {@link ZenBpmMessageStartScan}. A lookup which would read more than
     * <code>message-start-lookup-max-instances</code> is {@link WorkflowAwareness#BPMS_UNAVAILABLE},
     * logged at WARN once until a lookup stays within the bound again.
     *
     * @param scope The workflow module and the processes asked about
     * @param aggregatePersistence The persistence of the aggregate, which names its id variable
     * @param workflowAggregateId The id of the aggregate
     * @param workflowId The key of the workflow's root instance VanillaBP holds, <code>null</code>
     *            where it holds none
     * @return {@link WorkflowAwareness#ACTIVE} where a workflow of the scope runs,
     *         {@link WorkflowAwareness#COMPLETED} where every one of them ended,
     *         {@link WorkflowAwareness#UNKNOWN_TO_BPMS} where the engine holds none, and
     *         {@link WorkflowAwareness#BPMS_UNAVAILABLE} where it could not be asked
     */
    @Override
    public WorkflowAwareness awarenessOfWorkflow(
        final WorkflowScope scope,
        final AggregatePersistenceAware<A> aggregatePersistence,
        final Object workflowAggregateId,
        final String workflowId
    ) {
        final var rest = clients();
        final var scopedIds = scopedIdsOf(scope);
        final var aggregateId = String.valueOf(workflowAggregateId);
        if (theEngineRunsTheWorkflowOfTheKey(rest, scopedIds, aggregatePersistence, aggregateId, workflowId)) {
            probeAnswered();
            return WorkflowAwareness.ACTIVE;
        }
        final var subject = "the workflow of aggregate '%s'".formatted(aggregateId);
        try {
            final var found = withTheLookupOfAMessageStart(awarenessByBusinessKey(rest, scopedIds, aggregateId,
                    null), rest, scope, scopedIds, aggregatePersistence, aggregateId, null);
            probeAnswered();
            return found;
        } catch (final ZenBpmMessageStartScan.BoundReached bound) {
            return boundReached(subject, bound);
        } catch (final RuntimeException failure) {
            return unavailable(subject, failure);
        }
    }

    /**
     * Whether the engine holds a workflow of an aggregate in the given scope, before a start is
     * sent again, counting every workflow of the aggregate: the probe of
     * {@link #awarenessOfWorkflowForRedispatch(WorkflowScope, AggregatePersistenceAware, Object, Instant)}
     * for an entry which does not say when it was planned.
     *
     * @param scope The workflow module and the processes asked about
     * @param aggregatePersistence The persistence of the aggregate
     * @param workflowAggregateId The id of the aggregate
     * @return {@link WorkflowAwareness#ACTIVE} or {@link WorkflowAwareness#COMPLETED} where the
     *         engine holds a workflow of the scope, {@link WorkflowAwareness#UNKNOWN_TO_BPMS} where
     *         it holds none, and {@link WorkflowAwareness#BPMS_UNAVAILABLE} where it could not be
     *         asked
     */
    @Override
    public WorkflowAwareness awarenessOfWorkflowForRedispatch(
        final WorkflowScope scope,
        final AggregatePersistenceAware<A> aggregatePersistence,
        final Object workflowAggregateId
    ) {
        return awarenessOfWorkflowForRedispatch(scope, aggregatePersistence, workflowAggregateId, null);
    }

    /**
     * Whether the engine holds a workflow of an aggregate in the given scope which started at or
     * after the moment its start was planned, asked before that start is sent again. An answer
     * other than {@link WorkflowAwareness#UNKNOWN_TO_BPMS} skips the start, so this probe is never
     * optimistic: it asks only the search, whose failure is
     * {@link WorkflowAwareness#BPMS_UNAVAILABLE}, and never the key of a workflow.
     * <p>
     * An aggregate may carry a second workflow once its first ended, so only an instance created at
     * or after <code>plannedAt</code> counts, ended ones included. The engine compares whole
     * milliseconds of its own clock; a moment inside a millisecond is moved to the next one, so an
     * instance created earlier in that millisecond does not count. That costs a second workflow at
     * worst, which at-least-once delivery allows, and never a lost one. For the same reason the
     * moment is not moved back for a clock which is behind. A workflow started by a message has no
     * business key and is not found by the search. Under <code>message-start-lookup: scan</code>,
     * where the search finds nothing, the instances of the processes of the scope a message starts
     * are read: every one created from <code>plannedAt</code>, ended ones included, or those which
     * run where <code>plannedAt</code> is <code>null</code>; one whose id variable names the
     * aggregate counts as the search's would, see {@link ZenBpmMessageStartScan}.
     *
     * @param scope The workflow module and the processes asked about
     * @param aggregatePersistence The persistence of the aggregate
     * @param workflowAggregateId The id of the aggregate
     * @param plannedAt When the start was planned, <code>null</code> where nobody knows: then
     *            every workflow of the aggregate counts
     * @return {@link WorkflowAwareness#ACTIVE} or {@link WorkflowAwareness#COMPLETED} where the
     *         engine holds such a workflow, {@link WorkflowAwareness#UNKNOWN_TO_BPMS} where it holds
     *         none, and {@link WorkflowAwareness#BPMS_UNAVAILABLE} where it could not be asked
     */
    @Override
    public WorkflowAwareness awarenessOfWorkflowForRedispatch(
        final WorkflowScope scope,
        final AggregatePersistenceAware<A> aggregatePersistence,
        final Object workflowAggregateId,
        final Instant plannedAt
    ) {
        final var rest = clients();
        final var scopedIds = scopedIdsOf(scope);
        final var aggregateId = String.valueOf(workflowAggregateId);
        final var subject = "the workflow of aggregate '%s' before its start is sent again".formatted(aggregateId);
        final var createdFrom = createdFromOf(plannedAt);
        try {
            final var found = withTheLookupOfAMessageStart(awarenessByBusinessKey(rest, scopedIds, aggregateId,
                    createdFrom), rest, scope, scopedIds, aggregatePersistence, aggregateId, createdFrom);
            probeAnswered();
            return found;
        } catch (final ZenBpmMessageStartScan.BoundReached bound) {
            return boundReached(subject, bound);
        } catch (final RuntimeException failure) {
            return unavailable(subject, failure);
        }
    }

    /**
     * The probe asks the engine, so its answer is evidence and not a guess.
     *
     * @return <code>true</code>
     */
    @Override
    public boolean canLocateWorkflows() {
        return true;
    }

    /**
     * How long a workflow may stay invisible to the search: <code>workflow-visibility-timeout</code>,
     * asked again every quarter of a second. The engine answers a read from the database of the
     * node it reaches without waiting for the partition's leader, so a node which lags behind may
     * not find a workflow which was just started. The window asked for one workflow is the same,
     * because a <code>404</code> for a key the engine just answered may come from such a node as
     * well.
     *
     * @return The window, none where the timeout is zero
     */
    @Override
    public WorkflowVisibilityDelay workflowVisibilityDelay() {
        return new WorkflowVisibilityDelay(factory
                .configuration()
                .getWorkflowVisibilityTimeout(), VISIBILITY_PROBE_INTERVAL);
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
        final var start = CreateInstanceRequest
                .latestVersionOf(scopedProcessId)
                .withBusinessKey(String.valueOf(aggregateId))
                .withVariables(variablesOf(request));
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

    // what a command of phase two sends about the workflow: the values the aggregate shares, and
    // its id
    private Map<String, Object> variablesOf(final PhaseTwoRequest<A> request) {
        return ZenBpmSharedValues.forCommand(request
                .aggregatePersistence()
                .getAggregateIdName(), request.workflowAggregateId(), sharedValuesOf(request));
    }

    // the aggregate is loaded here, after the caller committed, because the request carries its
    // id only. One which is gone by now sends the id alone, which is all a workflow needs to be
    // found
    private Map<String, Object> sharedValuesOf(final PhaseTwoRequest<A> request) {
        final var aggregate = request
                .aggregatePersistence()
                .loadById(request.workflowAggregateId());
        if (aggregate == null) {
            log.warn(
                    "ZenBPM[{}]: the workflow aggregate '{}' could not be loaded, so what is sent about workflow '{}' of workflow module '{}' carries its id alone and no shared value",
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

    private WorkflowAwareness awarenessOfJob(
        final WorkflowScope scope,
        final String taskId,
        final String kind
    ) {
        final var rest = clients();
        final var key = ZenBpmKeys.keyOf(taskId);
        if (key.isEmpty()) {
            log.debug("ZenBPM[{}]: {} '{}' is no key of this engine, so the engine does not know it", adapterId, kind,
                    taskId);
            return WorkflowAwareness.UNKNOWN_TO_BPMS;
        }
        final var subject = "%s '%s'".formatted(kind, taskId);
        final Optional<JobRef> job;
        try {
            job = rest.job(key.getAsLong());
        } catch (final RuntimeException failure) {
            if (ZenBpmKeys.isAKeyOfAPartitionTheEngineDoesNotHave(rest, key.getAsLong(), failure)) {
                probeAnswered();
                log.debug("ZenBPM[{}]: {} names partition {}, which the engine does not have, so it does not know it",
                        adapterId, subject, ZenBpmKeys.partitionOf(key.getAsLong()));
                return WorkflowAwareness.UNKNOWN_TO_BPMS;
            }
            return unavailable(subject, failure);
        }
        try {
            if (job.isEmpty()) {
                probeAnswered();
                return WorkflowAwareness.UNKNOWN_TO_BPMS;
            }
            final var instanceKey = job
                    .get()
                    .instanceKey();
            final var facts = factory
                    .instances()
                    .facts(instanceKey);
            if (facts.isEmpty()) {
                // the job names an instance the node asked does not hold yet, so whose task it is
                // cannot be said now
                throw new IllegalStateException("the engine holds the job, but not its process instance %d"
                        .formatted(instanceKey));
            }
            probeAnswered();
            if (!scopedIdsOf(scope).contains(facts
                    .get()
                    .scopedProcessId())) {
                return WorkflowAwareness.UNKNOWN_TO_BPMS;
            }
            return switch (job
                    .get()
                    .state()) {
                case ACTIVE, FAILED -> WorkflowAwareness.ACTIVE;
                case COMPLETED, TERMINATED -> WorkflowAwareness.COMPLETED;
            };
        } catch (final RuntimeException failure) {
            return unavailable(subject, failure);
        }
    }

    private static Set<String> jobElementsOf(
        final String workflowModuleId,
        final ProcessDefinitionRef version,
        final ZenBpmRestClient rest
    ) {
        final var bpmn = rest
                .processDefinition(version.key())
                .bpmnData();
        final var jobElements = new LinkedHashSet<String>();
        ZenBpmModelReader
                .read(workflowModuleId, "version %d of process '%s'".formatted(version.version(), version
                        .bpmnProcessId()), new ByteArrayInputStream(bpmn.getBytes(StandardCharsets.UTF_8)))
                .forEach(model -> Stream
                        .concat(model
                                .tasks()
                                .stream(),
                                model
                                        .userTasks()
                                        .stream())
                        .forEach(task -> jobElements.add(task.activityId())));
        return jobElements;
    }

    // the key only shortens a YES, so whatever else it leads to, a failure included, is left to
    // the search
    private boolean theEngineRunsTheWorkflowOfTheKey(
        final ZenBpmRestClient rest,
        final Set<String> scopedIds,
        final AggregatePersistenceAware<A> aggregatePersistence,
        final String aggregateId,
        final String workflowId
    ) {
        final var key = ZenBpmKeys.keyOf(workflowId);
        if (key.isEmpty()) {
            return false;
        }
        try {
            final var idVariableName = aggregatePersistence.getAggregateIdName();
            return rest
                    .instance(key.getAsLong())
                    .filter(instance -> isRunning(instance.state()))
                    .filter(instance -> scopedIds.contains(instance.bpmnProcessId()))
                    .filter(instance -> isTheWorkflowOf(aggregateId, idVariableName, instance))
                    .isPresent();
        } catch (final RuntimeException failure) {
            log.debug(
                    "ZenBPM[{}]: the engine could not be asked for workflow '{}' of aggregate '{}', so the search answers",
                    adapterId, workflowId, aggregateId, failure);
            return false;
        }
    }

    private WorkflowAwareness awarenessByBusinessKey(
        final ZenBpmRestClient rest,
        final Set<String> scopedIds,
        final String aggregateId,
        final Instant createdFrom
    ) {
        var answer = WorkflowAwareness.UNKNOWN_TO_BPMS;
        for (final var scopedId : scopedIds) {
            final var found = awarenessAmong(rest, InstanceFilter
                    .anyInstance()
                    .withBusinessKey(aggregateId)
                    .withBpmnProcessId(scopedId)
                    .withCreatedFrom(createdFrom));
            if (found == WorkflowAwareness.ACTIVE) {
                return found;
            }
            if (found == WorkflowAwareness.COMPLETED) {
                answer = found;
            }
        }
        return answer;
    }

    // a workflow the engine started by a message has no business key, so under the scan its
    // instances are read where the search found none which runs
    private WorkflowAwareness withTheLookupOfAMessageStart(
        final WorkflowAwareness found,
        final ZenBpmRestClient rest,
        final WorkflowScope scope,
        final Set<String> scopedIds,
        final AggregatePersistenceAware<A> aggregatePersistence,
        final String aggregateId,
        final Instant createdFrom
    ) {
        if (found == WorkflowAwareness.ACTIVE) {
            return found;
        }
        final var toScan = messageStartScan.processesToScan(scope, scopedIds);
        if (toScan.isEmpty()) {
            return found;
        }
        final var scanned = messageStartScan.find(rest, toScan, aggregatePersistence.getAggregateIdName(),
                aggregateId, createdFrom);
        lookupsReachTheBound.set(false);
        return scanned == WorkflowAwareness.UNKNOWN_TO_BPMS ? found : scanned;
    }

    // a bound which is too low fails every lookup of a large process, so its first WARN is worth
    // it and the others are not, until a lookup stays within the bound again
    private WorkflowAwareness boundReached(
        final String subject,
        final ZenBpmMessageStartScan.BoundReached bound
    ) {
        probeAnswered();
        if (lookupsReachTheBound.compareAndSet(false, true)) {
            log.warn(
                    "ZenBPM[{}]: the lookup of {} among the workflows started by a message reports BPMS_UNAVAILABLE: {}; further lookups which reach the bound are logged at DEBUG until one stays within it",
                    adapterId, subject, bound.getMessage());
        } else {
            log.debug(
                    "ZenBPM[{}]: the lookup of {} among the workflows started by a message reports BPMS_UNAVAILABLE: {}",
                    adapterId, subject, bound.getMessage());
        }
        return WorkflowAwareness.BPMS_UNAVAILABLE;
    }

    // one page holds every instance of an aggregate but in a case nobody plans for; where it does
    // not and none of those read runs, the engine counts the running ones
    private static WorkflowAwareness awarenessAmong(
        final ZenBpmRestClient rest,
        final InstanceFilter filter
    ) {
        final var page = rest.instances(filter);
        if (page.totalCount() == 0) {
            return WorkflowAwareness.UNKNOWN_TO_BPMS;
        }
        if (page
                .items()
                .stream()
                .anyMatch(instance -> isRunning(instance.state()))) {
            return WorkflowAwareness.ACTIVE;
        }
        if (page.last()) {
            return WorkflowAwareness.COMPLETED;
        }
        final var active = rest
                .instances(filter
                        .withState(ProcessInstanceRef.State.ACTIVE)
                        .withSize(1))
                .totalCount();
        final var failed = rest
                .instances(filter
                        .withState(ProcessInstanceRef.State.FAILED)
                        .withSize(1))
                .totalCount();
        return (active + failed) > 0 ? WorkflowAwareness.ACTIVE : WorkflowAwareness.COMPLETED;
    }

    // an instance with an incident can still be acted on: an operator resolves it, or it is
    // cancelled
    private static boolean isRunning(final ProcessInstanceRef.State state) {
        return (state == ProcessInstanceRef.State.ACTIVE) || (state == ProcessInstanceRef.State.FAILED);
    }

    private static boolean isTheWorkflowOf(
        final String aggregateId,
        final String idVariableName,
        final ProcessInstanceRef instance
    ) {
        if (aggregateId.equals(instance.businessKey())) {
            return true;
        }
        final var idVariable = instance
                .variables()
                .get(idVariableName);
        return (idVariable != null) && aggregateId.equals(String.valueOf(idVariable));
    }

    // the engine reads whole milliseconds, so a moment inside one is moved to the next
    private static Instant createdFromOf(final Instant plannedAt) {
        if (plannedAt == null) {
            return null;
        }
        final var millisecond = plannedAt.truncatedTo(ChronoUnit.MILLIS);
        return millisecond.equals(plannedAt) ? plannedAt : millisecond.plusMillis(1);
    }

    private Set<String> scopedIdsOf(final WorkflowScope scope) {
        final var scopedIds = new LinkedHashSet<String>();
        scope
                .bpmnProcessIds()
                .forEach(bpmnProcessId -> scopedIds.add(collaborators
                        .scoping()
                        .scopedProcessId(scope.workflowModuleId(), bpmnProcessId, adapterId)));
        return scopedIds;
    }

    private void probeAnswered() {
        probesFail.set(false);
    }

    // an engine which is away fails every probe, so its first failure is worth a WARN and the
    // others are not, until the engine answered again
    private WorkflowAwareness unavailable(
        final String subject,
        final RuntimeException failure
    ) {
        if (probesFail.compareAndSet(false, true)) {
            log.warn(
                    "ZenBPM[{}]: the engine could not be asked for {}, so the probe reports BPMS_UNAVAILABLE; check that the engine at {} answers within {}; further failures are logged at DEBUG until the engine answers a probe again",
                    adapterId, subject, ZenBpmAdapterConfiguration.propertyKey(adapterId, "rest-address"),
                    ZenBpmAdapterConfiguration.propertyKey(adapterId, "request-timeout"), failure);
        } else {
            log.debug("ZenBPM[{}]: the engine could not be asked for {}, so the probe reports BPMS_UNAVAILABLE",
                    adapterId, subject, failure);
        }
        return WorkflowAwareness.BPMS_UNAVAILABLE;
    }

    // an adapter id without clients answers with the message of its validation, which names the
    // keys to set
    private ZenBpmRestClient clients() {
        final var unconfigured = factory.unconfiguredMessage();
        if (unconfigured.isPresent()) {
            throw new IllegalStateException(unconfigured.get());
        }
        return factory.rest();
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

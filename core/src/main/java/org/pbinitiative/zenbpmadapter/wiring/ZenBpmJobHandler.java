/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.wiring;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.pbinitiative.zenbpmadapter.ZenBpmDeployedProcesses;
import org.pbinitiative.zenbpmadapter.ZenBpmInstanceCache;
import org.pbinitiative.zenbpmadapter.ZenBpmInstanceCache.InstanceFacts;
import org.pbinitiative.zenbpmadapter.ZenBpmProcessingContext;
import org.pbinitiative.zenbpmadapter.ZenBpmProcessingContext.WiredProcess;
import org.pbinitiative.zenbpmadapter.client.ReceivedJob;
import org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration;
import org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration.AsyncTaskMaxAgeAction;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientFactory;
import org.pbinitiative.zenbpmadapter.client.ZenBpmCommandRetry;
import org.pbinitiative.zenbpmadapter.client.ZenBpmErrors;
import org.pbinitiative.zenbpmadapter.client.ZenBpmJobStream;
import org.pbinitiative.zenbpmadapter.client.ZenBpmRestClient;
import org.pbinitiative.zenbpmadapter.client.api.JobFailure;
import org.pbinitiative.zenbpmadapter.client.api.JobRef;
import org.pbinitiative.zenbpmadapter.processservice.ZenBpmSharedValues;

import io.vanillabp.integration.adapter.spi.AdapterCollaborators;
import io.vanillabp.integration.adapter.spi.AggregateSyncMode;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;
import io.vanillabp.integration.adapter.spi.workflowtask.DeliveryOfAnUnknownWorkflowException;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskKind;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;
import lombok.extern.slf4j.Slf4j;

/**
 * Runs the jobs of one workflow module: builds the invocation context of a job, hands it to the
 * platform's core, which runs the <code>&#64;WorkflowTask</code> method in a transaction of its own,
 * and reports the outcome to the engine. The {@link ZenBpmJobDispatcher} of the adapter id hands
 * it a job together with the facts of the job's instance, on a handler thread, under a running
 * {@link ZenBpmLockRenewal}.
 * <p>
 * Delivery is at least once, so the order is the one which converges: load, invoke, save and
 * commit inside the core, then the engine. A crash after the commit lets the engine hand the job
 * out again, and the core answers that delivery from its record.
 * <ul>
 * <li><b>The aggregate.</b> It is named by the variable which carries the aggregate's id, else by
 * the business key, and the business key is reported to the core beside it, which refuses a
 * delivery whose two disagree. An instance of an embedded sub-process carries both from its parent;
 * one which names neither is read in the workflow's root instance. An instance which names no
 * aggregate at all was started past VanillaBP, and its job is failed without retries, so an
 * incident shows it to an operator, see decision 33 in the repository's DECISIONS.md.</li>
 * <li><b>A workflow this application does not own.</b> The core refuses the delivery with
 * {@link DeliveryOfAnUnknownWorkflowException}; the job is failed without retries and with the
 * core's message, so the incident carries the explanation where an operator reads it.</li>
 * <li><b>A completed task.</b> The values the aggregate shares now, and its id, are written into
 * the job's instance and every instance above it up to the workflow's root, and the job is
 * completed with them. A sub-process holds a copy of the variables of the instance above it, taken
 * when it started, and what is written into it is gone when it ends, so a gateway inside the
 * sub-process and one behind it see the new values only that way, see decision 35 in the
 * repository's DECISIONS.md. An update which finds the workflow ended, and a completion which finds
 * the job gone, are what at-least-once delivery leaves over: logged, and nothing else.</li>
 * <li><b>A BPMN error.</b> The values are written as for a completed task, and the job is failed
 * with the error code as the model carries it, scoped, with the same values, the delivery's token
 * and the job stream's client id: the failure releases the stream's lock, and a repeat after a lost
 * answer changes nothing. An error boundary event or an error event sub-process of the model takes
 * over; where none catches the code, the engine raises an incident, which a WARN names where the
 * job read right after shows it. A failure with an error code neither reads nor spends the job's
 * retries.</li>
 * <li><b>A task which stays open</b> for its asynchronous completion. The job is parked: the
 * renewal of its lock ends, and the lock is extended once by <code>async-task-lock-renewal</code>.
 * When that lapses, the engine hands the job out again, the core answers from the record of this
 * delivery that the task is still open, and the job is parked again: the engine's redelivery
 * drives the renewal, and no timer of the adapter's does. Where the task waited longer than the
 * platform's maximum age and <code>async-task-max-age-action</code> is <code>incident</code>, the
 * job is failed without retries instead, so an incident names the aggregate and the age.</li>
 * <li><b>A handler which threw.</b> The core rolled its transaction back, and the job is failed
 * without an error code, so the engine spends one of its retries: while retries remain it hands the
 * job out again after the backoff, to whichever node has a slot, and at the last one it raises an
 * incident naming the attempts and the message. The failure carries the exception's type and
 * message, the configured <code>retry-backoff</code> where a level sets one, the delivery's token,
 * so a repeat after a lost answer counts once, and the job stream's client id, which releases the
 * lock for the backoff, see decision 27 in the repository's DECISIONS.md. How many retries a job
 * has is the model's, else the engine's default, see decision 38 in the repository's
 * DECISIONS.md.</li>
 * </ul>
 * <b>The tasks the engine took away.</b> The engine tells nobody about a job it ended, as an
 * interrupting boundary event or a cancelled instance does. So once the outcome of a delivery
 * reached the engine (a completion, a BPMN error, a parked task), the core is handed a
 * {@link ZenBpmOpenTaskProbe} of the job's process, with which it asks about the other tasks it
 * believes open in the same workflow and reports the ones which are gone as cancelled. Not after a
 * delivery the handler refused, nor after one whose outcome did not reach the engine.
 * Every command goes through a {@link ZenBpmCommandRetry}, bounded by the job's lock and stopped at
 * once while the module shuts down. A command which fails all the same leaves the job to its lock.
 * <p>
 * <b>The shutdown.</b> Every handler enters the module's {@link ZenBpmDrain} with its job and leaves
 * it when it returns, so the module's stop can wait for it. While the module stops, a job which
 * arrives is not started, an outcome a handler reached is still reported, and a handler which threw
 * is not failed at the engine, so its job keeps its retries.
 */
@Slf4j
public final class ZenBpmJobHandler {

    private final String adapterId;

    private final String workflowModuleId;

    /**
     * The claimed processes of the module, by the ids the engine knows them under.
     */
    private final Map<String, WiredProcess> processes = new LinkedHashMap<>();

    private final Map<String, Duration> jobTimeouts;

    private final WorkflowTaskInvoker invoker;

    private final NameClashAvoidanceSupport scoping;

    private final ZenBpmInstanceCache instances;

    private final ZenBpmRestClient rest;

    private final ZenBpmJobStream stream;

    private final ZenBpmDeployedProcesses deployedProcesses;

    private final String clientId;

    private final Duration asyncTaskLockRenewal;

    private final AsyncTaskMaxAgeAction asyncTaskMaxAgeAction;

    private final ZenBpmRetryBackoffResolver retryBackoffs;

    /**
     * What the core asks about the other open tasks of a workflow, by plain process id.
     */
    private final Map<String, ZenBpmOpenTaskProbe> probes = new LinkedHashMap<>();

    /**
     * The name of the variable of the aggregate's id, by plain process id, as the core answered it.
     */
    private final Map<String, String> idVariableNames = new ConcurrentHashMap<>();

    private final ZenBpmDrain drain;

    /**
     * The handler of a workflow module whose processing starts.
     *
     * @param adapterId The adapter id
     * @param factory The clients of the adapter id
     * @param collaborators What the platform hands to the adapter id
     * @param context What the deployment of the module collected
     * @param retryBackoffs The <code>retry-backoff</code> of each task, resolved over the four
     *            levels of the configuration
     */
    public ZenBpmJobHandler(
        final String adapterId,
        final ZenBpmClientFactory factory,
        final AdapterCollaborators collaborators,
        final ZenBpmProcessingContext context,
        final ZenBpmRetryBackoffResolver retryBackoffs
    ) {
        this.adapterId = adapterId;
        this.workflowModuleId = context.workflowModuleId();
        context
                .wiredProcesses()
                .values()
                .forEach(process -> processes.put(process.scopedBpmnProcessId(), process));
        this.jobTimeouts = context.jobTimeouts();
        this.invoker = collaborators.workflowTaskInvoker();
        this.scoping = collaborators.scoping();
        this.instances = factory.instances();
        this.rest = factory.rest();
        this.stream = factory.stream();
        this.deployedProcesses = factory.deployedProcesses();
        this.clientId = factory.clientId();
        this.asyncTaskLockRenewal = factory
                .configuration()
                .getAsyncTaskLockRenewal();
        this.asyncTaskMaxAgeAction = factory
                .configuration()
                .asyncTaskMaxAgeActionInUse();
        this.retryBackoffs = retryBackoffs;
        this.drain = new ZenBpmDrain(adapterId, workflowModuleId);
        // the core names a task by the definition it was reported under, which is the plain one
        processes
                .values()
                .forEach(process -> probes.put(process.bpmnProcessId(),
                        new ZenBpmOpenTaskProbe(adapterId, rest, instances, process
                                .jobTypes()
                                .stream()
                                .map(jobType -> scoping.plainTaskDefinition(workflowModuleId, process.bpmnProcessId(),
                                        jobType, adapterId))
                                .collect(Collectors.toSet()))));
    }

    /**
     * The workflow module whose jobs this handler runs.
     *
     * @return The workflow module id
     */
    public String workflowModuleId() {
        return workflowModuleId;
    }

    /**
     * The job types the module subscribes, each with the <code>job-timeout</code> it is subscribed
     * with.
     *
     * @return The durations by job type, as the engine knows the types
     */
    public Map<String, Duration> jobTimeouts() {
        return jobTimeouts;
    }

    /**
     * The claimed process of the module a job belongs to, where the module serves the job.
     *
     * @param scopedProcessId The process of the job's instance, as the engine knows it
     * @param jobType The job's type
     * @return The process, empty where the module holds no such process or serves no such job type
     *         in it
     */
    public Optional<WiredProcess> processServing(
        final String scopedProcessId,
        final String jobType
    ) {
        return Optional
                .ofNullable(processes.get(scopedProcessId))
                .filter(process -> process
                        .jobTypes()
                        .contains(jobType));
    }

    /**
     * The name of the variable which carries the aggregate's id in the instances of a process.
     *
     * @param scopedProcessId The process, as the engine knows it
     * @return The name; <code>null</code> where the module holds no such process
     */
    public String idVariableNameOf(final String scopedProcessId) {
        final var process = processes.get(scopedProcessId);
        if (process == null) {
            return null;
        }
        return idVariableNames.computeIfAbsent(process.bpmnProcessId(), plainProcessId -> invoker
                .resolveWorkflowAggregateIdName(workflowModuleId, plainProcessId));
    }

    /**
     * Marks the module as shutting down: a command which fails is not repeated any more, so the
     * shutdown is not held up by it, a job which arrives now is not started, and a handler which
     * throws from now on is not failed at the engine.
     */
    public void stopping() {
        drain.stopping();
    }

    /**
     * Whether the module shuts down.
     *
     * @return <code>true</code> once {@link #stopping()} was called
     */
    public boolean isStopping() {
        return drain.isStopping();
    }

    /**
     * What the module's handlers run, which its shutdown waits for.
     *
     * @return The drain of the module
     */
    public ZenBpmDrain drain() {
        return drain;
    }

    /**
     * Runs one job, on a handler thread.
     *
     * @param received The job as the job stream received it
     * @param facts The facts of the job's instance, with the variable of the aggregate's id where
     *            the instance has it
     * @param process The claimed process the job belongs to
     * @param renewal The renewal of the job's lock, which tells how long the lock lasts
     */
    public void handle(
        final ReceivedJob received,
        final InstanceFacts facts,
        final WiredProcess process,
        final ZenBpmLockRenewal.Renewal renewal
    ) {
        final var job = received.job();
        final var plainProcessId = process.bpmnProcessId();
        final var taskDefinition = scoping.plainTaskDefinition(workflowModuleId, plainProcessId, job.getType(),
                adapterId);
        final var delivery = new Delivery(job.getKey(), taskDefinition, plainProcessId, new ZenBpmCommandRetry(
                adapterId, job.getKey(), taskDefinition, renewal::lockEnd, this::isStopping));
        // entered before the module's state is read, and the stop sets the state before it looks
        // at what entered: so either the stop waits for this handler or this handler sees the stop
        drain.enter(job.getKey(), taskDefinition);
        try {
            if (drain.isStopping()) {
                log.info("""
                        ZenBPM[{}]: job {} (task '{}') arrived while workflow module '{}' shuts down, so it is not \
                        started; the closing job stream releases its lock""", adapterId, job.getKey(), taskDefinition,
                        workflowModuleId);
                return;
            }
            run(received, facts, delivery, renewal);
        } catch (final RuntimeException e) {
            // what reaches here failed on its way to the engine after its repetitions; the
            // invocation itself is answered in run
            if (drain.isStopping()) {
                log.info(
                        "ZenBPM[{}]: job {} (task '{}') could not be answered while workflow module '{}' shuts down; it is left to its lock: {}",
                        adapterId, job.getKey(), taskDefinition, workflowModuleId, e.getMessage());
                return;
            }
            log.warn(
                    "ZenBPM[{}]: job {} (task '{}') could not be answered to the engine; it is left to its lock, after which the engine hands it out again",
                    adapterId, job.getKey(), taskDefinition, e);
        } finally {
            drain.leave(job.getKey());
        }
    }

    private void run(
        final ReceivedJob received,
        final InstanceFacts facts,
        final Delivery delivery,
        final ZenBpmLockRenewal.Renewal renewal
    ) {
        final var job = received.job();
        final var idVariableName = idVariableNameOf(facts.scopedProcessId());
        final var path = delivery.retry().read("walk up to the workflow", () -> instances.instancesUpToTheWorkflow(
                facts.key()), Optional::isEmpty);
        if (path.isEmpty()) {
            log.info("""
                    ZenBPM[{}]: an instance above instance {} of job {} (task '{}') is not known to the engine node \
                    asked, which may lag behind; the job is left to its lock, after which the engine hands it out \
                    again""", adapterId, facts.key(), job.getKey(), delivery.taskDefinition());
            return;
        }
        final var workflowId = path
                .get()
                .getLast();
        final var named = aggregateNamedIn(facts, idVariableName, workflowId, delivery);
        if (named.isEmpty()) {
            failWithoutAnAggregate(received, facts, idVariableName, delivery);
            return;
        }
        final var aggregate = named.get();
        renewal.describeAggregate(aggregate.id());
        drain.describe(job.getKey(), aggregate.id());
        final var context = new Invocation(adapterId, received, delivery.taskDefinition(), aggregate, workflowId, facts
                .version(), deployedProcesses
                        .versionOf(workflowModuleId, delivery.plainProcessId())
                        .map(deployed -> facts.version() < deployed)
                        .orElse(false));
        final WorkflowTaskOutcome outcome;
        try {
            outcome = invoker.invokeWorkflowTask(workflowModuleId, delivery.plainProcessId(), context);
        } catch (final DeliveryOfAnUnknownWorkflowException e) {
            failAsAWorkflowNobodyOwnsHere(received, e, delivery);
            return;
        } catch (final RuntimeException e) {
            failAThrownHandler(received, aggregate, e, delivery);
            return;
        }
        final var reachedTheEngine = switch (outcome.kind()) {
            case COMPLETED -> complete(job.getKey(), path.get(), idVariableName, aggregate, delivery);
            case BPMN_ERROR -> throwBpmnError(received, path.get(), idVariableName, aggregate, outcome, delivery);
            case COMPLETION_PENDING -> keepOpen(received, aggregate, outcome, delivery, renewal);
        };
        if (reachedTheEngine) {
            reportTheTasksTheEngineNoLongerHas(delivery, context);
        }
    }

    // the id variable first: it is what VanillaBP reads the aggregate by, and the business key is
    // reported beside it, so the core refuses an instance whose two name different aggregates
    private Optional<Aggregate> aggregateNamedIn(
        final InstanceFacts facts,
        final String idVariableName,
        final long workflowId,
        final Delivery delivery
    ) {
        final var named = Aggregate.namedIn(facts, idVariableName);
        if (named.isPresent() || (facts.key() == workflowId)) {
            return named;
        }
        // an instance of a sub-process takes both from its parent when it starts, so a workflow
        // the engine started and VanillaBP named only later has them in its root alone
        return delivery.retry
                .read("instance", () -> instances.facts(workflowId, idVariableName), Optional::isEmpty)
                .flatMap(root -> Aggregate.namedIn(root, idVariableName));
    }

    private boolean complete(
        final long jobKey,
        final List<Long> path,
        final String idVariableName,
        final Aggregate aggregate,
        final Delivery delivery
    ) {
        final var variables = sharedValuesOf(idVariableName, aggregate, delivery);
        if (!wroteTheValues(jobKey, path, variables, aggregate, delivery, "completed")) {
            return false;
        }
        try {
            delivery.retry().send("completion", () -> rest.completeJob(jobKey, variables));
        } catch (final RuntimeException e) {
            if (!ZenBpmErrors.isGoneJob(e)) {
                throw e;
            }
            log.warn("""
                    ZenBPM[{}]: job {} (task '{}', workflow aggregate '{}') was gone when it was to be completed: \
                    another delivery of it answered first, or its element or workflow ended meanwhile. The handler's \
                    work is committed: this is what at-least-once delivery leaves over.""", adapterId, jobKey,
                    delivery.taskDefinition(), aggregate.id());
            return false;
        }
        return true;
    }

    // the outgoing flow of an error boundary event may branch on the aggregate as much as the flow
    // behind a completed task, so a BPMN error carries the same values
    private boolean throwBpmnError(
        final ReceivedJob received,
        final List<Long> path,
        final String idVariableName,
        final Aggregate aggregate,
        final WorkflowTaskOutcome outcome,
        final Delivery delivery
    ) {
        final var job = received.job();
        // the error codes of the model were scoped while it was deployed, and the catching event is
        // in the same workflow module as the task
        final var errorCode = scoping.scopedIdentifier(workflowModuleId, outcome.errorCode(), adapterId);
        final var variables = sharedValuesOf(idVariableName, aggregate, delivery);
        if (!wroteTheValues(job.getKey(), path, variables, aggregate, delivery, "failed with its BPMN error")) {
            return false;
        }
        // the engine puts the message into the incident only, which it raises where nothing catches
        // the code, and appends the code to it
        final var message = """
                BPMN error '%s' of task '%s' (workflow aggregate '%s') is caught by no error boundary event and no \
                error event sub-process, its code""".formatted(outcome.errorName(), delivery.taskDefinition(),
                aggregate.id());
        try {
            delivery.retry().send("BPMN error", () -> rest.failJob(job.getKey(), JobFailure
                    .withErrorCode(errorCode, message)
                    .withVariables(variables)
                    .ofTheDelivery(job.getDeliveryToken(), clientId)));
        } catch (final RuntimeException e) {
            if (!ZenBpmErrors.isGoneJob(e)) {
                throw e;
            }
            log.warn("""
                    ZenBPM[{}]: job {} (task '{}', workflow aggregate '{}') was gone when its BPMN error '{}' was to \
                    be sent: another delivery of it answered first, or its element or workflow ended meanwhile. The \
                    handler's work is committed: this is what at-least-once delivery leaves over.""", adapterId, job
                    .getKey(), delivery.taskDefinition(), aggregate.id(), outcome.errorCode());
            return false;
        }
        warnWhereNothingCaughtIt(job.getKey(), errorCode, aggregate, outcome, delivery);
        return true;
    }

    // the engine answers a BPMN error alike whether an event caught it or it raised an incident, so
    // the job is read once: a node which lags behind may still answer it active, and then nothing is
    // said, but the incident is there all the same
    private void warnWhereNothingCaughtIt(
        final long jobKey,
        final String errorCode,
        final Aggregate aggregate,
        final WorkflowTaskOutcome outcome,
        final Delivery delivery
    ) {
        final Optional<JobRef> job;
        try {
            job = rest.job(jobKey);
        } catch (final RuntimeException e) {
            log.debug("ZenBPM[{}]: job {} (task '{}') could not be read after its BPMN error: {}", adapterId, jobKey,
                    delivery.taskDefinition(), e.getMessage());
            return;
        }
        if (job
                .map(JobRef::state)
                .filter(JobRef.State.FAILED::equals)
                .isEmpty()) {
            return;
        }
        log.warn("""
                ZenBPM[{}]: no error boundary event and no error event sub-process catches the BPMN error '{}' of job \
                {} (task '{}', workflow aggregate '{}'), whose code is '{}' at the engine, so the engine raised an \
                incident on the task. Model an event which catches the code, or resolve or cancel the instance.""",
                adapterId, outcome.errorCode(), jobKey, delivery.taskDefinition(), aggregate.id(), errorCode);
    }

    // the task is completed later through the process service, and the redelivery after the window
    // is answered from the record of this delivery, which parks the job again
    private boolean keepOpen(
        final ReceivedJob received,
        final Aggregate aggregate,
        final WorkflowTaskOutcome outcome,
        final Delivery delivery,
        final ZenBpmLockRenewal.Renewal renewal
    ) {
        final var jobKey = received
                .job()
                .getKey();
        if (outcome.maxAgeExceeded() && (asyncTaskMaxAgeAction == AsyncTaskMaxAgeAction.INCIDENT)) {
            failAsOverdue(received, aggregate, outcome.openFor(), delivery);
            return false;
        }
        final Instant lockedUntil;
        try {
            lockedUntil = renewal.park(asyncTaskLockRenewal, delivery.retry());
        } catch (final RuntimeException e) {
            if (!ZenBpmErrors.lockLost(e) && !ZenBpmErrors.isGoneJob(e)) {
                throw e;
            }
            // the application completed the task meanwhile, or the lock lapsed and the engine hands
            // the job out again, which parks it then
            log.debug("ZenBPM[{}]: job {} (task '{}') was not locked any more when it was to stay open: {}",
                    adapterId, jobKey, delivery.taskDefinition(), e.getMessage());
            return false;
        }
        // the parked job holds a slot of its type at the engine until its lock lapses
        stream.parked(received
                .job()
                .getType(), jobKey, lockedUntil);
        log.debug("ZenBPM[{}]: job {} (task '{}') stays open for its asynchronous completion, locked for {}",
                adapterId, jobKey, delivery.taskDefinition(), asyncTaskLockRenewal);
        return true;
    }

    // where operators look: the core says it in a log line, and an incident names it at the
    // engine. Without retries: with retries left the job would come back only after its backoff
    private void failAsOverdue(
        final ReceivedJob received,
        final Aggregate aggregate,
        final Duration openFor,
        final Delivery delivery
    ) {
        final var message = """
                Task '%s' of workflow aggregate '%s' (process '%s' of workflow module '%s') has waited for its \
                asynchronous completion for %s, longer than vanillabp.delivery.max-task-age allows. The application \
                owes it a ProcessService#completeTask or #cancelTask. Complete or cancel it and resolve this \
                incident, raise the maximum age where such a wait is legitimate, or set %s back to 'report'."""
                .formatted(delivery.taskDefinition(), aggregate.id(), delivery.plainProcessId(), workflowModuleId,
                        openFor, ZenBpmAdapterConfiguration.propertyKey(adapterId, "async-task-max-age-action"));
        log.warn("ZenBPM[{}]: job {} is failed without retries, so an incident shows it: {}", adapterId, received
                .job()
                .getKey(), message);
        failWithoutRetries(received, message, delivery);
    }

    // after the outcome, because answering this very job can be what takes another task away, such
    // as the completion which fires a boundary event. Nothing of it may cost the delivery, whose
    // outcome reached the engine already
    private void reportTheTasksTheEngineNoLongerHas(
        final Delivery delivery,
        final Invocation wakeUp
    ) {
        try {
            invoker.reportTasksTheBpmsNoLongerHas(workflowModuleId, delivery.plainProcessId(), wakeUp, probes.get(
                    delivery.plainProcessId()));
        } catch (final RuntimeException e) {
            log.debug(
                    "ZenBPM[{}]: the other open tasks of the workflow of job {} (task '{}') could not be looked at; the next delivery of the workflow looks again",
                    adapterId, delivery.jobKey(), delivery.taskDefinition(), e);
        }
    }

    private Map<String, Object> sharedValuesOf(
        final String idVariableName,
        final Aggregate aggregate,
        final Delivery delivery
    ) {
        return ZenBpmSharedValues.forCommand(idVariableName, aggregate.id(), invoker.syncedWorkflowAggregateValues(
                workflowModuleId, delivery.plainProcessId(), aggregate.id(), AggregateSyncMode.FULL));
    }

    // see decision 35 in the repository's DECISIONS.md
    private boolean wroteTheValues(
        final long jobKey,
        final List<Long> path,
        final Map<String, Object> variables,
        final Aggregate aggregate,
        final Delivery delivery,
        final String answer
    ) {
        for (final var instanceKey : path) {
            try {
                // a 404 comes from a node which lags behind: the job of the instance was just delivered
                delivery.retry().send("update of the variables of instance %d".formatted(instanceKey),
                        ZenBpmErrors::variablesFoundNoInstance, () -> rest.patchVariables(instanceKey, variables));
            } catch (final RuntimeException e) {
                if (!ZenBpmErrors.variablesFoundTheInstanceEnded(e)) {
                    throw e;
                }
                log.warn("""
                        ZenBPM[{}]: the workflow of job {} (task '{}', workflow aggregate '{}') ended or was cancelled \
                        while the handler ran, so the job is not {}. The handler's work is committed: this is what \
                        at-least-once delivery leaves over.""", adapterId, jobKey, delivery.taskDefinition(), aggregate
                        .id(), answer);
                return false;
            }
        }
        return true;
    }

    // see decision 33 in the repository's DECISIONS.md
    private void failWithoutAnAggregate(
        final ReceivedJob received,
        final InstanceFacts facts,
        final String idVariableName,
        final Delivery delivery
    ) {
        final var message = """
                Process instance %d of process '%s' names no workflow aggregate: it has no business key and no \
                variable '%s'. An instance started through VanillaBP carries both, so this one was started past it. \
                Set the variable '%s' to the id of the workflow aggregate, or the business key, and resolve the \
                incident, or cancel the instance."""
                .formatted(facts.key(), facts.scopedProcessId(), idVariableName, idVariableName);
        log.warn(
                "ZenBPM[{}]: job {} (task '{}' of workflow module '{}') is failed without retries, so an incident shows it: {}",
                adapterId, received
                        .job()
                        .getKey(),
                delivery.taskDefinition(), workflowModuleId, message);
        failWithoutRetries(received, message, delivery);
    }

    private void failAsAWorkflowNobodyOwnsHere(
        final ReceivedJob received,
        final DeliveryOfAnUnknownWorkflowException refusal,
        final Delivery delivery
    ) {
        // no stack trace: the message says everything, and the trace would only show where the
        // core read its database
        log.warn("""
                ZenBPM[{}]: job {} (task '{}') is about a workflow this application does not own; it is failed \
                without retries, and the incident reads: {}""", adapterId, received
                .job()
                .getKey(), delivery.taskDefinition(), refusal.getMessage());
        failWithoutRetries(received, refusal.getMessage(), delivery);
    }

    private void failWithoutRetries(
        final ReceivedJob received,
        final String message,
        final Delivery delivery
    ) {
        final var job = received.job();
        try {
            delivery.retry().send("failure", () -> rest.failJob(job.getKey(), JobFailure
                    .withMessage(message)
                    .withRetries(0)
                    .ofTheDelivery(job.getDeliveryToken(), clientId)));
        } catch (final RuntimeException e) {
            if (!ZenBpmErrors.isGoneJob(e)) {
                throw e;
            }
            log.debug("ZenBPM[{}]: job {} (task '{}') was gone when it was to be failed, so there is nothing to fail",
                    adapterId, job.getKey(), delivery.taskDefinition(), e);
        }
    }

    // see decision 27 in the repository's DECISIONS.md: the engine counts the attempts and waits the
    // backoff, so the failure names no retries, and it names the delivery and the stream's client
    // id, so a repeat after a lost answer counts once and the lock is released for the backoff
    private void failAThrownHandler(
        final ReceivedJob received,
        final Aggregate aggregate,
        final RuntimeException thrown,
        final Delivery delivery
    ) {
        final var job = received.job();
        // read after the handler returned: one which started before the stop and threw during it
        // was cut off by the shutdown, and a failure would spend a retry for that
        if (drain.isStopping()) {
            log.info("""
                    ZenBPM[{}]: the handler of job {} (task '{}', workflow aggregate '{}') threw while workflow \
                    module '{}' shuts down, so the job is not failed and keeps its retries; the closing job stream \
                    releases its lock, and the engine hands it to another node: {}""", adapterId, job.getKey(),
                    delivery.taskDefinition(), aggregate.id(), workflowModuleId, thrownAsText(thrown));
            return;
        }
        final var retryBackoff = retryBackoffs.retryBackoffFor(workflowModuleId, delivery.plainProcessId(), delivery
                .taskDefinition());
        final var retriesLeft = Math.max(0, job.getRetries() - 1);
        // the type belongs into the incident as much as the message: a NullPointerException says
        // nothing else
        final var message = "Task '%s' of workflow aggregate '%s' threw %s".formatted(delivery.taskDefinition(),
                aggregate.id(), thrownAsText(thrown));
        if (retriesLeft == 0) {
            log.warn(
                    "ZenBPM[{}]: the handler of job {} (task '{}', workflow aggregate '{}') threw {} at attempt {}, which leaves no retries; the job is failed, and the engine raises an incident",
                    adapterId, job.getKey(), delivery.taskDefinition(), aggregate.id(), thrown
                            .getClass()
                            .getName(),
                    job.getAttempt(), thrown);
        } else {
            log.warn(
                    "ZenBPM[{}]: the handler of job {} (task '{}', workflow aggregate '{}') threw {} at attempt {}; the job is failed with {} retries left, and the engine hands it out again after {}",
                    adapterId, job.getKey(), delivery.taskDefinition(), aggregate.id(), thrown
                            .getClass()
                            .getName(),
                    job.getAttempt(), retriesLeft, retryBackoff
                            .map(Duration::toString)
                            .orElse("the retryBackoff of the task's model, else the engine's default"),
                    thrown);
        }
        final var failure = JobFailure
                .withMessage(message)
                .withRetryBackoff(retryBackoff.orElse(null))
                .ofTheDelivery(job.getDeliveryToken(), clientId);
        try {
            delivery.retry().send("failure", () -> rest.failJob(job.getKey(), failure));
        } catch (final RuntimeException e) {
            if (!ZenBpmErrors.isGoneJob(e)) {
                throw e;
            }
            log.warn("""
                    ZenBPM[{}]: job {} (task '{}', workflow aggregate '{}') was gone when its failure was to be sent: \
                    the engine handed it out again and the newer delivery decides, or its element or workflow ended \
                    meanwhile""", adapterId, job.getKey(), delivery.taskDefinition(), aggregate.id());
        }
    }

    private static String thrownAsText(final RuntimeException thrown) {
        final var message = thrown.getMessage();
        if ((message == null) || message.isBlank()) {
            return thrown
                    .getClass()
                    .getName();
        }
        return "%s: %s".formatted(thrown
                .getClass()
                .getName(), message);
    }

    /**
     * What one delivery of a job is about, as far as the handler found out before it invokes.
     */
    private record Delivery(
        long jobKey,
        String taskDefinition,
        String plainProcessId,
        ZenBpmCommandRetry retry
    ) {
    }

    /**
     * The workflow aggregate an instance names.
     *
     * @param id The aggregate's id, as the variable or the business key carry it
     * @param businessKey The business key of the instance, <code>null</code> where it has none
     */
    private record Aggregate(
        String id,
        String businessKey
    ) {

        private static Optional<Aggregate> namedIn(
            final InstanceFacts facts,
            final String idVariableName
        ) {
            final var variable = facts
                    .variables()
                    .get(idVariableName);
            if (variable != null) {
                return Optional.of(new Aggregate(String.valueOf(variable), facts.businessKey()));
            }
            return Optional
                    .ofNullable(facts.businessKey())
                    .map(businessKey -> new Aggregate(businessKey, businessKey));
        }

    }

    /**
     * The invocation context of one delivery of a job, built from the job and the facts of its
     * instance; it asks the engine nothing.
     */
    private static final class Invocation implements TaskInvocationContext {

        private final String adapterId;

        private final ReceivedJob received;

        private final String taskDefinition;

        private final Aggregate aggregate;

        private final long workflowId;

        private final int version;

        private final boolean predatesDeployedVersion;

        private Map<String, Object> inputVariables;

        private Invocation(
            final String adapterId,
            final ReceivedJob received,
            final String taskDefinition,
            final Aggregate aggregate,
            final long workflowId,
            final int version,
            final boolean predatesDeployedVersion
        ) {
            this.adapterId = adapterId;
            this.received = received;
            this.taskDefinition = taskDefinition;
            this.aggregate = aggregate;
            this.workflowId = workflowId;
            this.version = version;
            this.predatesDeployedVersion = predatesDeployedVersion;
        }

        @Override
        public String getTaskDefinition() {
            return taskDefinition;
        }

        @Override
        public String getWorkflowAggregateId() {
            return aggregate.id();
        }

        @Override
        public String getBusinessKey() {
            return aggregate.businessKey();
        }

        @Override
        public String getBpmnElementId() {
            // the core finds the method by the task definition or by the element id, and an
            // element id is never scoped
            return received
                    .job()
                    .getElementId();
        }

        @Override
        public String getWorkflowId() {
            // the root instance of the task's own process, see decision 28 in the repository's
            // DECISIONS.md
            return String.valueOf(workflowId);
        }

        @Override
        public String getTaskId() {
            return String.valueOf(received
                    .job()
                    .getKey());
        }

        @Override
        public TaskKind getTaskKind() {
            return TaskKind.TASK;
        }

        @Override
        public String getProcessVersion() {
            // written as a start reports the version of its instance, so a start and the
            // deliveries of one workflow name the same version
            return String.valueOf(version);
        }

        @Override
        public String getAdapterId() {
            return adapterId;
        }

        @Override
        public String getDeliveryId() {
            // the same for every delivery of one job, and a new job for every new activation
            return getTaskId();
        }

        @Override
        public String getActivationId() {
            // the job stream names no element instance, and the job key differs between two
            // activations of one element as the contract asks
            return getTaskId();
        }

        @Override
        public Object getTaskParameter(final String name) {
            if (inputVariables == null) {
                inputVariables = received.inputVariables();
            }
            return inputVariables.get(name);
        }

        @Override
        public boolean predatesDeployedVersion() {
            return predatesDeployedVersion;
        }

    }

}

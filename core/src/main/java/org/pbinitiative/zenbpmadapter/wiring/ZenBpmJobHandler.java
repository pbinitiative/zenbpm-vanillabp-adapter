/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.wiring;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.pbinitiative.zenbpmadapter.ZenBpmDeployedProcesses;
import org.pbinitiative.zenbpmadapter.ZenBpmInstanceCache;
import org.pbinitiative.zenbpmadapter.ZenBpmInstanceCache.InstanceFacts;
import org.pbinitiative.zenbpmadapter.ZenBpmProcessingContext;
import org.pbinitiative.zenbpmadapter.ZenBpmProcessingContext.WiredProcess;
import org.pbinitiative.zenbpmadapter.client.ReceivedJob;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientFactory;
import org.pbinitiative.zenbpmadapter.client.ZenBpmCommandRetry;
import org.pbinitiative.zenbpmadapter.client.ZenBpmErrors;
import org.pbinitiative.zenbpmadapter.client.ZenBpmRestClient;
import org.pbinitiative.zenbpmadapter.client.api.JobFailure;
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
 * <li><b>Everything else</b>, a BPMN error, a task which stays open for its asynchronous
 * completion, and a handler which threw, is not carried out yet: logged at WARN, and the job is
 * left to its lock, after which the engine hands it out again.</li>
 * </ul>
 * Every command goes through a {@link ZenBpmCommandRetry}, bounded by the job's lock and stopped at
 * once while the module shuts down. A command which fails all the same leaves the job to its lock.
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

    private final ZenBpmDeployedProcesses deployedProcesses;

    private final String clientId;

    /**
     * The name of the variable of the aggregate's id, by plain process id, as the core answered it.
     */
    private final Map<String, String> idVariableNames = new ConcurrentHashMap<>();

    private volatile boolean stopping;

    /**
     * The handler of a workflow module whose processing starts.
     *
     * @param adapterId The adapter id
     * @param factory The clients of the adapter id
     * @param collaborators What the platform hands to the adapter id
     * @param context What the deployment of the module collected
     */
    public ZenBpmJobHandler(
        final String adapterId,
        final ZenBpmClientFactory factory,
        final AdapterCollaborators collaborators,
        final ZenBpmProcessingContext context
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
        this.deployedProcesses = factory.deployedProcesses();
        this.clientId = factory.clientId();
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
     * shutdown is not held up by it.
     */
    public void stopping() {
        stopping = true;
    }

    /**
     * Whether the module shuts down.
     *
     * @return <code>true</code> once {@link #stopping()} was called
     */
    public boolean isStopping() {
        return stopping;
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
        try {
            run(received, facts, delivery, renewal);
        } catch (final RuntimeException e) {
            // what reaches here failed on its way to the engine after its repetitions; the
            // invocation itself is answered in run
            final var stoppingNow = stopping;
            if (stoppingNow) {
                log.info(
                        "ZenBPM[{}]: job {} (task '{}') could not be answered while workflow module '{}' shuts down; it is left to its lock: {}",
                        adapterId, job.getKey(), taskDefinition, workflowModuleId, e.getMessage());
                return;
            }
            log.warn(
                    "ZenBPM[{}]: job {} (task '{}') could not be answered to the engine; it is left to its lock, after which the engine hands it out again",
                    adapterId, job.getKey(), taskDefinition, e);
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
            leaveAThrownHandlerToItsLock(delivery, aggregate, e);
            return;
        }
        switch (outcome.kind()) {
            case COMPLETED -> complete(job.getKey(), path.get(), idVariableName, aggregate, delivery);
            case BPMN_ERROR, COMPLETION_PENDING -> leaveAnOutcomeToItsLock(delivery, aggregate, outcome);
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

    private void complete(
        final long jobKey,
        final List<Long> path,
        final String idVariableName,
        final Aggregate aggregate,
        final Delivery delivery
    ) {
        final var variables = ZenBpmSharedValues.forCommand(idVariableName, aggregate.id(), invoker
                .syncedWorkflowAggregateValues(workflowModuleId, delivery.plainProcessId(), aggregate.id(),
                        AggregateSyncMode.FULL));
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
                        while the handler ran, so the job is not completed. The handler's work is committed: this is \
                        what at-least-once delivery leaves over.""", adapterId, jobKey, delivery.taskDefinition(),
                        aggregate.id());
                return;
            }
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
        }
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

    private void leaveAThrownHandlerToItsLock(
        final Delivery delivery,
        final Aggregate aggregate,
        final RuntimeException thrown
    ) {
        log.warn(
                """
                        ZenBPM[{}]: the handler of job {} (task '{}', workflow aggregate '{}') threw, and this adapter does not \
                        fail a job at the engine yet; the job is left to its lock, after which the engine hands it out \
                        again""",
                adapterId, delivery.jobKey(), delivery.taskDefinition(), aggregate.id(), thrown);
    }

    private void leaveAnOutcomeToItsLock(
        final Delivery delivery,
        final Aggregate aggregate,
        final WorkflowTaskOutcome outcome
    ) {
        log.warn("""
                ZenBPM[{}]: the handler of job {} (task '{}', workflow aggregate '{}') answered {}, which this \
                adapter does not carry out yet; the job is left to its lock, after which the engine hands it out \
                again""", adapterId, delivery.jobKey(), delivery.taskDefinition(), aggregate.id(), outcome.kind());
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

/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.wiring;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

import org.pbinitiative.zenbpmadapter.ZenBpmInstanceCache.InstanceFacts;
import org.pbinitiative.zenbpmadapter.ZenBpmProcessingContext.WiredProcess;
import org.pbinitiative.zenbpmadapter.client.ReceivedJob;
import org.pbinitiative.zenbpmadapter.client.ZenBpmCommandRetry;
import org.pbinitiative.zenbpmadapter.client.ZenBpmErrors;
import org.pbinitiative.zenbpmadapter.client.ZenBpmRestClient;
import org.pbinitiative.zenbpmadapter.client.api.JobFailure;
import org.pbinitiative.zenbpmadapter.model.ZenBpmMarkerTasks;
import org.pbinitiative.zenbpmadapter.observability.ZenBpmMetrics;

import io.vanillabp.integration.adapter.spi.AggregateSyncMode;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartContext;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartInvoker;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartResult;
import io.vanillabp.spi.service.BpmsStartTrigger;
import lombok.extern.slf4j.Slf4j;

/**
 * Serves the job of a start marker, through which the adapter hears that a workflow started (see
 * decision 26 in the repository's DECISIONS.md): it tells the platform's core, which decides what
 * the start is, and then lets the workflow go on. The {@link ZenBpmJobHandler} of the workflow
 * module hands it the job, inside the module's drain and under the renewal of the job's lock.
 * <ul>
 * <li><b>The workflow carries a name</b>, its business key or the variable of the aggregate's id,
 * and an aggregate of that id exists: the application started it, or this is a second delivery of
 * the marker. Nothing is built, and the job is completed without writing anything, so the
 * application's own start costs one job and one transaction.</li>
 * <li><b>The workflow carries no name</b>: a timer or a message started it, or somebody started it
 * past VanillaBP. The application's <code>&#64;WorkflowStartedByBpms</code> method builds the
 * aggregate and names it, and the id and the values it shares are written into the instance before
 * the job is completed, so a second delivery finds the name, see decision 25 in the repository's
 * DECISIONS.md. It finds it in the instance and not in the job: the engine hands out a job with the
 * variables its instance had when the job was created, on every delivery, so the id is read from
 * the facts of the instance, which the instance cache reads again where it did not hold the id.</li>
 * <li><b>The workflow carries a name no aggregate has</b>: the core refuses the start, and the job
 * is failed like the job of a handler which threw, see decision 27 in the repository's
 * DECISIONS.md.</li>
 * </ul>
 * In every case but the refusal the core writes the start row with the key of the instance, so a
 * later operation on the aggregate finds the workflow through it. The instance of a marker's job
 * is the workflow's root, because a marker is written into the process itself and never into a
 * sub-process. A crash after the aggregate's commit and before its id reached the instance lets the
 * next delivery build a second aggregate, unless the application's id is deterministic; the
 * Camunda 8 adapter has the same window. So does a next delivery whose read of the instance a node
 * answers which lags behind and has not seen the update yet, see decision 46 in the repository's
 * DECISIONS.md.
 */
@Slf4j
final class ZenBpmBpmsInitiatedStartHandler {

    private final String adapterId;

    private final String workflowModuleId;

    private final BpmsInitiatedStartInvoker invoker;

    private final ZenBpmRestClient rest;

    private final String clientId;

    private final ZenBpmRetryBackoffResolver retryBackoffs;

    private final ZenBpmMetrics metrics;

    ZenBpmBpmsInitiatedStartHandler(
        final String adapterId,
        final String workflowModuleId,
        final BpmsInitiatedStartInvoker invoker,
        final ZenBpmRestClient rest,
        final String clientId,
        final ZenBpmRetryBackoffResolver retryBackoffs,
        final ZenBpmMetrics metrics
    ) {
        this.adapterId = adapterId;
        this.workflowModuleId = workflowModuleId;
        this.invoker = invoker;
        this.rest = rest;
        this.clientId = clientId;
        this.retryBackoffs = retryBackoffs;
        this.metrics = metrics;
    }

    /**
     * Serves one delivery of a start marker's job, on a handler thread.
     *
     * @param received The job as the job stream received it
     * @param facts The facts of the job's instance
     * @param process The claimed process the marker belongs to
     * @param retry What repeats a command which failed for a moment, within the job's lock
     * @param stopping Whether the workflow module shuts down
     */
    void handle(
        final ReceivedJob received,
        final InstanceFacts facts,
        final WiredProcess process,
        final ZenBpmCommandRetry retry,
        final BooleanSupplier stopping
    ) {
        final var job = received.job();
        final var start = new Start(adapterId, received, facts, process);
        final BpmsInitiatedStartResult result;
        try {
            result = invoker.startWorkflowByBpms(workflowModuleId, process.bpmnProcessId(), start);
        } catch (final RuntimeException e) {
            fail(received, start, e, retry, stopping);
            return;
        }
        if (!carriesTheName(start.getVariables(), result) && !nameTheWorkflow(job.getInstanceKey(), result,
                start, retry)) {
            return;
        }
        try {
            retry.send("completion", () -> rest.completeJob(job.getKey(), null));
        } catch (final RuntimeException e) {
            if (!ZenBpmErrors.isGoneJob(e)) {
                throw e;
            }
            log.warn("""
                    ZenBPM[{}]: the job {} of the start of workflow aggregate '{}' (start event '{}' of process \
                    '{}') was gone when it was to be completed: another delivery of it answered first, or the \
                    workflow ended meanwhile. The start is reported: this is what at-least-once delivery leaves \
                    over.""", adapterId, job.getKey(), result.workflowAggregateId(), start.getStartEventId(), process
                    .bpmnProcessId());
            return;
        }
        metrics.jobCompleted(adapterId);
        log.debug("ZenBPM[{}]: workflow aggregate '{}' started at start event '{}' of process '{}' (instance {}), {}",
                adapterId, result.workflowAggregateId(), start.getStartEventId(), process.bpmnProcessId(), job
                        .getInstanceKey(),
                result.created() ? "built by the application, which named the workflow"
                        : "which exists already, so the workflow is the application's");
    }

    // the instance carries the id where the application started it, or where an earlier delivery
    // of this marker wrote it; the variables are the job's with the id the instance holds now
    private static boolean carriesTheName(
        final Map<String, Object> variables,
        final BpmsInitiatedStartResult result
    ) {
        final var name = result.workflowAggregateIdName();
        final var carried = name == null ? null : variables.get(name);
        return result
                .variables()
                .isEmpty() || ((carried != null) && result
                        .workflowAggregateId()
                        .equals(String.valueOf(carried)));
    }

    // see decision 25 in the repository's DECISIONS.md: written before the completion, so a second
    // delivery finds the name and builds nothing
    private boolean nameTheWorkflow(
        final long instanceKey,
        final BpmsInitiatedStartResult result,
        final Start start,
        final ZenBpmCommandRetry retry
    ) {
        try {
            // a 404 comes from a node which lags behind: the job of the instance was just delivered
            retry.send("update of the variables of instance %d".formatted(instanceKey),
                    ZenBpmErrors::variablesFoundNoInstance, () -> rest.patchVariables(instanceKey, result.variables()));
        } catch (final RuntimeException e) {
            if (!ZenBpmErrors.variablesFoundTheInstanceEnded(e)) {
                throw e;
            }
            log.warn("""
                    ZenBPM[{}]: the workflow of instance {} (start event '{}') ended or was cancelled while its \
                    start was reported, so its marker is not completed. Workflow aggregate '{}' is committed: this \
                    is what at-least-once delivery leaves over.""", adapterId, instanceKey, start.getStartEventId(),
                    result.workflowAggregateId());
            return false;
        }
        return true;
    }

    // see decision 27 in the repository's DECISIONS.md, as for a handler of a task which threw
    private void fail(
        final ReceivedJob received,
        final Start start,
        final RuntimeException thrown,
        final ZenBpmCommandRetry retry,
        final BooleanSupplier stopping
    ) {
        final var job = received.job();
        if (stopping.getAsBoolean()) {
            log.info("""
                    ZenBPM[{}]: the start of the workflow of instance {} (start event '{}') failed while workflow \
                    module '{}' shuts down, so its job is not failed and keeps its retries; the closing job stream \
                    releases its lock: {}""", adapterId, job.getInstanceKey(), start.getStartEventId(),
                    workflowModuleId, thrownAsText(thrown));
            return;
        }
        final var retryBackoff = retryBackoffs.retryBackoffFor(workflowModuleId, start.bpmnProcessId(),
                ZenBpmMarkerTasks.START_MARKER_TASK);
        final var retriesLeft = Math.max(0, job.getRetries() - 1);
        final var message = "The start of the workflow at start event '%s' of process '%s' (workflow module '%s') failed: %s"
                .formatted(start.getStartEventId(), start.bpmnProcessId(), workflowModuleId, thrownAsText(thrown));
        log.warn(
                "ZenBPM[{}]: the start of the workflow of instance {} at start event '{}' failed at attempt {}; its job is failed with {} retries left{}",
                adapterId, job.getInstanceKey(), start.getStartEventId(), job.getAttempt(), retriesLeft,
                retriesLeft == 0 ? ", so the engine raises an incident" : "", thrown);
        try {
            retry.send("failure", () -> rest.failJob(job.getKey(), JobFailure
                    .withMessage(message)
                    .withRetryBackoff(retryBackoff.orElse(null))
                    .ofTheDelivery(job.getDeliveryToken(), clientId)));
            metrics.jobFailed(adapterId);
        } catch (final RuntimeException e) {
            if (!ZenBpmErrors.isGoneJob(e)) {
                throw e;
            }
            log.warn("""
                    ZenBPM[{}]: the job {} of the start at start event '{}' was gone when its failure was to be \
                    sent: the engine handed it out again and the newer delivery decides, or the workflow ended \
                    meanwhile""", adapterId, job.getKey(), start.getStartEventId());
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
     * What the core is told about one start: built from the job and the facts of its instance, it
     * asks the engine nothing.
     */
    private static final class Start implements BpmsInitiatedStartContext {

        private final String adapterId;

        private final String startEventId;

        private final BpmsStartTrigger.Kind kind;

        private final Map<String, Object> variables;

        private final long instanceKey;

        private final int version;

        private final String businessKey;

        private final String bpmnProcessId;

        private Start(
            final String adapterId,
            final ReceivedJob received,
            final InstanceFacts facts,
            final WiredProcess process
        ) {
            final var job = received.job();
            this.adapterId = adapterId;
            this.startEventId = ZenBpmMarkerTasks
                    .startEventOf(job.getElementId())
                    .orElse(job.getElementId());
            // a start event this boot's model does not hold belongs to an older version of the
            // process, and is taken for a plain one
            this.kind = process
                    .startMarkers()
                    .getOrDefault(startEventId, BpmsStartTrigger.Kind.NONE);
            // the job's variables are those of the instance when the job was created, so the id
            // an earlier delivery wrote is taken from the instance
            final var variables = new LinkedHashMap<>(received.inputVariables());
            variables.putAll(facts.variables());
            this.variables = Collections.unmodifiableMap(variables);
            this.instanceKey = job.getInstanceKey();
            this.version = facts.version();
            // a called instance whose call activity cut the caller's key carries an empty one
            this.businessKey = (facts.businessKey() == null) || facts
                    .businessKey()
                    .isBlank() ? null : facts.businessKey();
            this.bpmnProcessId = process.bpmnProcessId();
        }

        private String bpmnProcessId() {
            return bpmnProcessId;
        }

        @Override
        public String getStartEventId() {
            return startEventId;
        }

        @Override
        public BpmsStartTrigger.Kind getKind() {
            return kind;
        }

        @Override
        public Map<String, Object> getVariables() {
            return variables;
        }

        @Override
        public String getNativeInstanceId() {
            // the workflow's root instance, see decision 28 in the repository's DECISIONS.md
            return String.valueOf(instanceKey);
        }

        @Override
        public String getProcessVersion() {
            // written as a start and a delivery of a task report the version of their instance
            return String.valueOf(version);
        }

        @Override
        public AggregateSyncMode getAggregateSyncMode() {
            // the engine evaluates the values the aggregate shares, so they travel with the start
            return AggregateSyncMode.FULL;
        }

        @Override
        public String getAdapterId() {
            return adapterId;
        }

        @Override
        public String getBusinessKey() {
            return businessKey;
        }

    }

}

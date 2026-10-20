/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.wiring;

import java.time.Instant;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import org.pbinitiative.zenbpmadapter.ZenBpmInstanceCache.InstanceFacts;
import org.pbinitiative.zenbpmadapter.ZenBpmProcessingContext.WiredProcess;
import org.pbinitiative.zenbpmadapter.client.ReceivedJob;
import org.pbinitiative.zenbpmadapter.client.ZenBpmCommandRetry;
import org.pbinitiative.zenbpmadapter.client.ZenBpmErrors;
import org.pbinitiative.zenbpmadapter.client.ZenBpmRestClient;
import org.pbinitiative.zenbpmadapter.client.api.JobFailure;
import org.pbinitiative.zenbpmadapter.client.api.ProcessInstanceRef;
import org.pbinitiative.zenbpmadapter.model.ZenBpmMarkerTasks;

import io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedContext;
import io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedInvoker;
import io.vanillabp.spi.service.WorkflowEnd;
import lombok.extern.slf4j.Slf4j;

/**
 * Serves the job of an end marker, through which the adapter hears that a workflow reached an end
 * event (see decision 26 in the repository's DECISIONS.md): it tells the platform's core, and only
 * then lets the end event fire, so the instance ends after the core committed. The
 * {@link ZenBpmJobHandler} of the workflow module hands it the job, inside the module's drain and
 * under the renewal of the job's lock.
 * <p>
 * An end event ends the workflow only where nothing else of it runs, so before an end is reported
 * the handler reads where the tokens of the workflow stand, see decision 52 in the repository's
 * DECISIONS.md:
 * <ul>
 * <li><b>A terminate end event</b> ends the workflow whatever else runs: the end is reported at
 * once.</li>
 * <li><b>A token stands anywhere but at an end marker or an end event</b>: another branch of the
 * workflow goes on, and its own end reports the end. The job is completed without a report.</li>
 * <li><b>A child instance still runs</b> although no token waits for it, which is what an event
 * sub-process is: the workflow ends once it ends, without passing an end marker. The job is left to
 * its lock, and the engine hands it out again once the lock lapsed, until the sub-process ended.</li>
 * <li><b>Nothing else runs</b>: the end is reported, as {@link WorkflowEnd.Kind#COMPLETED} with the
 * end event's id, the root instance's key as the workflow (see decision 28 in the repository's
 * DECISIONS.md), its business key and its version. Before the application's
 * <code>&#64;WorkflowEnded</code> method runs, the core reports every task it believes open in that
 * workflow as cancelled. An aggregate the application deleted is skipped by the core, and the
 * workflow ends.</li>
 * </ul>
 * Two branches which reach their end markers at the same moment may both report the end: the
 * platform delivers the end at least once, and so does every second delivery of the marker. A
 * report which throws fails the job like the job of a handler which threw, see decision 27 in the
 * repository's DECISIONS.md, and the incident the engine raises at the last retry names the
 * <code>&#64;WorkflowEnded</code> method.
 */
@Slf4j
final class ZenBpmWorkflowEndedHandler {

    private final String adapterId;

    private final String workflowModuleId;

    private final WorkflowEndedInvoker invoker;

    private final ZenBpmRestClient rest;

    private final String clientId;

    private final ZenBpmRetryBackoffResolver retryBackoffs;

    ZenBpmWorkflowEndedHandler(
        final String adapterId,
        final String workflowModuleId,
        final WorkflowEndedInvoker invoker,
        final ZenBpmRestClient rest,
        final String clientId,
        final ZenBpmRetryBackoffResolver retryBackoffs
    ) {
        this.adapterId = adapterId;
        this.workflowModuleId = workflowModuleId;
        this.invoker = invoker;
        this.rest = rest;
        this.clientId = clientId;
        this.retryBackoffs = retryBackoffs;
    }

    /**
     * Serves one delivery of an end marker's job, on a handler thread.
     *
     * @param received The job as the job stream received it
     * @param facts The facts of the job's instance, with the variable of the aggregate's id where
     *            the instance has it
     * @param process The claimed process the marker belongs to
     * @param idVariableName The name of the variable which carries the aggregate's id
     * @param retry What repeats a command which failed for a moment, within the job's lock
     * @param stopping Whether the workflow module shuts down
     */
    void handle(
        final ReceivedJob received,
        final InstanceFacts facts,
        final WiredProcess process,
        final String idVariableName,
        final ZenBpmCommandRetry retry,
        final BooleanSupplier stopping
    ) {
        final var job = received.job();
        final var endEventId = ZenBpmMarkerTasks
                .endEventOf(job.getElementId())
                .orElse(job.getElementId());
        // an end event this boot's model does not hold belongs to an older version of the process,
        // and is taken for one which ends its branch only, so no end is reported too early
        final var ending = process
                .endMarkers()
                .getOrDefault(endEventId, ZenBpmMarkerTasks.Ending.BRANCH);
        if (ending == ZenBpmMarkerTasks.Ending.BRANCH) {
            final var elsewhere = whatElseRuns(job.getInstanceKey(), process, retry);
            if (elsewhere.isPresent()) {
                goOnWithout(received, endEventId, process, elsewhere.get(), retry);
                return;
            }
        }
        final var aggregate = NamedAggregate.in(facts, idVariableName);
        if (aggregate.isEmpty()) {
            log.info("""
                    ZenBPM[{}]: the workflow of instance {} of process '{}' reached end event '{}', but the instance \
                    names no workflow aggregate: it has no business key and no variable '{}', so its end is not \
                    reported""", adapterId, job.getInstanceKey(), process.bpmnProcessId(), endEventId,
                    idVariableName);
            complete(received, endEventId, process, retry);
            return;
        }
        final var ended = new Ended(adapterId, endEventId, aggregate.get(), facts, job.getInstanceKey());
        try {
            invoker.workflowEnded(workflowModuleId, process.bpmnProcessId(), ended);
        } catch (final RuntimeException e) {
            fail(received, ended, process, e, retry, stopping);
            return;
        }
        if (complete(received, endEventId, process, retry)) {
            log.debug("ZenBPM[{}]: workflow aggregate '{}' ended at end event '{}' of process '{}' (instance {})",
                    adapterId, ended.getWorkflowAggregateId(), endEventId, process.bpmnProcessId(), job
                            .getInstanceKey());
        }
    }

    // see decision 52 in the repository's DECISIONS.md: empty where nothing else of the workflow
    // runs; a token at an end event waits for the job of a message end event, and ends there
    private Optional<Elsewhere> whatElseRuns(
        final long instanceKey,
        final WiredProcess process,
        final ZenBpmCommandRetry retry
    ) {
        final var standing = retry.read("read of the tokens of instance %d".formatted(instanceKey),
                () -> rest.activeElementsOf(instanceKey), Optional::isEmpty);
        if (standing.isEmpty()) {
            return Optional.of(Elsewhere.UNKNOWN);
        }
        if (standing
                .get()
                .stream()
                .anyMatch(elementId -> !elementId.startsWith(ZenBpmMarkerTasks.ENDED) && !process
                        .endMarkers()
                        .containsKey(elementId))) {
            return Optional.of(Elsewhere.ANOTHER_BRANCH);
        }
        final var children = retry.read("read of the child instances of instance %d".formatted(instanceKey),
                () -> rest.children(instanceKey), read -> false);
        if (children
                .stream()
                .anyMatch(child -> child.state() == ProcessInstanceRef.State.ACTIVE)) {
            return Optional.of(Elsewhere.AN_EVENT_SUB_PROCESS);
        }
        return Optional.empty();
    }

    private void goOnWithout(
        final ReceivedJob received,
        final String endEventId,
        final WiredProcess process,
        final Elsewhere elsewhere,
        final ZenBpmCommandRetry retry
    ) {
        final var job = received.job();
        switch (elsewhere) {
            case UNKNOWN -> log.info("""
                    ZenBPM[{}]: instance {} of end event '{}' is not known to the engine node asked, which may lag \
                    behind; the job is left to its lock, after which the engine hands it out again""", adapterId, job
                    .getInstanceKey(), endEventId);
            case AN_EVENT_SUB_PROCESS -> log.info("""
                    ZenBPM[{}]: the workflow of instance {} of process '{}' reached end event '{}' while an event \
                    sub-process of it still runs, and it ends once that sub-process ended; so its end is not \
                    reported yet, and the job of the end marker is left to its lock, after which the engine hands it \
                    out again""", adapterId, job.getInstanceKey(), process.bpmnProcessId(), endEventId);
            case ANOTHER_BRANCH -> {
                log.debug("""
                        ZenBPM[{}]: end event '{}' of process '{}' ends a branch of the workflow of instance {}, \
                        which goes on in another branch; its end is reported where the last branch ends""",
                        adapterId, endEventId, process.bpmnProcessId(), job.getInstanceKey());
                complete(received, endEventId, process, retry);
            }
        }
    }

    // a job gone meanwhile was answered by another delivery, or the workflow was cancelled
    private boolean complete(
        final ReceivedJob received,
        final String endEventId,
        final WiredProcess process,
        final ZenBpmCommandRetry retry
    ) {
        final var job = received.job();
        try {
            retry.send("completion", () -> rest.completeJob(job.getKey(), null));
        } catch (final RuntimeException e) {
            if (!ZenBpmErrors.isGoneJob(e)) {
                throw e;
            }
            log.warn("""
                    ZenBPM[{}]: the job {} of end event '{}' of process '{}' was gone when it was to be completed: \
                    another delivery of it answered first, or the workflow was cancelled meanwhile. What the core was \
                    told is committed: this is what at-least-once delivery leaves over.""", adapterId, job.getKey(),
                    endEventId, process.bpmnProcessId());
            return false;
        }
        return true;
    }

    // see decision 27 in the repository's DECISIONS.md, as for a handler of a task which threw
    private void fail(
        final ReceivedJob received,
        final Ended ended,
        final WiredProcess process,
        final RuntimeException thrown,
        final ZenBpmCommandRetry retry,
        final BooleanSupplier stopping
    ) {
        final var job = received.job();
        if (stopping.getAsBoolean()) {
            log.info("""
                    ZenBPM[{}]: the report of the end of workflow aggregate '{}' (end event '{}') failed while \
                    workflow module '{}' shuts down, so its job is not failed and keeps its retries; the closing job \
                    stream releases its lock: {}""", adapterId, ended.getWorkflowAggregateId(), ended
                    .getEndEventId(), workflowModuleId, thrownAsText(thrown));
            return;
        }
        final var retryBackoff = retryBackoffs.retryBackoffFor(workflowModuleId, process.bpmnProcessId(),
                ZenBpmMarkerTasks.END_MARKER_TASK);
        final var retriesLeft = Math.max(0, job.getRetries() - 1);
        final var message = """
                The end of workflow aggregate '%s' at end event '%s' of process '%s' (workflow module '%s') could not \
                be reported: the @WorkflowEnded method of the process, or the platform's work around it, threw %s"""
                .formatted(ended.getWorkflowAggregateId(), ended.getEndEventId(), process.bpmnProcessId(),
                        workflowModuleId, thrownAsText(thrown));
        log.warn(
                "ZenBPM[{}]: the report of the end of workflow aggregate '{}' at end event '{}' failed at attempt {}; its job is failed with {} retries left{}",
                adapterId, ended.getWorkflowAggregateId(), ended.getEndEventId(), job.getAttempt(), retriesLeft,
                retriesLeft == 0 ? ", so the engine raises an incident" : "", thrown);
        try {
            retry.send("failure", () -> rest.failJob(job.getKey(), JobFailure
                    .withMessage(message)
                    .withRetryBackoff(retryBackoff.orElse(null))
                    .ofTheDelivery(job.getDeliveryToken(), clientId)));
        } catch (final RuntimeException e) {
            if (!ZenBpmErrors.isGoneJob(e)) {
                throw e;
            }
            log.warn("""
                    ZenBPM[{}]: the job {} of end event '{}' was gone when its failure was to be sent: the engine \
                    handed it out again and the newer delivery decides, or the workflow was cancelled meanwhile""",
                    adapterId, job.getKey(), ended.getEndEventId());
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
     * What else of a workflow runs where one of its branches reached an end event.
     */
    private enum Elsewhere {

        /** The engine node asked did not know the instance. */
        UNKNOWN,

        /** A token stands at an element which is no end. */
        ANOTHER_BRANCH,

        /** A child instance runs which no token waits for. */
        AN_EVENT_SUB_PROCESS

    }

    /**
     * What the core is told about one end: built from the job and the facts of its instance, it
     * asks the engine nothing.
     */
    private static final class Ended implements WorkflowEndedContext {

        private final String adapterId;

        private final String endEventId;

        private final String aggregateId;

        private final String businessKey;

        private final int version;

        private final long instanceKey;

        private final Instant endTime = Instant.now();

        private Ended(
            final String adapterId,
            final String endEventId,
            final NamedAggregate aggregate,
            final InstanceFacts facts,
            final long instanceKey
        ) {
            this.adapterId = adapterId;
            this.endEventId = endEventId;
            this.aggregateId = aggregate.id();
            this.businessKey = (aggregate.businessKey() == null) || aggregate
                    .businessKey()
                    .isBlank() ? null : aggregate.businessKey();
            this.version = facts.version();
            this.instanceKey = instanceKey;
        }

        @Override
        public String getWorkflowAggregateId() {
            return aggregateId;
        }

        @Override
        public WorkflowEnd.Kind getKind() {
            // a cancelled instance passes no marker, see decision 26 in the repository's DECISIONS.md
            return WorkflowEnd.Kind.COMPLETED;
        }

        @Override
        public Instant getEndTime() {
            // the engine reports no time of the end, so it is the moment the marker was served
            return endTime;
        }

        @Override
        public String getEndEventId() {
            return endEventId;
        }

        @Override
        public String getProcessVersion() {
            // written as a delivery of a task reports the version of its instance
            return String.valueOf(version);
        }

        @Override
        public String getAdapterId() {
            return adapterId;
        }

        @Override
        public String getWorkflowId() {
            // a marker is written into the process itself, so the job's instance is the workflow's
            // root, see decision 28 in the repository's DECISIONS.md
            return String.valueOf(instanceKey);
        }

        @Override
        public String getBusinessKey() {
            return businessKey;
        }

    }

}

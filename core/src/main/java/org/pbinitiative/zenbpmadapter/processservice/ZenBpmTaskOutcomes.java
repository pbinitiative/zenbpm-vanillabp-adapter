/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.processservice;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import org.pbinitiative.zenbpmadapter.ZenBpmInstanceCache.InstanceFacts;
import org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientFactory;
import org.pbinitiative.zenbpmadapter.client.ZenBpmErrors;
import org.pbinitiative.zenbpmadapter.client.ZenBpmRestClient;
import org.pbinitiative.zenbpmadapter.client.api.JobFailure;
import org.pbinitiative.zenbpmadapter.client.api.JobRef;

import io.vanillabp.integration.adapter.spi.AdapterCollaborators;
import io.vanillabp.integration.adapter.spi.PhaseOneRequest;
import io.vanillabp.integration.adapter.spi.PhaseTwoRequest;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskKind;
import io.vanillabp.spi.process.TaskNotFoundException;
import lombok.extern.slf4j.Slf4j;

/**
 * What the application reports about a task it left open for its asynchronous completion, in both
 * phases: its completion, and its cancellation with a BPMN error. A task is a job of the engine, and
 * the task id is the job's key. A user task is a job as well, so one instance of this class serves
 * the tasks and another the user tasks, each with the word for its kind in every message. Each
 * finds no job of the other kind, as the platform hands the id of each kind to a command of its own
 * and other engines keep the two apart, see decision 51 in the repository's DECISIONS.md.
 * <p>
 * <b>Phase one</b> asks the engine, right before the caller's transaction commits, whether the job
 * still waits and whose it is, and asks nothing when the application calls: the later the check,
 * the smaller the window in which its answer goes stale before phase two. Both questions are reads,
 * which move nothing. A job which ended, and one the engine does not know, throw the
 * {@link TaskNotFoundException} the platform throws where its own probe finds no engine holding the
 * task, so an application catches one type. A job of a process another workflow module serves, and
 * one which carries an incident, end the transaction with an {@link IllegalStateException} which
 * says why: the task is there, but this call cannot end it. A read is answered by a node of the
 * engine which may lag behind, so a job or an instance it does not know yet is asked for again
 * within <code>workflow-visibility-timeout</code>.
 * <p>
 * <b>Phase two</b> writes the values the aggregate shares now, and its id, into the job's instance
 * and every instance above it up to the workflow's root, see decision 35 in the repository's
 * DECISIONS.md, and then completes the job, or fails it with the error code as the deployed model
 * carries it and the same values, which an error boundary event or an error event sub-process of
 * the model takes over. A job which ended meanwhile, and a workflow which ended, are what
 * at-least-once delivery leaves over: a WARN, and nothing else, so the outbox entry is done. A
 * failure which may pass is thrown, so the outbox repeats phase two, and so is a job which carries
 * an incident, because the engine takes no command for it until the incident is resolved, and then
 * the job waits again; a task id which is no number never becomes one, which the classification
 * reads as permanent.
 * <p>
 * Phase two names no delivery, because the application completes a task without one, on whichever
 * node it runs. The engine completes a job whoever asks, and fails one without a delivery token
 * whoever asks as well; the failure names the job stream's client id, which releases the lock where
 * this node holds it. The engine drops the lock of a job it completed, failed for this node, or
 * refused a command for because the job ended, so this node then ends the count of a job it
 * parked, see decision 40 in the repository's DECISIONS.md; a job another node parked counts there
 * until its lock would have lapsed, and so does a job which ended without a command of phase two,
 * whose lock the engine keeps as long. Phase two is not a run of a handler, so the
 * shutdown does not wait for it, and it is sent while a workflow module stops as well.
 *
 * @param <A> The workflow aggregate type
 */
@Slf4j
final class ZenBpmTaskOutcomes<A> {

    /**
     * How often a job or an instance the node asked does not know yet is asked for again.
     */
    private static final Duration VISIBILITY_INTERVAL = Duration.ofMillis(250);

    private final String adapterId;

    private final TaskKind kind;

    /**
     * What the messages call the kind: "task" or "user task".
     */
    private final String word;

    /**
     * The word which starts a sentence: "Task" or "User task".
     */
    private final String capitalWord;

    private final ZenBpmClientFactory factory;

    private final AdapterCollaborators collaborators;

    private final Function<PhaseTwoRequest<A>, Map<String, Object>> variables;

    /**
     * The handlers of the tasks, or of the user tasks, of one adapter id.
     *
     * @param adapterId The adapter id
     * @param kind The kind of task the handlers end
     * @param factory The factory of the adapter id
     * @param collaborators What the platform hands to the adapter id
     * @param variables The values the aggregate of a request shares, and its id, as a start sends
     *            them
     */
    ZenBpmTaskOutcomes(
        final String adapterId,
        final TaskKind kind,
        final ZenBpmClientFactory factory,
        final AdapterCollaborators collaborators,
        final Function<PhaseTwoRequest<A>, Map<String, Object>> variables
    ) {
        this.adapterId = adapterId;
        this.kind = kind;
        this.word = wordFor(kind);
        this.capitalWord = "%S%s".formatted(word.substring(0, 1), word.substring(1));
        this.factory = factory;
        this.collaborators = collaborators;
        this.variables = variables;
    }

    /**
     * Phase one of a completion: registers the check of the task, to run right before the caller's
     * transaction commits.
     *
     * @param request The request
     * @throws IllegalStateException With the message of the validation where the adapter id has no
     *             clients
     */
    void checkBeforeTheCompletion(final PhaseOneRequest<A> request) {
        registerTheCheck(request, "completes");
    }

    /**
     * Phase one of a cancellation: registers the check of the task, to run right before the
     * caller's transaction commits.
     *
     * @param request The request
     * @throws IllegalStateException With the message of the validation where the adapter id has no
     *             clients
     */
    void checkBeforeTheCancellation(final PhaseOneRequest<A> request) {
        registerTheCheck(request, "cancels");
    }

    /**
     * Phase two of a completion.
     *
     * @param request The request
     * @throws IllegalStateException With the message of the validation where the adapter id has no
     *             clients
     * @throws NumberFormatException Where the task id is no number, which is permanent
     */
    void complete(final PhaseTwoRequest<A> request) {
        final var rest = factory.rest();
        final var key = Long.parseLong(request.taskId());
        final var job = waitingJob(rest, key, request, "completed");
        if (job.isEmpty()) {
            return;
        }
        final var values = variables.apply(request);
        if (!wroteTheValues(rest, job.get(), values, request, "completed")) {
            return;
        }
        try {
            rest.completeJob(key, values);
            log.debug("ZenBPM[{}]: completed {} {} of workflow aggregate '{}'", adapterId, word, key, request
                    .workflowAggregateId());
        } catch (final RuntimeException e) {
            if (!ZenBpmErrors.isGoneJob(e)) {
                throw e;
            }
            refuseAJobWhichCarriesAnIncident(rest, key, "completed", e);
            // the engine drops the lock of a job it refused a command for because it ended
            warnGone(key, request, "completed", "it ended meanwhile");
        }
        endTheCountOfAParkedJob(job.get());
    }

    /**
     * Phase two of a cancellation: a BPMN error with the code the request names.
     *
     * @param request The request
     * @throws IllegalStateException With the message of the validation where the adapter id has no
     *             clients
     * @throws NumberFormatException Where the task id is no number, which is permanent
     */
    void cancel(final PhaseTwoRequest<A> request) {
        final var rest = factory.rest();
        final var key = Long.parseLong(request.taskId());
        final var job = waitingJob(rest, key, request, "cancelled");
        if (job.isEmpty()) {
            return;
        }
        final var values = variables.apply(request);
        if (!wroteTheValues(rest, job.get(), values, request, "cancelled")) {
            return;
        }
        // the error codes of the model were scoped while it was deployed, and the catching event is
        // in the same workflow module as the task
        final var errorCode = collaborators
                .scoping()
                .scopedIdentifier(request.workflowModuleId(), request.bpmnErrorCode(), adapterId);
        // the engine puts the message into the incident only, which it raises where nothing catches
        // the code, and appends the code to it
        final var element = job
                .get()
                .elementId();
        final var message = """
                %s %d (element '%s', workflow aggregate '%s') was cancelled with BPMN error '%s', which no error \
                boundary event and no error event sub-process catches, its code""".formatted(capitalWord, key, element,
                request.workflowAggregateId(), request.bpmnErrorCode());
        try {
            rest.failJob(key, JobFailure
                    .withErrorCode(errorCode, message)
                    .withVariables(values)
                    .ofTheDelivery(0, factory.clientId()));
            log.debug("ZenBPM[{}]: cancelled {} {} of workflow aggregate '{}' with BPMN error '{}'", adapterId, word,
                    key, request.workflowAggregateId(), request.bpmnErrorCode());
        } catch (final RuntimeException e) {
            if (!ZenBpmErrors.isGoneJob(e)) {
                throw e;
            }
            refuseAJobWhichCarriesAnIncident(rest, key, "cancelled", e);
            // the engine drops the lock of a job it refused a command for because it ended
            warnGone(key, request, "cancelled", "it ended meanwhile");
            endTheCountOfAParkedJob(job.get());
            return;
        }
        endTheCountOfAParkedJob(job.get());
        warnWhereNothingCaughtIt(rest, key, errorCode, request);
    }

    /**
     * What the messages call a kind of task.
     *
     * @param kind The kind
     * @return "task" or "user task"
     */
    static String wordFor(final TaskKind kind) {
        return kind == TaskKind.USER_TASK ? "user task" : "task";
    }

    private void registerTheCheck(
        final PhaseOneRequest<A> request,
        final String operation
    ) {
        final var unconfigured = factory.unconfiguredMessage();
        if (unconfigured.isPresent()) {
            throw new IllegalStateException(unconfigured.get());
        }
        final var workflowModuleId = request.workflowModuleId();
        final var taskId = request.taskId();
        collaborators
                .preCommitRegistrar()
                .beforeCommit(request
                        .aggregatePersistence()
                        .getAggregateClass(), () -> check(workflowModuleId, taskId, operation));
    }

    // runs right before the commit, inside the caller's transaction, which a throw rolls back
    private void check(
        final String workflowModuleId,
        final String taskId,
        final String operation
    ) {
        final var key = ZenBpmKeys.keyOf(taskId);
        if (key.isEmpty()) {
            throw taskNotFound(taskId, operation, "it is no key of this engine, which hands out positive numbers only");
        }
        final var rest = factory.rest();
        final var job = jobOf(rest, key.getAsLong(), taskId, operation);
        if (job.isAUserTask() != (kind == TaskKind.USER_TASK)) {
            throw ofTheOtherKind(job, taskId, operation);
        }
        switch (job.state()) {
            case COMPLETED, TERMINATED -> throw taskNotFound(taskId, operation, """
                    the engine holds it as %s: it was completed or cancelled meanwhile, by another call, an event of \
                    the model or the end of its workflow""".formatted(job
                    .state()
                    .name()
                    .toLowerCase()));
            case FAILED -> throw new IllegalStateException("""
                    ZenBPM adapter '%s': %s '%s' carries an incident at the engine, which takes neither a completion \
                    nor a cancellation of the %s until the incident is resolved, so the transaction which %s it is \
                    rolled back. Resolve the incident first; where %s raised it, set that key back to 'report' before, \
                    or the next delivery of the task raises it again."""
                    .formatted(adapterId, word, taskId, word, operation,
                            ZenBpmAdapterConfiguration.propertyKey(adapterId,
                                    "async-task-max-age-action")));
            case ACTIVE -> refuseATaskOfAnotherModule(rest, job, workflowModuleId, taskId, operation);
        }
    }

    // the rule the job dispatcher routes a delivery by: the process of the job's own instance, and
    // the workflow module which serves that process, so a check and a delivery never disagree about
    // whose task a job is
    private void refuseATaskOfAnotherModule(
        final ZenBpmRestClient rest,
        final JobRef job,
        final String workflowModuleId,
        final String taskId,
        final String operation
    ) {
        final Optional<InstanceFacts> facts;
        try {
            facts = withinTheVisibilityWindow(() -> factory
                    .instances()
                    .facts(job.instanceKey()));
        } catch (final RuntimeException e) {
            throw notAnswered(taskId, operation, e);
        }
        if (facts.isEmpty()) {
            throw new IllegalStateException("""
                    ZenBPM adapter '%s': the engine holds %s '%s', but the node asked does not know its process \
                    instance %d, not even after %s, so whose %s it is cannot be said, and the transaction which %s \
                    it is rolled back. Try again; where this lasts, raise %s."""
                    .formatted(adapterId, word, taskId, job.instanceKey(), workflowVisibilityTimeout(), word, operation,
                            ZenBpmAdapterConfiguration.propertyKey(adapterId, "workflow-visibility-timeout")));
        }
        final var scopedProcessId = facts
                .map(InstanceFacts::scopedProcessId)
                .orElseThrow();
        final var servingModule = factory
                .deployedProcesses()
                .moduleServing(scopedProcessId);
        if (servingModule
                .filter(workflowModuleId::equals)
                .isPresent()) {
            return;
        }
        throw new IllegalStateException("""
                ZenBPM adapter '%s': %s '%s' is a %s of process '%s' at the engine, which %s serves, not \
                workflow module '%s', so the transaction which %s it is rolled back. Call the process service of \
                the workflow which the %s belongs to."""
                .formatted(adapterId, word, taskId, word, scopedProcessId, servingModule
                        .map("workflow module '%s'"::formatted)
                        .orElse("no workflow module of this application"), workflowModuleId, operation, word));
    }

    private JobRef jobOf(
        final ZenBpmRestClient rest,
        final long key,
        final String taskId,
        final String operation
    ) {
        final Optional<JobRef> job;
        try {
            job = withinTheVisibilityWindow(() -> rest.job(key));
        } catch (final RuntimeException e) {
            if (ZenBpmKeys.isAKeyOfAPartitionTheEngineDoesNotHave(rest, key, e)) {
                throw taskNotFound(taskId, operation, "it names partition %d, which the engine does not have"
                        .formatted(ZenBpmKeys.partitionOf(key)));
            }
            throw notAnswered(taskId, operation, e);
        }
        return job.orElseThrow(() -> taskNotFound(taskId, operation, """
                the engine does not know it, not even after %s: it is no task this engine handed out, or its \
                workflow ended and the engine removed its history""".formatted(workflowVisibilityTimeout())));
    }

    private IllegalStateException notAnswered(
        final String taskId,
        final String operation,
        final RuntimeException failure
    ) {
        return new IllegalStateException("""
                ZenBPM adapter '%s' could not ask the engine whether %s '%s' still waits, so the transaction which \
                %s it is rolled back. Try again once the engine at %s answers within %s."""
                .formatted(adapterId, word, taskId, operation, ZenBpmAdapterConfiguration.propertyKey(adapterId,
                        "rest-address"),
                        ZenBpmAdapterConfiguration.propertyKey(adapterId, "request-timeout")), failure);
    }

    // a read is answered by a node of the engine which may lag behind, so what it does not know yet
    // is asked for again; an interrupted thread asks no more and keeps its interrupt
    private <T> Optional<T> withinTheVisibilityWindow(final Supplier<Optional<T>> read) {
        final var deadline = Instant
                .now()
                .plus(workflowVisibilityTimeout());
        var found = read.get();
        while (found.isEmpty() && Instant
                .now()
                .isBefore(deadline)) {
            try {
                Thread.sleep(VISIBILITY_INTERVAL);
            } catch (final InterruptedException e) {
                Thread
                        .currentThread()
                        .interrupt();
                return found;
            }
            found = read.get();
        }
        return found;
    }

    private Duration workflowVisibilityTimeout() {
        return factory
                .configuration()
                .getWorkflowVisibilityTimeout();
    }

    private TaskNotFoundException taskNotFound(
        final String taskId,
        final String operation,
        final String why
    ) {
        log.info("ZenBPM[{}]: the check before the commit found {} '{}' gone: {}", adapterId, word, taskId, why);
        return new TaskNotFoundException("""
                ZenBPM adapter '%s': %s '%s' is gone, %s. So the transaction which %s it is rolled back."""
                .formatted(adapterId, word, taskId, why, operation));
    }

    // the platform asks with the id of the kind the caller named, and other engines find nothing
    // of the other kind under it, so neither does this one, whatever the state of the job
    private TaskNotFoundException ofTheOtherKind(
        final JobRef job,
        final String taskId,
        final String operation
    ) {
        final var element = job.isAUserTask() ? "a user task" : "a %s, which is no user task".formatted(job
                .elementType());
        final var methods = job.isAUserTask() ? "completeUserTask or #cancelUserTask" : "completeTask or #cancelTask";
        log.info("ZenBPM[{}]: the check before the commit found no {} '{}': its job is one of {}", adapterId, word,
                taskId, element);
        return new TaskNotFoundException("""
                ZenBPM adapter '%s': there is no %s '%s': the engine holds a job under that key, but of element '%s', \
                %s. So the transaction which %s it is rolled back. A job of that element is ended by \
                ProcessService#%s."""
                .formatted(adapterId, word, taskId, job.elementId(), element, operation, methods));
    }

    // a node which lags behind answers a job in an earlier state, never in a later one, so a job
    // it answers as ended has ended; one it does not know yet is asked for again by the outbox, and
    // so is one which carries an incident, which a resolution makes wait again
    private Optional<JobRef> waitingJob(
        final ZenBpmRestClient rest,
        final long key,
        final PhaseTwoRequest<A> request,
        final String answer
    ) {
        final var job = rest
                .job(key)
                .orElseThrow(() -> new IllegalStateException(
                        "ZenBPM adapter '%s': the engine node asked does not know %s %d yet, so it is not %s now"
                                .formatted(adapterId, word, key, answer)));
        if (job.state() == JobRef.State.ACTIVE) {
            return Optional.of(job);
        }
        if (job.state() == JobRef.State.FAILED) {
            throw carriesAnIncident(key, answer);
        }
        // nothing is sent, so the engine keeps the lock of a job this node parked until it lapses,
        // and the count stays as long
        warnGone(key, request, answer, "the engine holds it as %s".formatted(job
                .state()
                .name()
                .toLowerCase()));
        return Optional.empty();
    }

    // the engine refuses a command for a job which carries an incident as it refuses one for a job
    // which ended, so after such a refusal the job is read again: what a resolution of the incident
    // makes wait again is not over, and the outbox repeats it
    private void refuseAJobWhichCarriesAnIncident(
        final ZenBpmRestClient rest,
        final long key,
        final String answer,
        final RuntimeException refusal
    ) {
        final boolean failed;
        try {
            failed = rest
                    .job(key)
                    .map(JobRef::state)
                    .filter(JobRef.State.FAILED::equals)
                    .isPresent();
        } catch (final RuntimeException e) {
            e.addSuppressed(refusal);
            throw e;
        }
        if (failed) {
            throw carriesAnIncident(key, answer);
        }
    }

    private IllegalStateException carriesAnIncident(
        final long key,
        final String answer
    ) {
        return new IllegalStateException("""
                ZenBPM adapter '%s': %s %d carries an incident at the engine, which takes neither a completion nor a \
                cancellation of it until the incident is resolved, so it is not %s now, and the outbox repeats it. \
                Resolve the incident; where %s raised it, set that key back to 'report' before, or the next delivery \
                of the task raises it again.""".formatted(adapterId, word, key, answer, ZenBpmAdapterConfiguration
                .propertyKey(adapterId, "async-task-max-age-action")));
    }

    // see decision 35 and decision 34 in the repository's DECISIONS.md: an update which finds no
    // instance is thrown, so the outbox repeats it, and one which finds the workflow ended ends the
    // operation
    private boolean wroteTheValues(
        final ZenBpmRestClient rest,
        final JobRef job,
        final Map<String, Object> values,
        final PhaseTwoRequest<A> request,
        final String answer
    ) {
        final List<Long> path = factory
                .instances()
                .instancesUpToTheWorkflow(job.instanceKey())
                .orElseThrow(() -> new IllegalStateException("""
                        ZenBPM adapter '%s': an instance above instance %d of %s %d is not known to the engine node \
                        asked, which may lag behind, so it is not %s now""".formatted(adapterId, job.instanceKey(),
                        word, job.key(), answer)));
        for (final var instanceKey : path) {
            try {
                rest.patchVariables(instanceKey, values);
            } catch (final RuntimeException e) {
                if (!ZenBpmErrors.variablesFoundTheInstanceEnded(e)) {
                    throw e;
                }
                warnGone(job.key(), request, answer, "its workflow ended or was cancelled meanwhile");
                return false;
            }
        }
        return true;
    }

    // the engine answers a BPMN error alike whether an event caught it or it raised an incident, so
    // the job is read once: a node which lags behind may still answer it active, and then nothing is
    // said, but the incident is there all the same
    private void warnWhereNothingCaughtIt(
        final ZenBpmRestClient rest,
        final long key,
        final String errorCode,
        final PhaseTwoRequest<A> request
    ) {
        final Optional<JobRef> job;
        try {
            job = rest.job(key);
        } catch (final RuntimeException e) {
            log.debug("ZenBPM[{}]: {} {} could not be read after its cancellation: {}", adapterId, word, key, e
                    .getMessage());
            return;
        }
        if (job
                .map(JobRef::state)
                .filter(JobRef.State.FAILED::equals)
                .isEmpty()) {
            return;
        }
        log.warn("""
                ZenBPM[{}]: no error boundary event and no error event sub-process catches the BPMN error '{}' with \
                which {} {} (workflow aggregate '{}') was cancelled, whose code is '{}' at the engine, so the engine \
                raised an incident on the {}. Model an event which catches the code, or resolve or cancel the \
                instance.""", adapterId, request.bpmnErrorCode(), word, key, request.workflowAggregateId(), errorCode,
                word);
    }

    private void warnGone(
        final long key,
        final PhaseTwoRequest<A> request,
        final String answer,
        final String why
    ) {
        log.warn("""
                ZenBPM[{}]: {} {} of workflow aggregate '{}' is not {}: {}. The application's work is committed: \
                this is what at-least-once delivery leaves over.""", adapterId, word, key,
                request.workflowAggregateId(),
                answer, why);
    }

    private void endTheCountOfAParkedJob(final JobRef job) {
        factory
                .stream()
                .parkedJobEnded(job.type(), job.key());
    }

}

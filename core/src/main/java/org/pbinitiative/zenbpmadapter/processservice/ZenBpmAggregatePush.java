/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.processservice;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import org.pbinitiative.zenbpmadapter.client.ZenBpmClientFactory;
import org.pbinitiative.zenbpmadapter.client.ZenBpmErrors;
import org.pbinitiative.zenbpmadapter.client.ZenBpmRestClient;
import org.pbinitiative.zenbpmadapter.client.api.ProcessInstanceRef;

import io.vanillabp.integration.adapter.spi.AdapterCollaborators;
import io.vanillabp.integration.adapter.spi.PhaseOneRequest;
import io.vanillabp.integration.adapter.spi.PhaseTwoRequest;
import io.vanillabp.integration.adapter.spi.WorkflowScope;
import lombok.extern.slf4j.Slf4j;

/**
 * Pushing a changed aggregate into the engine, in both phases.
 * <p>
 * <b>Phase one</b> asks nothing: the core found the workflow before, and the engine must not see
 * the values of a transaction which may still roll back.
 * <p>
 * <b>Phase two</b> writes the values the aggregate shares now, and its id, with <code>PATCH
 * /v1/process-instances/{key}/variables</code>. The engine evaluates a condition on the variables
 * of the instance its token runs in, and an instance the engine starts below another one, an
 * embedded sub-process, an iteration of a multi-instance element or a called process, holds a copy
 * of its own taken when it started, see decision 35 in the repository's DECISIONS.md.
 * <p>
 * A push of the workflow, without a task, writes the workflow's root first and then every instance
 * below it which runs, at any depth, each before the instances below it are read, so an instance
 * started meanwhile copies the new values from its parent. The walk does not enter an instance a call
 * activity started on another aggregate, because what runs there is not this aggregate's business,
 * see decision 49 in the repository's DECISIONS.md. Where the platform knows the called process, it
 * says whether the called process shares the aggregate of its caller; for any other called process,
 * the instance's business key or id variable has to name the aggregate. The root is the instance of the key
 * VanillaBP wrote down for the workflow where it runs the workflow, else the root instance the
 * aggregate's business key finds, else, under <code>message-start-lookup: scan</code>, the running
 * instance of a process a message starts whose id variable names the aggregate. Where none runs,
 * the push is a warned no-op: the core found the workflow when it dispatched the push, so it ended
 * since. Where the engine did not answer for the key and nothing else finds the workflow, the failure
 * is thrown, so the outbox repeats the push: the key is the only way to a workflow the engine
 * started.
 * <p>
 * A push into the scope of a task writes the instance the task's job waits in, which is the
 * instance of the sub-process or the iteration the task runs in, and nothing else: the root and
 * every other branch keep their values, and the task's completion writes the values up to the root
 * later. The job is read for its instance, because the row VanillaBP keeps of a task names the
 * workflow and not the instance the task waits in. A job whose instance ended makes the push a
 * warned no-op: writing the root instead would be a push of the workflow, which the application did
 * not ask for. A job the engine answers <code>404</code> for is thrown, so the outbox repeats the
 * push: the engine keeps a job until the history of its instance is removed, so the answer comes
 * from a node which lags behind. The push is not skipped while a handler holds
 * the task: no field of the task's row says so, and the handler sees the job's variables of its
 * creation either way.
 * <p>
 * An instance which ended between its reading and its writing is passed over. The engine answers
 * <code>404</code> to the write of an instance a node which lags behind does not know yet, which is
 * thrown, so the outbox repeats the push, see decision 34 in the repository's DECISIONS.md.
 *
 * @param <A> The workflow aggregate type
 */
@Slf4j
final class ZenBpmAggregatePush<A> {

    private final String adapterId;

    private final ZenBpmClientFactory factory;

    private final AdapterCollaborators collaborators;

    private final Function<PhaseTwoRequest<A>, Map<String, Object>> variables;

    private final ZenBpmRunningWorkflows runningWorkflows;

    private final ZenBpmMessageStartScan messageStartScan;

    /**
     * The handler of the pushes of one adapter id.
     *
     * @param adapterId The adapter id
     * @param factory The factory of the adapter id
     * @param collaborators What the platform hands to the adapter id
     * @param variables The values the aggregate of a request shares, and its id, as a start sends
     *            them
     * @param runningWorkflows How the probes find the instance of a workflow
     * @param messageStartScan The lookup of a workflow a message started, under
     *            <code>message-start-lookup: scan</code>
     */
    ZenBpmAggregatePush(
        final String adapterId,
        final ZenBpmClientFactory factory,
        final AdapterCollaborators collaborators,
        final Function<PhaseTwoRequest<A>, Map<String, Object>> variables,
        final ZenBpmRunningWorkflows runningWorkflows,
        final ZenBpmMessageStartScan messageStartScan
    ) {
        this.adapterId = adapterId;
        this.factory = factory;
        this.collaborators = collaborators;
        this.variables = variables;
        this.runningWorkflows = runningWorkflows;
        this.messageStartScan = messageStartScan;
    }

    /**
     * Phase one: refuses only where the adapter id has no clients.
     *
     * @param request The request
     * @throws IllegalStateException With the message of the validation where the adapter id has no
     *             clients
     */
    void checkNothing(final PhaseOneRequest<A> request) {
        final var unconfigured = factory.unconfiguredMessage();
        if (unconfigured.isPresent()) {
            throw new IllegalStateException(unconfigured.get());
        }
    }

    /**
     * Phase two: writes the values into the workflow, or into the scope of the task the request
     * names.
     *
     * @param request The request
     * @throws IllegalStateException With the message of the validation where the adapter id has no
     *             clients
     */
    void push(final PhaseTwoRequest<A> request) {
        final var rest = factory.rest();
        if (request.taskId() == null) {
            pushIntoTheWorkflow(rest, request);
            return;
        }
        pushIntoTheScopeOfTheTask(rest, request);
    }

    private void pushIntoTheWorkflow(
        final ZenBpmRestClient rest,
        final PhaseTwoRequest<A> request
    ) {
        final var aggregateId = String.valueOf(request.workflowAggregateId());
        final var idVariableName = request
                .aggregatePersistence()
                .getAggregateIdName();
        final var scope = WorkflowScope.of(request.workflowModuleId(), request.bpmnProcessId());
        final var scopedIds = Set.of(collaborators
                .scoping()
                .scopedProcessId(request.workflowModuleId(), request.bpmnProcessId(), adapterId));
        Optional<ProcessInstanceRef> ofTheKey;
        RuntimeException failureOfTheKey = null;
        try {
            ofTheKey = runningWorkflows.ofTheKeyOrFailure(rest, scopedIds, idVariableName, aggregateId, request
                    .workflowId());
        } catch (final RuntimeException failure) {
            ofTheKey = Optional.empty();
            failureOfTheKey = failure;
        }
        final var root = ofTheKey
                .or(() -> ZenBpmRunningWorkflows.byBusinessKey(rest, scopedIds, aggregateId))
                .or(() -> startedByAMessage(rest, scope, scopedIds, idVariableName, aggregateId));
        if (root.isEmpty()) {
            // a workflow the engine started has no business key, so the key may be the only way to
            // it: an engine which did not answer for the key says nothing about whether it ended
            if (failureOfTheKey != null) {
                throw failureOfTheKey;
            }
            warnNothingRuns(request);
            return;
        }
        final var values = variables.apply(request);
        if (!wrote(rest, root
                .get()
                .key(), values)) {
            warnNothingRuns(request);
            return;
        }
        final var below = pushIntoTheInstancesBelow(rest, request.workflowModuleId(), root.get(), idVariableName,
                aggregateId, values);
        log.info("ZenBPM[{}]: pushed the changed aggregate '{}' into workflow {} and the instances {} below it",
                adapterId, aggregateId, root
                        .get()
                        .key(),
                below);
    }

    // see decision 49 in the repository's DECISIONS.md: an instance is written before the
    // instances below it are read, and a called instance of another aggregate is not entered
    private List<Long> pushIntoTheInstancesBelow(
        final ZenBpmRestClient rest,
        final String workflowModuleId,
        final ProcessInstanceRef root,
        final String idVariableName,
        final String aggregateId,
        final Map<String, Object> values
    ) {
        final var written = new ArrayList<Long>();
        final var parents = new ArrayDeque<ProcessInstanceRef>();
        parents.add(root);
        while (!parents.isEmpty()) {
            final var parent = parents.poll();
            for (final var child : rest.children(parent.key())) {
                if (!ZenBpmRunningWorkflows.isRunning(child.state()) || isCalledOnAnotherAggregate(workflowModuleId,
                        parent, child, idVariableName, aggregateId)) {
                    continue;
                }
                if (wrote(rest, child.key(), values)) {
                    written.add(child.key());
                    parents.add(child);
                }
            }
        }
        return written;
    }

    private void pushIntoTheScopeOfTheTask(
        final ZenBpmRestClient rest,
        final PhaseTwoRequest<A> request
    ) {
        final var taskId = request.taskId();
        final var key = ZenBpmKeys.keyOf(taskId);
        if (key.isEmpty()) {
            warnTheScopeIsGone(request, "it is no key of this engine");
            return;
        }
        // the engine keeps a job until the history of its instance is removed, so a 404 comes from
        // a node which lags behind, see decision 34 in the repository's DECISIONS.md
        final var job = rest.job(key.getAsLong());
        if (job.isEmpty()) {
            throw new IllegalStateException(
                    "ZenBPM[%s]: the engine does not know task '%s' of workflow '%s' of workflow module '%s' yet, so the changed aggregate '%s' is not pushed into its scope now; a node which lags behind answers so for a task handed out a moment ago, and the outbox repeats the push"
                            .formatted(adapterId, taskId, request.bpmnProcessId(), request.workflowModuleId(), request
                                    .workflowAggregateId()));
        }
        final var scope = job
                .get()
                .instanceKey();
        if (!wrote(rest, scope, variables.apply(request))) {
            warnTheScopeIsGone(request, "the instance %d it waits in ended".formatted(scope));
            return;
        }
        log.info("ZenBPM[{}]: pushed the changed aggregate '{}' into instance {}, which task '{}' waits in",
                adapterId, request.workflowAggregateId(), scope, taskId);
    }

    // under message-start-lookup: scan a workflow a message started is found as the probes find it
    private Optional<ProcessInstanceRef> startedByAMessage(
        final ZenBpmRestClient rest,
        final WorkflowScope scope,
        final Set<String> scopedIds,
        final String idVariableName,
        final String aggregateId
    ) {
        final var toScan = messageStartScan.processesToScan(scope, scopedIds);
        if (toScan.isEmpty()) {
            return Optional.empty();
        }
        return messageStartScan.running(rest, toScan, idVariableName, aggregateId);
    }

    // an instance which ended meanwhile takes no values, and a node which lags behind and does not
    // know it yet is thrown, so the outbox repeats the push
    private static boolean wrote(
        final ZenBpmRestClient rest,
        final long instanceKey,
        final Map<String, Object> values
    ) {
        try {
            rest.patchVariables(instanceKey, values);
            return true;
        } catch (final RuntimeException failure) {
            if (ZenBpmErrors.variablesFoundTheInstanceEnded(failure)) {
                return false;
            }
            throw failure;
        }
    }

    // the engine hands a called instance a copy of its caller's variables, so the caller's id
    // variable says nothing about the aggregate of the called process: where the platform knows the
    // called process, it tells whether the two share the aggregate, as it does for the rewrite of
    // the call while deploying, see decision 45 in the repository's DECISIONS.md
    private boolean isCalledOnAnotherAggregate(
        final String workflowModuleId,
        final ProcessInstanceRef parent,
        final ProcessInstanceRef child,
        final String idVariableName,
        final String aggregateId
    ) {
        if (child.processType() != ProcessInstanceRef.ProcessType.CALL_ACTIVITY) {
            return false;
        }
        final var scoping = collaborators.scoping();
        final var wiring = collaborators.workflowTaskWiring();
        final var called = scoping.plainProcessId(workflowModuleId, child.bpmnProcessId(), adapterId);
        if (wiring.isClaimedByAWorkflowService(workflowModuleId, called)) {
            return !wiring.workflowsShareTheWorkflowAggregate(workflowModuleId, scoping.plainProcessId(
                    workflowModuleId, parent.bpmnProcessId(), adapterId), called);
        }
        return !ZenBpmRunningWorkflows.isTheWorkflowOf(aggregateId, idVariableName, child);
    }

    private void warnNothingRuns(final PhaseTwoRequest<A> request) {
        log.warn(
                "ZenBPM[{}]: no running workflow '{}' of workflow module '{}' was found for aggregate '{}', so the changed aggregate is not pushed; the workflow ended after the push was planned, and the outbox entry is consumed",
                adapterId, request.bpmnProcessId(), request.workflowModuleId(), request.workflowAggregateId());
    }

    private void warnTheScopeIsGone(
        final PhaseTwoRequest<A> request,
        final String why
    ) {
        log.warn(
                "ZenBPM[{}]: the changed aggregate '{}' is not pushed into the scope of task '{}' of workflow '{}' of workflow module '{}', because {}; the workflow's own scope is not written instead, and the outbox entry is consumed",
                adapterId, request.workflowAggregateId(), request.taskId(), request.bpmnProcessId(), request
                        .workflowModuleId(),
                why);
    }

}

/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.processservice;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.pbinitiative.zenbpmadapter.client.ZenBpmRestClient;
import org.pbinitiative.zenbpmadapter.client.api.InstanceFilter;
import org.pbinitiative.zenbpmadapter.client.api.ProcessInstanceRef;

import lombok.extern.slf4j.Slf4j;

/**
 * The instance which runs the workflow of an aggregate, as the probes and the push of an aggregate
 * find it: by the key VanillaBP wrote down for the workflow, and by the aggregate's business key.
 * <p>
 * The key may only make a YES shorter: an instance which runs, of a process of the scope, whose
 * business key or id variable is the aggregate's id, is the workflow. Everything else the key leads
 * to is not: another state, because an aggregate may carry a second workflow once its first ended
 * and the key may name the first; another process or another aggregate, because the core hands the
 * same key to every adapter it asks; a <code>404</code>, which cannot tell an ended workflow from one
 * the engine never held; a <code>502</code>, which the engine answers for a key of a partition it
 * does not have; and any failure. A workflow the engine started has no business key, so the key is
 * the only way to find it, see decision 25 in the repository's DECISIONS.md.
 */
@Slf4j
final class ZenBpmRunningWorkflows {

    /**
     * The states of an instance which runs, an incident included: an operator resolves it, or it is
     * cancelled.
     */
    private static final List<ProcessInstanceRef.State> RUNNING = List.of(ProcessInstanceRef.State.ACTIVE,
            ProcessInstanceRef.State.FAILED);

    private final String adapterId;

    /**
     * The lookups of one adapter id.
     *
     * @param adapterId The adapter id, which the log names
     */
    ZenBpmRunningWorkflows(final String adapterId) {
        this.adapterId = adapterId;
    }

    /**
     * The instance a key names, where it runs the workflow of the aggregate in the scope.
     *
     * @param rest The REST client of the adapter id
     * @param scopedIds The processes of the scope, as the engine knows them
     * @param idVariableName The name of the aggregate's id variable
     * @param aggregateId The id of the aggregate
     * @param workflowId The key VanillaBP holds, <code>null</code> where it holds none
     * @return The instance, empty where the key leads to anything else, a failure included
     */
    Optional<ProcessInstanceRef> ofTheKey(
        final ZenBpmRestClient rest,
        final Set<String> scopedIds,
        final String idVariableName,
        final String aggregateId,
        final String workflowId
    ) {
        final var key = ZenBpmKeys.keyOf(workflowId);
        if (key.isEmpty()) {
            return Optional.empty();
        }
        try {
            return rest
                    .instance(key.getAsLong())
                    .filter(instance -> isRunning(instance.state()))
                    .filter(instance -> scopedIds.contains(instance.bpmnProcessId()))
                    .filter(instance -> isTheWorkflowOf(aggregateId, idVariableName, instance));
        } catch (final RuntimeException failure) {
            log.debug(
                    "ZenBPM[{}]: the engine could not be asked for workflow '{}' of aggregate '{}', so the search answers",
                    adapterId, workflowId, aggregateId, failure);
            return Optional.empty();
        }
    }

    /**
     * The root instance which runs under the aggregate's business key, of a process of the scope.
     * The engine hands the business key of an instance on to the instances it starts below it, so
     * only an instance without a parent counts. One whose state the search reads first wins: an
     * instance which waits, else one with an incident.
     *
     * @param rest The REST client of the adapter id
     * @param scopedIds The processes of the scope, as the engine knows them
     * @param aggregateId The id of the aggregate
     * @return The instance, empty where none runs
     * @throws RuntimeException What the REST client throws where the engine did not answer
     */
    static Optional<ProcessInstanceRef> byBusinessKey(
        final ZenBpmRestClient rest,
        final Set<String> scopedIds,
        final String aggregateId
    ) {
        for (final var scopedId : scopedIds) {
            for (final var state : RUNNING) {
                // the instances below a root are created after it, so their keys are higher and the
                // root comes first
                final var root = rest
                        .instances(InstanceFilter
                                .anyInstance()
                                .withBusinessKey(aggregateId)
                                .withBpmnProcessId(scopedId)
                                .withState(state))
                        .items()
                        .stream()
                        .filter(instance -> instance.parentKey() == null)
                        .findFirst();
                if (root.isPresent()) {
                    return root;
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Whether an instance runs: it waits, or it carries an incident.
     *
     * @param state The state of the instance
     * @return Whether it runs
     */
    static boolean isRunning(final ProcessInstanceRef.State state) {
        return RUNNING.contains(state);
    }

    /**
     * Whether an instance is one of the workflow of the aggregate: its business key or its id
     * variable is the aggregate's id.
     *
     * @param aggregateId The id of the aggregate
     * @param idVariableName The name of the aggregate's id variable
     * @param instance The instance
     * @return Whether it names the aggregate
     */
    static boolean isTheWorkflowOf(
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

}

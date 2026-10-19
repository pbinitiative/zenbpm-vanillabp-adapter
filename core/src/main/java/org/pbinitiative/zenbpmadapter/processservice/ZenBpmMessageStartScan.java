/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.processservice;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.pbinitiative.zenbpmadapter.ZenBpmDeployedProcesses.ModuleMessages;
import org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration;
import org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration.MessageStartLookup;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientFactory;
import org.pbinitiative.zenbpmadapter.client.ZenBpmRestClient;
import org.pbinitiative.zenbpmadapter.client.api.InstanceFilter;
import org.pbinitiative.zenbpmadapter.client.api.ProcessInstanceRef;

import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.adapter.spi.WorkflowScope;

/**
 * The third step of the probes for a workflow under <code>message-start-lookup: scan</code>: where
 * neither the key the core holds nor the business key finds the workflow, the instances of the
 * processes of the scope which a message starts are read, and the one whose id variable names the
 * aggregate is the workflow. The engine gives a workflow it starts by a message no business key, so
 * this finds one whose start row does not exist yet or any more.
 * <p>
 * The probe of the election reads the instances which run, those with an incident included. The
 * probe asked before a start is sent again reads every instance created from the moment the start
 * was planned, ended ones included, and where nobody knows that moment, those which run. A lookup
 * reads at most <code>message-start-lookup-max-instances</code> instances, all processes and pages
 * together, and throws once it would read more without having found the workflow, which the probe
 * answers as {@link WorkflowAwareness#BPMS_UNAVAILABLE}. A workflow of such a process which ended is
 * not read by the election's probe, so it stays unknown to it, which the platform's contract for
 * adapters does not allow; that is why the step is an opt-in.
 */
final class ZenBpmMessageStartScan {

    /**
     * The states of the instances the probe of the election reads: those which run, an incident
     * included.
     */
    private static final List<ProcessInstanceRef.State> RUNNING = List.of(ProcessInstanceRef.State.ACTIVE,
            ProcessInstanceRef.State.FAILED);

    /**
     * No filter by state, so every instance is read.
     */
    private static final List<ProcessInstanceRef.State> EVERY_STATE = Collections.singletonList(null);

    private final String adapterId;

    private final ZenBpmClientFactory factory;

    /**
     * The scan of one adapter id.
     *
     * @param adapterId The adapter id
     * @param factory The factory of the adapter id, which knows the configuration and the processes
     *            the workflow modules deployed
     */
    ZenBpmMessageStartScan(
        final String adapterId,
        final ZenBpmClientFactory factory
    ) {
        this.adapterId = adapterId;
        this.factory = factory;
    }

    /**
     * The processes of the scope a lookup reads: those of which a version the engine holds has a
     * message start event, as the deployment of this start read them, because a workflow a message
     * started on an older version runs on after a newer one dropped the event; none where
     * <code>message-start-lookup</code> is not <code>scan</code>.
     *
     * @param scope The workflow module and the processes asked about
     * @param scopedIds The ids, as the engine knows them, of the processes of the scope
     * @return The ids to read, empty where nothing is read
     */
    Set<String> processesToScan(
        final WorkflowScope scope,
        final Set<String> scopedIds
    ) {
        if (factory
                .configuration()
                .getMessageStartLookup() != MessageStartLookup.SCAN) {
            return Set.of();
        }
        final var startedByMessage = factory
                .deployedProcesses()
                .messagesOf(scope.workflowModuleId())
                .map(ModuleMessages::startedByMessage)
                .orElse(Set.of());
        final var toScan = new LinkedHashSet<String>(scopedIds);
        toScan.retainAll(startedByMessage);
        return toScan;
    }

    /**
     * Looks for the workflow of an aggregate among the instances of the given processes.
     *
     * @param rest The REST client of the adapter id
     * @param scopedIds The processes to read, as the engine knows them
     * @param idVariableName The name of the aggregate's id variable
     * @param aggregateId The id of the aggregate
     * @param createdFrom The moment from which every instance counts, ended ones included;
     *            <code>null</code> to read the instances which run
     * @return {@link WorkflowAwareness#ACTIVE} where such an instance runs,
     *         {@link WorkflowAwareness#COMPLETED} where every one read ended, and
     *         {@link WorkflowAwareness#UNKNOWN_TO_BPMS} where none was read
     * @throws BoundReached Where more instances would have to be read than
     *             <code>message-start-lookup-max-instances</code> allows
     */
    WorkflowAwareness find(
        final ZenBpmRestClient rest,
        final Set<String> scopedIds,
        final String idVariableName,
        final String aggregateId,
        final Instant createdFrom
    ) {
        final var found = lookUp(rest, scopedIds, idVariableName, aggregateId, createdFrom);
        if (found.running() != null) {
            return WorkflowAwareness.ACTIVE;
        }
        return found.endedOnes() ? WorkflowAwareness.COMPLETED : WorkflowAwareness.UNKNOWN_TO_BPMS;
    }

    /**
     * The instance which runs the workflow of an aggregate, among the running instances of the
     * given processes.
     *
     * @param rest The REST client of the adapter id
     * @param scopedIds The processes to read, as the engine knows them
     * @param idVariableName The name of the aggregate's id variable
     * @param aggregateId The id of the aggregate
     * @return The first instance read whose id variable names the aggregate, empty where none does
     * @throws BoundReached Where more instances would have to be read than
     *             <code>message-start-lookup-max-instances</code> allows
     */
    Optional<ProcessInstanceRef> running(
        final ZenBpmRestClient rest,
        final Set<String> scopedIds,
        final String idVariableName,
        final String aggregateId
    ) {
        return Optional.ofNullable(lookUp(rest, scopedIds, idVariableName, aggregateId, null).running());
    }

    private Found lookUp(
        final ZenBpmRestClient rest,
        final Set<String> scopedIds,
        final String idVariableName,
        final String aggregateId,
        final Instant createdFrom
    ) {
        final var bound = factory
                .configuration()
                .getMessageStartLookupMaxInstances();
        final var states = createdFrom == null ? RUNNING : EVERY_STATE;
        var read = 0;
        var endedOnes = false;
        for (final var scopedId : scopedIds) {
            for (final var state : states) {
                var filter = InstanceFilter
                        .anyInstance()
                        .withBpmnProcessId(scopedId)
                        .withState(state)
                        .withCreatedFrom(createdFrom);
                while (true) {
                    final var page = rest.instances(filter);
                    for (final var instance : page.items()) {
                        read += 1;
                        if (read > bound) {
                            throw new BoundReached(adapterId, bound, new ArrayList<>(scopedIds));
                        }
                        if (!namesTheAggregate(instance, idVariableName, aggregateId)) {
                            continue;
                        }
                        if (isRunning(instance.state())) {
                            return new Found(instance, endedOnes);
                        }
                        endedOnes = true;
                    }
                    if (page.last()) {
                        break;
                    }
                    filter = filter.withPage(filter.page() + 1);
                }
            }
        }
        return new Found(null, endedOnes);
    }

    private static boolean namesTheAggregate(
        final ProcessInstanceRef instance,
        final String idVariableName,
        final String aggregateId
    ) {
        final var idVariable = instance
                .variables()
                .get(idVariableName);
        return (idVariable != null) && aggregateId.equals(String.valueOf(idVariable));
    }

    private static boolean isRunning(final ProcessInstanceRef.State state) {
        return (state == ProcessInstanceRef.State.ACTIVE) || (state == ProcessInstanceRef.State.FAILED);
    }

    /**
     * A lookup which would read more instances than <code>message-start-lookup-max-instances</code>
     * allows, without having found the workflow.
     */
    static final class BoundReached extends IllegalStateException {

        private static final long serialVersionUID = 1L;

        /**
         * The lookup which reached the bound.
         *
         * @param adapterId The adapter id
         * @param bound The bound
         * @param scopedIds The processes read
         */
        BoundReached(
            final String adapterId,
            final int bound,
            final List<String> scopedIds
        ) {
            super("""
                    the processes %s hold more instances to read than %s allows (%d), so the workflow was not looked \
                    for among all of them; raise that bound, or set %s to '%s' to leave the lookup to the start row \
                    alone""".formatted(scopedIds, ZenBpmAdapterConfiguration.propertyKey(adapterId,
                    "message-start-lookup-max-instances"), bound,
                    ZenBpmAdapterConfiguration.propertyKey(adapterId,
                            "message-start-lookup"),
                    MessageStartLookup.START_ROW.configured()));
        }

    }


    /**
     * What a lookup found.
     *
     * @param running The first instance read which runs the workflow, <code>null</code> where none
     *            does
     * @param endedOnes Whether an instance of the workflow which ended was read before
     */
    private record Found(
        ProcessInstanceRef running,
        boolean endedOnes
    ) {
    }

}

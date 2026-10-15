/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.processservice;

import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.pbinitiative.zenbpmadapter.client.ZenBpmClientFactory;

import io.vanillabp.integration.adapter.spi.AdapterCollaborators;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.adapter.spi.PhaseOperationHandler;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.adapter.spi.WorkflowScope;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.spi.process.ProcessDefinition;
import io.vanillabp.spi.process.WorkflowHistory;

/**
 * What the core asks of one ZenBPM adapter id at runtime: one instance per configured adapter
 * id, built by the platform modules.
 * <p>
 * This version of the adapter answers who it is and nothing about the engine yet. It serves every
 * operation the core requires of each adapter, so the core accepts it while the application
 * starts, and every handler, every probe and every method of the viewer/history API throws:
 * where the adapter id has no clients, the
 * message of its validation, which names the keys to set; otherwise an
 * {@link UnsupportedOperationException} which says that the call is not built yet. Nothing pretends
 * to have done the work. The operations a BPMS may lack, sending a signal and telling the BPMS about
 * a changed aggregate, are left out, so the core answers them as not supported.
 * <p>
 * Each field has an accessor of its own and no Lombok getter on the class, see decision 13 in the
 * repository's DECISIONS.md.
 *
 * @param <A> The workflow aggregate type
 */
@SuppressWarnings("LombokGetterMayBeUsed")
public class ZenBpmProcessService<A> implements MigratableProcessService<A> {

    private final String adapterId;

    private final ZenBpmClientFactory factory;

    private final AdapterCollaborators collaborators;

    private final Map<PhaseOperation, PhaseOperationHandler<A>> phaseOperations;

    /**
     * Builds the process service of one adapter id.
     *
     * @param adapterId The adapter id
     * @param factory The factory of the adapter id, from the registry
     * @param collaborators What the platform hands to the adapter id
     * @throws IllegalArgumentException Where the factory or the collaborators belong to another
     *             adapter id, a defect of the platform module's wiring
     */
    public ZenBpmProcessService(
        final String adapterId,
        final ZenBpmClientFactory factory,
        final AdapterCollaborators collaborators
    ) {
        if (!adapterId.equals(factory.getAdapterId()) || !adapterId.equals(collaborators.adapterId())) {
            throw new IllegalArgumentException(
                    "The process service of ZenBPM adapter id '%s' was given the factory of adapter id '%s' and the collaborators of adapter id '%s'; the platform module wires the three of one id."
                            .formatted(adapterId, factory.getAdapterId(), collaborators.adapterId()));
        }
        this.adapterId = adapterId;
        this.factory = factory;
        this.collaborators = collaborators;
        final var handlers = new LinkedHashMap<PhaseOperation, PhaseOperationHandler<A>>();
        PhaseOperation.CORE_OPERATIONS
                .stream()
                .filter(PhaseOperation::requiredOfEveryAdapter)
                .forEach(operation -> handlers.put(operation, PhaseOperationHandler.of(request -> {
                    throw notBuiltYet("phase one of %s".formatted(operation.name()));
                }, request -> {
                    throw notBuiltYet("phase two of %s".formatted(operation.name()));
                })));
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
     * A handler for every operation the core requires of each adapter, each of which throws.
     *
     * @return The handlers, which cannot be changed
     */
    @Override
    public Map<PhaseOperation, PhaseOperationHandler<A>> phaseOperations() {
        return phaseOperations;
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

}

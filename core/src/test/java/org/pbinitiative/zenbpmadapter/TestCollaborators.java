/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter;

import java.lang.reflect.Proxy;

import io.vanillabp.integration.adapter.spi.AdapterCollaborators;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;
import io.vanillabp.integration.adapter.spi.PreCommitRegistrar;
import io.vanillabp.integration.adapter.spi.WorkflowAggregateSync;
import io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedInvoker;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartInvoker;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring;

/**
 * What the platform hands to an adapter id, for a test which builds a service of this adapter by
 * hand. Every collaborator fails the test where it is called, so a service which starts to use one
 * says so.
 */
public final class TestCollaborators {

    private TestCollaborators() {
    }

    /**
     * A complete set of collaborators, none of which may be called.
     *
     * @param adapterId The adapter id they are handed to
     * @return The collaborators
     */
    public static AdapterCollaborators of(final String adapterId) {
        return of(adapterId, unused(NameClashAvoidanceSupport.class));
    }

    /**
     * A complete set of collaborators, of which only the scoping may be called.
     *
     * @param adapterId The adapter id they are handed to
     * @param scoping The platform's name-clash scoping, as the test wants it to answer
     * @return The collaborators
     */
    public static AdapterCollaborators of(
        final String adapterId,
        final NameClashAvoidanceSupport scoping
    ) {
        return of(adapterId, scoping, unused(WorkflowTaskWiring.class));
    }

    /**
     * A complete set of collaborators, of which only the wiring may be called.
     *
     * @param adapterId The adapter id they are handed to
     * @param workflowTaskWiring The platform's wiring, as the test wants it to answer
     * @return The collaborators
     */
    public static AdapterCollaborators of(
        final String adapterId,
        final WorkflowTaskWiring workflowTaskWiring
    ) {
        return of(adapterId, unused(NameClashAvoidanceSupport.class), workflowTaskWiring);
    }

    /**
     * A complete set of collaborators, of which only the scoping and the wiring may be called.
     *
     * @param adapterId The adapter id they are handed to
     * @param scoping The platform's name-clash scoping, as the test wants it to answer
     * @param workflowTaskWiring The platform's wiring, as the test wants it to answer
     * @return The collaborators
     */
    public static AdapterCollaborators of(
        final String adapterId,
        final NameClashAvoidanceSupport scoping,
        final WorkflowTaskWiring workflowTaskWiring
    ) {
        return AdapterCollaborators
                .forAdapter(adapterId)
                .workflowTaskWiring(workflowTaskWiring)
                .workflowTaskInvoker(unused(WorkflowTaskInvoker.class))
                .scoping(scoping)
                .workflowAggregateSync(unused(WorkflowAggregateSync.class))
                .preCommitRegistrar(unused(PreCommitRegistrar.class))
                .workflowEndedInvoker(unused(WorkflowEndedInvoker.class))
                .bpmsInitiatedStartInvoker(unused(BpmsInitiatedStartInvoker.class))
                .build();
    }

    private static <T> T unused(final Class<T> type) {
        return type.cast(Proxy.newProxyInstance(TestCollaborators.class.getClassLoader(), new Class<?>[]{
                type
        }, (
            proxy,
            method,
            arguments) -> {
            throw new AssertionError("%s.%s is not expected to be called".formatted(type.getSimpleName(), method
                    .getName()));
        }));
    }

}

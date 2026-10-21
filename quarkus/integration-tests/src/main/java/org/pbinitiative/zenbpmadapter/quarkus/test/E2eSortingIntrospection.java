/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.test;

import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * What the Quarkus end-to-end tests of the versions of a process see of the running application:
 * they start a workflow of the process <code>Sorting</code> on its latest version, keep a sorting
 * whose workflow the test starts on version 1 over the engine's REST API, and read which method
 * served its task, all through these <code>introspect/sortings/...</code> endpoints. Each endpoint
 * which writes commits before it answers.
 */
@Path("/introspect/sortings")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class E2eSortingIntrospection {

    @Inject
    E2eSortingWorkflow sortings;

    @Inject
    EntityManager entityManager;

    /**
     * Built by CDI.
     */
    public E2eSortingIntrospection() {
    }

    /**
     * Starts the workflow of a new sorting on the latest version.
     *
     * @param id The id of the sorting
     * @return The id
     */
    @POST
    @Path("/{id}")
    @Transactional
    public Map<String, Object> startSorting(@PathParam("id") final String id) {
        sortings.start(new E2eSorting(id));
        return Map.of("id", id);
    }

    /**
     * Keeps a new sorting without starting a workflow for it.
     *
     * @param id The id of the sorting
     * @return The id
     */
    @POST
    @Path("/{id}/kept")
    @Transactional
    public Map<String, Object> keepSorting(@PathParam("id") final String id) {
        entityManager.persist(new E2eSorting(id));
        return Map.of("id", id);
    }

    /**
     * Which method served the task of a sorting.
     *
     * @param id The id of the sorting
     * @return The method, empty before one served it
     */
    @GET
    @Path("/{id}")
    @Transactional
    public Map<String, Object> sorting(@PathParam("id") final String id) {
        final var sorting = entityManager.find(E2eSorting.class, id);
        if (sorting == null) {
            throw new NotFoundException("no sorting '%s'".formatted(id));
        }
        return Map.of("servedBy", sorting.getServedBy() == null ? "" : sorting.getServedBy());
    }

}

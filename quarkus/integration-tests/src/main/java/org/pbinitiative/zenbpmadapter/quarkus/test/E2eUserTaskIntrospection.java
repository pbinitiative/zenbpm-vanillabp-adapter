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
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * What the Quarkus end-to-end tests of user tasks see of the running application: they start the
 * workflows of the process <code>Reviewing</code>, read their aggregates, and complete or cancel the
 * user task, all through these <code>introspect/user-tasks/...</code> endpoints, because the
 * application runs in a JVM of its own. Each endpoint which starts, completes or cancels something
 * commits before it answers.
 */
@Path("/introspect/user-tasks")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class E2eUserTaskIntrospection {

    @Inject
    E2eReviewWorkflow reviews;

    @Inject
    EntityManager entityManager;

    /**
     * Built by CDI.
     */
    public E2eUserTaskIntrospection() {
    }

    /**
     * Starts the workflow of a new review.
     *
     * @param id The review's id
     * @return The id
     */
    @POST
    @Path("/reviews/{id}")
    @Transactional
    public Map<String, Object> startReview(@PathParam("id") final String id) {
        reviews.start(new E2eReview(id));
        return Map.of("id", id);
    }

    /**
     * Reads a review.
     *
     * @param id The review's id
     * @return The review
     */
    @GET
    @Path("/reviews/{id}")
    @Transactional
    public E2eReview review(@PathParam("id") final String id) {
        return found(id);
    }

    /**
     * Accepts the paper of a review and completes its user task.
     *
     * @param id The review's id
     * @return The id
     */
    @POST
    @Path("/reviews/{id}/acceptance")
    @Transactional
    public Map<String, Object> accept(@PathParam("id") final String id) {
        reviews.accept(found(id));
        return Map.of("id", id);
    }

    /**
     * Cancels the user task of a review with a BPMN error.
     *
     * @param id The review's id
     * @param errorCode The code of the BPMN error
     * @return The id
     */
    @POST
    @Path("/reviews/{id}/withdrawal")
    @Transactional
    public Map<String, Object> withdraw(
        @PathParam("id") final String id,
        @QueryParam("errorCode") final String errorCode
    ) {
        reviews.withdraw(found(id), errorCode);
        return Map.of("id", id);
    }

    private E2eReview found(final String id) {
        final var review = entityManager.find(E2eReview.class, id);
        if (review == null) {
            throw new NotFoundException("no review '%s'".formatted(id));
        }
        return review;
    }

}

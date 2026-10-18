/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.test;

import java.time.Instant;
import java.util.List;
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
 * What the Quarkus end-to-end tests of task processing see of the running application: they start
 * the workflows of the processes <code>TaskMatrix</code>, <code>TaskOutcomes</code>,
 * <code>TaskTakenAway</code> and <code>TaskFailures</code>, read their aggregates, complete an
 * approval left open, and read the attempts of a charge, all through these
 * <code>introspect/tasks/...</code> endpoints, because the application runs in a JVM of its own.
 * Each endpoint which starts or completes something commits before it answers.
 */
@Path("/introspect/tasks")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class E2eTaskIntrospection {

    @Inject
    E2eParcelWorkflow parcels;

    @Inject
    E2eClaimWorkflow claims;

    @Inject
    E2eReservationWorkflow reservations;

    @Inject
    E2ePaymentWorkflow payments;

    @Inject
    EntityManager entityManager;

    /**
     * Built by CDI.
     */
    public E2eTaskIntrospection() {
    }

    /**
     * Starts the workflow of a new parcel.
     *
     * @param id The id of the parcel
     * @return The id
     */
    @POST
    @Path("/parcels/{id}")
    @Transactional
    public Map<String, Object> startParcel(@PathParam("id") final String id) {
        parcels.start(new E2eParcel(id));
        return Map.of("id", id);
    }

    /**
     * A parcel as the database holds it.
     *
     * @param id The id of the parcel
     * @return The parcel
     */
    @GET
    @Path("/parcels/{id}")
    @Transactional
    public E2eParcel parcel(@PathParam("id") final String id) {
        return found(E2eParcel.class, id);
    }

    /**
     * Starts the workflow of a new claim.
     *
     * @param id The id of the claim
     * @param scenario {@link E2eClaim#ERROR} or {@link E2eClaim#PENDING}
     * @param errorCode The code of the BPMN error the inspection raises
     * @return The id
     */
    @POST
    @Path("/claims/{id}")
    @Transactional
    public Map<String, Object> startClaim(
        @PathParam("id") final String id,
        @QueryParam("scenario") final String scenario,
        @QueryParam("errorCode") final String errorCode
    ) {
        claims.start(new E2eClaim(id, scenario, errorCode));
        return Map.of("id", id);
    }

    /**
     * A claim as the database holds it.
     *
     * @param id The id of the claim
     * @return The claim
     */
    @GET
    @Path("/claims/{id}")
    @Transactional
    public E2eClaim claim(@PathParam("id") final String id) {
        return found(E2eClaim.class, id);
    }

    /**
     * Approves a claim and completes its approval left open, in one transaction.
     *
     * @param id The id of the claim
     * @return The id
     */
    @POST
    @Path("/claims/{id}/approval")
    @Transactional
    public Map<String, Object> approve(@PathParam("id") final String id) {
        final var claim = found(E2eClaim.class, id);
        claim.approve();
        claims.completeTheApproval(claim, claim.getApprovalTaskId());
        return Map.of("id", id);
    }

    /**
     * Starts the workflow of a new reservation.
     *
     * @param id The id of the reservation
     * @return The id
     */
    @POST
    @Path("/reservations/{id}")
    @Transactional
    public Map<String, Object> startReservation(@PathParam("id") final String id) {
        reservations.start(new E2eReservation(id));
        return Map.of("id", id);
    }

    /**
     * A reservation as the database holds it.
     *
     * @param id The id of the reservation
     * @return The reservation
     */
    @GET
    @Path("/reservations/{id}")
    @Transactional
    public E2eReservation reservation(@PathParam("id") final String id) {
        return found(E2eReservation.class, id);
    }

    /**
     * Starts the workflow of a new payment.
     *
     * @param id The id of the payment
     * @return The id
     */
    @POST
    @Path("/payments/{id}")
    @Transactional
    public Map<String, Object> startPayment(@PathParam("id") final String id) {
        payments.start(new E2ePayment(id));
        return Map.of("id", id);
    }

    /**
     * When the charge of a payment was attempted.
     *
     * @param id The id of the payment
     * @return The moments in milliseconds since the epoch, in the order of the attempts
     */
    @GET
    @Path("/payments/{id}/attempts")
    public List<Long> attempts(@PathParam("id") final String id) {
        return payments
                .attemptsOf(id)
                .stream()
                .map(Instant::toEpochMilli)
                .toList();
    }

    private <A> A found(
        final Class<A> aggregateClass,
        final String id
    ) {
        final var aggregate = entityManager.find(aggregateClass, id);
        if (aggregate == null) {
            throw new NotFoundException("no %s '%s'".formatted(aggregateClass.getSimpleName(), id));
        }
        return aggregate;
    }

}

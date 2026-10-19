/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.test;

import java.util.Map;

import io.vanillabp.integration.spi.TaskDeliveryLog;
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
 * What the Quarkus end-to-end tests of messages and of the pushed aggregate see of the running
 * application: they start the workflows of the processes <code>Letters</code>,
 * <code>Postcards</code> and <code>Shipments</code>, correlate their messages, push their
 * aggregates, complete a task left open, and read the start row of a workflow a message started,
 * all through these <code>introspect/messages/...</code> endpoints, because the application runs in
 * a JVM of its own. Each endpoint which starts, correlates, pushes or completes something commits
 * before it answers.
 */
@Path("/introspect/messages")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class E2eMessageIntrospection {

    private static final String WORKFLOW_MODULE_ID = "zenbpm-e2e";

    @Inject
    E2eLetterWorkflow letters;

    @Inject
    E2ePostcardWorkflow postcards;

    @Inject
    E2eShipmentWorkflow shipments;

    @Inject
    TaskDeliveryLog deliveryLog;

    @Inject
    EntityManager entityManager;

    /**
     * Built by CDI.
     */
    public E2eMessageIntrospection() {
    }

    /**
     * Starts the workflow of a new letter.
     *
     * @param id The id of the letter
     * @param scenario {@link E2eLetter#AWAIT} or {@link E2eLetter#DELAY}
     * @return The id
     */
    @POST
    @Path("/letters/{id}")
    @Transactional
    public Map<String, Object> startLetter(
        @PathParam("id") final String id,
        @QueryParam("scenario") final String scenario
    ) {
        letters.start(new E2eLetter(id, scenario));
        return Map.of("id", id);
    }

    /**
     * Approves a letter and correlates the message <code>goAhead</code>, in one transaction.
     *
     * @param id The id of the letter
     * @return The id
     */
    @POST
    @Path("/letters/{id}/go-ahead")
    @Transactional
    public Map<String, Object> goAhead(@PathParam("id") final String id) {
        final var letter = found(E2eLetter.class, id);
        letter.setStatus("approved");
        letters.goAhead(letter);
        return Map.of("id", id);
    }

    /**
     * Starts the workflow of a new postcard by the message <code>postcardPosted</code>.
     *
     * @param id The id of the postcard
     * @return The id
     */
    @POST
    @Path("/postcards/{id}")
    @Transactional
    public Map<String, Object> post(@PathParam("id") final String id) {
        postcards.post(new E2ePostcard(id));
        return Map.of("id", id);
    }

    /**
     * The row VanillaBP wrote when the workflow of a postcard started.
     *
     * @param id The id of the postcard
     * @return The process and the key of the instance the row names, empty where there is no row
     *         yet
     */
    @GET
    @Path("/postcards/{id}/start-row")
    @Transactional
    public Map<String, Object> startRowOfPostcard(@PathParam("id") final String id) {
        return deliveryLog
                .workflowStartOf(WORKFLOW_MODULE_ID, "Postcards", id)
                .<Map<String, Object>>map(row -> Map.of("bpmnProcessId", row.bpmnProcessId(), "adapterId", row
                        .adapterId(), "workflowId", row.workflowId()))
                .orElse(Map.of());
    }

    /**
     * Stamps a postcard and correlates the message <code>stamped</code>, in one transaction.
     *
     * @param id The id of the postcard
     * @return The id
     */
    @POST
    @Path("/postcards/{id}/stamp")
    @Transactional
    public Map<String, Object> stamp(@PathParam("id") final String id) {
        final var postcard = found(E2ePostcard.class, id);
        postcard.setStatus("stamped");
        postcards.stamp(postcard);
        return Map.of("id", id);
    }

    /**
     * Starts the workflow of a new shipment.
     *
     * @param id The id of the shipment
     * @param scenario {@link E2eShipment#GATEWAY} or {@link E2eShipment#TASK}
     * @return The id
     */
    @POST
    @Path("/shipments/{id}")
    @Transactional
    public Map<String, Object> startShipment(
        @PathParam("id") final String id,
        @QueryParam("scenario") final String scenario
    ) {
        shipments.start(new E2eShipment(id, scenario));
        return Map.of("id", id);
    }

    /**
     * A shipment as the database holds it.
     *
     * @param id The id of the shipment
     * @return The shipment
     */
    @GET
    @Path("/shipments/{id}")
    @Transactional
    public E2eShipment shipment(@PathParam("id") final String id) {
        return found(E2eShipment.class, id);
    }

    /**
     * Approves a shipment and pushes it into its workflow, in one transaction.
     *
     * @param id The id of the shipment
     * @return The id
     */
    @POST
    @Path("/shipments/{id}/push")
    @Transactional
    public Map<String, Object> push(@PathParam("id") final String id) {
        final var shipment = found(E2eShipment.class, id);
        shipment.approve();
        shipments.changed(shipment);
        return Map.of("id", id);
    }

    /**
     * Approves a shipment and pushes it into the scope of its inspection, in one transaction.
     *
     * @param id The id of the shipment
     * @return The id
     */
    @POST
    @Path("/shipments/{id}/push-into-the-inspection")
    @Transactional
    public Map<String, Object> pushIntoTheInspection(@PathParam("id") final String id) {
        final var shipment = found(E2eShipment.class, id);
        shipment.approve();
        shipments.changedForTheInspection(shipment);
        return Map.of("id", id);
    }

    /**
     * Completes the inspection of a shipment left open.
     *
     * @param id The id of the shipment
     * @return The id
     */
    @POST
    @Path("/shipments/{id}/inspection")
    @Transactional
    public Map<String, Object> completeTheInspection(@PathParam("id") final String id) {
        shipments.completeTheInspection(found(E2eShipment.class, id));
        return Map.of("id", id);
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

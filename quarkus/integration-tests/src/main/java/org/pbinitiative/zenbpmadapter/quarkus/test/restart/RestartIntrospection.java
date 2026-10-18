/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.test.restart;

import java.util.List;
import java.util.Map;

import org.pbinitiative.zenbpmadapter.client.ZenBpmClientRegistry;
import org.pbinitiative.zenbpmadapter.client.ZenBpmRestClient;
import org.pbinitiative.zenbpmadapter.client.api.InstanceFilter;
import org.pbinitiative.zenbpmadapter.client.api.JobFilter;

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
 * What the restart test sees of the running application: it starts the workflow of a booking,
 * reads the booking, the instance and the jobs of its workflow, asks whether a packing is held, and
 * confirms a booking by completing its task. The application runs in a JVM of its own, which the
 * test stops and starts again, so everything travels through these <code>restart/...</code>
 * endpoints while it runs. Each endpoint which starts or completes something commits before it
 * answers.
 */
@Path("/restart")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class RestartIntrospection {

    private static final String ADAPTER_ID = "zenbpm";

    @Inject
    RestartBookingWorkflow workflow;

    @Inject
    ZenBpmClientRegistry clients;

    @Inject
    EntityManager entityManager;

    /**
     * Built by CDI.
     */
    public RestartIntrospection() {
    }

    /**
     * Starts the workflow of a new booking.
     *
     * @param id The id of the booking
     * @param kind {@link RestartBooking#CONFIRM} or {@link RestartBooking#PACK}
     * @return The id
     */
    @POST
    @Path("/bookings/{id}")
    @Transactional
    public Map<String, Object> start(
        @PathParam("id") final String id,
        @QueryParam("kind") final String kind
    ) {
        workflow.start(new RestartBooking(id, kind));
        return Map.of("id", id);
    }

    /**
     * A booking as the database holds it.
     *
     * @param id The id of the booking
     * @return The booking
     */
    @GET
    @Path("/bookings/{id}")
    @Transactional
    public RestartBooking booking(@PathParam("id") final String id) {
        return found(id);
    }

    /**
     * Confirms a booking and completes its task left open, in one transaction.
     *
     * @param id The id of the booking
     * @return The id
     */
    @POST
    @Path("/bookings/{id}/confirmation")
    @Transactional
    public Map<String, Object> confirm(@PathParam("id") final String id) {
        final var booking = found(id);
        booking.confirm();
        workflow.completeTheConfirmation(booking, booking.getConfirmationTaskId());
        return Map.of("id", id);
    }

    /**
     * Whether the packing of a booking waits in its handler right now.
     *
     * @param id The id of the booking
     * @return <code>held</code>, <code>true</code> or <code>false</code>
     */
    @GET
    @Path("/bookings/{id}/packing")
    public Map<String, Object> packing(@PathParam("id") final String id) {
        return Map.of("held", workflow.isPackingHeld(id));
    }

    /**
     * The instances the engine holds under a booking's id as their business key.
     *
     * @param id The id of the booking
     * @return The <code>key</code>, as text, and the <code>state</code> of each instance
     */
    @GET
    @Path("/instances/{id}")
    public List<Map<String, Object>> instances(@PathParam("id") final String id) {
        return rest()
                .instances(InstanceFilter
                        .anyInstance()
                        .withBusinessKey(id))
                .items()
                .stream()
                .map(instance -> Map.<String, Object>of("key", String.valueOf(instance.key()), "state", instance
                        .state()
                        .name()))
                .toList();
    }

    /**
     * The jobs the engine holds for one instance.
     *
     * @param instanceKey The key of the instance
     * @return The <code>key</code>, as text, the <code>state</code> and the <code>elementId</code>
     *         of each job
     */
    @GET
    @Path("/jobs/{instanceKey}")
    public List<Map<String, Object>> jobs(@PathParam("instanceKey") final long instanceKey) {
        return rest()
                .jobs(JobFilter
                        .anyJob()
                        .withProcessInstanceKey(instanceKey))
                .items()
                .stream()
                .map(job -> Map.<String, Object>of("key", String.valueOf(job.key()), "state", job
                        .state()
                        .name(), "elementId", job.elementId()))
                .toList();
    }

    private RestartBooking found(final String id) {
        final var booking = entityManager.find(RestartBooking.class, id);
        if (booking == null) {
            throw new NotFoundException("no booking '%s'".formatted(id));
        }
        return booking;
    }

    private ZenBpmRestClient rest() {
        return clients
                .getFactory(ADAPTER_ID)
                .rest();
    }

}

/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.pbinitiative.zenbpmadapter.client.ZenBpmClientRegistry;
import org.pbinitiative.zenbpmadapter.client.ZenBpmRestClient;
import org.pbinitiative.zenbpmadapter.client.api.InstanceFilter;
import org.pbinitiative.zenbpmadapter.client.api.JobFilter;
import org.pbinitiative.zenbpmadapter.client.api.ProcessInstanceRef;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Status;
import jakarta.transaction.Transactional;
import jakarta.transaction.UserTransaction;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * What the Quarkus end-to-end tests see of the running application. A prod-mode test runs the
 * application in a JVM of its own, so nothing of it can be injected into the test, and every
 * question and every operation travels through these <code>introspect/...</code> endpoints.
 * <p>
 * The endpoints which start a workflow open their transaction themselves ({@link UserTransaction}),
 * because the tests are about what happens inside the caller's transaction and what only happens
 * after its commit. The instances are read from the engine through the adapter's own client, so the
 * test asks the engine the booted application talks to.
 */
@Path("/introspect")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class E2eIntrospection {

    private static final String ADAPTER_ID = "zenbpm";

    private static final String WORKFLOW_MODULE_ID = "zenbpm-e2e";

    @Inject
    E2eWorkflow workflow;

    @Inject
    ZenBpmClientRegistry clients;

    @Inject
    EntityManager entityManager;

    @Inject
    UserTransaction userTransaction;

    /**
     * Built by CDI.
     */
    public E2eIntrospection() {
    }

    /**
     * Starts the workflow of a new aggregate and commits, asking the engine for the instances of the
     * aggregate's id before the commit.
     *
     * @param id The id of the new aggregate
     * @return The id, and how many instances the engine held for it inside the transaction
     * @throws Exception Where the transaction cannot be run
     */
    @POST
    @Path("/start/{id}")
    public Map<String, Object> start(@PathParam("id") final String id) throws Exception {
        userTransaction.begin();
        try {
            workflow.start(new E2eAggregate(id, "a book", "gift-wrapped"));
            final var instancesInsideTheTransaction = instancesOf(id).size();
            userTransaction.commit();
            return Map.of("id", id, "instancesInsideTheTransaction", instancesInsideTheTransaction);
        } finally {
            rollBackWhatIsLeftOpen();
        }
    }

    /**
     * Starts the workflow of a new aggregate and rolls the transaction back, so neither the
     * aggregate nor the entry the start wrote into the phase-two outbox survives it.
     *
     * @param id The id of the new aggregate
     * @return The id
     * @throws Exception Where the transaction cannot be run
     */
    @POST
    @Path("/rollback/{id}")
    public Map<String, Object> rollback(@PathParam("id") final String id) throws Exception {
        userTransaction.begin();
        try {
            workflow.start(new E2eAggregate(id, "a lamp", null));
        } finally {
            rollBackWhatIsLeftOpen();
        }
        return Map.of("id", id);
    }

    /**
     * The instances the engine holds under an aggregate's id as their business key.
     *
     * @param id The id of the aggregate
     * @return Key, process, version, state, business key and variables of each instance
     */
    @GET
    @Path("/instances/{id}")
    public List<Map<String, Object>> instances(@PathParam("id") final String id) {
        return instancesOf(id)
                .stream()
                .map(E2eIntrospection::describe)
                .toList();
    }

    /**
     * The keys of the jobs the engine holds for one instance.
     *
     * @param instanceKey The key of the instance
     * @return The keys of its jobs, as text, as {@link #instances} gives the key of an instance
     */
    @GET
    @Path("/jobs/{instanceKey}")
    public List<String> jobs(@PathParam("instanceKey") final long instanceKey) {
        return rest()
                .jobs(JobFilter
                        .anyJob()
                        .withProcessInstanceKey(instanceKey))
                .items()
                .stream()
                .map(job -> String.valueOf(job.key()))
                .toList();
    }

    /**
     * Whether the database holds an aggregate.
     *
     * @param id The id of the aggregate
     * @return <code>exists</code>, <code>true</code> or <code>false</code>
     */
    @GET
    @Path("/aggregates/{id}")
    @Transactional
    public Map<String, Object> aggregate(@PathParam("id") final String id) {
        return Map.of("exists", entityManager.find(E2eAggregate.class, id) != null);
    }

    /**
     * The version the adapter registered for a process of the workflow module while it deployed.
     *
     * @param bpmnProcessId The id of the process as its file declares it
     * @return The version, <code>none</code> where the adapter registered none
     */
    @GET
    @Path("/versions/{bpmnProcessId}")
    @Produces(MediaType.TEXT_PLAIN)
    public String version(@PathParam("bpmnProcessId") final String bpmnProcessId) {
        return clients
                .getFactory(ADAPTER_ID)
                .deployedProcesses()
                .versionOf(WORKFLOW_MODULE_ID, bpmnProcessId)
                .map(String::valueOf)
                .orElse("none");
    }

    private List<ProcessInstanceRef> instancesOf(final String id) {
        return rest()
                .instances(InstanceFilter
                        .anyInstance()
                        .withBusinessKey(id))
                .items();
    }

    private ZenBpmRestClient rest() {
        return clients
                .getFactory(ADAPTER_ID)
                .rest();
    }

    // a start which failed, or one rolled back on purpose, leaves the transaction open
    private void rollBackWhatIsLeftOpen() throws Exception {
        if (userTransaction.getStatus() != Status.STATUS_NO_TRANSACTION) {
            userTransaction.rollback();
        }
    }

    private static Map<String, Object> describe(final ProcessInstanceRef instance) {
        // the variables may hold a null, which Map.of does not take
        final var described = new LinkedHashMap<String, Object>();
        described.put("key", String.valueOf(instance.key()));
        described.put("bpmnProcessId", instance.bpmnProcessId());
        described.put("version", instance.version());
        described.put("state", instance
                .state()
                .name());
        described.put("businessKey", instance.businessKey());
        described.put("variables", instance.variables());
        return described;
    }

}

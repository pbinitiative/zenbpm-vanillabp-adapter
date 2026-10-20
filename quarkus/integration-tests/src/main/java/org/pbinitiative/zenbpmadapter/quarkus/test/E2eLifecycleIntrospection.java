/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.test;

import java.util.List;
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
import jakarta.ws.rs.core.MediaType;

/**
 * What the Quarkus end-to-end tests of the lifecycle of a workflow see of the running application:
 * they start the workflows of the process <code>Packing</code> and read what their tasks were told
 * and how they ended, and they read the aggregate the application built for a workflow of the
 * process <code>Receiving</code>, its start row, and send it the message <code>unloaded</code>, all
 * through these <code>introspect/lifecycle/...</code> endpoints, because the application runs in a
 * JVM of its own. Each endpoint which starts or correlates something commits before it answers.
 */
@Path("/introspect/lifecycle")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class E2eLifecycleIntrospection {

    private static final String WORKFLOW_MODULE_ID = "zenbpm-e2e";

    @Inject
    E2ePackingWorkflow packings;

    @Inject
    E2eReceiptWorkflow receipts;

    @Inject
    TaskDeliveryLog deliveryLog;

    @Inject
    EntityManager entityManager;

    /**
     * Built by CDI.
     */
    public E2eLifecycleIntrospection() {
    }

    /**
     * Starts the workflow of a new packing of the items <code>a</code>, <code>b</code> and
     * <code>c</code>.
     *
     * @param id The id of the packing
     * @return The id
     */
    @POST
    @Path("/packings/{id}")
    @Transactional
    public Map<String, Object> startPacking(@PathParam("id") final String id) {
        packings.start(new E2ePacking(id, List.of("a", "b", "c")));
        return Map.of("id", id);
    }

    /**
     * What the workflow of a packing told its tasks, and the end event it reported.
     *
     * @param id The id of the packing
     * @return The iterations told to the tasks packing (<code>packed</code>) and weighing
     *         (<code>weighed</code>) an item, each as <code>&lt;item&gt;:&lt;index&gt;/&lt;total&gt;</code>
     *         in the order they ran, and the end event, empty before the end
     */
    @GET
    @Path("/packings/{id}")
    @Transactional
    public Map<String, Object> packing(@PathParam("id") final String id) {
        final var packing = found(E2ePacking.class, id);
        return Map.of("packed", iterationsOf(packings.packed(), id), "weighed", iterationsOf(packings.weighed(), id),
                "endEvent", packing.getEndEvent() == null ? "" : packing.getEndEvent());
    }

    /**
     * The receipt the application built for a workflow started past VanillaBP with an order.
     *
     * @param orderRef The order the start carried
     * @return The id and the order of the receipt, and whether it was unloaded
     */
    @GET
    @Path("/receipts/{orderRef}")
    @Transactional
    public Map<String, Object> receipt(@PathParam("orderRef") final String orderRef) {
        final var receipt = found(E2eReceipt.class, "receipt-%s".formatted(orderRef));
        return Map.of("id", receipt.getId(), "orderRef", receipt.getOrderRef(), "unloaded", receipt.isUnloaded());
    }

    /**
     * The row VanillaBP wrote when the workflow of a receipt started.
     *
     * @param orderRef The order the start carried
     * @return The process and the key of the instance the row names, empty where there is no row
     *         yet
     */
    @GET
    @Path("/receipts/{orderRef}/start-row")
    @Transactional
    public Map<String, Object> startRowOfReceipt(@PathParam("orderRef") final String orderRef) {
        return deliveryLog
                .workflowStartOf(WORKFLOW_MODULE_ID, "Receiving", "receipt-%s".formatted(orderRef))
                .<Map<String, Object>>map(row -> Map.of("bpmnProcessId", row.bpmnProcessId(), "adapterId", row
                        .adapterId(), "workflowId", row.workflowId()))
                .orElse(Map.of());
    }

    /**
     * Marks a receipt unloaded and correlates the message <code>unloaded</code>, in one
     * transaction.
     *
     * @param orderRef The order the start carried
     * @return The id of the receipt
     */
    @POST
    @Path("/receipts/{orderRef}/unloaded")
    @Transactional
    public Map<String, Object> unloaded(@PathParam("orderRef") final String orderRef) {
        final var receipt = found(E2eReceipt.class, "receipt-%s".formatted(orderRef));
        receipt.unload();
        receipts.unloaded(receipt);
        return Map.of("id", receipt.getId());
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

    // the iterations told for one packing, as <item>:<index>/<total>
    private static List<String> iterationsOf(
        final List<String> told,
        final String id
    ) {
        return told
                .stream()
                .filter(iteration -> iteration.startsWith("%s:".formatted(id)))
                .map(iteration -> iteration.substring(id.length() + 1))
                .toList();
    }

}

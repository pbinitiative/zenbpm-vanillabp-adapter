/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientRegistry;
import org.pbinitiative.zenbpmadapter.client.ZenBpmRestClient;
import org.pbinitiative.zenbpmadapter.client.api.InstanceFilter;
import org.pbinitiative.zenbpmadapter.client.api.ProcessInstanceRef;
import org.pbinitiative.zenbpmadapter.springboot.it.push.PushItApplication;
import org.pbinitiative.zenbpmadapter.springboot.it.push.Shipment;
import org.pbinitiative.zenbpmadapter.springboot.it.push.ShipmentRepository;
import org.pbinitiative.zenbpmadapter.test.EngineUnderTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.ProcessService;

/**
 * What a booted Spring Boot application does with an aggregate it pushes, against the pinned engine:
 * a push without a task writes the values the aggregate shares into the workflow's root and into
 * every instance below it which runs, so a gateway behind a timer takes the branch of the pushed
 * value, in the root, inside an embedded sub-process and inside every iteration of a multi-instance
 * sub-process, each of which reads a copy of its own, but not inside a workflow the root called on
 * an aggregate of its own; a push into the scope of a task writes the instance the task waits in
 * and leaves the root as it was.
 * <p>
 * The application {@link PushItApplication} runs with <code>push-it.yaml</code>. Its process
 * <code>Shipments</code> has a path per scenario of {@link Shipment}, each of which decides on
 * <code>approved</code>, which is <code>false</code> when the workflow starts. One engine serves the
 * class, so every case uses aggregates of its own. The adapter reaches the engine through a proxy
 * which refuses nothing and writes down what the adapter sent.
 */
@ExtendWith(SuppressOutputExtension.class)
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = PushItApplication.class, properties = "spring.config.name=push-it")
@DirtiesContext
class ZenBpmAggregateChangedIT {

    /**
     * How long a case waits for what the engine or the application does. Each step takes
     * milliseconds, or the five seconds of a timer, so the limit only turns a step which never
     * happens into a failure.
     */
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    /**
     * How many iterations the multi-instance sub-process <code>Boxing</code> runs.
     */
    private static final int ITERATIONS = 3;

    @Container
    private static final EngineUnderTest ENGINE = new EngineUnderTest();

    private static final RefusingEngineProxy PROXY = new RefusingEngineProxy(ENGINE::restAddress);

    @Autowired
    private ProcessService<Shipment> processService;

    @Autowired
    private ShipmentRepository shipments;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private ZenBpmClientRegistry clients;

    @AfterAll
    static void stopTheProxy() {
        PROXY.close();
    }

    @Test
    @DisplayName("A push of the workflow reaches the gateway behind the timer the workflow waits at, which takes the branch of the pushed value")
    void aGlobalPushReachesTheGateway() {
        final var shipmentId = uniqueShipmentId();
        final var instance = start(shipmentId, Shipment.GATEWAY);
        awaitTheWaitAt(instance, "WaitToShip");

        approveAnd(shipmentId, shipment -> processService.aggregateChanged(shipment));
        final var ended = awaitTheEndOf(instance);

        assertAll(
                () -> assertEquals(ProcessInstanceRef.State.COMPLETED, ended.state()),
                () -> assertEquals(Set.of("Shipped"), endsOf(instance)),
                () -> assertEquals(Boolean.TRUE, ended
                        .variables()
                        .get("approved")));
    }

    @Test
    @DisplayName("A push of the workflow reaches a gateway inside an embedded sub-process, whose instance holds a copy of the root's values taken when it started, because the sub-process's instance is written as well")
    void aGlobalPushReachesAGatewayInsideASubProcess() {
        final var shipmentId = uniqueShipmentId();
        final var instance = start(shipmentId, Shipment.SUB_PROCESS);
        final var packing = awaitTheChildrenWaitingAt(instance, "WaitToPack", 1).getFirst();

        approveAnd(shipmentId, shipment -> processService.aggregateChanged(shipment));
        awaitTheEndOf(instance);

        assertAll(
                () -> assertEquals(Set.of("PackedForShipping"), endsOf(packing)),
                () -> assertEquals(List.of(patchOf(instance)), patchesOf(instance)),
                () -> assertEquals(List.of(patchOf(packing)), patchesOf(packing)));
    }

    @Test
    @DisplayName("A push of the workflow reaches the gateway of every iteration of a parallel multi-instance sub-process: an iteration which waited reads only its own values, so each of them is written")
    void aGlobalPushReachesTheGatewayOfEveryIteration() {
        final var shipmentId = uniqueShipmentId();
        final var instance = start(shipmentId, Shipment.ITERATIONS);
        final var body = eventually(() -> rest()
                .children(instance)
                .stream()
                .findFirst(), "the multi-instance body to start")
                .key();
        final var iterations = awaitTheChildrenWaitingAt(body, "WaitToBox", ITERATIONS);

        approveAnd(shipmentId, shipment -> processService.aggregateChanged(shipment));
        awaitTheEndOf(instance);

        final var ends = new ArrayList<Set<String>>();
        iterations.forEach(iteration -> ends.add(endsOf(iteration)));
        assertEquals(List.of(Set.of("BoxedForShipping"), Set.of("BoxedForShipping"), Set.of("BoxedForShipping")),
                ends);
    }

    @Test
    @DisplayName("A push into the scope of a task writes the instance of the sub-process the task waits in and leaves the root's values as they were; the completion then writes them everywhere")
    void aTaskScopedPushWritesTheChildInstance() {
        final var shipmentId = uniqueShipmentId();
        final var instance = start(shipmentId, Shipment.TASK);
        final var taskId = eventually(() -> shipments
                .findById(shipmentId)
                .map(Shipment::getInspection), "the inspection to be left open");
        final var inspection = rest()
                .children(instance)
                .getFirst()
                .key();
        final var patchesBefore = patchesOf(instance);

        approveAnd(shipmentId, shipment -> processService.aggregateChanged(shipment, taskId));
        eventually(() -> Boolean.TRUE.equals(variableOf(inspection, "approved")),
                "the sub-process's instance to carry the pushed value");
        final var rootAfterThePush = variableOf(instance, "approved");
        // read again right before the completion, which writes the root itself
        final var patchesAfterThePush = patchesOf(instance);
        approveAnd(shipmentId, shipment -> processService.completeTask(shipment, taskId));
        awaitTheEndOf(instance);

        assertAll(
                () -> assertEquals(Boolean.FALSE, rootAfterThePush),
                () -> assertEquals(patchesBefore, patchesAfterThePush),
                () -> assertEquals(Set.of("InspectedForShipping"), endsOf(inspection)));
    }

    @Test
    @DisplayName("A push of the workflow does not enter a workflow it called on an aggregate of its own, although that one carries a copy of the caller's id variable and shares a value of the same name: its gateway keeps its own value")
    void aGlobalPushLeavesAWorkflowCalledOnAnAggregateOfItsOwnAlone() {
        final var shipmentId = uniqueShipmentId();
        final var instance = start(shipmentId, Shipment.CALLED);
        final var rating = awaitTheChildrenWaitingAt(instance, "WaitToRate", 1).getFirst();
        final var ratingId = eventually(() -> Optional.ofNullable(variableOf(rating, "ratingId")),
                "the called workflow's start marker to name its own aggregate");
        // the start marker wrote the rating's id into its instance
        final var ratingPatchesBefore = patchesOf(rating);

        approveAnd(shipmentId, shipment -> processService.aggregateChanged(shipment));
        awaitTheEndOf(instance);

        assertAll(
                () -> assertEquals("rating-%s".formatted(shipmentId), ratingId),
                () -> assertEquals(shipmentId, variableOf(rating, "id")),
                () -> assertEquals(List.of(patchOf(instance)), patchesOf(instance)),
                () -> assertEquals(List.of(patchOf(rating)), ratingPatchesBefore),
                () -> assertEquals(ratingPatchesBefore, patchesOf(rating)),
                () -> assertEquals(Set.of("RatedAndHeld"), endsOf(rating)));
    }

    @DynamicPropertySource
    static void theEngine(final DynamicPropertyRegistry registry) {
        registry.add("vanillabp.adapters.zenbpm.rest-address", PROXY::address);
        registry.add("vanillabp.adapters.zenbpm.grpc-address", ENGINE::grpcAddress);
    }

    private static String uniqueShipmentId() {
        return "shipment-%s".formatted(UUID.randomUUID());
    }

    private long start(
        final String shipmentId,
        final String scenario
    ) {
        transactionTemplate
                .executeWithoutResult(status -> processService.startWorkflow(new Shipment(shipmentId, scenario)));
        return eventually(() -> rest()
                .instances(InstanceFilter
                        .anyInstance()
                        .withBusinessKey(shipmentId))
                .items()
                .stream()
                .filter(instance -> instance.parentKey() == null)
                .findFirst(), "the workflow to start")
                .key();
    }

    // the shipment is approved before the operation, in the same transaction, as an application does
    private void approveAnd(
        final String shipmentId,
        final Consumer<Shipment> operation
    ) {
        transactionTemplate.executeWithoutResult(status -> {
            final var shipment = shipments
                    .findById(shipmentId)
                    .orElseThrow();
            shipment.approve();
            operation.accept(shipment);
        });
    }

    private void awaitTheWaitAt(
        final long instanceKey,
        final String elementId
    ) {
        eventually(() -> waitingElementsOf(instanceKey).contains(elementId), "the workflow to wait at %s".formatted(
                elementId));
    }

    // the children of an instance once the given number of them waits at the element, in the order
    // of their keys
    private List<Long> awaitTheChildrenWaitingAt(
        final long instanceKey,
        final String elementId,
        final int count
    ) {
        return eventually(() -> {
            final var waiting = rest()
                    .children(instanceKey)
                    .stream()
                    .map(ProcessInstanceRef::key)
                    .filter(child -> waitingElementsOf(child).contains(elementId))
                    .sorted()
                    .toList();
            return waiting.size() == count ? Optional.of(waiting) : Optional.empty();
        }, "%d instances to wait at %s".formatted(count, elementId));
    }

    private ProcessInstanceRef awaitTheEndOf(final long instanceKey) {
        return eventually(() -> rest()
                .instance(instanceKey)
                .filter(instance -> instance.state() != ProcessInstanceRef.State.ACTIVE), "the workflow to end");
    }

    private Object variableOf(
        final long instanceKey,
        final String name
    ) {
        return rest()
                .instance(instanceKey)
                .orElseThrow()
                .variables()
                .get(name);
    }

    private static List<String> patchesOf(final long instanceKey) {
        return PROXY
                .received()
                .stream()
                .filter(patchOf(instanceKey)::equals)
                .toList();
    }

    private static String patchOf(final long instanceKey) {
        return "PATCH /v1/process-instances/%d/variables".formatted(instanceKey);
    }

    // the elements an instance is in now, read past the adapter's client, which keeps no element
    private static Set<String> waitingElementsOf(final long instanceKey) {
        final var waiting = new TreeSet<String>();
        ENGINE
                .json("/v1/process-instances/%d".formatted(instanceKey))
                .path("activeElementInstances")
                .forEach(element -> waiting.add(element
                        .path("elementId")
                        .asText()));
        return waiting;
    }

    // the end events the instance passed, from its history
    private static Set<String> endsOf(final long instanceKey) {
        final var ends = new TreeSet<String>();
        ENGINE
                .json("/v1/process-instances/%d/history?size=100".formatted(instanceKey))
                .path("items")
                .forEach(element -> {
                    if ("END_EVENT".equals(element
                            .path("elementType")
                            .asText())) {
                        ends.add(element
                                .path("elementId")
                                .asText());
                    }
                });
        return ends;
    }

    private ZenBpmRestClient rest() {
        return clients
                .getFactory("zenbpm")
                .rest();
    }

    private static void eventually(
        final BooleanSupplier condition,
        final String what
    ) {
        eventually(() -> condition.getAsBoolean() ? Optional.of(Boolean.TRUE) : Optional.empty(), what);
    }

    private static <T> T eventually(
        final Supplier<Optional<T>> read,
        final String what
    ) {
        final var deadline = Instant
                .now()
                .plus(TIMEOUT);
        while (true) {
            final var found = read.get();
            if (found.isPresent()) {
                return found.get();
            }
            assertTrue(Instant
                    .now()
                    .isBefore(deadline), () -> "waited %s for %s".formatted(TIMEOUT, what));
            LockSupport.parkNanos(Duration
                    .ofMillis(50)
                    .toNanos());
        }
    }

}

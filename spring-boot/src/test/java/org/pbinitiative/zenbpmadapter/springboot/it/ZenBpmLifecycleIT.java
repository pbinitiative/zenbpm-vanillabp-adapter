/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientRegistry;
import org.pbinitiative.zenbpmadapter.client.ZenBpmRestClient;
import org.pbinitiative.zenbpmadapter.client.api.CreateInstanceRequest;
import org.pbinitiative.zenbpmadapter.client.api.InstanceFilter;
import org.pbinitiative.zenbpmadapter.client.api.ProcessInstanceRef;
import org.pbinitiative.zenbpmadapter.springboot.it.lifecycle.Delivery;
import org.pbinitiative.zenbpmadapter.springboot.it.lifecycle.DeliveryRepository;
import org.pbinitiative.zenbpmadapter.springboot.it.lifecycle.DeliveryWorkflow;
import org.pbinitiative.zenbpmadapter.springboot.it.lifecycle.Invoice;
import org.pbinitiative.zenbpmadapter.springboot.it.lifecycle.InvoiceRepository;
import org.pbinitiative.zenbpmadapter.springboot.it.lifecycle.LifecycleItApplication;
import org.pbinitiative.zenbpmadapter.springboot.it.lifecycle.Tick;
import org.pbinitiative.zenbpmadapter.springboot.it.lifecycle.TickRepository;
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
 * How a booted Spring Boot application hears about the start of its workflows, against the pinned
 * engine: through the marker the adapter writes behind every start event. A workflow a timer
 * started gets the aggregate the application's <code>&#64;WorkflowStartedByBpms</code> method
 * built and named, and is found through the instance key of its start afterwards, which is what a
 * correlation needs, because the workflow has no business key. So does a workflow somebody started
 * over the engine's REST API without a business key. A workflow the application started itself
 * passes its marker without a second aggregate. And a process called on an aggregate of its own
 * starts without the caller's name, so its own method builds its aggregate.
 * <p>
 * The application {@link LifecycleItApplication} runs with <code>lifecycle-it.yaml</code>. Its
 * process <code>Ticking</code> is started once by its timer, two seconds after it was deployed;
 * every other case starts workflows of its own.
 */
@ExtendWith(SuppressOutputExtension.class)
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = LifecycleItApplication.class, properties = "spring.config.name=lifecycle-it")
@DirtiesContext
class ZenBpmLifecycleIT {

    /**
     * How long a case waits for what the engine or the application does. The timer fires two
     * seconds after the deployment, and every other step takes milliseconds or one outbox window of
     * two seconds, so the limit only turns a step which never happens into a failure.
     */
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Container
    private static final EngineUnderTest ENGINE = new EngineUnderTest();

    @Autowired
    private ProcessService<Tick> tickProcessService;

    @Autowired
    private ProcessService<Delivery> deliveryProcessService;

    @Autowired
    private TickRepository ticks;

    @Autowired
    private DeliveryRepository deliveries;

    @Autowired
    private InvoiceRepository invoices;

    @Autowired
    private DeliveryWorkflow deliveryWorkflow;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private ZenBpmClientRegistry clients;

    @Test
    @DisplayName("A workflow its timer started gets the aggregate the application's method built and named: the id variable holds it, the task behind the start ran on it, and a correlation on it reaches the workflow through the instance key of its start")
    void aTimerStartBuildsTheAggregate() {
        final var tick = eventually(() -> ticks
                .findAll()
                .stream()
                .filter(Tick::isTicked)
                .findFirst());
        final var instance = eventually(() -> rest()
                .instances(InstanceFilter
                        .anyInstance()
                        .withBpmnProcessId("deploy-it__Ticking"))
                .items()
                .stream()
                .findFirst());

        transactionTemplate.executeWithoutResult(status -> tickProcessService.correlateMessage(ticks
                .findById(tick.getId())
                .orElseThrow(), "proceed"));
        final var ended = awaitTheEndOf(instance.key());

        assertAll(
                () -> assertEquals("TIMER", tick.getStartedBy()),
                () -> assertEquals("TwoSecondsAfterDeploying", tick.getStartEvent()),
                () -> assertNull(instance.businessKey()),
                () -> assertEquals(tick.getId(), ended
                        .variables()
                        .get("id")),
                () -> assertEquals(ProcessInstanceRef.State.COMPLETED, ended.state()));
    }

    @Test
    @DisplayName("A workflow somebody started over the engine's REST API without a business key gets the aggregate the application's method built, named in its id variable, and its task runs on it")
    void aWorkflowStartedPastVanillaBpGetsAnAggregate() {
        final var orderRef = uniqueOrderRef();

        final var started = startPastVanillaBp(orderRef);
        final var delivery = eventually(() -> deliveries
                .findById("delivery-%s".formatted(orderRef))
                .filter(Delivery::isDelivered));
        final var ended = awaitTheEndOf(started.key());

        assertAll(
                () -> assertNull(started.businessKey()),
                () -> assertEquals(orderRef, delivery.getOrderRef()),
                () -> assertEquals(1, Collections.frequency(deliveryWorkflow.builtFor(), orderRef)),
                () -> assertEquals(delivery.getId(), ended
                        .variables()
                        .get("id")),
                () -> assertEquals(ProcessInstanceRef.State.COMPLETED, ended.state()));
    }

    @Test
    @DisplayName("A workflow the application started itself passes its start marker without a second aggregate: the method is not called, and the instance keeps the id it was started with")
    void theApplicationsOwnStartBuildsNothing() {
        final var orderRef = uniqueOrderRef();
        final var deliveryId = "own-%s".formatted(orderRef);

        transactionTemplate.executeWithoutResult(status -> deliveryProcessService.startWorkflow(new Delivery(
                deliveryId, orderRef)));
        final var started = eventually(() -> rest()
                .instances(InstanceFilter
                        .anyInstance()
                        .withBusinessKey(deliveryId))
                .items()
                .stream()
                .findFirst());
        final var delivery = eventually(() -> deliveries
                .findById(deliveryId)
                .filter(Delivery::isDelivered));
        final var ended = awaitTheEndOf(started.key());

        assertAll(
                () -> assertFalse(deliveryWorkflow
                        .builtFor()
                        .contains(orderRef)),
                () -> assertFalse(deliveries.existsById("delivery-%s".formatted(orderRef))),
                () -> assertEquals(deliveryId, delivery.getId()),
                () -> assertEquals(deliveryId, ended
                        .variables()
                        .get("id")),
                () -> assertEquals(ProcessInstanceRef.State.COMPLETED, ended.state()));
    }

    @Test
    @DisplayName("A process called on an aggregate of its own starts without the caller's business key and id, so its own method builds its aggregate, and the caller keeps its id")
    void aProcessCalledOnAnAggregateOfItsOwnBuildsItsOwn() {
        final var orderRef = uniqueOrderRef();
        final var deliveryId = "own-%s".formatted(orderRef);

        // started by the application, so the caller carries a business key and an id variable,
        // both of which the called instance would take over
        transactionTemplate.executeWithoutResult(status -> deliveryProcessService.startWorkflow(new Delivery(
                deliveryId, orderRef)));
        final var started = eventually(() -> rest()
                .instances(InstanceFilter
                        .anyInstance()
                        .withBusinessKey(deliveryId))
                .items()
                .stream()
                .findFirst());
        final var invoice = eventually(() -> invoices
                .findById("invoice-%s".formatted(orderRef))
                .filter(Invoice::isIssued));
        final var ended = awaitTheEndOf(started.key());
        final var called = rest()
                .children(started.key())
                .stream()
                .filter(child -> child.processType() == ProcessInstanceRef.ProcessType.CALL_ACTIVITY)
                .findFirst()
                .orElseThrow();

        assertAll(
                () -> assertEquals("deploy-it__Invoicing", called.bpmnProcessId()),
                () -> assertTrue((called.businessKey() == null) || called
                        .businessKey()
                        .isEmpty(), called::toString),
                () -> assertEquals(invoice.getId(), called
                        .variables()
                        .get("id")),
                () -> assertEquals(deliveryId, ended
                        .variables()
                        .get("id")),
                () -> assertEquals(ProcessInstanceRef.State.COMPLETED, ended.state()));
    }

    @DynamicPropertySource
    static void theEngine(final DynamicPropertyRegistry registry) {
        registry.add("vanillabp.adapters.zenbpm.rest-address", ENGINE::restAddress);
        registry.add("vanillabp.adapters.zenbpm.grpc-address", ENGINE::grpcAddress);
    }

    private ProcessInstanceRef startPastVanillaBp(final String orderRef) {
        return rest().createInstance(CreateInstanceRequest
                .latestVersionOf("deploy-it__Delivering")
                .withVariables(Map.of("orderRef", orderRef)));
    }

    private ProcessInstanceRef awaitTheEndOf(final long key) {
        return eventually(() -> rest()
                .instance(key)
                .filter(instance -> instance.state() != ProcessInstanceRef.State.ACTIVE));
    }

    private ZenBpmRestClient rest() {
        return clients
                .getFactory("zenbpm")
                .rest();
    }

    private static String uniqueOrderRef() {
        return "order-%s".formatted(UUID.randomUUID());
    }

    private static <T> T eventually(final Supplier<Optional<T>> read) {
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
                    .isBefore(deadline), () -> "waited %s".formatted(TIMEOUT));
            LockSupport.parkNanos(Duration
                    .ofMillis(50)
                    .toNanos());
        }
    }

}

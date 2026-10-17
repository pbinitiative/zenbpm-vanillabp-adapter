/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientRegistry;
import org.pbinitiative.zenbpmadapter.client.api.InstanceFilter;
import org.pbinitiative.zenbpmadapter.client.api.ProcessInstanceRef;
import org.pbinitiative.zenbpmadapter.processservice.ZenBpmProcessService;
import org.pbinitiative.zenbpmadapter.springboot.it.start.Order;
import org.pbinitiative.zenbpmadapter.springboot.it.start.StartItApplication;
import org.pbinitiative.zenbpmadapter.test.EngineUnderTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.adapter.spi.WorkflowScope;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.outbox.PhaseTwoOutboxReader;
import io.vanillabp.spi.process.ProcessService;

/**
 * The two-phase start of a booted Spring Boot application against the pinned engine:
 * {@code ProcessService#startWorkflow} inside a JPA transaction writes an entry into the platform's
 * JDBC phase-two outbox, and only once the transaction commits does the outbox start the instance,
 * under the aggregate's id as its business key, with the id and the values the aggregate shares as
 * variables. A transaction which rolls back leaves neither an entry nor an instance behind. The
 * probes of the adapter, asked directly, find the started workflow and know none for an aggregate
 * nobody started one for.
 * <p>
 * The application runs the workflow module <code>deploy-it</code> under <code>use-prefix</code> with
 * the process <code>Ordering</code>, configured by <code>start-it.yaml</code>. Its instance stays
 * active for an hour behind its one task, which the adapter runs, so the tests see a workflow which
 * runs. It is started once for the class, against an engine of its own.
 */
@ExtendWith(SuppressOutputExtension.class)
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = StartItApplication.class, properties = "spring.config.name=start-it")
@DirtiesContext
class ZenBpmDeploymentAndStartIT {

    private static final String ADAPTER_ID = "zenbpm";

    private static final WorkflowScope ORDERING = WorkflowScope.of("deploy-it", "Ordering");

    private static final String SCOPED_PROCESS_ID = "deploy-it__Ordering";

    /**
     * How long a committed start may take to show at the engine. The outbox dispatches right after
     * the commit and the engine answers in milliseconds, so the limit only turns an instance which
     * never comes into a failure.
     */
    private static final Duration START_TIMEOUT = Duration.ofSeconds(30);

    /**
     * The probes read the aggregate's id variable by the name of its id attribute, and that name
     * is all they ask of the persistence.
     */
    private static final AggregatePersistenceAware<Order> PERSISTENCE = new AggregatePersistenceAware<>() {

        @Override
        public Class<Order> getAggregateClass() {
            return Order.class;
        }

        @Override
        public String getAggregateIdName() {
            return "id";
        }

    };

    @Container
    private static final EngineUnderTest ENGINE = new EngineUnderTest();

    @Autowired
    private ProcessService<Order> processService;

    @Autowired
    private ZenBpmProcessService<Order> adapter;

    @Autowired
    private ZenBpmClientRegistry clients;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("A start inside a committed transaction creates no instance before the commit, and after it an active one under the aggregate id, carrying the id and the shared values")
    void instanceAppearsOnlyAfterCommit() {
        final var orderId = uniqueOrderId();

        final var insideTheTransaction = transactionTemplate.execute(status -> {
            processService.startWorkflow(new Order(orderId, "a book", "gift-wrapped"));
            return instancesOf(orderId);
        });
        final var afterTheCommit = awaitTheInstanceOf(orderId);

        assertAll(
                () -> assertEquals(List.of(), insideTheTransaction),
                () -> assertEquals(ProcessInstanceRef.State.ACTIVE, afterTheCommit.state()),
                () -> assertEquals(SCOPED_PROCESS_ID, afterTheCommit.bpmnProcessId()),
                () -> assertEquals(orderId, afterTheCommit.businessKey()),
                () -> assertEquals(orderId, afterTheCommit
                        .variables()
                        .get("id")),
                () -> assertEquals("a book", afterTheCommit
                        .variables()
                        .get("item")),
                () -> assertFalse(afterTheCommit
                        .variables()
                        .containsKey("note"), afterTheCommit::toString));
    }

    @Test
    @DisplayName("A start inside a transaction which rolls back leaves no outbox entry, and no instance appears while the outbox dispatches two later starts")
    void noInstanceAfterRollback() {
        final var rolledBackOrderId = uniqueOrderId();

        final var rollback = assertThrowsExactly(RolledBack.class, () -> transactionTemplate.execute(status -> {
            processService.startWorkflow(new Order(rolledBackOrderId, "a lamp", null));
            throw new RolledBack();
        }));
        // a commit wakes the outbox, which then dispatches every entry that is due. So once the
        // instance of a later start exists, the outbox ran after the rollback, and an entry the
        // rollback had left behind would have been dispatched in that run
        final var laterOrderIds = List.of(uniqueOrderId(), uniqueOrderId());
        for (final var laterOrderId : laterOrderIds) {
            transactionTemplate.executeWithoutResult(
                    status -> processService.startWorkflow(new Order(laterOrderId, "a chair", null)));
            awaitTheInstanceOf(laterOrderId);
        }
        final var outbox = PhaseTwoOutboxReader
                .ofTheVanillaBpOutbox(dataSource)
                .entries();

        assertAll(
                () -> assertEquals("rolled back on purpose", rollback.getMessage()),
                () -> assertEquals(List.of(), instancesOf(rolledBackOrderId)),
                () -> assertTrue(outbox
                        .stream()
                        .noneMatch(entry -> rolledBackOrderId.equals(entry.aggregateId())), outbox::toString),
                // the reader sees the entries of the later starts, so it reads the outbox the
                // application writes and the assertion above is not empty by accident
                () -> assertTrue(outbox
                        .stream()
                        .map(PhaseTwoOutboxReader.Entry::aggregateId)
                        .toList()
                        .containsAll(laterOrderIds), outbox::toString));
    }

    @Test
    @DisplayName("Both probes, asked directly, search the engine and know no workflow of an aggregate nobody started one for")
    void aWorkflowNobodyStartedIsUnknownToBothProbes() {
        final MigratableProcessService<Order> probes = adapter;
        final var neverStartedOrderId = uniqueOrderId();

        assertAll(
                () -> assertEquals(WorkflowAwareness.UNKNOWN_TO_BPMS, probes.awarenessOfWorkflow(ORDERING, PERSISTENCE,
                        neverStartedOrderId)),
                () -> assertEquals(WorkflowAwareness.UNKNOWN_TO_BPMS, probes.awarenessOfWorkflowForRedispatch(ORDERING,
                        PERSISTENCE, neverStartedOrderId)));
    }

    @Test
    @DisplayName("The probes, asked directly, find a started workflow by its business key and by its key, and the probe before a repeated start counts it")
    void theProbeFindsTheStartedWorkflow() {
        final MigratableProcessService<Order> probes = adapter;
        final var orderId = uniqueOrderId();
        transactionTemplate
                .executeWithoutResult(status -> processService.startWorkflow(new Order(orderId, "a pen", null)));
        final var key = String.valueOf(awaitTheInstanceOf(orderId).key());

        assertAll(
                () -> assertEquals(WorkflowAwareness.ACTIVE, probes.awarenessOfWorkflow(ORDERING, PERSISTENCE,
                        orderId)),
                () -> assertEquals(WorkflowAwareness.ACTIVE, probes.awarenessOfWorkflow(ORDERING, PERSISTENCE, orderId,
                        key)),
                () -> assertEquals(WorkflowAwareness.ACTIVE, probes.awarenessOfWorkflowForRedispatch(ORDERING,
                        PERSISTENCE, orderId)));
    }

    // only the test knows the ports the container was given
    @DynamicPropertySource
    static void theEngineUnderTest(final DynamicPropertyRegistry registry) {
        registry.add("vanillabp.adapters.zenbpm.rest-address", ENGINE::restAddress);
        registry.add("vanillabp.adapters.zenbpm.grpc-address", ENGINE::grpcAddress);
    }

    // the order ids of every test are new, because all tests of the class share one engine
    private static String uniqueOrderId() {
        return "order-%s".formatted(UUID.randomUUID());
    }

    private List<ProcessInstanceRef> instancesOf(final String orderId) {
        return clients
                .getFactory(ADAPTER_ID)
                .rest()
                .instances(InstanceFilter
                        .anyInstance()
                        .withBusinessKey(orderId))
                .items();
    }

    private ProcessInstanceRef awaitTheInstanceOf(final String orderId) {
        final var deadline = Instant
                .now()
                .plus(START_TIMEOUT);
        var found = instancesOf(orderId);
        while (found.isEmpty() && Instant
                .now()
                .isBefore(deadline)) {
            LockSupport.parkNanos(Duration
                    .ofMillis(50)
                    .toNanos());
            found = instancesOf(orderId);
        }
        final var instances = found;
        assertEquals(1, instances.size(), () -> "the instances of order '%s' after %s: %s".formatted(orderId,
                START_TIMEOUT, instances));
        return instances.getFirst();
    }

    /**
     * What makes a transaction of a test roll back.
     */
    private static final class RolledBack extends RuntimeException {

        private static final long serialVersionUID = 1L;

        RolledBack() {
            super("rolled back on purpose");
        }

    }

}

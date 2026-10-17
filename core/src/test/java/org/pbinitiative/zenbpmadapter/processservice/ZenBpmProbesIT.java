/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.processservice;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pbinitiative.zenbpmadapter.TestCollaborators;
import org.pbinitiative.zenbpmadapter.TestScoping;
import org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientFactory;
import org.pbinitiative.zenbpmadapter.client.ZenBpmRestClient;
import org.pbinitiative.zenbpmadapter.client.api.CreateInstanceRequest;
import org.pbinitiative.zenbpmadapter.client.api.JobFilter;
import org.pbinitiative.zenbpmadapter.client.api.JobRef;
import org.pbinitiative.zenbpmadapter.client.api.ProcessInstanceRef;
import org.pbinitiative.zenbpmadapter.test.EngineUnderTest;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.adapter.spi.WorkflowScope;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The probes against the pinned engine: what they answer for a workflow which runs, which ended,
 * which has no business key and which belongs to another process, how a key the engine cannot
 * place is handled, from which moment the probe before a repeated start counts, and which instance
 * the workflow id of a task names. The module is not scoped, so the engine knows the processes by
 * the ids the files declare.
 */
@ExtendWith(SuppressOutputExtension.class)
@Testcontainers(disabledWithoutDocker = true)
class ZenBpmProbesIT {

    private static final String ADAPTER_ID = "probes";

    private static final WorkflowScope ORDERS = WorkflowScope.of("shop", "probe-order");

    private static final WorkflowScope INVOICES = WorkflowScope.of("shop", "probe-invoice");

    /**
     * A key of partition 0, which holds what every partition shares, like the process definitions.
     * The engine answers <code>502</code> for it.
     */
    private static final String KEY_OF_NO_PARTITION = "1";

    private static final Duration PATIENCE = Duration.ofSeconds(10);

    @Container
    private static final EngineUnderTest ENGINE = new EngineUnderTest();

    private static ZenBpmClientFactory factory;

    private static ZenBpmRestClient rest;

    private static ZenBpmProcessService<Object> processService;

    @BeforeAll
    static void deployTheFixtures() {
        final var configuration = new ZenBpmAdapterConfiguration();
        configuration.setRestAddress(ENGINE.restAddress());
        factory = new ZenBpmClientFactory(ADAPTER_ID, configuration, () -> "probes-it");
        rest = factory.rest();
        rest.deployBpmn(fixture("probe-order.bpmn"));
        rest.deployBpmn(fixture("probe-caller.bpmn"));
        processService = new ZenBpmProcessService<>(ADAPTER_ID, factory, TestCollaborators.of(ADAPTER_ID, TestScoping
                .of(NameClashAvoidance.NONE)), (
                    module,
                    process) -> null);
    }

    @AfterAll
    static void closeTheFactory() {
        factory.close();
    }

    @Test
    @DisplayName("A workflow which runs is active by its business key and by its key")
    void aRunningWorkflowIsActive() {
        final var aggregateId = uniqueAggregateId();
        final var started = start("probe-order", aggregateId, true);

        assertAll(
                () -> assertEquals(WorkflowAwareness.ACTIVE, processService.awarenessOfWorkflow(ORDERS, persistence(),
                        aggregateId)),
                () -> assertEquals(WorkflowAwareness.ACTIVE, processService.awarenessOfWorkflow(ORDERS, persistence(),
                        aggregateId, String.valueOf(started.key()))));
    }

    @Test
    @DisplayName("A workflow without a business key, as the engine starts one, is found by its key and its id variable only")
    void aWorkflowWithoutABusinessKeyIsFoundByItsKeyOnly() {
        final var aggregateId = uniqueAggregateId();
        final var started = start("probe-order", aggregateId, false);

        assertAll(
                () -> assertEquals(WorkflowAwareness.UNKNOWN_TO_BPMS, processService.awarenessOfWorkflow(ORDERS,
                        persistence(), aggregateId)),
                () -> assertEquals(WorkflowAwareness.ACTIVE, processService.awarenessOfWorkflow(ORDERS, persistence(),
                        aggregateId, String.valueOf(started.key()))));
    }

    @Test
    @DisplayName("A workflow which ended is completed, and its key does not make it anything else")
    void anEndedWorkflowIsCompleted() {
        final var aggregateId = uniqueAggregateId();
        final var started = start("probe-order", aggregateId, true);
        rest.completeJob(waitingJobOf(started.key()).key(), Map.of());
        eventually(() -> rest
                .instance(started.key())
                .filter(instance -> instance.state() == ProcessInstanceRef.State.COMPLETED));

        assertAll(
                () -> assertEquals(WorkflowAwareness.COMPLETED, processService.awarenessOfWorkflow(ORDERS,
                        persistence(), aggregateId)),
                () -> assertEquals(WorkflowAwareness.COMPLETED, processService.awarenessOfWorkflow(ORDERS,
                        persistence(), aggregateId, String.valueOf(started.key()))));
    }

    @Test
    @DisplayName("A workflow of another process with the same aggregate id is unknown, by its business key and by its key")
    void aWorkflowOfAnotherProcessIsUnknown() {
        final var aggregateId = uniqueAggregateId();
        final var started = start("probe-order", aggregateId, true);

        assertAll(
                () -> assertEquals(WorkflowAwareness.UNKNOWN_TO_BPMS, processService.awarenessOfWorkflow(INVOICES,
                        persistence(), aggregateId)),
                () -> assertEquals(WorkflowAwareness.UNKNOWN_TO_BPMS, processService.awarenessOfWorkflow(INVOICES,
                        persistence(), aggregateId, String.valueOf(started.key()))));
    }

    @Test
    @DisplayName("A key the engine answers 502 for falls through to the search, which decides")
    void aKeyOfNoPartitionFallsThroughToTheSearch() {
        final var aggregateId = uniqueAggregateId();
        start("probe-order", aggregateId, true);

        assertAll(
                () -> assertEquals(WorkflowAwareness.ACTIVE, processService.awarenessOfWorkflow(ORDERS, persistence(),
                        aggregateId, KEY_OF_NO_PARTITION)),
                () -> assertEquals(WorkflowAwareness.UNKNOWN_TO_BPMS, processService.awarenessOfWorkflow(ORDERS,
                        persistence(), uniqueAggregateId(), KEY_OF_NO_PARTITION)));
    }

    @Test
    @DisplayName("Before a start is sent again, an instance created at the planned moment counts, and one created a millisecond before it does not")
    void theRedispatchProbeCountsFromThePlannedMoment() {
        final var aggregateId = uniqueAggregateId();
        final var started = start("probe-order", aggregateId, true);
        final var createdAt = started.createdAt();

        assertAll(
                () -> assertEquals(WorkflowAwareness.ACTIVE, processService.awarenessOfWorkflowForRedispatch(ORDERS,
                        persistence(), aggregateId, createdAt)),
                () -> assertEquals(WorkflowAwareness.UNKNOWN_TO_BPMS, processService
                        .awarenessOfWorkflowForRedispatch(ORDERS, persistence(), aggregateId, createdAt.plusMillis(
                                1))),
                () -> assertEquals(WorkflowAwareness.UNKNOWN_TO_BPMS, processService
                        .awarenessOfWorkflowForRedispatch(ORDERS, persistence(), aggregateId, createdAt.plusNanos(
                                1))),
                () -> assertEquals(WorkflowAwareness.ACTIVE, processService.awarenessOfWorkflowForRedispatch(ORDERS,
                        persistence(), aggregateId, null)),
                () -> assertEquals(WorkflowAwareness.UNKNOWN_TO_BPMS, processService
                        .awarenessOfWorkflowForRedispatch(INVOICES, persistence(), aggregateId, null)));
    }

    @Test
    @DisplayName("The workflow id of a task inside a sub-process is the root instance's key, and a task of a called process names the called instance")
    void theWorkflowIdOfATaskIsItsRootUpToACallActivity() {
        final var order = start("probe-order", uniqueAggregateId(), true);
        final var caller = start("probe-caller", uniqueAggregateId(), true);
        final var taskOfTheOrder = waitingJobOf(order.key());
        final var taskOfTheCalledOrder = waitingJobOf(caller.key());
        final var calledOrder = rest
                .children(caller.key())
                .getFirst();

        assertAll(
                () -> assertNotEquals(order.key(), taskOfTheOrder.instanceKey()),
                () -> assertEquals(OptionalLong.of(order.key()), factory
                        .instances()
                        .workflowIdOf(taskOfTheOrder.instanceKey())),
                () -> assertEquals(ProcessInstanceRef.ProcessType.CALL_ACTIVITY, calledOrder.processType()),
                () -> assertEquals(OptionalLong.of(calledOrder.key()), factory
                        .instances()
                        .workflowIdOf(taskOfTheCalledOrder.instanceKey())));
    }

    private static ProcessInstanceRef start(
        final String bpmnProcessId,
        final String aggregateId,
        final boolean withBusinessKey
    ) {
        final var start = CreateInstanceRequest
                .latestVersionOf(bpmnProcessId)
                .withVariables(Map.of("orderId", aggregateId));
        return rest.createInstance(withBusinessKey ? start.withBusinessKey(aggregateId) : start);
    }

    // the job waits in a child instance, so the instances below the root are asked one by one
    private static JobRef waitingJobOf(final long rootKey) {
        return eventually(() -> {
            final var instances = new ArrayList<Long>(List.of(rootKey));
            for (var index = 0; index < instances.size(); index++) {
                final var key = instances.get(index);
                final var waiting = rest
                        .jobs(JobFilter
                                .anyJob()
                                .withProcessInstanceKey(key)
                                .withState(JobRef.State.ACTIVE))
                        .items();
                if (!waiting.isEmpty()) {
                    return Optional.of(waiting.getFirst());
                }
                rest
                        .children(key)
                        .forEach(child -> instances.add(child.key()));
            }
            return Optional.empty();
        });
    }

    private static <T> T eventually(final Supplier<Optional<T>> read) {
        final var deadline = Instant
                .now()
                .plus(PATIENCE);
        while (true) {
            final var found = read.get();
            if (found.isPresent() || Instant
                    .now()
                    .isAfter(deadline)) {
                return found.orElseThrow();
            }
            LockSupport.parkNanos(Duration
                    .ofMillis(50)
                    .toNanos());
        }
    }

    private static String uniqueAggregateId() {
        return "order-%s".formatted(UUID.randomUUID());
    }

    private static AggregatePersistenceAware<Object> persistence() {
        return new AggregatePersistenceAware<>() {

            @Override
            public String getAggregateIdName() {
                return "orderId";
            }

            @Override
            public Object loadById(final Object aggregateId) {
                throw new AssertionError("a probe loads no aggregate");
            }

        };
    }

    private static byte[] fixture(final String name) {
        try (var file = ZenBpmProbesIT.class.getResourceAsStream("/probes/%s".formatted(name))) {
            return file.readAllBytes();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

}

/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.processservice;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pbinitiative.zenbpmadapter.TestCollaborators;
import org.pbinitiative.zenbpmadapter.TestScoping;
import org.pbinitiative.zenbpmadapter.ZenBpmDeployedProcesses.ModuleMessages;
import org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration;
import org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration.MessageStartLookup;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientFactory;
import org.pbinitiative.zenbpmadapter.client.api.InstanceFilter;
import org.pbinitiative.zenbpmadapter.client.api.ProcessInstanceRef;
import org.pbinitiative.zenbpmadapter.test.EngineUnderTest;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.vanillabp.integration.adapter.spi.AggregateSyncMode;
import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.adapter.spi.PhaseTwoRequest;
import io.vanillabp.integration.adapter.spi.WorkflowAggregateSync;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.adapter.spi.WorkflowScope;
import io.vanillabp.integration.adapter.spi.workflowstart.WorkflowStartReport;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Phase two of a start against the pinned engine: the instance it reports is the one the engine
 * holds under the aggregate's id, with the id and the shared values as its variables, and a start of
 * a process nothing deployed is permanent. A start by a message starts an instance which carries the
 * same, and reports nothing; many of one name at once each start their workflow, and under
 * <code>message-start-lookup: scan</code> the probes find such a workflow by its id variable.
 */
@ExtendWith(SuppressOutputExtension.class)
@Testcontainers(disabledWithoutDocker = true)
class ZenBpmProcessServiceIT {

    private static final String ADAPTER_ID = "starts";

    private static final WorkflowScope STARTED_BY_A_MESSAGE = new WorkflowScope("shop", List.of("contract-started"));

    @Container
    private static final EngineUnderTest ENGINE = new EngineUnderTest();

    private static ZenBpmClientFactory factory;

    private static ZenBpmProcessService<Object> processService;

    @BeforeAll
    static void deployTheFixture() {
        final var configuration = new ZenBpmAdapterConfiguration();
        configuration.setRestAddress(ENGINE.restAddress());
        factory = new ZenBpmClientFactory(ADAPTER_ID, configuration, () -> "starts-it");
        factory
                .rest()
                .deployBpmn(fixture("contract-task.bpmn"));
        factory
                .rest()
                .deployBpmn(fixture("contract-started.bpmn"));
        processService = processService(factory);
    }

    @AfterAll
    static void closeTheFactory() {
        factory.close();
    }

    @Test
    @DisplayName("A start reports the key and the version of the instance the engine holds under the aggregate's id, which carries the id and the shared values")
    void aStartReportsTheInstanceTheEngineHolds() {
        final var aggregateId = "order-%s".formatted(UUID.randomUUID());
        final var reported = new ArrayList<List<String>>();

        processService
                .phaseOperations()
                .get(PhaseOperation.START_WORKFLOW)
                .phaseTwo(start("contract-task", aggregateId, reported));

        final var found = factory
                .rest()
                .instances(InstanceFilter
                        .anyInstance()
                        .withBusinessKey(aggregateId))
                .items();
        assertEquals(1, found.size(), found::toString);
        final var instance = found.getFirst();
        assertAll(
                () -> assertEquals(List.of(List.of(String.valueOf(instance.key()), String.valueOf(instance
                        .version()))), reported),
                () -> assertEquals("contract-task", instance.bpmnProcessId()),
                () -> assertEquals(aggregateId, instance
                        .variables()
                        .get("orderId")),
                () -> assertEquals(1000L, instance
                        .variables()
                        .get("amount")));
    }

    @Test
    @DisplayName("A start of a process nothing deployed is answered 404, read as permanent, and reports nothing")
    void aStartOfAProcessNothingDeployedIsPermanent() {
        final var reported = new ArrayList<List<String>>();

        final var failure = assertThrows(IllegalStateException.class, () -> processService
                .phaseOperations()
                .get(PhaseOperation.START_WORKFLOW)
                .phaseTwo(start("never-deployed", "order-%s".formatted(UUID.randomUUID()), reported)));

        assertAll(
                () -> assertTrue(failure
                        .getMessage()
                        .contains("the engine holds no process 'never-deployed'"), failure.getMessage()),
                () -> assertFalse(processService.isPhaseTwoFailureRepeatable(failure)),
                () -> assertEquals(List.of(), reported));
    }

    @Test
    @DisplayName("A start by a message starts an instance which carries the id and the shared values and reports nothing; the probe finds it by the id variable under scan only, also before a start is sent again")
    void aStartByAMessageStartsAWorkflowTheScanFinds() {
        final var aggregateId = "order-%s".formatted(UUID.randomUUID());
        final var reported = new ArrayList<List<String>>();
        final var plannedAt = Instant.now();

        processService
                .phaseOperations()
                .get(PhaseOperation.START_WORKFLOW_BY_MESSAGE)
                .phaseTwo(startByMessage(aggregateId, reported));

        final var instance = eventually(aggregateId);
        try (var scanningFactory = scanningFactory()) {
            final var scanning = processService(scanningFactory);
            assertAll(
                    () -> assertEquals(List.of(), reported),
                    () -> assertEquals(1000L, instance
                            .variables()
                            .get("amount")),
                    () -> assertEquals(WorkflowAwareness.UNKNOWN_TO_BPMS, processService.awarenessOfWorkflow(
                            STARTED_BY_A_MESSAGE, persistence(), aggregateId)),
                    () -> assertEquals(WorkflowAwareness.ACTIVE, scanning.awarenessOfWorkflow(STARTED_BY_A_MESSAGE,
                            persistence(), aggregateId)),
                    () -> assertEquals(WorkflowAwareness.ACTIVE, scanning.awarenessOfWorkflowForRedispatch(
                            STARTED_BY_A_MESSAGE, persistence(), aggregateId, plannedAt)),
                    () -> assertEquals(WorkflowAwareness.UNKNOWN_TO_BPMS, scanning.awarenessOfWorkflowForRedispatch(
                            STARTED_BY_A_MESSAGE, persistence(), aggregateId, Instant
                                    .now()
                                    .plusSeconds(60))));
        }
    }

    @Test
    @DisplayName("Twenty starts by one message at the same moment each start their workflow, because the adapter publishes them one after the other")
    void startsByOneMessageAtOnceEachStartTheirWorkflow() throws Exception {
        final var aggregateIds = new ArrayList<String>();
        try (var starts = Executors.newFixedThreadPool(20)) {
            final var done = new ArrayList<Future<?>>();
            for (var start = 0; start < 20; ++start) {
                final var aggregateId = "order-%s".formatted(UUID.randomUUID());
                aggregateIds.add(aggregateId);
                done.add(starts.submit(() -> processService
                        .phaseOperations()
                        .get(PhaseOperation.START_WORKFLOW_BY_MESSAGE)
                        .phaseTwo(startByMessage(aggregateId, new ArrayList<>()))));
            }
            for (final var start : done) {
                start.get(30, TimeUnit.SECONDS);
            }
        }

        assertAll(aggregateIds
                .stream()
                .map(aggregateId -> () -> eventually(aggregateId)));
    }

    private static PhaseTwoRequest<Object> start(
        final String bpmnProcessId,
        final String aggregateId,
        final List<List<String>> reported
    ) {
        return new PhaseTwoRequest<>("shop", bpmnProcessId, new AggregatePersistenceAware<>() {

            @Override
            public String getAggregateIdName() {
                return "orderId";
            }

            @Override
            public Object loadById(final Object id) {
                return new Object();
            }

        }, aggregateId, Map.of(), new WorkflowStartReport() {

            @Override
            public void startedWorkflow(final String workflowId) {
                reported.add(List.of(workflowId));
            }

            @Override
            public void startedWorkflow(
                final String workflowId,
                final String processVersion
            ) {
                reported.add(List.of(workflowId, processVersion));
            }

        });
    }

    private static PhaseTwoRequest<Object> startByMessage(
        final String aggregateId,
        final List<List<String>> reported
    ) {
        final var request = start("contract-started", aggregateId, reported);
        return new PhaseTwoRequest<>(request.workflowModuleId(), request.bpmnProcessId(), request
                .aggregatePersistence(), aggregateId, Map.of(PhaseTwoCall.ARG_MESSAGE_NAME,
                        "contract-started"), new WorkflowStartReport() {

                            @Override
                            public void startedWorkflow(final String workflowId) {
                                reported.add(List.of(workflowId));
                            }

                            @Override
                            public void startedWorkflow(
                                final String workflowId,
                                final String processVersion
                            ) {
                                reported.add(List.of(workflowId, processVersion));
                            }

                        });
    }

    // the running instance of contract-started whose id variable names the aggregate, once the
    // engine shows it
    private static ProcessInstanceRef eventually(final String aggregateId) {
        final var deadline = Instant
                .now()
                .plusSeconds(10);
        while (true) {
            final var found = factory
                    .rest()
                    .instances(InstanceFilter
                            .anyInstance()
                            .withBpmnProcessId("contract-started"))
                    .items()
                    .stream()
                    .filter(instance -> aggregateId.equals(instance
                            .variables()
                            .get("orderId")))
                    .findFirst();
            if (found.isPresent()) {
                return found.get();
            }
            assertTrue(Instant
                    .now()
                    .isBefore(deadline),
                    () -> "no instance of contract-started names aggregate '%s'".formatted(
                            aggregateId));
            LockSupport.parkNanos(Duration
                    .ofMillis(100)
                    .toNanos());
        }
    }

    // a factory of the engine under message-start-lookup scan, whose deployment remembered that a
    // message starts contract-started
    private static ZenBpmClientFactory scanningFactory() {
        final var configuration = new ZenBpmAdapterConfiguration();
        configuration.setRestAddress(ENGINE.restAddress());
        configuration.setMessageStartLookup(MessageStartLookup.SCAN);
        final var scanning = new ZenBpmClientFactory(ADAPTER_ID, configuration, () -> "starts-it");
        final var messages = new ModuleMessages(List.of("contract-started"), Set.of(), Set.of("contract-started"), Set
                .of("contract-started"), List.of());
        scanning
                .deployedProcesses()
                .recordMessages("shop", messages, () -> messages);
        return scanning;
    }

    private static ZenBpmProcessService<Object> processService(final ZenBpmClientFactory factory) {
        return new ZenBpmProcessService<>(ADAPTER_ID, factory, TestCollaborators.of(ADAPTER_ID, TestScoping.of(
                NameClashAvoidance.NONE),
                new SharingTheAmount()), (
                    module,
                    process) -> null);
    }

    private static AggregatePersistenceAware<Object> persistence() {
        return new AggregatePersistenceAware<>() {

            @Override
            public String getAggregateIdName() {
                return "orderId";
            }

        };
    }

    private static byte[] fixture(final String name) {
        try (var resource = ZenBpmProcessServiceIT.class.getResourceAsStream("/contract/%s".formatted(name))) {
            return resource.readAllBytes();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * A sync model which shares the amount of every aggregate.
     */
    private static final class SharingTheAmount implements WorkflowAggregateSync {

        @Override
        public Map<String, Object> syncedValues(
            final Object workflowAggregate,
            final AggregateSyncMode adapterDefault
        ) {
            return Map.of("amount", 1000);
        }

        @Override
        public void validateSyncModel(final Class<?> workflowAggregateClass) {
        }

    }

}

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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pbinitiative.zenbpmadapter.TestCollaborators;
import org.pbinitiative.zenbpmadapter.TestScoping;
import org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientFactory;
import org.pbinitiative.zenbpmadapter.client.api.InstanceFilter;
import org.pbinitiative.zenbpmadapter.test.EngineUnderTest;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.vanillabp.integration.adapter.spi.AggregateSyncMode;
import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.adapter.spi.PhaseTwoRequest;
import io.vanillabp.integration.adapter.spi.WorkflowAggregateSync;
import io.vanillabp.integration.adapter.spi.workflowstart.WorkflowStartReport;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Phase two of a start against the pinned engine: the instance it reports is the one the engine
 * holds under the aggregate's id, with the id and the shared values as its variables, and a start of
 * a process nothing deployed is permanent.
 */
@ExtendWith(SuppressOutputExtension.class)
@Testcontainers(disabledWithoutDocker = true)
class ZenBpmProcessServiceIT {

    private static final String ADAPTER_ID = "starts";

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
                .deployBpmn(contractTask());
        processService = new ZenBpmProcessService<>(ADAPTER_ID, factory, TestCollaborators.of(ADAPTER_ID, TestScoping
                .of(NameClashAvoidance.NONE),
                new SharingTheAmount()), (
                    module,
                    process) -> null);
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

    private static byte[] contractTask() {
        try (var resource = ZenBpmProcessServiceIT.class.getResourceAsStream("/contract/contract-task.bpmn")) {
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

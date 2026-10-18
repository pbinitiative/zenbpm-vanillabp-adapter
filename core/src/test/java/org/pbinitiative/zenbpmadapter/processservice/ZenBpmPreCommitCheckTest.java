/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.processservice;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pbinitiative.zenbpmadapter.TestCollaborators;
import org.pbinitiative.zenbpmadapter.TestScoping;
import org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientFactory;

import io.vanillabp.integration.adapter.spi.AggregateSyncMode;
import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.adapter.spi.PhaseOneRequest;
import io.vanillabp.integration.adapter.spi.PreCommitRegistrar;
import io.vanillabp.integration.adapter.spi.WorkflowAggregateSync;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Where phase one of a completion and of a cancellation asks the engine: not when the application
 * calls, but when the platform runs the checks registered for the transaction of the aggregate,
 * right before it commits, which keeps the window between the check and phase two small. Proven
 * without an engine: the REST address is a closed port, so any contact fails. Nothing fails until
 * the hook runs, and the hook fails when it runs, so a commit would be rolled back.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmPreCommitCheckTest {

    private final RecordingRegistrar registrar = new RecordingRegistrar();

    private final ZenBpmClientFactory factory = factoryOfAClosedPort();

    private final ZenBpmProcessService<Order> processService = new ZenBpmProcessService<>("orders", factory, TestCollaborators
            .withPreCommitRegistrar("orders", TestScoping.of(NameClashAvoidance.USE_PREFIX),
                    new SharingNothing(), registrar), (
                        module,
                        process) -> null);

    @AfterEach
    void closeTheFactory() {
        factory.close();
    }

    @Test
    @DisplayName("Phase one of a completion and of a cancellation only registers a check for the transaction of the aggregate: the engine is not asked when the application calls")
    void phaseOneRegistersTheCheckWithoutAskingTheEngine() {
        final var completion = processService
                .phaseOperations()
                .get(PhaseOperation.COMPLETE_TASK);
        final var cancellation = processService
                .phaseOperations()
                .get(PhaseOperation.CANCEL_TASK);

        assertDoesNotThrow(() -> completion.phaseOne(request(Map.of(PhaseTwoCall.ARG_TASK_ID, "123"))));
        assertDoesNotThrow(() -> cancellation.phaseOne(request(Map.of(PhaseTwoCall.ARG_TASK_ID, "124",
                PhaseTwoCall.ARG_BPMN_ERROR_CODE, "withdrawn"))));

        assertAll(
                () -> assertEquals(2, registrar.checks.size(), "one check per operation"),
                () -> assertEquals(List.of(Order.class, Order.class), registrar.aggregateClasses));
    }

    @Test
    @DisplayName("The registered check asks the engine when the hook runs, and an engine which cannot be asked fails it, so the commit is rolled back")
    void theCheckAsksTheEngineWhenTheHookRuns() {
        processService
                .phaseOperations()
                .get(PhaseOperation.COMPLETE_TASK)
                .phaseOne(request(Map.of(PhaseTwoCall.ARG_TASK_ID, "123")));
        final var check = registrar.checks.getFirst();

        // Exception, not Throwable: a hook which catches Exception around the check has to see it
        final var failure = assertThrows(Exception.class, check::run);

        assertEquals(IllegalStateException.class, failure.getClass(), failure::toString);
    }

    private static PhaseOneRequest<Order> request(final Map<String, String> args) {
        return new PhaseOneRequest<>("shop", "Order", new OrderPersistence(), new Order(), args);
    }

    private static ZenBpmClientFactory factoryOfAClosedPort() {
        final var configuration = new ZenBpmAdapterConfiguration();
        // nothing listens on port 1, so every request fails at once
        configuration.setRestAddress("http://localhost:1");
        configuration.setWorkflowVisibilityTimeout(Duration.ZERO);
        return new ZenBpmClientFactory("orders", configuration, () -> "shop");
    }

    /**
     * Keeps every check and the aggregate class it was registered for, as the platform would
     * resolve the transaction runner of that aggregate by it.
     */
    private static final class RecordingRegistrar implements PreCommitRegistrar {

        private final List<Runnable> checks = new ArrayList<>();

        private final List<Class<?>> aggregateClasses = new ArrayList<>();

        @Override
        public void beforeCommit(
            final Class<?> workflowAggregateClass,
            final Runnable check
        ) {
            aggregateClasses.add(workflowAggregateClass);
            checks.add(check);
        }

    }

    /**
     * The aggregate of the test.
     */
    private static final class Order {
    }

    /**
     * The persistence of the aggregate, which names its class.
     */
    private static final class OrderPersistence implements AggregatePersistenceAware<Order> {

        @Override
        public Class<Order> getAggregateClass() {
            return Order.class;
        }

    }

    /**
     * A sync model which shares nothing; phase one never asks it.
     */
    private static final class SharingNothing implements WorkflowAggregateSync {

        @Override
        public Map<String, Object> syncedValues(
            final Object workflowAggregate,
            final AggregateSyncMode adapterDefault
        ) {
            return Map.of();
        }

        @Override
        public void validateSyncModel(final Class<?> workflowAggregateClass) {
        }

    }

}

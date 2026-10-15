/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.processservice;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pbinitiative.zenbpmadapter.TestCollaborators;
import org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientFactory;

import io.vanillabp.integration.adapter.spi.PhaseOperationHandler;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The process service of this version of the adapter: it says who it is, serves every operation
 * the core requires of an adapter, and refuses every call with words which say why, instead of
 * pretending to have done the work.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmProcessServiceTest {

    private static final String NOT_CONFIGURED = "ZenBPM adapter 'orders' is not configured: set the key";

    @Test
    @DisplayName("The process service names its adapter id and its collaborators")
    void theProcessServiceSaysWhoItIs() {
        final var collaborators = TestCollaborators.of("orders");
        final var processService = new ZenBpmProcessService<Object>("orders", unconfigured("orders"), collaborators);

        assertAll(
                () -> assertEquals("orders", processService.getAdapterId()),
                () -> assertSame(collaborators, processService.collaborators()));
    }

    @Test
    @DisplayName("A factory or collaborators of another adapter id are refused, naming all three ids")
    void theThreeOfOneAdapterIdAreWiredTogether() {
        final var otherFactory = assertThrows(IllegalArgumentException.class, () -> new ZenBpmProcessService<>(
                "orders", unconfigured("billing"), TestCollaborators.of("orders")));
        final var otherCollaborators = assertThrows(IllegalArgumentException.class, () -> new ZenBpmProcessService<>(
                "orders", unconfigured("orders"), TestCollaborators.of("billing")));

        assertAll(
                () -> assertEquals(
                        "The process service of ZenBPM adapter id 'orders' was given the factory of adapter id 'billing' and the collaborators of adapter id 'orders'; the platform module wires the three of one id.",
                        otherFactory.getMessage()),
                () -> assertTrue(otherCollaborators
                        .getMessage()
                        .contains("the collaborators of adapter id 'billing'"), otherCollaborators.getMessage()));
    }

    @Test
    @DisplayName("Every operation the core requires of an adapter is served, and only those, in a map nobody can change")
    void everyRequiredOperationIsServed() {
        final var operations = new ZenBpmProcessService<Object>("orders", unconfigured("orders"), TestCollaborators
                .of("orders")).phaseOperations();

        assertAll(
                () -> assertEquals(PhaseOperation.CORE_OPERATIONS
                        .stream()
                        .filter(PhaseOperation::requiredOfEveryAdapter)
                        .toList(), List.copyOf(operations.keySet())),
                () -> assertFalse(operations.containsKey(PhaseOperation.SEND_SIGNAL)),
                () -> assertFalse(operations.containsKey(PhaseOperation.AGGREGATE_CHANGED)),
                () -> assertThrows(UnsupportedOperationException.class, () -> operations.put(
                        PhaseOperation.SEND_SIGNAL, null)));
    }

    @Test
    @DisplayName("Every handler, probe and viewer method refuses with the message of the validation where the adapter id has no clients")
    void anAdapterIdWithoutClientsRefusesWithItsValidationMessage() {
        final var processService = new ZenBpmProcessService<Object>("orders", unconfigured("orders"), TestCollaborators
                .of("orders"));

        assertAll(calls(processService)
                .map(call -> () -> assertEquals(NOT_CONFIGURED, assertThrows(IllegalStateException.class,
                        call::run).getMessage())));
    }

    @Test
    @DisplayName("Every handler, probe and viewer method of a configured adapter id says that it is not built yet, and what it was asked to do")
    void aConfiguredAdapterIdSaysThatTheCallIsNotBuiltYet() {
        try (var factory = configured("orders")) {
            final var processService = new ZenBpmProcessService<Object>("orders", factory, TestCollaborators.of(
                    "orders"));

            final var messages = calls(processService)
                    .map(call -> assertThrows(UnsupportedOperationException.class, call::run).getMessage())
                    .toList();

            assertAll(
                    () -> assertEquals(
                            "ZenBPM adapter 'orders': phase one of START_WORKFLOW is implemented in a later story",
                            messages.getFirst()),
                    () -> assertEquals(
                            "ZenBPM adapter 'orders': phase two of START_WORKFLOW is implemented in a later story",
                            messages.get(1)),
                    () -> assertEquals(List.of(
                            "ZenBPM adapter 'orders': asking for task 'task-1' is implemented in a later story",
                            "ZenBPM adapter 'orders': asking for the workflow of an aggregate is implemented in a later story",
                            "ZenBPM adapter 'orders': asking for user task 'task-2' is implemented in a later story",
                            "ZenBPM adapter 'orders': listing the process definitions of workflow 'Order' of workflow module 'shop' is implemented in a later story",
                            "ZenBPM adapter 'orders': reading the BPMN XML of process definition 'definition-1' of workflow module 'shop' is implemented in a later story",
                            "ZenBPM adapter 'orders': reading the history of workflow 'Order' of workflow module 'shop' is implemented in a later story"),
                            messages.subList(messages.size() - 6, messages.size())));
        }
    }

    // both phases of every handler, then the three probes, then the three viewer methods
    private static Stream<Runnable> calls(final ZenBpmProcessService<Object> processService) {
        final var handlers = processService
                .phaseOperations()
                .values()
                .stream()
                .flatMap(handler -> Stream.<Consumer<PhaseOperationHandler<Object>>>of(
                        phases -> phases.phaseOne(null),
                        phases -> phases.phaseTwo(null))
                        .map(phase -> (Runnable) () -> phase.accept(handler)));
        return Stream.concat(handlers, Stream.of(
                () -> processService.awarenessOfTask(null, "order-1", "task-1"),
                () -> processService.awarenessOfWorkflow(null, null, "order-1"),
                () -> processService.awarenessOfUserTask(null, "order-1", "task-2"),
                () -> processService.getProcessDefinitions("shop", "Order", null, "order-1", null),
                () -> processService.getBpmnXml("shop", "Order", "definition-1"),
                () -> processService.getWorkflowHistory("shop", "Order", null, "order-1", null)));
    }

    private static ZenBpmClientFactory unconfigured(final String adapterId) {
        return ZenBpmClientFactory.unconfigured(adapterId, new ZenBpmAdapterConfiguration(), NOT_CONFIGURED);
    }

    private static ZenBpmClientFactory configured(final String adapterId) {
        final var configuration = new ZenBpmAdapterConfiguration();
        configuration.setRestAddress("http://localhost:8080");
        return new ZenBpmClientFactory(adapterId, configuration, () -> "shop");
    }

}

/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.processservice;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.pbinitiative.zenbpmadapter.TestCollaborators;
import org.pbinitiative.zenbpmadapter.TestScoping;
import org.pbinitiative.zenbpmadapter.ZenBpmDeployedProcesses;
import org.pbinitiative.zenbpmadapter.ZenBpmDeployedProcesses.DeployedProcess;
import org.pbinitiative.zenbpmadapter.ZenBpmDeployedProcesses.ModuleMessages;
import org.pbinitiative.zenbpmadapter.client.FakeEngine;
import org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientFactory;

import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.adapter.spi.PhaseOneRequest;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Phase one of a start by a message: the message name is judged by the start messages of the newest
 * version of each process the workflow module deployed, as the deployment remembered them, and
 * read again before a name is refused, and the engine is never asked whether a start event takes
 * it. A name the newest version of a process starts with passes; any other name is refused, naming
 * the start messages and, where a model waits for the name, <code>correlateMessage</code>; where
 * not every model is known, the check says nothing.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmStartMessageDeclarationTest {

    private final FakeEngine engine = FakeEngine.started();

    @AfterEach
    void stopTheEngine() {
        engine.close();
    }

    @Test
    @DisplayName("A message the newest version of a process starts with passes, compared by the name the engine holds, and nothing is read again and the engine is not asked")
    void aMessageTheNewestVersionStartsWithPasses() {
        final var readings = new AtomicInteger();
        try (var factory = configured()) {
            final var messages = messages(Set.of("shop__paid"), Set.of("shop__ordered"));
            factory
                    .deployedProcesses()
                    .recordMessages("shop", messages, () -> {
                        readings.incrementAndGet();
                        return messages;
                    });

            assertDoesNotThrow(() -> checkTheStart(factory, "ordered"));
        }

        assertAll(
                () -> assertEquals(0, readings.get()),
                () -> assertEquals(List.of(), engine.requests()));
    }

    @Test
    @DisplayName("A message the newest version of no process starts with is refused, naming the processes read and the messages they start with, after the versions were read again")
    void aMessageNoProcessStartsWithIsRefusedNamingTheStarts() {
        final var readings = new AtomicInteger();
        try (var factory = configured()) {
            final var messages = messages(Set.of("shop__paid"), Set.of("shop__ordered", "shop__reordered"));
            factory
                    .deployedProcesses()
                    .recordMessages("shop", messages, () -> {
                        readings.incrementAndGet();
                        return messages;
                    });

            final var refusal = assertThrows(IllegalArgumentException.class, () -> checkTheStart(factory,
                    "orderd"));
            final var withoutAnyStart = assertThrows(IllegalArgumentException.class, () -> {
                keep(factory.deployedProcesses(), "billing", messages(Set.of(), Set.of()));
                checkTheStart(factory, "billing", "billed");
            });

            assertAll(
                    () -> assertEquals("""
                            ZenBPM adapter 'orders' cannot start workflow 'Order' of workflow module 'shop' by message \
                            'orderd' (scoped: 'shop__orderd'): the newest version the engine holds of the processes \
                            [shop__Order, shop__Invoice] starts with none of that name, and ZenBPM hands a start \
                            message to the start events of the newest version of a process only. They start with: \
                            [shop__ordered, shop__reordered]. Correct the name passed to startWorkflowByMessage, or \
                            start the process with that message.""", refusal.getMessage()),
                    () -> assertEquals("""
                            ZenBPM adapter 'orders' cannot start workflow 'Order' of workflow module 'billing' by \
                            message 'billed' (scoped: 'billing__billed'): the newest version the engine holds of the \
                            processes [shop__Order, shop__Invoice] starts with none of that name, and ZenBPM hands a \
                            start message to the start events of the newest version of a process only. They start \
                            with: none. Correct the name passed to startWorkflowByMessage, or start the process with \
                            that message.""", withoutAnyStart.getMessage()),
                    () -> assertEquals(1, readings.get()));
        }

        assertEquals(List.of(), engine.requests());
    }

    @Test
    @DisplayName("A message a model waits for and no newest version starts with is refused, naming correlateMessage")
    void aMessageAModelWaitsForIsRefusedNamingCorrelateMessage() {
        try (var factory = configured()) {
            keep(factory.deployedProcesses(), "shop", messages(Set.of("shop__paid"), Set.of("shop__ordered")));

            final var refusal = assertThrows(IllegalArgumentException.class, () -> checkTheStart(factory, "paid"));

            assertEquals(
                    "A model waits for that message, so to continue a workflow which waits for it use correlateMessage instead.",
                    refusal
                            .getMessage()
                            .substring(refusal
                                    .getMessage()
                                    .indexOf("A model waits")));
        }
    }

    @Test
    @DisplayName("A name is refused only by the models the engine holds when it is refused: a name the newest version another node deployed since starts with passes once the versions were read again")
    void aRefusalRestsOnTheModelsReadAgain() {
        try (var factory = configured()) {
            final var now = messages(Set.of(), Set.of("shop__ordered", "shop__requested"));
            factory
                    .deployedProcesses()
                    .recordMessages("shop", messages(Set.of(), Set.of("shop__ordered")), () -> now);

            assertDoesNotThrow(() -> checkTheStart(factory, "requested"));
            assertEquals(Set.of("shop__ordered", "shop__requested"), factory
                    .deployedProcesses()
                    .messagesOf("shop")
                    .orElseThrow()
                    .starting());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("aPictureWhichMissesAModel")
    @DisplayName("The check says nothing where it cannot see every model which may start with the name")
    void theCheckSaysNothingWhereAModelIsUnknown(
        final String where,
        final Consumer<ZenBpmDeployedProcesses> deployment
    ) {
        try (var factory = configured()) {
            deployment.accept(factory.deployedProcesses());

            assertDoesNotThrow(() -> checkTheStart(factory, "orderd"));
        }

        assertEquals(List.of(), engine.requests());
    }

    static Stream<Arguments> aPictureWhichMissesAModel() {
        return Stream.of(
                Arguments.of("the workflow module deployed no process in this start, an older definition still runs",
                        (Consumer<ZenBpmDeployedProcesses>) deployed -> keep(deployed, "billing", messages(Set.of(),
                                Set.of("billing__billed")))),
                Arguments.of("a version of a process could not be read",
                        (Consumer<ZenBpmDeployedProcesses>) deployed -> keep(deployed, "shop", new ModuleMessages(List
                                .of("shop__Order"), Set.of(), Set.of("shop__ordered"), Set.of("shop__Order"), List.of(
                                        "the versions of process 'shop__Order' (status 500)")))),
                Arguments.of("a version of a process could not be read again before the refusal",
                        (Consumer<ZenBpmDeployedProcesses>) deployed -> deployed.recordMessages("shop", messages(Set
                                .of(), Set.of("shop__ordered")),
                                () -> new ModuleMessages(List.of("shop__Order"), Set.of(), Set.of("shop__ordered"), Set
                                        .of("shop__Order"), List.of(
                                                "the versions of process 'shop__Order' (status 500)")))),
                Arguments.of(
                        "the workflow module serves a process it deployed no model for, the old id of a renamed one",
                        (Consumer<ZenBpmDeployedProcesses>) deployed -> {
                            deployed.record(new DeployedProcess("shop", "Order", "shop__Order", 11, 1, null, "<x/>"));
                            deployed.recordServed("shop", "shop__Order");
                            deployed.recordServed("shop", "shop__OrderBeforeTheRename");
                            keep(deployed, "shop", messages(Set.of(), Set.of("shop__ordered")));
                        }));
    }

    private void checkTheStart(
        final ZenBpmClientFactory factory,
        final String messageName
    ) {
        checkTheStart(factory, "shop", messageName);
    }

    // through the handler the process service registers, the boundary the core calls
    private void checkTheStart(
        final ZenBpmClientFactory factory,
        final String workflowModuleId,
        final String messageName
    ) {
        new ZenBpmProcessService<Object>("orders", factory, TestCollaborators.of("orders", TestScoping.of(
                NameClashAvoidance.USE_PREFIX)), (
                    module,
                    process) -> null)
                .phaseOperations()
                .get(PhaseOperation.START_WORKFLOW_BY_MESSAGE)
                .phaseOne(request(workflowModuleId, messageName));
    }

    private static PhaseOneRequest<Object> request(
        final String workflowModuleId,
        final String messageName
    ) {
        return new PhaseOneRequest<>(workflowModuleId, "Order", new AggregatePersistenceAware<>() {

            @Override
            public String getAggregateIdName() {
                return "orderId";
            }

        }, new Object(), Map.of(PhaseTwoCall.ARG_MESSAGE_NAME, messageName));
    }

    // the models read again are those read while deploying
    private static void keep(
        final ZenBpmDeployedProcesses deployed,
        final String workflowModuleId,
        final ModuleMessages messages
    ) {
        deployed.recordMessages(workflowModuleId, messages, () -> messages);
    }

    private static ModuleMessages messages(
        final Set<String> awaited,
        final Set<String> starting
    ) {
        return new ModuleMessages(List.of("shop__Order", "shop__Invoice"), awaited, starting, Set
                .of("shop__Order"), List.of());
    }

    private ZenBpmClientFactory configured() {
        final var configuration = new ZenBpmAdapterConfiguration();
        configuration.setRestAddress(engine.address());
        return new ZenBpmClientFactory("orders", configuration, () -> "shop");
    }

}

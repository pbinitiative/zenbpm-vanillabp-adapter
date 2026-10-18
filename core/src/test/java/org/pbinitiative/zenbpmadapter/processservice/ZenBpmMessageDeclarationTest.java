/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.processservice;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * Phase one of a correlation: the message name is judged by the messages of the models the engine
 * holds for the processes the workflow module deployed, which the deployment remembered and which
 * are read again before a name is refused, and the engine is never asked whether something waits.
 * A name a model waits for passes, a name the newest version of a process starts it with is refused
 * with the hint to start by message, and a name no model knows is refused naming the models read;
 * where not every model is known, the check says nothing.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmMessageDeclarationTest {

    private final FakeEngine engine = FakeEngine.started();

    @AfterEach
    void stopTheEngine() {
        engine.close();
    }

    @Test
    @DisplayName("A message a model of the workflow module waits for passes, compared by the name the engine holds, and the engine is not asked")
    void aMessageAModelWaitsForPasses() {
        try (var factory = configured()) {
            keep(factory, "shop", messages(Set.of("shop__paid", "shop__shipped"), Set.of("shop__ordered")));

            assertDoesNotThrow(() -> checkTheMessage(factory, "paid"));
        }

        assertEquals(List.of(), engine.requests());
    }

    @Test
    @DisplayName("A message a model only starts its process with is refused, naming startWorkflowByMessage and why the engine would start a workflow")
    void aMessageAModelOnlyStartsWithIsRefused() {
        try (var factory = configured()) {
            keep(factory, "shop", messages(Set.of("shop__paid"), Set.of("shop__ordered")));

            final var refusal = assertThrows(IllegalArgumentException.class, () -> checkTheMessage(factory,
                    "ordered"));

            assertEquals("""
                    ZenBPM adapter 'orders' cannot correlate message 'ordered' (scoped: 'shop__ordered') to workflow \
                    'Order' of workflow module 'shop': a model of the workflow module starts its process with that \
                    message, and none waits for it. ZenBPM hands a message no workflow waits for to a start event of \
                    the same name, so the correlation would start a new workflow. To START a workflow by a message \
                    use startWorkflowByMessage instead.""", refusal.getMessage());
        }

        assertEquals(List.of(), engine.requests());
    }

    @Test
    @DisplayName("A message the newest version of a process starts with is refused although a model waits for it, because ZenBPM would start a new workflow with a correlation which comes before the workflow waits")
    void aMessageTheNewestVersionStartsWithIsRefusedAlthoughAModelWaitsForIt() {
        try (var factory = configured()) {
            keep(factory, "shop", messages(Set.of("shop__paid", "shop__ordered"), Set.of("shop__ordered")));

            final var refusal = assertThrows(IllegalArgumentException.class, () -> checkTheMessage(factory,
                    "ordered"));

            assertEquals("""
                    ZenBPM adapter 'orders' cannot correlate message 'ordered' (scoped: 'shop__ordered') to workflow \
                    'Order' of workflow module 'shop': the newest version of a process of the workflow module starts \
                    with that message, and a model the engine holds waits for it as well. ZenBPM hands a message no \
                    workflow waits for under its correlation key yet to the start event of the newest version, so a \
                    correlation which comes before the workflow waits would start a new workflow instead. Give the \
                    message which starts the process another name than the message a workflow waits for.""",
                    refusal.getMessage());
        }

        assertEquals(List.of(), engine.requests());
    }

    @Test
    @DisplayName("A name is refused only by the models the engine holds when it is refused: they are read again first, so a name a version deployed by another node since waits for passes; a name which passes reads nothing again")
    void aRefusalRestsOnTheModelsReadAgain() {
        final var readings = new AtomicInteger();
        try (var factory = configured()) {
            final var now = messages(Set.of("shop__paid", "shop__settled"), Set.of());
            factory
                    .deployedProcesses()
                    .recordMessages("shop", messages(Set.of("shop__paid"), Set.of()), () -> {
                        readings.incrementAndGet();
                        return now;
                    });

            assertDoesNotThrow(() -> checkTheMessage(factory, "paid"));
            final var readingsWhereItPassed = readings.get();
            assertDoesNotThrow(() -> checkTheMessage(factory, "settled"));
            assertDoesNotThrow(() -> checkTheMessage(factory, "settled"));
            final var refusal = assertThrows(IllegalArgumentException.class, () -> checkTheMessage(factory,
                    "payed"));

            assertAll(
                    () -> assertEquals(0, readingsWhereItPassed),
                    () -> assertEquals(2, readings.get()),
                    () -> assertTrue(refusal
                            .getMessage()
                            .contains("the messages they wait for are: [shop__paid, shop__settled]"),
                            refusal::getMessage));
        }
    }

    @Test
    @DisplayName("A message no model of the workflow module knows is refused, naming the processes read and the messages they wait for")
    void aMessageNoModelKnowsIsRefusedNamingTheModels() {
        try (var factory = configured()) {
            keep(factory, "shop", messages(Set.of("shop__shipped", "shop__paid"), Set.of("shop__ordered")));
            keep(factory, "billing", messages(Set.of(), Set.of()));

            final var refusal = assertThrows(IllegalArgumentException.class, () -> checkTheMessage(factory,
                    "payed"));
            final var refusalWithoutAnyMessage = assertThrows(IllegalArgumentException.class, () -> checkTheMessage(
                    factory, "billing", "payed"));

            assertAll(
                    () -> assertEquals("""
                            ZenBPM adapter 'orders' cannot correlate message 'payed' (scoped: 'shop__payed') to \
                            workflow 'Order' of workflow module 'shop': no model of the workflow module waits for that \
                            message. ZenBPM keeps no message nothing waits for, so its publication would be repeated \
                            until 'vanillabp.outbox.wait-for-visibility-at-most' passed and then blocked. Read was \
                            every version the engine holds of the processes [shop__Order, shop__Invoice], and the \
                            messages they wait for are: [shop__paid, shop__shipped]. Correct the name passed to \
                            correlateMessage, or declare the message at the element which waits for it.""", refusal
                            .getMessage()),
                    () -> assertEquals("""
                            ZenBPM adapter 'orders' cannot correlate message 'payed' (scoped: 'billing__payed') to \
                            workflow 'Order' of workflow module 'billing': no model of the workflow module waits for \
                            that message. ZenBPM keeps no message nothing waits for, so its publication would be \
                            repeated until 'vanillabp.outbox.wait-for-visibility-at-most' passed and then blocked. \
                            Read was every version the engine holds of the processes [shop__Order, shop__Invoice], \
                            and the messages they wait for are: none. Correct the name passed to correlateMessage, or \
                            declare the message at the element which waits for it.""", refusalWithoutAnyMessage
                            .getMessage()));
        }

        assertEquals(List.of(), engine.requests());
    }

    @Test
    @DisplayName("Under name-clash-avoidance none the name is compared as written, and so is it named")
    void underNoneTheNameIsComparedAsWritten() {
        try (var factory = configured()) {
            keep(factory, "crm", messages(Set.of("called"), Set.of()));
            final var processService = new ZenBpmProcessService<Object>("orders", factory, TestCollaborators.of(
                    "orders", TestScoping.of(NameClashAvoidance.NONE)), (
                        module,
                        process) -> null);
            final var correlation = processService
                    .phaseOperations()
                    .get(PhaseOperation.CORRELATE_MESSAGE);

            assertDoesNotThrow(() -> correlation.phaseOne(request("crm", "called")));
            final var refusal = assertThrows(IllegalArgumentException.class, () -> correlation.phaseOne(request(
                    "crm", "mailed")));

            assertTrue(refusal
                    .getMessage()
                    .startsWith(
                            "ZenBPM adapter 'orders' cannot correlate message 'mailed' to workflow 'Order' of workflow module 'crm': no model"),
                    refusal.getMessage());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("aPictureWhichMissesAModel")
    @DisplayName("The check says nothing where it cannot see every model which may carry the name")
    void theCheckSaysNothingWhereAModelIsUnknown(
        final String where,
        final Consumer<ZenBpmDeployedProcesses> deployment
    ) {
        try (var factory = configured()) {
            deployment.accept(factory.deployedProcesses());

            assertDoesNotThrow(() -> checkTheMessage(factory, "payed"));
        }

        assertEquals(List.of(), engine.requests());
    }

    static Stream<Arguments> aPictureWhichMissesAModel() {
        return Stream.of(
                Arguments.of("the workflow module deployed no process in this start, an older definition still runs",
                        (Consumer<ZenBpmDeployedProcesses>) deployed -> keep(deployed, "billing", messages(Set.of(
                                "billing__payed"), Set.of()))),
                Arguments.of("a version of a process could not be read",
                        (Consumer<ZenBpmDeployedProcesses>) deployed -> keep(deployed, "shop", new ModuleMessages(List
                                .of("shop__Order"), Set.of("shop__paid"), Set.of(), List.of(
                                        "the versions of process 'shop__Order' (status 500)")))),
                Arguments.of("a version of a process could not be read again before the refusal",
                        (Consumer<ZenBpmDeployedProcesses>) deployed -> deployed.recordMessages("shop", messages(Set
                                .of("shop__paid"), Set.of()),
                                () -> new ModuleMessages(List.of("shop__Order"), Set.of(
                                        "shop__paid"), Set.of(), List.of(
                                                "the versions of process 'shop__Order' (status 500)")))),
                Arguments.of(
                        "the workflow module serves a process it deployed no model for, the old id of a renamed one",
                        (Consumer<ZenBpmDeployedProcesses>) deployed -> {
                            deployed.record(new DeployedProcess("shop", "Order", "shop__Order", 11, 1, null, "<x/>"));
                            deployed.recordServed("shop", "shop__Order");
                            deployed.recordServed("shop", "shop__OrderBeforeTheRename");
                            keep(deployed, "shop", messages(Set.of("shop__paid"), Set.of()));
                        }));
    }

    private void checkTheMessage(
        final ZenBpmClientFactory factory,
        final String messageName
    ) {
        checkTheMessage(factory, "shop", messageName);
    }

    // through the handler the process service registers, the boundary the core calls
    private void checkTheMessage(
        final ZenBpmClientFactory factory,
        final String workflowModuleId,
        final String messageName
    ) {
        new ZenBpmProcessService<Object>("orders", factory, TestCollaborators.of("orders", TestScoping.of(
                NameClashAvoidance.USE_PREFIX)), (
                    module,
                    process) -> null)
                .phaseOperations()
                .get(PhaseOperation.CORRELATE_MESSAGE)
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
        final ZenBpmClientFactory factory,
        final String workflowModuleId,
        final ModuleMessages messages
    ) {
        keep(factory.deployedProcesses(), workflowModuleId, messages);
    }

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
        return new ModuleMessages(List.of("shop__Order", "shop__Invoice"), awaited, starting, List.of());
    }

    private ZenBpmClientFactory configured() {
        final var configuration = new ZenBpmAdapterConfiguration();
        configuration.setRestAddress(engine.address());
        return new ZenBpmClientFactory("orders", configuration, () -> "shop");
    }

}

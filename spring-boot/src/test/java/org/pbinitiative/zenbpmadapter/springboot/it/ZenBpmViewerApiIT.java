/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientRegistry;
import org.pbinitiative.zenbpmadapter.springboot.it.viewer.Parcel;
import org.pbinitiative.zenbpmadapter.springboot.it.viewer.ParcelRepository;
import org.pbinitiative.zenbpmadapter.springboot.it.viewer.ShippingWorkflow;
import org.pbinitiative.zenbpmadapter.springboot.it.viewer.ViewerItApplication;
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
import io.vanillabp.spi.process.ProcessDefinition;
import io.vanillabp.spi.process.ProcessDefinitionNotFoundException;
import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.process.WorkflowElementHistory;
import io.vanillabp.spi.process.WorkflowElementType;
import io.vanillabp.spi.process.WorkflowHistory;
import io.vanillabp.spi.process.WorkflowNotFoundException;

/**
 * The viewer/history API of a booted Spring Boot application against the pinned engine. The
 * workflow <code>Shipping</code> passes a task, an embedded sub-process, a call activity of the
 * process <code>Packing</code> on the same aggregate, and a multi-instance task with two iterations,
 * and the application listens to its end, so its model carries an end marker. A viewer gets the
 * definitions of both processes, each with the BPMN the engine holds, and the history of the
 * workflow, in which the call activity names the instance it started, whose history the viewer
 * gets through it. A task with an incident carries the engine's message, a context which belongs
 * to another workflow is not served, and a definition the engine does not hold is reported as
 * unknown.
 * <p>
 * The application {@link ViewerItApplication} runs with <code>viewer-it.yaml</code> under
 * <code>use-prefix</code>, so the engine holds the processes as <code>deploy-it__Shipping</code> and
 * <code>deploy-it__Packing</code>, while the viewer names them as the workflow module does.
 */
@ExtendWith(SuppressOutputExtension.class)
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = ViewerItApplication.class, properties = "spring.config.name=viewer-it")
@DirtiesContext
class ZenBpmViewerApiIT {

    /**
     * How long a case waits for a workflow to end or to fail. Every step takes milliseconds, so the
     * limit only turns a step which never happens into a failure.
     */
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Container
    private static final EngineUnderTest ENGINE = new EngineUnderTest();

    @Autowired
    private ProcessService<Parcel> processService;

    @Autowired
    private ParcelRepository parcels;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private ZenBpmClientRegistry clients;

    @Test
    @DisplayName("A workflow with a call activity shows both definitions with the BPMN the engine holds, and its history names the instance the call activity started, whose history the viewer gets through it")
    void aWorkflowWithACallActivityShowsBothDefinitionsAndBothHistories() throws IOException {
        final var parcel = start(false);
        final var history = eventually(() -> Optional
                .of(processService.getWorkflowHistory(parcel, null))
                .filter(read -> read.endTime() != null));

        final var definitions = processService.getProcessDefinitions(parcel, null);
        final var shipping = definitions.getFirst();
        final var packing = definitions.getLast();
        final var shippingXml = xmlOf(shipping);
        final var packingXml = xmlOf(packing);
        final var callPacking = only(history, "CallPacking");
        final var packingContext = callPacking.secondaryWorkflowHistoryContext();
        final var packingHistory = processService.getWorkflowHistory(parcel, packingContext);
        final var packingDefinitions = processService.getProcessDefinitions(parcel, packingContext);

        assertAll(
                () -> assertEquals(2, definitions.size(), definitions::toString),
                () -> assertEquals("zenbpm#%s".formatted(keyOf("deploy-it__Shipping")), shipping.id()),
                () -> assertEquals("Shipping", shipping.bpmnProcessId()),
                () -> assertEquals("1", shipping.version()),
                () -> assertNull(shipping.usedByElements()),
                () -> assertEquals("zenbpm#%s".formatted(keyOf("deploy-it__Packing")), packing.id()),
                () -> assertEquals("Packing", packing.bpmnProcessId()),
                () -> assertEquals(List.of("CallPacking"), packing.usedByElements()),
                // the BPMN as the engine holds it, the end marker the deployment wrote included
                () -> assertTrue(shippingXml.contains("id=\"deploy-it__Shipping\""), shippingXml),
                () -> assertTrue(shippingXml.contains("id=\"vanillabp__ended__Shipped\""), shippingXml),
                () -> assertTrue(packingXml.contains("id=\"deploy-it__Packing\""), packingXml),
                () -> assertEquals(shipping.id(), history.processDefinitionId()),
                () -> assertNotNull(history.startTime()),
                () -> assertEquals(List.of("Ordered", "Flow_ToCheck", "Check", "Flow_ToWrapping", "Wrapping",
                        "WrappingStarted", "Flow_ToWrap", "Wrap", "Flow_ToWrapped", "Wrapped", "Flow_ToPacking",
                        "CallPacking", "Flow_ToLabel", "Label", "Label", "Label", "Flow_ToShipped", "Shipped"),
                        elementIdsOf(history)),
                () -> assertEquals(WorkflowElementType.SUB_PROCESS, only(history, "Wrapping").elementType()),
                () -> assertEquals(WorkflowElementType.SERVICE_TASK, only(history, "Wrap").elementType()),
                () -> assertEquals(WorkflowElementType.CALL_ACTIVITY, callPacking.elementType()),
                () -> assertEquals(List.of(WorkflowElementType.MULTI_INSTANCE, WorkflowElementType.SERVICE_TASK,
                        WorkflowElementType.SERVICE_TASK),
                        history
                                .elementsHistory()
                                .stream()
                                .filter(element -> "Label".equals(element.elementId()))
                                .map(WorkflowElementHistory::elementType)
                                .toList()),
                // every element the history names is drawn in the BPMN the viewer serves
                () -> assertTrue(history
                        .elementsHistory()
                        .stream()
                        .allMatch(element -> shippingXml.contains("bpmnElement=\"%s\"".formatted(element
                                .elementId()))),
                        shippingXml),
                () -> assertTrue(history
                        .elementsHistory()
                        .stream()
                        .noneMatch(element -> element.isCanceled() || (element.error() != null))),
                () -> assertNotNull(packingContext),
                () -> assertEquals(packing.id(), packingHistory.processDefinitionId()),
                () -> assertEquals(List.of("PackingStarted", "Flow_ToPack", "Pack", "Flow_ToPacked", "Packed"),
                        elementIdsOf(packingHistory)),
                () -> assertEquals(List.of(packing.id()), packingDefinitions
                        .stream()
                        .map(ProcessDefinition::id)
                        .toList()),
                () -> assertNull(packingDefinitions
                        .getFirst()
                        .usedByElements()));
    }

    @Test
    @DisplayName("A task whose handler failed carries the engine's message, and the workflow which waits there has not ended")
    void aTaskWithAnIncidentCarriesTheEnginesMessage() {
        final var parcel = start(true);

        final var history = eventually(() -> Optional
                .of(processService.getWorkflowHistory(parcel, null))
                .filter(read -> read
                        .elementsHistory()
                        .stream()
                        .anyMatch(element -> element.error() != null)));
        final var check = only(history, "Check");

        assertAll(
                () -> assertTrue(check
                        .error()
                        .contains(ShippingWorkflow.THE_CHECK_FAILED), check::error),
                () -> assertNull(check.endTime()),
                () -> assertNull(history.endTime()),
                () -> assertFalse(elementIdsOf(history).contains("Wrapping"), elementIdsOf(history)::toString));
    }

    @Test
    @DisplayName("The history context of another workflow's call activity is not served, so another workflow cannot be read through it")
    void aContextOfAnotherWorkflowIsNotServed() {
        final var parcel = start(false);
        final var other = start(false);
        final var otherHistory = eventually(() -> Optional
                .of(processService.getWorkflowHistory(other, null))
                .filter(read -> read.endTime() != null));
        final var otherContext = only(otherHistory, "CallPacking").secondaryWorkflowHistoryContext();
        eventually(() -> Optional
                .of(processService.getWorkflowHistory(parcel, null))
                .filter(read -> read.endTime() != null));

        assertAll(
                () -> assertThrows(WorkflowNotFoundException.class, () -> processService.getWorkflowHistory(parcel,
                        otherContext)),
                () -> assertThrows(WorkflowNotFoundException.class, () -> processService.getProcessDefinitions(parcel,
                        otherContext)),
                () -> assertThrows(WorkflowNotFoundException.class, () -> processService.getWorkflowHistory(parcel,
                        "no key")));
    }

    @Test
    @DisplayName("A definition the engine does not hold is reported as unknown")
    void aDefinitionTheEngineDoesNotHoldIsUnknown() {
        final var notFound = assertThrows(ProcessDefinitionNotFoundException.class, () -> processService.getBpmnXml(
                "zenbpm#1"));

        assertTrue(notFound
                .getMessage()
                .contains("'1'"), notFound::getMessage);
    }

    @DynamicPropertySource
    static void theEngine(final DynamicPropertyRegistry registry) {
        registry.add("vanillabp.adapters.zenbpm.rest-address", ENGINE::restAddress);
        registry.add("vanillabp.adapters.zenbpm.grpc-address", ENGINE::grpcAddress);
    }

    private Parcel start(final boolean checkFails) {
        return transactionTemplate.execute(status -> processService.startWorkflow(parcels.save(new Parcel(
                "parcel-%s".formatted(UUID.randomUUID()), checkFails))));
    }

    private long keyOf(final String scopedProcessId) {
        return clients
                .getFactory("zenbpm")
                .rest()
                .processDefinitions(scopedProcessId, true)
                .getFirst()
                .key();
    }

    private String xmlOf(final ProcessDefinition definition) throws IOException {
        try (InputStream xml = processService.getBpmnXml(definition.id())) {
            return new String(xml.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<String> elementIdsOf(final WorkflowHistory history) {
        return history
                .elementsHistory()
                .stream()
                .map(WorkflowElementHistory::elementId)
                .toList();
    }

    private static WorkflowElementHistory only(
        final WorkflowHistory history,
        final String elementId
    ) {
        final var elements = history
                .elementsHistory()
                .stream()
                .filter(element -> elementId.equals(element.elementId()))
                .toList();
        assertEquals(1, elements.size(), () -> "%s in %s".formatted(elementId, history));
        return elements.getFirst();
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

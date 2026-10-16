/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.model;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pbinitiative.zenbpmadapter.TestWiring;
import org.w3c.dom.Element;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Which input mappings carry what the methods serving a job read into a multi-instance body. The
 * fixture <code>models/task-parameters.bpmn</code> has a plain task, a multi-instance send task,
 * and a service task, a user task and a task without a task definition inside two nested
 * multi-instance sub-processes, the outer of which maps one of the names already.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmTaskParametersTest {

    @Test
    @DisplayName("A multi-instance task maps what its methods and the extensions' read, and the aggregate id, into itself, but not its item")
    void aMultiInstanceTaskMapsTheNamesIntoItself() {
        final var model = read();

        ZenBpmTaskParameters.apply(model, "shop", wiring().wiring());

        assertEquals(List.of("=channel -> channel", "=orderId -> orderId", "=template -> template"), inputsOf(model,
                "NotifyEveryCustomer"));
    }

    @Test
    @DisplayName("A job inside multi-instance sub-processes gets its names mapped into every one of them, a target the modeller maps already left out, and an item only into the sub-processes inside the one which declares it")
    void theNamesOfAJobInsideABodyAreMappedIntoEveryBodyAroundIt() {
        final var model = read();

        ZenBpmTaskParameters.apply(model, "shop", wiring().wiring());

        assertAll(
                () -> assertEquals(List.of("=parcelWeight -> weight", "=address -> address", "=orderId -> orderId",
                        "=itemCode -> itemCode", "=printer -> printer", "=signature -> signature"),
                        inputsOf(model,
                                "EveryParcel")),
                () -> assertEquals(List.of("=itemCode -> itemCode", "=orderId -> orderId", "=parcel -> parcel",
                        "=printer -> printer"), inputsOf(model, "EveryItem")),
                () -> assertEquals(List.of("=parcel.address -> address"), inputsOf(model, "Ship")));
    }

    @Test
    @DisplayName("A job outside every multi-instance body gets nothing, and nothing is asked about it: the engine hands it the variables of its scope")
    void aJobOutsideABodyIsLeftAlone() {
        final var model = read();
        final var wiring = wiring();

        ZenBpmTaskParameters.apply(model, "shop", wiring.wiring());

        assertAll(
                () -> assertEquals(List.of(), inputsOf(model, "Charge")),
                () -> assertTrue(wiring
                        .calls()
                        .stream()
                        .noneMatch(call -> call.contains("Charge") || call.contains("charge")),
                        wiring
                                .calls()
                                .toString()));
    }

    @Test
    @DisplayName("The names are asked with the element id and the task definition, the extensions' with both keys, element id first, and the aggregate id's name once")
    void theNamesAreAskedByBothKeys() {
        final var wiring = wiring();

        ZenBpmTaskParameters.apply(read(), "shop", wiring.wiring());

        assertEquals(List.of(
                "resolveWorkflowAggregateIdName[shop, Orders]",
                "taskParameterNames[shop, Orders, NotifyEveryCustomer]",
                "taskParameterNames[shop, Orders, notify]",
                "extensionTaskParameterNames[shop, Orders, [NotifyEveryCustomer, notify]]",
                "taskParameterNames[shop, Orders, Ship]",
                "taskParameterNames[shop, Orders, ship]",
                "extensionTaskParameterNames[shop, Orders, [Ship, ship]]",
                "taskParameterNames[shop, Orders, Label]",
                "extensionTaskParameterNames[shop, Orders, [Label]]",
                "taskParameterNames[shop, Orders, Sign]",
                "taskParameterNames[shop, Orders, user-task-type]",
                "extensionTaskParameterNames[shop, Orders, [Sign, user-task-type]]"), wiring.calls());
    }

    @Test
    @DisplayName("A model without a multi-instance body is left as it was written, and the platform is asked nothing")
    void aModelWithoutABodyIsLeftAsItWasWritten() {
        final var model = read("version-1.bpmn");
        final var wiring = TestWiring
                .claiming("Orders")
                .withTaskParameters("charge", "amount");
        final var untouched = read("version-1.bpmn");

        ZenBpmTaskParameters.apply(model, "shop", wiring.wiring());

        assertAll(
                () -> assertTrue(untouched
                        .document()
                        .isEqualNode(model.document())),
                () -> assertEquals(List.of(), wiring.calls()));
    }

    private static TestWiring wiring() {
        return TestWiring
                .claiming("Orders")
                .withTaskParameters("charge", "amount")
                .withTaskParameters("NotifyEveryCustomer", "template")
                .withTaskParameters("notify", "customer", "channel")
                .withTaskParameters("ship", "weight", "address", "parcel")
                .withExtensionTaskParameters("Sign", "signature")
                .withTaskParameters("Label", "itemCode", "parcel", "item")
                .withExtensionTaskParameters("Label", "printer");
    }

    private static List<String> inputsOf(
        final ZenBpmBpmnModel model,
        final String elementId
    ) {
        final var inputs = new ArrayList<String>();
        final var element = ZenBpmInputMappings
                .elementsById(model.document())
                .get(elementId);
        for (final var ioMapping : ZenBpmModelReader.extensions(element, "ioMapping")) {
            for (var child = ioMapping.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (child instanceof Element input && "input".equals(input.getLocalName())) {
                    inputs.add("%s -> %s".formatted(input.getAttribute("source"), input.getAttribute("target")));
                }
            }
        }
        return inputs;
    }

    private static ZenBpmBpmnModel read() {
        return read("task-parameters.bpmn");
    }

    private static ZenBpmBpmnModel read(final String fixture) {
        try (InputStream file = ZenBpmTaskParametersTest.class.getResourceAsStream("/models/%s".formatted(fixture))) {
            return ZenBpmModelReader
                    .read("shop", fixture, file)
                    .getFirst();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

}

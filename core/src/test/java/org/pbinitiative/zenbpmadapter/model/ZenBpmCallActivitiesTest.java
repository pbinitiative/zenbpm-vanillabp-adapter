/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.model;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pbinitiative.zenbpmadapter.TestStarts;
import org.pbinitiative.zenbpmadapter.TestWiring;
import org.w3c.dom.Element;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Which call activities start their process without the caller's name: those which call a process
 * of the workflow module which starts a workflow of its own on an aggregate of its own, and whose
 * modeller wrote no <code>in</code>. The process <code>Orders</code> calls one process of each kind.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmCallActivitiesTest {

    private static final String CALLER = """
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                xmlns:zenbpm="http://zenbpm.pbinitiative.org/1.0" id="d">
              <bpmn:process id="Orders" isExecutable="true">
                <bpmn:callActivity id="CallInvoicing">
                  <bpmn:extensionElements>
                    <zenbpm:calledElement processId="Invoicing" />
                  </bpmn:extensionElements>
                </bpmn:callActivity>
                <bpmn:callActivity id="CallBilling">
                  <bpmn:extensionElements>
                    <zenbpm:calledElement processId="Billing" />
                    <zenbpm:ioMapping>
                      <zenbpm:input source="=orderId" target="orderId" />
                    </zenbpm:ioMapping>
                  </bpmn:extensionElements>
                </bpmn:callActivity>
                <bpmn:callActivity id="CallShipping">
                  <bpmn:extensionElements>
                    <zenbpm:calledElement processId="Shipping" />
                  </bpmn:extensionElements>
                </bpmn:callActivity>
                <bpmn:callActivity id="CallElsewhere">
                  <bpmn:extensionElements>
                    <zenbpm:calledElement processId="Elsewhere" />
                  </bpmn:extensionElements>
                </bpmn:callActivity>
                <bpmn:callActivity id="CallAStep">
                  <bpmn:extensionElements>
                    <zenbpm:calledElement processId="Step" />
                  </bpmn:extensionElements>
                </bpmn:callActivity>
                <bpmn:callActivity id="CallWithAKey">
                  <bpmn:extensionElements>
                    <zenbpm:calledElement processId="Invoicing" />
                    <zenbpm:in businessKey="=orderId" />
                  </bpmn:extensionElements>
                </bpmn:callActivity>
                <bpmn:callActivity id="CallNothing" />
              </bpmn:process>
            </bpmn:definitions>
            """;

    @Test
    @DisplayName("A call of a process which starts a workflow of its own on an aggregate of its own gets an empty business key and an empty id variable")
    void aWorkflowOfItsOwnStartsWithoutTheCallersName() {
        final var model = read();

        cut(model);

        assertAll(
                () -> assertEquals(List.of(""), businessKeysOf(model, "CallInvoicing")),
                () -> assertEquals(List.of("=null -> orderId"), inputsOf(model, "CallInvoicing")));
    }

    @Test
    @DisplayName("An id variable the modeller maps already keeps the modeller's mapping, and the business key is emptied all the same")
    void theModellersMappingOfTheIdStays() {
        final var model = read();

        cut(model);

        assertAll(
                () -> assertEquals(List.of(""), businessKeysOf(model, "CallBilling")),
                () -> assertEquals(List.of("=orderId -> orderId"), inputsOf(model, "CallBilling")));
    }

    @Test
    @DisplayName("A call of a process on the caller's aggregate, of a process nobody of the module claims, of a called step, one with an in of the modeller's and one naming no process are left as they are")
    void everyOtherCallIsLeftAlone() {
        final var model = read();

        cut(model);

        assertAll(
                () -> assertEquals(List.of(), businessKeysOf(model, "CallShipping")),
                () -> assertEquals(List.of(), inputsOf(model, "CallShipping")),
                () -> assertEquals(List.of(), businessKeysOf(model, "CallElsewhere")),
                () -> assertEquals(List.of(), inputsOf(model, "CallElsewhere")),
                () -> assertEquals(List.of(), businessKeysOf(model, "CallAStep")),
                () -> assertEquals(List.of(), inputsOf(model, "CallAStep")),
                () -> assertEquals(List.of(), businessKeysOf(model, "CallNothing")),
                () -> assertEquals(List.of(), inputsOf(model, "CallNothing")),
                () -> assertEquals(List.of("=orderId"), businessKeysOf(model, "CallWithAKey")),
                () -> assertEquals(List.of(), inputsOf(model, "CallWithAKey")));
    }

    private static void cut(final ZenBpmBpmnModel model) {
        ZenBpmCallActivities.cutTheCallersName(model, "shop", TestWiring
                .claiming("Orders", "Invoicing", "Billing", "Shipping", "Step")
                .sharingTheAggregateWith("Shipping")
                .wiring(),
                TestStarts
                        .everyProcessStartingAWorkflow()
                        .called("Step")
                        .startInvoker());
    }

    private static List<String> businessKeysOf(
        final ZenBpmBpmnModel model,
        final String elementId
    ) {
        final var element = ZenBpmInputMappings
                .elementsById(model.document())
                .get(elementId);
        return ZenBpmModelReader
                .extensions(element, "in")
                .stream()
                .map(in -> in.getAttribute("businessKey"))
                .toList();
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
        return ZenBpmModelReader
                .read("shop", "calls.bpmn", new ByteArrayInputStream(CALLER.getBytes(StandardCharsets.UTF_8)))
                .getFirst();
    }

}

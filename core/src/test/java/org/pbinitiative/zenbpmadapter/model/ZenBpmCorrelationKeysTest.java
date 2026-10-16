/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.model;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What the adapter writes so that a message correlates by the aggregate id, and the messages it
 * refuses. The fixture <code>models/correlation-keys.bpmn</code> has messages with and without a
 * key of the modeller's, a message only a start event names, and catch elements inside two nested
 * multi-instance sub-processes, one of which the modeller maps the target into already.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmCorrelationKeysTest {

    private static final String ZENBPM = "http://zenbpm.pbinitiative.org/1.0";

    @Test
    @DisplayName("Every message a workflow waits for and which carries no key gets the aggregate id as its key, once; a key of the modeller's and a message only a start event names are left")
    void aMessageWithoutAKeyGetsTheAggregateId() {
        final var model = read("correlation-keys.bpmn");

        ZenBpmCorrelationKeys.apply(model, () -> "orderId");

        final var document = model.document();
        assertAll(
                () -> assertEquals(List.of("=orderId"), keysOf(document, "Message_Paid")),
                () -> assertEquals(List.of("=shipmentId"), keysOf(document, "Message_Shipped")),
                () -> assertEquals(List.of(), keysOf(document, "Message_Ordered")),
                () -> assertEquals(List.of("=orderId"), keysOf(document, "Message_Complaint")),
                () -> assertEquals(List.of("properties", "subscription"), childrenOf(extensionElementsOf(document,
                        "Message_Complaint"))),
                () -> assertEquals(List.of("=orderId"), keysOf(document, "Message_Cancelled")),
                () -> assertEquals(List.of("=orderId"), keysOf(document, "Message_Recalled")),
                () -> assertEquals(ZENBPM, subscriptionOf(document, "Message_Paid").getNamespaceURI()),
                () -> assertEquals("zenbpm", subscriptionOf(document, "Message_Paid").getPrefix()),
                () -> assertEquals(ZENBPM, document
                        .getDocumentElement()
                        .getAttribute("xmlns:zenbpm")));
    }

    @Test
    @DisplayName("Every multi-instance sub-process around a catch element whose message got the key maps the aggregate id into its body, unless the modeller maps that target there already")
    void theAggregateIdIsCarriedIntoMultiInstanceBodies() {
        final var model = read("correlation-keys.bpmn");

        ZenBpmCorrelationKeys.apply(model, () -> "orderId");

        final var document = model.document();
        final var everyParcel = elementOf(document, "EveryParcel");
        assertAll(
                () -> assertEquals(List.of("documentation", "extensionElements", "incoming", "outgoing",
                        "multiInstanceLoopCharacteristics"), childrenOf(everyParcel).subList(0, 5)),
                () -> assertEquals(List.of("input =orderId -> orderId"), mappingsOf(everyParcel)),
                () -> assertEquals(List.of("input =parcel.items -> items", "input =orderId -> orderId",
                        "output =items -> shippedItems"), mappingsOf(elementOf(document, "EveryItem"))),
                () -> assertEquals("http://camunda.org/schema/zeebe/1.0", firstInputOf(elementOf(document,
                        "EveryItem"), 1).getNamespaceURI()),
                () -> assertEquals(List.of("input =order.id -> orderId"), mappingsOf(elementOf(document,
                        "EveryReturn"))));
    }

    @Test
    @DisplayName("A file whose messages all carry a key is left as it is, and the aggregate id is not even asked for")
    void aFileWithEveryKeyModelledIsLeftAsItIs() {
        final var text = """
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:zeebe="http://camunda.org/schema/zeebe/1.0" id="keyed">
                  <bpmn:message id="Message_Paid" name="paid">
                    <bpmn:extensionElements>
                      <zeebe:subscription correlationKey="=orderId" />
                    </bpmn:extensionElements>
                  </bpmn:message>
                  <bpmn:process id="Orders" isExecutable="true">
                    <bpmn:receiveTask id="AwaitPayment" messageRef="Message_Paid" />
                    <bpmn:receiveTask id="AwaitNothing" messageRef="Message_Missing" />
                  </bpmn:process>
                </bpmn:definitions>
                """;
        final var model = readInline(text);
        final var asked = new AtomicInteger();

        ZenBpmCorrelationKeys.apply(model, () -> {
            asked.incrementAndGet();
            return "orderId";
        });

        assertAll(
                () -> assertTrue(readInline(text)
                        .document()
                        .isEqualNode(model.document())),
                () -> assertEquals(0, asked.get()));
    }

    @Test
    @DisplayName("Where the file binds the prefix 'zenbpm' to another namespace, the engine's namespace gets a prefix of its own")
    void theEnginesNamespaceGetsAFreePrefix() {
        final var model = readInline("""
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:zenbpm="urn:something-else" xmlns:zenbpm1="urn:something-else-again" id="prefixed">
                  <bpmn:message id="Message_Paid" name="paid" />
                  <bpmn:process id="Orders" isExecutable="true">
                    <bpmn:receiveTask id="AwaitPayment" messageRef="Message_Paid" />
                  </bpmn:process>
                </bpmn:definitions>
                """);

        ZenBpmCorrelationKeys.apply(model, () -> "orderId");

        final var subscription = subscriptionOf(model.document(), "Message_Paid");
        assertAll(
                () -> assertEquals("zenbpm2", subscription.getPrefix()),
                () -> assertEquals(ZENBPM, subscription.getNamespaceURI()));
    }

    @Test
    @DisplayName("In a file written in the default namespace, the extensionElements added to an element have no prefix either")
    void additionsToAFileInTheDefaultNamespaceHaveNoPrefix() {
        final var model = readInline("""
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" id="unprefixed">
                  <message id="Message_Paid" name="paid" />
                  <process id="Orders" isExecutable="true">
                    <subProcess id="EveryParcel">
                      <multiInstanceLoopCharacteristics>
                        <extensionElements>
                          <loopCharacteristics xmlns="http://zenbpm.pbinitiative.org/1.0" inputCollection="=parcels" />
                        </extensionElements>
                      </multiInstanceLoopCharacteristics>
                      <receiveTask id="AwaitPayment" messageRef="Message_Paid" />
                    </subProcess>
                  </process>
                </definitions>
                """);

        ZenBpmCorrelationKeys.apply(model, () -> "orderId");

        final var everyParcel = elementOf(model.document(), "EveryParcel");
        final var extensionElements = elementsOf(everyParcel).getFirst();
        assertAll(
                () -> assertEquals("extensionElements", extensionElements.getNodeName()),
                () -> assertEquals(ZenBpmModelReader.BPMN, extensionElements.getNamespaceURI()),
                () -> assertEquals(List.of("input =orderId -> orderId"), mappingsOf(everyParcel)));
    }

    @Test
    @DisplayName("A message which starts a workflow of the module and which a claimed workflow waits for is refused, naming both elements, in one file or across files")
    void aMessageWhichStartsAndIsAwaitedIsRefused() {
        final var starting = new LinkedHashMap<String, String>();
        final var awaited = new LinkedHashMap<String, String>();
        ZenBpmCorrelationKeys.refuseMessagesWhichStartAndAreAwaited("shop", starting, awaited, readInline("""
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="waiting">
                  <bpmn:message id="Message_Paid" name="paid" />
                  <bpmn:message id="Message_Unnamed" />
                  <bpmn:process id="Orders" isExecutable="true">
                    <bpmn:receiveTask id="AwaitPayment" messageRef="Message_Paid" />
                    <bpmn:receiveTask id="AwaitAnything" messageRef="Message_Unnamed" />
                  </bpmn:process>
                </bpmn:definitions>
                """), true);

        final var failure = assertThrows(IllegalStateException.class, () -> ZenBpmCorrelationKeys
                .refuseMessagesWhichStartAndAreAwaited("shop", starting, awaited, readInline("""
                        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="starting">
                          <bpmn:message id="Message_Paid" name="paid" />
                          <bpmn:message id="Message_Refunded" name="refunded" />
                          <bpmn:process id="Payments" isExecutable="true">
                            <bpmn:startEvent id="PaymentReceived">
                              <bpmn:messageEventDefinition messageRef="Message_Paid" />
                            </bpmn:startEvent>
                            <bpmn:startEvent id="RefundReceived">
                              <bpmn:messageEventDefinition messageRef="Message_Refunded" />
                            </bpmn:startEvent>
                            <bpmn:intermediateCatchEvent id="Refunded">
                              <bpmn:messageEventDefinition messageRef="Message_Refunded" />
                            </bpmn:intermediateCatchEvent>
                          </bpmn:process>
                        </bpmn:definitions>
                        """), true));

        assertEquals("""
                Workflow module 'shop' uses a message both to start a workflow and as a message a workflow waits for:
                  - 'paid': starts Payments/PaymentReceived, awaited by Orders/AwaitPayment
                  - 'refunded': starts Payments/RefundReceived, awaited by Payments/Refunded
                ZenBPM hands a correlated message which no waiting workflow matches to the start event of the \
                same name, so a correlation which arrives while the workflow does not wait, or after it went \
                on, would start another workflow instead of failing. Give the message which starts a workflow \
                and the message a workflow waits for different names.""", failure.getMessage());
    }

    @Test
    @DisplayName("A message a process nobody claims waits for does not count, the messages it starts with do")
    void theCatchElementsOfAProcessNobodyClaimsDoNotCount() {
        final var starting = new LinkedHashMap<String, String>();
        final var awaited = new LinkedHashMap<String, String>();

        assertDoesNotThrow(() -> ZenBpmCorrelationKeys.refuseMessagesWhichStartAndAreAwaited("shop", starting,
                awaited, readInline("""
                        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="unclaimed">
                          <bpmn:message id="Message_Paid" name="paid" />
                          <bpmn:process id="Partner" isExecutable="true">
                            <bpmn:startEvent id="PaymentReceived">
                              <bpmn:messageEventDefinition messageRef="Message_Paid" />
                            </bpmn:startEvent>
                            <bpmn:receiveTask id="AwaitPayment" messageRef="Message_Paid" />
                          </bpmn:process>
                        </bpmn:definitions>
                        """), false));
        assertAll(
                () -> assertEquals(Map.of("paid", "Partner/PaymentReceived"), starting),
                () -> assertEquals(Map.of(), awaited));
    }

    private static List<String> keysOf(
        final Document document,
        final String messageId
    ) {
        final var keys = new ArrayList<String>();
        for (final var subscription : ZenBpmModelReader.extensions(elementOf(document, messageId), "subscription")) {
            keys.add(subscription.getAttribute("correlationKey"));
        }
        return keys;
    }

    private static Element subscriptionOf(
        final Document document,
        final String messageId
    ) {
        return ZenBpmModelReader
                .extension(elementOf(document, messageId), "subscription")
                .orElseThrow();
    }

    private static Element extensionElementsOf(
        final Document document,
        final String elementId
    ) {
        return (Element) elementOf(document, elementId)
                .getElementsByTagNameNS(ZenBpmModelReader.BPMN, "extensionElements")
                .item(0);
    }

    private static List<String> mappingsOf(final Element element) {
        final var mappings = new ArrayList<String>();
        for (final var ioMapping : ZenBpmModelReader.extensions(element, "ioMapping")) {
            for (final var mapping : elementsOf(ioMapping)) {
                mappings.add("%s %s -> %s".formatted(mapping.getLocalName(), mapping.getAttribute("source"), mapping
                        .getAttribute("target")));
            }
        }
        return mappings;
    }

    private static Element firstInputOf(
        final Element element,
        final int index
    ) {
        return elementsOf(ZenBpmModelReader
                .extension(element, "ioMapping")
                .orElseThrow()).get(index);
    }

    private static List<String> childrenOf(final Element element) {
        return elementsOf(element)
                .stream()
                .map(Element::getLocalName)
                .toList();
    }

    private static List<Element> elementsOf(final Element parent) {
        final var children = new ArrayList<Element>();
        for (var child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element) {
                children.add(element);
            }
        }
        return children;
    }

    private static Element elementOf(
        final Document document,
        final String id
    ) {
        return ZenBpmInputMappings
                .elementsById(document)
                .get(id);
    }

    private static ZenBpmBpmnModel readInline(final String text) {
        return ZenBpmModelReader
                .read("shop", "inline.bpmn", new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)))
                .getFirst();
    }

    private static ZenBpmBpmnModel read(final String fixture) {
        try (InputStream file = ZenBpmCorrelationKeysTest.class.getResourceAsStream("/models/%s".formatted(
                fixture))) {
            return ZenBpmModelReader
                    .read("shop", fixture, file)
                    .getFirst();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

}

/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.model;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.w3c.dom.Element;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Where the start markers go and how they are drawn. The fixture <code>models/markers.bpmn</code>
 * has a plain, a message and a timer start event leading to one task, an embedded sub-process and
 * an event sub-process with start events of their own, and a plain, a terminate and an error end
 * event, all drawn.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmMarkerTasksTest {

    private static final String JOB_TYPE = "vanillabp__started__shop__Orders";

    @Test
    @DisplayName("A marker follows every start event of the process itself, plain, message and timer alike, and takes over the flow which left it")
    void aMarkerFollowsEveryStartEventOfTheProcessItself() {
        final var model = read();

        ZenBpmMarkerTasks.insertStartMarkers(model, JOB_TYPE);

        final var elements = ZenBpmInputMappings.elementsById(model.document());
        assertAll(List
                .of("Plain", "ByMessage", "ByTimer")
                .stream()
                .map(startEvent -> (Executable) () -> {
                    final var markerId = "vanillabp__started__%s".formatted(startEvent);
                    final var flowId = "%s__flow".formatted(markerId);
                    final var marker = elements.get(markerId);
                    final var leaving = elements.get("Flow_%s".formatted(startEvent));
                    final var newFlow = elements.get(flowId);
                    assertAll(
                            () -> assertEquals("serviceTask", marker.getLocalName()),
                            () -> assertEquals("Orders", ((Element) marker.getParentNode()).getAttribute("id")),
                            () -> assertEquals("VanillaBP: workflow started", marker.getAttribute("name")),
                            () -> assertEquals(Optional.of(JOB_TYPE), ZenBpmModelReader
                                    .extension(marker, "taskDefinition")
                                    .map(definition -> definition.getAttribute("type"))),
                            () -> assertEquals(List.of(flowId), references(elements.get(startEvent), "outgoing")),
                            () -> assertEquals(List.of(flowId), references(marker, "incoming")),
                            () -> assertEquals(List.of("Flow_%s".formatted(startEvent)), references(marker,
                                    "outgoing")),
                            () -> assertEquals(startEvent, newFlow.getAttribute("sourceRef")),
                            () -> assertEquals(markerId, newFlow.getAttribute("targetRef")),
                            () -> assertEquals(markerId, leaving.getAttribute("sourceRef")),
                            () -> assertEquals("Weigh", leaving.getAttribute("targetRef")));
                }));
    }

    @Test
    @DisplayName("A message and a timer start event keep their definition behind the new outgoing flow")
    void anEventKeepsItsDefinition() {
        final var model = read();

        ZenBpmMarkerTasks.insertStartMarkers(model, JOB_TYPE);

        final var elements = ZenBpmInputMappings.elementsById(model.document());
        assertAll(
                () -> assertEquals(List.of("outgoing", "messageEventDefinition"), children(elements.get(
                        "ByMessage"))),
                () -> assertEquals(List.of("outgoing", "timerEventDefinition"), children(elements.get("ByTimer"))));
    }

    @Test
    @DisplayName("The start events of an embedded and of an event sub-process get no marker, and no end event gets one either")
    void nothingButTheStartsOfTheProcessItselfGetsAMarker() {
        final var model = read();

        ZenBpmMarkerTasks.insertStartMarkers(model, JOB_TYPE);

        assertEquals(List.of("vanillabp__started__Plain", "vanillabp__started__Plain__flow",
                "vanillabp__started__ByMessage", "vanillabp__started__ByMessage__flow", "vanillabp__started__ByTimer",
                "vanillabp__started__ByTimer__flow"), idsWritten(model));
    }

    @Test
    @DisplayName("Every marker is drawn 80 points right of its start event, the new flow from the event to the marker, and the edge which left the event leaves the marker")
    void everyMarkerIsDrawn() {
        final var model = read();

        ZenBpmMarkerTasks.insertStartMarkers(model, JOB_TYPE);

        assertAll(
                () -> assertEquals(List.of("240", "78", "60", "40"), boundsOf(model, "vanillabp__started__Plain")),
                () -> assertEquals(List.of("240", "164", "60", "40"), boundsOf(model,
                        "vanillabp__started__ByMessage")),
                () -> assertEquals(List.of("196,98", "240,98"), waypointsOf(model, "vanillabp__started__Plain__flow")),
                () -> assertEquals(List.of("300,98", "226,98", "226,120", "256,120"), waypointsOf(model,
                        "Flow_Plain")));
    }

    @Test
    @DisplayName("A model which carries its markers already is left as it is")
    void aSecondInsertionWritesNothing() {
        final var model = read();
        ZenBpmMarkerTasks.insertStartMarkers(model, JOB_TYPE);
        final var once = model
                .document()
                .cloneNode(true);

        ZenBpmMarkerTasks.insertStartMarkers(model, JOB_TYPE);

        assertTrue(once.isEqualNode(model.document()));
    }

    @Test
    @DisplayName("A model without a diagram gets its marker and nothing drawn, and a flow which names its start event only by sourceRef is taken over too")
    void aModelWithoutADiagramGetsItsMarkerUndrawn() {
        final var model = inline("""
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="d">
                  <bpmn:process id="Orders" isExecutable="true">
                    <bpmn:startEvent id="Start" />
                    <bpmn:endEvent id="End" />
                    <bpmn:sequenceFlow id="Flow" sourceRef="Start" targetRef="End" />
                  </bpmn:process>
                </bpmn:definitions>
                """);

        ZenBpmMarkerTasks.insertStartMarkers(model, JOB_TYPE);

        final var elements = ZenBpmInputMappings.elementsById(model.document());
        assertAll(
                () -> assertEquals("vanillabp__started__Start", elements
                        .get("Flow")
                        .getAttribute("sourceRef")),
                () -> assertEquals(List.of(), references(elements.get("vanillabp__started__Start"), "outgoing")),
                () -> assertEquals(0, model
                        .document()
                        .getElementsByTagNameNS("http://www.omg.org/spec/BPMN/20100524/DI", "BPMNShape")
                        .getLength()));
    }

    @Test
    @DisplayName("A start event whose shape has no bounds gets an undrawn marker, and bounds without a coordinate count it as 0")
    void aShapeWithoutBoundsDrawsNothing() {
        final var model = inline(
                """
                        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:di="urn:something-else"
                            xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" xmlns:dc="http://www.omg.org/spec/DD/20100524/DC" id="d">
                          <bpmn:process id="Orders" isExecutable="true">
                            <bpmn:startEvent id="Unbounded" />
                            <bpmn:startEvent id="Unplaced" />
                          </bpmn:process>
                          <bpmndi:BPMNDiagram id="Diagram">
                            <bpmndi:BPMNPlane id="Plane" bpmnElement="Orders">
                              <bpmndi:BPMNShape id="Unbounded_di" bpmnElement="Unbounded" />
                              <bpmndi:BPMNShape id="Unplaced_di" bpmnElement="Unplaced">
                                <dc:Bounds width="36" height="36" />
                              </bpmndi:BPMNShape>
                            </bpmndi:BPMNPlane>
                          </bpmndi:BPMNDiagram>
                        </bpmn:definitions>
                        """);

        ZenBpmMarkerTasks.insertStartMarkers(model, JOB_TYPE);

        assertAll(
                () -> assertEquals(List.of(), boundsOf(model, "vanillabp__started__Unbounded")),
                () -> assertEquals(List.of("80", "-2", "60", "40"), boundsOf(model, "vanillabp__started__Unplaced")),
                // the file binds no prefix to the namespace of the waypoints, and 'di' to another
                // namespace, so the waypoints get a prefix of their own
                () -> assertEquals(List.of("36,18", "80,18"), waypointsOf(model,
                        "vanillabp__started__Unplaced__flow")),
                () -> assertEquals("http://www.omg.org/spec/DD/20100524/DI", model
                        .document()
                        .getDocumentElement()
                        .getAttribute("xmlns:di1")));
    }

    @Test
    @DisplayName("A model which writes BPMN without a prefix gets its marker and the new flow without one")
    void aModelWithoutAPrefixGetsItsMarkerWithoutOne() {
        final var model = inline("""
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" id="d">
                  <process id="Orders" isExecutable="true">
                    <startEvent id="Start">
                      <outgoing>Flow</outgoing>
                    </startEvent>
                    <endEvent id="End">
                      <incoming>Flow</incoming>
                    </endEvent>
                    <sequenceFlow id="Flow" sourceRef="Start" targetRef="End" />
                  </process>
                </definitions>
                """);

        ZenBpmMarkerTasks.insertStartMarkers(model, JOB_TYPE);

        final var elements = ZenBpmInputMappings.elementsById(model.document());
        assertAll(
                () -> assertEquals("serviceTask", elements
                        .get("vanillabp__started__Start")
                        .getTagName()),
                () -> assertEquals("sequenceFlow", elements
                        .get("vanillabp__started__Start__flow")
                        .getTagName()),
                () -> assertEquals(List.of("Flow"), references(elements.get("vanillabp__started__Start"),
                        "outgoing")));
    }

    @Test
    @DisplayName("The job type of the start markers carries the process as the engine knows it, and the start event is read back from a marker's id")
    void theJobTypeAndTheStartEventOfAMarker() {
        assertAll(
                () -> assertEquals("vanillabp__started__shop__Orders", ZenBpmMarkerTasks.startMarkerJobType(
                        "shop__Orders")),
                () -> assertEquals(Optional.of("Plain"), ZenBpmMarkerTasks.startEventOf("vanillabp__started__Plain")),
                () -> assertEquals(Optional.empty(), ZenBpmMarkerTasks.startEventOf("vanillabp__started__")),
                () -> assertEquals(Optional.empty(), ZenBpmMarkerTasks.startEventOf("Weigh")));
    }

    @Test
    @DisplayName("A rewritten model is no longer the file as it was written, so its document is what reaches the engine")
    void aRewrittenModelIsWrittenOut() {
        final var model = read();

        ZenBpmMarkerTasks.insertStartMarkers(model, JOB_TYPE);

        final var written = new String(ZenBpmModelWriter.bytesOf(model), StandardCharsets.UTF_8);
        assertAll(
                () -> assertFalse(ZenBpmModelReader.isAsWritten(model)),
                () -> assertTrue(written.contains("id=\"vanillabp__started__Plain\""), written));
    }

    private static List<String> references(
        final Element element,
        final String localName
    ) {
        final var found = new ArrayList<String>();
        for (var child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element reference && localName.equals(reference.getLocalName())) {
                found.add(reference.getTextContent());
            }
        }
        return found;
    }

    private static List<String> children(final Element element) {
        final var found = new ArrayList<String>();
        for (var child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element named) {
                found.add(named.getLocalName());
            }
        }
        return found;
    }

    private static List<String> idsWritten(final ZenBpmBpmnModel model) {
        final var written = new ArrayList<String>();
        final var elements = model
                .document()
                .getElementsByTagNameNS(ZenBpmModelReader.BPMN, "*");
        for (var index = 0; index < elements.getLength(); ++index) {
            final var id = ((Element) elements.item(index)).getAttribute("id");
            if (id.startsWith(ZenBpmMarkerTasks.RESERVED_PREFIX)) {
                written.add(id);
            }
        }
        return written;
    }

    private static List<String> boundsOf(
        final ZenBpmBpmnModel model,
        final String bpmnElement
    ) {
        final var shapes = model
                .document()
                .getElementsByTagNameNS("http://www.omg.org/spec/BPMN/20100524/DI", "BPMNShape");
        for (var index = 0; index < shapes.getLength(); ++index) {
            final var shape = (Element) shapes.item(index);
            if (bpmnElement.equals(shape.getAttribute("bpmnElement"))) {
                final var bounds = (Element) shape
                        .getElementsByTagNameNS("http://www.omg.org/spec/DD/20100524/DC", "Bounds")
                        .item(0);
                return List.of(bounds.getAttribute("x"), bounds.getAttribute("y"), bounds.getAttribute("width"),
                        bounds.getAttribute("height"));
            }
        }
        return List.of();
    }

    private static List<String> waypointsOf(
        final ZenBpmBpmnModel model,
        final String bpmnElement
    ) {
        final var edges = model
                .document()
                .getElementsByTagNameNS("http://www.omg.org/spec/BPMN/20100524/DI", "BPMNEdge");
        for (var index = 0; index < edges.getLength(); ++index) {
            final var edge = (Element) edges.item(index);
            if (bpmnElement.equals(edge.getAttribute("bpmnElement"))) {
                final var waypoints = new ArrayList<String>();
                final var found = edge.getElementsByTagNameNS("http://www.omg.org/spec/DD/20100524/DI", "waypoint");
                for (var waypoint = 0; waypoint < found.getLength(); ++waypoint) {
                    final var point = (Element) found.item(waypoint);
                    waypoints.add("%s,%s".formatted(point.getAttribute("x"), point.getAttribute("y")));
                }
                return waypoints;
            }
        }
        return List.of();
    }

    private static ZenBpmBpmnModel inline(final String bpmn) {
        return ZenBpmModelReader
                .read("shop", "inline.bpmn", new ByteArrayInputStream(bpmn.getBytes(StandardCharsets.UTF_8)))
                .getFirst();
    }

    private static ZenBpmBpmnModel read() {
        try (InputStream file = ZenBpmMarkerTasksTest.class.getResourceAsStream("/models/markers.bpmn")) {
            return ZenBpmModelReader
                    .read("shop", "markers.bpmn", file)
                    .getFirst();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

}

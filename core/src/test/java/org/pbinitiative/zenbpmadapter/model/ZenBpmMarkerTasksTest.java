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
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.w3c.dom.Element;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Where the start and end markers go and how they are drawn. The fixture
 * <code>models/markers.bpmn</code> has a plain, a message and a timer start event leading to one
 * task, an embedded sub-process and an event sub-process with start and end events of their own,
 * and a plain, a terminate and an error end event, all drawn; its event sub-process starts by a
 * message, so nothing in the process catches the error.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmMarkerTasksTest {

    private static final String JOB_TYPE = "vanillabp__started__shop__Orders";

    private static final String END_JOB_TYPE = "vanillabp__ended__shop__Orders";

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
    @DisplayName("An end marker stands in front of every end event of the process itself, plain and terminate alike, and takes over the flow which led to it; each end event is told by how much of the workflow it ends")
    void anEndMarkerStandsInFrontOfEveryEndEventOfTheProcessItself() {
        final var model = read();

        final var endMarkers = ZenBpmMarkerTasks.insertEndMarkers(model, END_JOB_TYPE);

        final var elements = ZenBpmInputMappings.elementsById(model.document());
        assertAll(Stream
                .concat(Stream.of((Executable) () -> assertEquals(Map.of("Shipped", ZenBpmMarkerTasks.Ending.BRANCH,
                        "Rejected", ZenBpmMarkerTasks.Ending.WORKFLOW), endMarkers)), Stream
                                .of("Shipped", "Rejected")
                                .map(endEvent -> (Executable) () -> {
                                    final var markerId = "vanillabp__ended__%s".formatted(endEvent);
                                    final var flowId = "%s__flow".formatted(markerId);
                                    final var marker = elements.get(markerId);
                                    final var arriving = elements.get("Flow_To%s".formatted(endEvent));
                                    final var newFlow = elements.get(flowId);
                                    assertAll(
                                            () -> assertEquals("serviceTask", marker.getLocalName()),
                                            () -> assertEquals("Orders", ((Element) marker.getParentNode())
                                                    .getAttribute("id")),
                                            () -> assertEquals("VanillaBP: workflow ended", marker.getAttribute(
                                                    "name")),
                                            () -> assertEquals(Optional.of(END_JOB_TYPE), ZenBpmModelReader
                                                    .extension(marker, "taskDefinition")
                                                    .map(definition -> definition.getAttribute("type"))),
                                            () -> assertEquals(List.of(flowId), references(elements.get(endEvent),
                                                    "incoming")),
                                            () -> assertEquals(List.of("Flow_To%s".formatted(endEvent)), references(
                                                    marker, "incoming")),
                                            () -> assertEquals(List.of(flowId), references(marker, "outgoing")),
                                            () -> assertEquals(markerId, newFlow.getAttribute("sourceRef")),
                                            () -> assertEquals(endEvent, newFlow.getAttribute("targetRef")),
                                            () -> assertEquals("Decide", arriving.getAttribute("sourceRef")),
                                            () -> assertEquals(markerId, arriving.getAttribute("targetRef")));
                                })));
    }

    @Test
    @DisplayName("A terminate end event keeps its definition behind the new incoming flow")
    void anEndEventKeepsItsDefinition() {
        final var model = read();

        ZenBpmMarkerTasks.insertEndMarkers(model, END_JOB_TYPE);

        assertEquals(List.of("incoming", "terminateEventDefinition"), children(ZenBpmInputMappings
                .elementsById(model.document())
                .get("Rejected")));
    }

    @Test
    @DisplayName("An error end event gets no end marker, because nothing ends there: the engine raises an incident where nothing catches the error, and an error event sub-process goes on where one catches it; nor do the end events of an embedded and of an event sub-process, nor a start event")
    void nothingButThePlainAndTerminateEndsOfTheProcessItselfGetAnEndMarker() {
        final var model = read();

        ZenBpmMarkerTasks.insertEndMarkers(model, END_JOB_TYPE);

        assertAll(
                () -> assertEquals(List.of("vanillabp__ended__Shipped", "vanillabp__ended__Shipped__flow",
                        "vanillabp__ended__Rejected", "vanillabp__ended__Rejected__flow"), idsWritten(model)),
                () -> assertEquals(List.of("Flow_ToLost"), references(ZenBpmInputMappings
                        .elementsById(model.document())
                        .get("Lost"), "incoming")));
    }

    @Test
    @DisplayName("A message end event gets its end marker in front and keeps its message and its task definition, so its own job is created only once the marker's job was completed")
    void aMessageEndEventGetsItsMarkerInFront() {
        final var model = inline("""
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:zenbpm="http://zenbpm.pbinitiative.org/1.0" id="d">
                  <bpmn:message id="Message_Sent" name="sent" />
                  <bpmn:process id="Orders" isExecutable="true">
                    <bpmn:startEvent id="Start" />
                    <bpmn:endEvent id="Sent">
                      <bpmn:extensionElements><zenbpm:taskDefinition type="send" /></bpmn:extensionElements>
                      <bpmn:messageEventDefinition messageRef="Message_Sent" />
                    </bpmn:endEvent>
                    <bpmn:sequenceFlow id="Flow" sourceRef="Start" targetRef="Sent" />
                  </bpmn:process>
                </bpmn:definitions>
                """);

        final var endMarkers = ZenBpmMarkerTasks.insertEndMarkers(model, END_JOB_TYPE);

        final var elements = ZenBpmInputMappings.elementsById(model.document());
        assertAll(
                () -> assertEquals(Map.of("Sent", ZenBpmMarkerTasks.Ending.BRANCH), endMarkers),
                () -> assertEquals("vanillabp__ended__Sent", elements
                        .get("Flow")
                        .getAttribute("targetRef")),
                () -> assertEquals(List.of("extensionElements", "incoming", "messageEventDefinition"), children(
                        elements.get("Sent"))));
    }

    @Test
    @DisplayName("Every end marker is drawn with its right edge 80 points left of the right edge of its end event, the new flow from the marker to the event, and the edge which led to the event leads to the marker")
    void everyEndMarkerIsDrawn() {
        final var model = read();

        ZenBpmMarkerTasks.insertEndMarkers(model, END_JOB_TYPE);

        assertAll(
                () -> assertEquals(List.of("682", "78", "60", "40"), boundsOf(model, "vanillabp__ended__Shipped")),
                () -> assertEquals(List.of("682", "164", "60", "40"), boundsOf(model, "vanillabp__ended__Rejected")),
                () -> assertEquals(List.of("742,98", "786,98"), waypointsOf(model, "vanillabp__ended__Shipped__flow")),
                () -> assertEquals(List.of("726,105", "756,105", "756,98", "682,98"), waypointsOf(model,
                        "Flow_ToShipped")));
    }

    @Test
    @DisplayName("A model without a diagram gets its end marker and nothing drawn, and a flow which names its end event only by targetRef is taken over too")
    void aModelWithoutADiagramGetsItsEndMarkerUndrawn() {
        final var model = inline("""
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="d">
                  <bpmn:process id="Orders" isExecutable="true">
                    <bpmn:startEvent id="Start" />
                    <bpmn:endEvent id="End" />
                    <bpmn:sequenceFlow id="Flow" sourceRef="Start" targetRef="End" />
                  </bpmn:process>
                </bpmn:definitions>
                """);

        ZenBpmMarkerTasks.insertEndMarkers(model, END_JOB_TYPE);

        final var elements = ZenBpmInputMappings.elementsById(model.document());
        assertAll(
                () -> assertEquals("vanillabp__ended__End", elements
                        .get("Flow")
                        .getAttribute("targetRef")),
                () -> assertEquals(List.of("Flow"), references(elements.get("vanillabp__ended__End"), "incoming")),
                () -> assertEquals(List.of("vanillabp__ended__End__flow"), references(elements.get("End"),
                        "incoming")),
                () -> assertEquals(0, model
                        .document()
                        .getElementsByTagNameNS("http://www.omg.org/spec/BPMN/20100524/DI", "BPMNShape")
                        .getLength()));
    }

    @Test
    @DisplayName("An end event whose shape has no bounds gets an undrawn end marker")
    void anEndEventWithoutBoundsDrawsNothing() {
        final var model = inline("""
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" id="d">
                  <bpmn:process id="Orders" isExecutable="true">
                    <bpmn:endEvent id="Unbounded" />
                  </bpmn:process>
                  <bpmndi:BPMNDiagram id="Diagram">
                    <bpmndi:BPMNPlane id="Plane" bpmnElement="Orders">
                      <bpmndi:BPMNShape id="Unbounded_di" bpmnElement="Unbounded" />
                    </bpmndi:BPMNPlane>
                  </bpmndi:BPMNDiagram>
                </bpmn:definitions>
                """);

        ZenBpmMarkerTasks.insertEndMarkers(model, END_JOB_TYPE);

        assertAll(
                () -> assertTrue(ZenBpmInputMappings
                        .elementsById(model.document())
                        .containsKey("vanillabp__ended__Unbounded")),
                () -> assertEquals(List.of(), boundsOf(model, "vanillabp__ended__Unbounded")),
                () -> assertEquals(List.of(), waypointsOf(model, "vanillabp__ended__Unbounded__flow")));
    }

    @Test
    @DisplayName("An edge which leads to the end event without a waypoint is left as it is, and the end marker is drawn all the same")
    void anEdgeWithoutWaypointsIsLeftAsItIs() {
        final var model = inline(
                """
                        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                            xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
                            id="d">
                          <bpmn:process id="Orders" isExecutable="true">
                            <bpmn:startEvent id="Start"><bpmn:outgoing>Flow</bpmn:outgoing></bpmn:startEvent>
                            <bpmn:endEvent id="End"><bpmn:incoming>Flow</bpmn:incoming></bpmn:endEvent>
                            <bpmn:sequenceFlow id="Flow" sourceRef="Start" targetRef="End" />
                          </bpmn:process>
                          <bpmndi:BPMNDiagram id="Diagram">
                            <bpmndi:BPMNPlane id="Plane" bpmnElement="Orders">
                              <bpmndi:BPMNShape id="End_di" bpmnElement="End">
                                <dc:Bounds x="300" y="80" width="36" height="36" />
                              </bpmndi:BPMNShape>
                              <bpmndi:BPMNEdge id="Flow_di" bpmnElement="Flow" />
                            </bpmndi:BPMNPlane>
                          </bpmndi:BPMNDiagram>
                        </bpmn:definitions>
                        """);

        ZenBpmMarkerTasks.insertEndMarkers(model, END_JOB_TYPE);

        assertAll(
                () -> assertEquals(List.of("196", "78", "60", "40"), boundsOf(model, "vanillabp__ended__End")),
                () -> assertEquals(List.of("256,98", "300,98"), waypointsOf(model, "vanillabp__ended__End__flow")),
                () -> assertEquals(List.of(), waypointsOf(model, "Flow")));
    }

    @Test
    @DisplayName("A model which carries its end markers already is left as it is, and they are read back from it as from a model the engine holds")
    void aSecondInsertionOfTheEndMarkersWritesNothing() {
        final var model = read();
        ZenBpmMarkerTasks.insertEndMarkers(model, END_JOB_TYPE);
        final var once = model
                .document()
                .cloneNode(true);
        final var held = ZenBpmModelReader
                .read("shop", "held.bpmn", new ByteArrayInputStream(ZenBpmModelWriter.bytesOf(model)))
                .getFirst();

        final var again = ZenBpmMarkerTasks.insertEndMarkers(model, END_JOB_TYPE);

        assertAll(
                () -> assertTrue(once.isEqualNode(model.document())),
                () -> assertEquals(again, ZenBpmMarkerTasks.endMarkersOf(held)),
                () -> assertEquals(Map.of(), ZenBpmMarkerTasks.endMarkersOf(read())));
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
    @DisplayName("The job types of the markers carry the process as the engine knows it, and the start and the end event are read back from a marker's id")
    void theJobTypeAndTheStartEventOfAMarker() {
        assertAll(
                () -> assertEquals("vanillabp__ended__shop__Orders", ZenBpmMarkerTasks.endMarkerJobType(
                        "shop__Orders")),
                () -> assertEquals(Optional.of("Shipped"), ZenBpmMarkerTasks.endEventOf("vanillabp__ended__Shipped")),
                () -> assertEquals(Optional.empty(), ZenBpmMarkerTasks.endEventOf("vanillabp__ended__")),
                () -> assertEquals(Optional.empty(), ZenBpmMarkerTasks.endEventOf("vanillabp__started__Plain")),
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

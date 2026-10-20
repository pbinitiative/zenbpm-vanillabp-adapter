/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.xml.XMLConstants;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Writes the marker tasks into the model of a process a workflow service claims: a service task
 * the engine hands out as a job, through which the adapter hears that a workflow started or ended.
 * The engine has no execution listeners, so a marker is the only point at which the adapter can act
 * before the workflow goes on or ends, see decision 26 in the repository's DECISIONS.md.
 * <p>
 * A start marker follows every start event of the process itself, none, message and timer alike,
 * and takes over the flows which leave it, so nothing of the workflow runs before its job is
 * completed. An end marker goes in front of every end event of the process itself and takes over
 * the flows which lead to it, so the end event is reached only once its job is completed; the end
 * event keeps its definition. An error end event gets none: where nothing catches the error the
 * engine raises an incident there and the workflow does not end, and where an error event
 * sub-process catches it the workflow goes on there, see decision 52 in the repository's
 * DECISIONS.md. The start and end events of embedded and event sub-processes get none: the engine
 * runs those as instances of their own, and a marker there would report such an instance as the
 * workflow.
 * <p>
 * Every marker is drawn: it gets a shape of 60 by 40 points with its middle on its event's middle.
 * A start marker's left edge is 80 points right of the left edge of its start event, an end
 * marker's right edge 80 points left of the right edge of its end event, and the new flow gets an
 * edge between the two; an edge which left the start event now leaves the marker, and one which led
 * to the end event now leads to the marker. The rest of the diagram is not moved, so a marker may
 * cover an element the modeller put closer than that. A model without a shape for the event gets
 * no shape for its marker either.
 * <p>
 * The ids of what is written start with {@value #RESERVED_PREFIX}, and an element of such an id is
 * never written twice, so a model which carries its markers already is left as it is. Why the
 * markers are written while wiring, under a job type which names the process, and why they are drawn
 * this way, is decision 44 in the repository's DECISIONS.md.
 */
public final class ZenBpmMarkerTasks {

    /**
     * The start of the id of every element the adapter writes into a model.
     */
    public static final String RESERVED_PREFIX = "vanillabp__";

    /**
     * The name a start marker goes by where the configuration resolves a value of a task for it,
     * such as its <code>job-timeout</code>, and in the messages about its job.
     */
    public static final String START_MARKER_TASK = RESERVED_PREFIX
            + "started";

    /**
     * The start of the id of a start marker, which the id of its start event follows.
     */
    public static final String STARTED = RESERVED_PREFIX
            + "started__";

    /**
     * The name an end marker goes by where the configuration resolves a value of a task for it, such
     * as its <code>job-timeout</code>, and in the messages about its job.
     */
    public static final String END_MARKER_TASK = RESERVED_PREFIX
            + "ended";

    /**
     * The start of the id of an end marker, which the id of its end event follows.
     */
    public static final String ENDED = RESERVED_PREFIX
            + "ended__";

    private static final String BPMNDI = "http://www.omg.org/spec/BPMN/20100524/DI";

    private static final String DC = "http://www.omg.org/spec/DD/20100524/DC";

    private static final String DI = "http://www.omg.org/spec/DD/20100524/DI";

    private static final double MARKER_WIDTH = 60;

    private static final double MARKER_HEIGHT = 40;

    private static final double MARKER_OFFSET = 80;

    private ZenBpmMarkerTasks() {
    }

    /**
     * The job type of the start markers of a process: one per process, and the same whatever the
     * name-clash-avoidance mode, because the id of the process is part of it, so no other process
     * on the engine shares it.
     *
     * @param scopedBpmnProcessId The id under which the engine holds the process
     * @return The job type
     */
    public static String startMarkerJobType(final String scopedBpmnProcessId) {
        return STARTED + scopedBpmnProcessId;
    }

    /**
     * The id of the start event a start marker follows.
     *
     * @param elementId The id of an element of the model
     * @return The id of the start event, empty where the element is no start marker
     */
    public static Optional<String> startEventOf(final String elementId) {
        if (!elementId.startsWith(STARTED) || (elementId.length() == STARTED.length())) {
            return Optional.empty();
        }
        return Optional.of(elementId.substring(STARTED.length()));
    }

    /**
     * The job type of the end markers of a process, built like that of its start markers, see
     * {@link #startMarkerJobType(String)}.
     *
     * @param scopedBpmnProcessId The id under which the engine holds the process
     * @return The job type
     */
    public static String endMarkerJobType(final String scopedBpmnProcessId) {
        return ENDED + scopedBpmnProcessId;
    }

    /**
     * The id of the end event an end marker stands in front of.
     *
     * @param elementId The id of an element of the model
     * @return The id of the end event, empty where the element is no end marker
     */
    public static Optional<String> endEventOf(final String elementId) {
        if (!elementId.startsWith(ENDED) || (elementId.length() == ENDED.length())) {
            return Optional.empty();
        }
        return Optional.of(elementId.substring(ENDED.length()));
    }

    /**
     * Writes a start marker behind every start event of the process itself.
     *
     * @param model The model of the process's file
     * @param jobType The job type of the markers, see {@link #startMarkerJobType(String)}
     */
    public static void insertStartMarkers(
        final ZenBpmBpmnModel model,
        final String jobType
    ) {
        final var elements = ZenBpmInputMappings.elementsById(model.document());
        for (final var startEvent : model.startEvents()) {
            final var markerId = STARTED + startEvent.elementId();
            if (elements.containsKey(markerId)) {
                continue;
            }
            insertBehind(elements.get(startEvent.elementId()), markerId, "VanillaBP: workflow started", jobType);
        }
    }

    /**
     * Writes an end marker in front of every end event of the process itself which is no error end
     * event.
     *
     * @param model The model of the process's file
     * @param jobType The job type of the markers, see {@link #endMarkerJobType(String)}
     * @return The end events with a marker in front, see {@link #endMarkersOf(ZenBpmBpmnModel)}
     */
    public static Map<String, Ending> insertEndMarkers(
        final ZenBpmBpmnModel model,
        final String jobType
    ) {
        final var elements = ZenBpmInputMappings.elementsById(model.document());
        for (final var endEventId : model.endEventIds()) {
            final var endEvent = elements.get(endEventId);
            final var markerId = ENDED + endEventId;
            if (elements.containsKey(markerId) || !bpmnChildren(endEvent, "errorEventDefinition").isEmpty()) {
                continue;
            }
            insertInFront(endEvent, markerId, jobType);
        }
        return endMarkersOf(model);
    }

    /**
     * The end events of the process itself which have an end marker in front, written by this boot
     * or by the version of the application which deployed the model, each with how much of the
     * workflow it ends.
     *
     * @param model The model of the process's file
     * @return The end events by id, in the order of the model
     */
    public static Map<String, Ending> endMarkersOf(final ZenBpmBpmnModel model) {
        final var elements = ZenBpmInputMappings.elementsById(model.document());
        final var endMarkers = new LinkedHashMap<String, Ending>();
        for (final var endEventId : model.endEventIds()) {
            if (elements.containsKey(ENDED + endEventId)) {
                endMarkers.put(endEventId, bpmnChildren(elements.get(endEventId), "terminateEventDefinition")
                        .isEmpty() ? Ending.BRANCH : Ending.WORKFLOW);
            }
        }
        return endMarkers;
    }

    // the marker takes over the flows leading to the event, those the engine follows (the targets of
    // the outgoing references of their sources) and those named by the event's incoming references,
    // and one new flow leads from it to the event
    private static void insertInFront(
        final Element event,
        final String markerId,
        final String jobType
    ) {
        final var document = event.getOwnerDocument();
        final var container = (Element) event.getParentNode();
        final var eventId = event.getAttribute("id");
        final var flowId = "%s__flow".formatted(markerId);
        final var marker = bpmnElement(event, "serviceTask");
        marker.setAttribute("id", markerId);
        marker.setAttribute("name", "VanillaBP: workflow ended");
        ZenBpmInputMappings
                .addExtension(marker, "taskDefinition")
                .setAttribute("type", jobType);
        final var arrivingFlows = new LinkedHashSet<String>();
        for (final var incoming : bpmnChildren(event, "incoming")) {
            arrivingFlows.add(incoming
                    .getTextContent()
                    .trim());
            marker.appendChild(incoming);
        }
        for (final var flow : bpmnChildren(container, "sequenceFlow")) {
            if (eventId.equals(flow.getAttribute("targetRef"))) {
                flow.setAttribute("targetRef", markerId);
                if (arrivingFlows.add(flow.getAttribute("id"))) {
                    marker.appendChild(reference(event, "incoming", flow.getAttribute("id")));
                }
            }
        }
        marker.appendChild(reference(event, "outgoing", flowId));
        event.insertBefore(reference(event, "incoming", flowId), firstChildBehindTheReferences(event));
        final var flow = bpmnElement(event, "sequenceFlow");
        flow.setAttribute("id", flowId);
        flow.setAttribute("sourceRef", markerId);
        flow.setAttribute("targetRef", eventId);
        container.insertBefore(marker, event);
        container.insertBefore(flow, event.getNextSibling());
        drawInFront(document, eventId, markerId, flowId, arrivingFlows);
    }

    private static void drawInFront(
        final Document document,
        final String eventId,
        final String markerId,
        final String flowId,
        final Set<String> arrivingFlows
    ) {
        final var eventShape = diagramElementOf(document, "BPMNShape", eventId);
        final var eventBounds = eventShape.flatMap(shape -> firstChild(shape, DC, "Bounds"));
        if (eventBounds.isEmpty()) {
            return;
        }
        final var bounds = eventBounds.get();
        final var middle = number(bounds, "y") + (number(bounds, "height") / 2);
        final var eventX = number(bounds, "x");
        final var markerX = (eventX + number(bounds, "width")) - MARKER_OFFSET - MARKER_WIDTH;
        final var plane = shapeOf(document, eventShape.get(), bounds, markerId, markerX, middle);
        final var edge = document.createElementNS(BPMNDI, qualified(eventShape
                .get()
                .getPrefix(), "BPMNEdge"));
        edge.setAttribute("id", "%s_di".formatted(flowId));
        edge.setAttribute("bpmnElement", flowId);
        edge.appendChild(waypoint(document, markerX + MARKER_WIDTH, middle));
        edge.appendChild(waypoint(document, eventX, middle));
        plane.appendChild(edge);
        for (final var arriving : arrivingFlows) {
            diagramElementOf(document, "BPMNEdge", arriving)
                    .flatMap(arrivingEdge -> lastChild(arrivingEdge, DI, "waypoint"))
                    .ifPresent(last -> {
                        last.setAttribute("x", text(markerX));
                        last.setAttribute("y", text(middle));
                    });
        }
    }

    // the marker takes over the flows leaving the event, those the engine follows (the event's
    // outgoing) and those a reader of sourceRef sees, and one new flow leads from the event to it
    private static void insertBehind(
        final Element event,
        final String markerId,
        final String name,
        final String jobType
    ) {
        final var document = event.getOwnerDocument();
        final var container = (Element) event.getParentNode();
        final var eventId = event.getAttribute("id");
        final var flowId = "%s__flow".formatted(markerId);
        final var marker = bpmnElement(event, "serviceTask");
        marker.setAttribute("id", markerId);
        marker.setAttribute("name", name);
        ZenBpmInputMappings
                .addExtension(marker, "taskDefinition")
                .setAttribute("type", jobType);
        marker.appendChild(reference(event, "incoming", flowId));
        final var leavingFlows = new LinkedHashSet<String>();
        for (final var outgoing : bpmnChildren(event, "outgoing")) {
            leavingFlows.add(outgoing
                    .getTextContent()
                    .trim());
            marker.appendChild(outgoing);
        }
        for (final var flow : bpmnChildren(container, "sequenceFlow")) {
            if (eventId.equals(flow.getAttribute("sourceRef"))) {
                flow.setAttribute("sourceRef", markerId);
                leavingFlows.add(flow.getAttribute("id"));
            }
        }
        event.insertBefore(reference(event, "outgoing", flowId), firstChildBehindTheReferences(event));
        final var flow = bpmnElement(event, "sequenceFlow");
        flow.setAttribute("id", flowId);
        flow.setAttribute("sourceRef", eventId);
        flow.setAttribute("targetRef", markerId);
        container.insertBefore(marker, event.getNextSibling());
        container.insertBefore(flow, marker.getNextSibling());
        draw(document, eventId, markerId, flowId, leavingFlows);
    }

    private static void draw(
        final Document document,
        final String eventId,
        final String markerId,
        final String flowId,
        final Set<String> leavingFlows
    ) {
        final var eventShape = diagramElementOf(document, "BPMNShape", eventId);
        if (eventShape.isEmpty()) {
            return;
        }
        final var eventBounds = firstChild(eventShape.get(), DC, "Bounds");
        if (eventBounds.isEmpty()) {
            return;
        }
        final var bounds = eventBounds.get();
        final var middle = number(bounds, "y") + (number(bounds, "height") / 2);
        final var markerX = number(bounds, "x") + MARKER_OFFSET;
        final var plane = shapeOf(document, eventShape.get(), bounds, markerId, markerX, middle);
        final var edge = document.createElementNS(BPMNDI, qualified(eventShape
                .get()
                .getPrefix(), "BPMNEdge"));
        edge.setAttribute("id", "%s_di".formatted(flowId));
        edge.setAttribute("bpmnElement", flowId);
        edge.appendChild(waypoint(document, number(bounds, "x") + number(bounds, "width"), middle));
        edge.appendChild(waypoint(document, markerX, middle));
        plane.appendChild(edge);
        for (final var leaving : leavingFlows) {
            diagramElementOf(document, "BPMNEdge", leaving)
                    .flatMap(leavingEdge -> firstChild(leavingEdge, DI, "waypoint"))
                    .ifPresent(first -> {
                        first.setAttribute("x", text(markerX + MARKER_WIDTH));
                        first.setAttribute("y", text(middle));
                    });
        }
    }

    // the marker's shape, put next to its event's; answers the plane both are drawn on
    private static Node shapeOf(
        final Document document,
        final Element eventShape,
        final Element eventBounds,
        final String markerId,
        final double markerX,
        final double middle
    ) {
        final var markerShape = document.createElementNS(BPMNDI, eventShape.getTagName());
        markerShape.setAttribute("id", "%s_di".formatted(markerId));
        markerShape.setAttribute("bpmnElement", markerId);
        final var markerBounds = document.createElementNS(DC, eventBounds.getTagName());
        markerBounds.setAttribute("x", text(markerX));
        markerBounds.setAttribute("y", text(middle - (MARKER_HEIGHT / 2)));
        markerBounds.setAttribute("width", text(MARKER_WIDTH));
        markerBounds.setAttribute("height", text(MARKER_HEIGHT));
        markerShape.appendChild(markerBounds);
        final var plane = eventShape.getParentNode();
        plane.insertBefore(markerShape, eventShape.getNextSibling());
        return plane;
    }

    private static Element waypoint(
        final Document document,
        final double x,
        final double y
    ) {
        final var waypoint = document.createElementNS(DI, qualified(prefixOf(document, DI, "di"), "waypoint"));
        waypoint.setAttribute("x", text(x));
        waypoint.setAttribute("y", text(y));
        return waypoint;
    }

    private static Optional<Element> diagramElementOf(
        final Document document,
        final String localName,
        final String bpmnElement
    ) {
        final var found = document.getElementsByTagNameNS(BPMNDI, localName);
        for (var index = 0; index < found.getLength(); ++index) {
            final var element = (Element) found.item(index);
            if (bpmnElement.equals(element.getAttribute("bpmnElement"))) {
                return Optional.of(element);
            }
        }
        return Optional.empty();
    }

    private static Element bpmnElement(
        final Element sibling,
        final String localName
    ) {
        return sibling
                .getOwnerDocument()
                .createElementNS(ZenBpmModelReader.BPMN, qualified(sibling.getPrefix(), localName));
    }

    private static Element reference(
        final Element sibling,
        final String localName,
        final String flowId
    ) {
        final var reference = bpmnElement(sibling, localName);
        reference.setTextContent(flowId);
        return reference;
    }

    // where the schema puts an outgoing reference: behind the documentation, the extension
    // elements and the other references, in front of the event definitions
    private static Node firstChildBehindTheReferences(final Element event) {
        for (var child = event.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && !(ZenBpmModelReader.BPMN.equals(element.getNamespaceURI()) && List
                    .of("documentation", "extensionElements", "incoming", "outgoing")
                    .contains(element.getLocalName()))) {
                return child;
            }
        }
        return null;
    }

    private static List<Element> bpmnChildren(
        final Element parent,
        final String localName
    ) {
        final var children = new ArrayList<Element>();
        for (var child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && ZenBpmModelReader.BPMN.equals(element
                    .getNamespaceURI()) && localName.equals(element.getLocalName())) {
                children.add(element);
            }
        }
        return children;
    }

    private static Optional<Element> firstChild(
        final Element parent,
        final String namespace,
        final String localName
    ) {
        for (var child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && namespace.equals(element.getNamespaceURI()) && localName.equals(
                    element.getLocalName())) {
                return Optional.of(element);
            }
        }
        return Optional.empty();
    }

    private static Optional<Element> lastChild(
        final Element parent,
        final String namespace,
        final String localName
    ) {
        for (var child = parent.getLastChild(); child != null; child = child.getPreviousSibling()) {
            if (child instanceof Element element && namespace.equals(element.getNamespaceURI()) && localName.equals(
                    element.getLocalName())) {
                return Optional.of(element);
            }
        }
        return Optional.empty();
    }

    // the prefix the file binds a namespace to on its root, declared there where it binds none
    private static String prefixOf(
        final Document document,
        final String namespace,
        final String preferred
    ) {
        final var root = document.getDocumentElement();
        final var declared = root.lookupPrefix(namespace);
        if (declared != null) {
            return declared;
        }
        var prefix = preferred;
        for (var number = 1; root.lookupNamespaceURI(prefix) != null; ++number) {
            prefix = "%s%d".formatted(preferred, number);
        }
        root.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "%s:%s".formatted(XMLConstants.XMLNS_ATTRIBUTE,
                prefix), namespace);
        return prefix;
    }

    private static double number(
        final Element bounds,
        final String attribute
    ) {
        final var value = bounds.getAttribute(attribute);
        return value.isBlank() ? 0 : Double.parseDouble(value);
    }

    // a whole number without a fraction, as a modeller writes it
    private static String text(final double value) {
        return BigDecimal
                .valueOf(value)
                .stripTrailingZeros()
                .toPlainString();
    }

    private static String qualified(
        final String prefix,
        final String localName
    ) {
        return prefix == null ? localName : "%s:%s".formatted(prefix, localName);
    }

    /**
     * How much of a workflow an end event ends.
     */
    public enum Ending {

        /**
         * The branch which reaches it: the workflow ends there only where nothing else of it runs.
         * A none and a message end event.
         */
        BRANCH,

        /**
         * The whole workflow, whatever else of it runs: a terminate end event.
         */
        WORKFLOW

    }

}

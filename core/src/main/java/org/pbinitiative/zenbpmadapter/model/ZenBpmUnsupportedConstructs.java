/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Refuses a BPMN file which uses what ZenBPM cannot run, before anything is sent to the engine.
 * <p>
 * Three kinds of element are refused, each for its own reason:
 * <ul>
 * <li>what the engine refuses to deploy: signal, conditional, escalation, compensation and cancel
 * events on intermediate and boundary events; script, manual and plain tasks and complex gateways;
 * any other element with sequence flows the engine does not know, an ad-hoc sub-process or a
 * transaction for example; a sub-process with more than one start event; a non-interrupting error
 * start event; an event-based gateway with a condition or leading to anything but an intermediate
 * catch event; a boolean attribute it cannot read. The engine would refuse the file with a
 * <code>400</code>; refused here, the message names every element at once.</li>
 * <li>what the engine deploys and then runs differently from the model. It drops the event
 * definition of a signal or conditional start event, of a signal, escalation, compensation or cancel
 * end event, and of an error end event whose definition has no id. It evaluates a condition only on
 * a flow leaving an exclusive or inclusive gateway and only where it starts with <code>=</code>, and
 * it never takes a flow without a condition of such a gateway with more than one flow, unless it is
 * the default. It runs an element with a standard loop once, and never reads a completion condition
 * or a loop cardinality. It takes literally what Camunda 8 evaluates when it starts with
 * <code>=</code>: message names, called processes, job types, decision ids and candidate groups. It
 * never starts a workflow from an event-based gateway, nor from a receive task inside a
 * sub-process. And it fails on a timer written as an expression (it reads ISO 8601 only), on a
 * multi-instance element whose collection is not an expression, on a business key not written as
 * one, and on an intermediate throw event without a definition or with a timer.</li>
 * <li>what would start a workflow nobody built a workflow aggregate for: a receive task of the
 * process itself with <code>instantiate="true"</code>.</li>
 * </ul>
 * The first two kinds are refused in every process of the file, also in one no workflow service
 * claims, because the adapter deploys the whole file and whoever serves that process would get
 * something other than its model. The third is refused only in a claimed process: a process nobody
 * claims belongs to somebody else, who starts it as they please.
 * <p>
 * Boolean attributes are read as the engine reads them, see {@link ZenBpmBooleans}.
 */
public final class ZenBpmUnsupportedConstructs {

    private static final Set<String> EVENT_DEFINITIONS_THE_ENGINE_REFUSES = Set.of("signalEventDefinition",
            "conditionalEventDefinition", "escalationEventDefinition", "compensateEventDefinition",
            "cancelEventDefinition");

    private static final Set<String> ELEMENTS_THE_ENGINE_REFUSES = Set.of("scriptTask", "manualTask", "task",
            "complexGateway");

    // what the engine reads into a process or a sub-process; any other element with a sequence
    // flow it refuses
    private static final Set<String> ELEMENTS_THE_ENGINE_KNOWS = Set.of("startEvent", "endEvent", "sequenceFlow",
            "serviceTask", "userTask", "businessRuleTask", "sendTask", "receiveTask", "parallelGateway",
            "exclusiveGateway", "eventBasedGateway", "inclusiveGateway", "intermediateCatchEvent",
            "intermediateThrowEvent", "callActivity", "boundaryEvent", "subProcess");

    private static final Set<String> ACTIVITIES = Set.of("serviceTask", "userTask", "businessRuleTask", "sendTask",
            "receiveTask", "callActivity", "subProcess");

    private static final Set<String> EVENTS_WHICH_CARRY_DEFINITIONS_THE_ENGINE_REFUSES = Set.of(
            "intermediateCatchEvent", "intermediateThrowEvent", "boundaryEvent");

    private static final Set<String> GATEWAYS_WHICH_READ_CONDITIONS = Set.of("exclusiveGateway",
            "inclusiveGateway");

    // the extension attributes Camunda 8 evaluates as FEEL where they start with '=', and which
    // ZenBPM takes literally, by the extension element holding them
    private static final Map<String, String> TAKEN_LITERALLY = Map.of("calledElement", "processId",
            "taskDefinition", "type", "calledDecision", "decisionId", "assignmentDefinition", "candidateGroups");

    private ZenBpmUnsupportedConstructs() {
    }

    /**
     * Ends the deployment of a file which uses what ZenBPM cannot run.
     *
     * @param workflowModuleId The workflow module the file belongs to
     * @param model The model of the file, as it was read
     * @param claimed Whether a workflow service of the application claims the process of the file
     * @throws IllegalStateException Naming the file, and every element with its process and why
     *             it cannot run
     */
    public static void check(
        final String workflowModuleId,
        final ZenBpmBpmnModel model,
        final boolean claimed
    ) {
        final var findings = new Findings(model.bpmnProcessId());
        findings.read(model
                .document()
                .getDocumentElement(), processOf(model), claimed);
        if (findings.isEmpty()) {
            return;
        }
        throw new IllegalStateException("BPMN file '%s' of workflow module '%s' uses what ZenBPM cannot run:\n%s"
                .formatted(model.filename(), workflowModuleId, findings.describe()));
    }

    // the reader read a file only where it holds this one process and no other
    private static Element processOf(final ZenBpmBpmnModel model) {
        return (Element) model
                .document()
                .getElementsByTagNameNS(ZenBpmModelReader.BPMN, "process")
                .item(0);
    }

    private static boolean isBpmn(final Node node) {
        return node instanceof Element && ZenBpmModelReader.BPMN.equals(node.getNamespaceURI());
    }

    private static List<Element> bpmnChildren(final Element parent) {
        final var children = new ArrayList<Element>();
        for (var child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (isBpmn(child) && !"extensionElements".equals(child.getLocalName())) {
                children.add((Element) child);
            }
        }
        return children;
    }

    private static List<Element> bpmnChildren(
        final Element parent,
        final String localName
    ) {
        return bpmnChildren(parent)
                .stream()
                .filter(child -> localName.equals(child.getLocalName()))
                .toList();
    }

    // an element kind with its article: 'an exclusiveGateway', 'a serviceTask'
    private static String withArticle(final String kind) {
        return "%s %s".formatted("aeiou".indexOf(kind.charAt(0)) >= 0 ? "an" : "a", kind);
    }

    private static boolean writtenAsAnExpression(final String value) {
        return value
                .trim()
                .startsWith("=");
    }

    /**
     * What one walk over a process found, in the order of the file, by the reason it cannot run.
     */
    private static final class Findings {

        private final String processId;

        private final List<String> refusedByTheEngine = new ArrayList<>();

        private final List<String> runDifferently = new ArrayList<>();

        private final List<String> startWithoutAnAggregate = new ArrayList<>();

        // every element of the process, by its id, to tell what a flow leaves and where it leads
        private final Map<String, Element> elements = new HashMap<>();

        // the sequence flows leaving each element, by the element's id
        private final Map<String, List<Element>> flowsLeaving = new HashMap<>();

        private Element process;

        private Findings(final String processId) {
            this.processId = processId;
        }

        private void read(
            final Element definitions,
            final Element process,
            final boolean claimed
        ) {
            this.process = process;
            collect(process);
            for (final var message : bpmnChildren(definitions, "message")) {
                if (writtenAsAnExpression(message.getAttribute("name"))) {
                    runDifferently.add(line(message,
                            "message name written as an expression, which the engine takes literally"));
                }
            }
            if (ZenBpmBooleans.isUnreadable(process, "isClosed")) {
                refusedByTheEngine.add("%s (isClosed=\"%s\" on the process, which is no boolean)".formatted(
                        processId, process.getAttribute("isClosed")));
            }
            walk(process, claimed);
        }

        private boolean isEmpty() {
            return refusedByTheEngine.isEmpty() && runDifferently.isEmpty() && startWithoutAnAggregate.isEmpty();
        }

        private String describe() {
            final var description = new StringBuilder();
            section(description, "ZenBPM refuses to deploy a file with these:", refusedByTheEngine);
            section(description, "ZenBPM deploys these, but runs something other than the model shows:",
                    runDifferently);
            section(description, """
                    These would start a workflow for which nobody built a workflow aggregate, because \
                    the engine starts it on its own:""", startWithoutAnAggregate);
            description.append("""
                    Model them with what ZenBPM runs, or take them out of the file.""");
            return description.toString();
        }

        private void collect(final Element container) {
            for (final var element : bpmnChildren(container)) {
                elements.put(element.getAttribute("id"), element);
                if ("sequenceFlow".equals(element.getLocalName())) {
                    flowsLeaving
                            .computeIfAbsent(element.getAttribute("sourceRef"), sourceRef -> new ArrayList<>())
                            .add(element);
                }
                collect(element);
            }
        }

        // the kind of the element with the given id, null where the process has none
        private String kindOf(final String id) {
            final var element = elements.get(id);
            return element == null ? null : element.getLocalName();
        }

        private void walk(
            final Element container,
            final boolean claimed
        ) {
            for (final var element : bpmnChildren(container)) {
                check(element, container, claimed);
                if ("subProcess".equals(element.getLocalName())) {
                    walk(element, claimed);
                }
            }
        }

        private void check(
            final Element element,
            final Element container,
            final boolean claimed
        ) {
            final var kind = element.getLocalName();
            final var unknownFlowNode = !ELEMENTS_THE_ENGINE_KNOWS.contains(kind) && hasSequenceFlows(element);
            if (ELEMENTS_THE_ENGINE_REFUSES.contains(kind) || unknownFlowNode) {
                refusedByTheEngine.add(line(element, kind));
            }
            checkBooleans(element, kind);
            for (final var child : bpmnChildren(element)) {
                checkChild(element, kind, child, container);
            }
            checkExtensions(element, kind);
            switch (kind) {
                case "intermediateThrowEvent" -> checkThrowEvent(element);
                case "sequenceFlow" -> checkCondition(element);
                case "subProcess" -> checkSubProcess(element);
                case "eventBasedGateway" -> checkEventBasedGateway(element);
                case "receiveTask" -> checkReceiveTask(element, container, claimed);
                default -> {
                    // nothing beyond what every element is asked above
                }
            }
        }

        // the engine refuses the whole file over a boolean attribute it cannot read
        private void checkBooleans(
            final Element element,
            final String kind
        ) {
            final var attributes = new ArrayList<String>();
            if (ACTIVITIES.contains(kind)) {
                attributes.add("isForCompensation");
            }
            switch (kind) {
                case "receiveTask", "eventBasedGateway" -> attributes.add("instantiate");
                case "subProcess" -> attributes.add("triggeredByEvent");
                case "startEvent" -> attributes.addAll(List.of("isInterrupting", "parallelMultiple"));
                case "boundaryEvent" -> attributes.add("cancelActivity");
                default -> {
                    // no other boolean attribute is read by the engine
                }
            }
            for (final var attribute : attributes) {
                if (ZenBpmBooleans.isUnreadable(element, attribute)) {
                    refusedByTheEngine.add(line(element, "%s=\"%s\", which is no boolean".formatted(attribute,
                            element.getAttribute(attribute))));
                }
            }
        }

        private void checkChild(
            final Element element,
            final String kind,
            final Element child,
            final Element container
        ) {
            final var childKind = child.getLocalName();
            if (EVENT_DEFINITIONS_THE_ENGINE_REFUSES.contains(childKind)) {
                checkEventDefinition(element, kind, childKind, container);
                return;
            }
            switch (childKind) {
                case "errorEventDefinition" -> {
                    if ("endEvent".equals(kind) && !child.hasAttribute("id")) {
                        runDifferently.add(line(element,
                                "errorEventDefinition without an id on endEvent, which the engine drops and runs a plain endEvent"));
                    }
                }
                case "standardLoopCharacteristics" -> runDifferently.add(line(element,
                        "standardLoopCharacteristics, which the engine ignores and runs the element once"));
                case "multiInstanceLoopCharacteristics" -> checkMultiInstance(element, child);
                case "timerEventDefinition" -> checkTimer(element, child);
                default -> {
                    // no other child changes how the engine runs the element
                }
            }
        }

        private void checkTimer(
            final Element element,
            final Element timer
        ) {
            for (final var time : bpmnChildren(timer)) {
                if (writtenAsAnExpression(time.getTextContent())) {
                    runDifferently.add(line(element,
                            "%s written as an expression, which the engine cannot read: it reads ISO 8601 only and fails on it"
                                    .formatted(time.getLocalName())));
                }
            }
        }

        // an event definition the engine cannot run: refused where the engine refuses it, dropped
        // from a start or end event
        private void checkEventDefinition(
            final Element element,
            final String kind,
            final String definition,
            final Element container
        ) {
            if (EVENTS_WHICH_CARRY_DEFINITIONS_THE_ENGINE_REFUSES.contains(kind)) {
                refusedByTheEngine.add(line(element, "%s on %s".formatted(definition, kind)));
                return;
            }
            if (!"startEvent".equals(kind) && !"endEvent".equals(kind)) {
                return;
            }
            if ("startEvent".equals(kind) && ZenBpmBooleans.valueOf(container, "triggeredByEvent", false)) {
                runDifferently.add(
                        line(element, "%s on startEvent, which the engine drops, so the event sub-process never starts"
                                .formatted(definition)));
                return;
            }
            runDifferently.add(line(element, "%s on %s, which the engine drops and runs a plain %s".formatted(
                    definition, kind, kind)));
        }

        private void checkMultiInstance(
            final Element element,
            final Element multiInstance
        ) {
            if (ZenBpmBooleans.isUnreadable(multiInstance, "isSequential")) {
                refusedByTheEngine.add(line(element, "isSequential=\"%s\", which is no boolean".formatted(
                        multiInstance.getAttribute("isSequential"))));
            }
            final var inputCollection = ZenBpmModelReader
                    .extension(multiInstance, "loopCharacteristics")
                    .map(loop -> loop.getAttribute("inputCollection"))
                    .orElse("");
            if (!writtenAsAnExpression(inputCollection)) {
                runDifferently.add(line(element,
                        "multiInstanceLoopCharacteristics without an inputCollection written as an expression, which the engine fails on when a workflow reaches it"));
            }
            for (final var characteristic : bpmnChildren(multiInstance)) {
                final var characteristicKind = characteristic.getLocalName();
                if ("completionCondition".equals(characteristicKind) && !characteristic
                        .getTextContent()
                        .isBlank()) {
                    runDifferently.add(line(element, "completionCondition, which the engine never reads"));
                } else if ("loopCardinality".equals(characteristicKind)) {
                    runDifferently.add(line(element, "loopCardinality, which the engine never reads"));
                }
            }
        }

        // values Camunda 8 evaluates and ZenBPM takes literally, and a business key the engine
        // fails on unless it is an expression
        private void checkExtensions(
            final Element element,
            final String kind
        ) {
            for (final var literal : TAKEN_LITERALLY.entrySet()) {
                for (final var extension : ZenBpmModelReader.extensions(element, literal.getKey())) {
                    if (writtenAsAnExpression(extension.getAttribute(literal.getValue()))) {
                        runDifferently
                                .add(line(element, "%s of %s written as an expression, which the engine takes literally"
                                        .formatted(literal.getValue(), literal.getKey())));
                    }
                }
            }
            if ("callActivity".equals(kind) || "subProcess".equals(kind)) {
                for (final var in : ZenBpmModelReader.extensions(element, "in")) {
                    final var businessKey = in
                            .getAttribute("businessKey")
                            .trim();
                    if (!businessKey.isEmpty() && !businessKey.startsWith("=")) {
                        runDifferently.add(line(element,
                                "businessKey not written as an expression, which the engine fails on when a workflow reaches it"));
                    }
                }
            }
        }

        private void checkThrowEvent(final Element element) {
            final var definitions = bpmnChildren(element)
                    .stream()
                    .map(Element::getLocalName)
                    .filter(childKind -> childKind.endsWith("EventDefinition"))
                    .toList();
            if (definitions.isEmpty()) {
                runDifferently.add(line(element,
                        "intermediateThrowEvent without an event definition, which fails when a workflow reaches it"));
            } else if (definitions.contains("timerEventDefinition")) {
                runDifferently.add(line(element,
                        "timerEventDefinition on intermediateThrowEvent, which fails when a workflow reaches it"));
            }
        }

        // the engine evaluates a condition only where an exclusive or inclusive gateway chooses its
        // way, and only where it is an expression; it refuses one leaving an event-based gateway, and
        // anywhere else it ignores it. A flow without a condition of such a gateway is taken only
        // where it is the gateway's one flow or its default
        private void checkCondition(final Element flow) {
            final var source = kindOf(flow.getAttribute("sourceRef"));
            final var conditions = bpmnChildren(flow, "conditionExpression")
                    .stream()
                    .filter(condition -> !condition
                            .getTextContent()
                            .isBlank())
                    .toList();
            if (conditions.isEmpty()) {
                checkFlowWithoutCondition(flow, source);
                return;
            }
            final var condition = conditions.getFirst();
            if (source == null) {
                runDifferently.add(line(flow,
                        "condition on a sequenceFlow leaving no element of the process, which the engine never reads"));
                return;
            }
            if ("eventBasedGateway".equals(source)) {
                refusedByTheEngine.add(line(flow, "condition on a sequenceFlow leaving an eventBasedGateway"));
                return;
            }
            if (!GATEWAYS_WHICH_READ_CONDITIONS.contains(source)) {
                runDifferently.add(line(flow,
                        "condition on a sequenceFlow leaving %s, which the engine ignores: it reads conditions only on the flows leaving an exclusive or inclusive gateway"
                                .formatted(withArticle(source))));
                return;
            }
            final var text = ZenBpmModelReader.plainTextOf(condition);
            if (text.isEmpty()) {
                runDifferently.add(line(flow,
                        "condition written with CDATA or a comment, which the engine reads as text and never evaluates"));
                return;
            }
            if (!writtenAsAnExpression(text.get())) {
                runDifferently.add(line(flow,
                        "condition without a leading '=', which the engine never evaluates: an exclusive gateway fails on it, an inclusive one never takes the flow"));
            }
        }

        private void checkFlowWithoutCondition(
            final Element flow,
            final String source
        ) {
            final var sourceRef = flow.getAttribute("sourceRef");
            if (!GATEWAYS_WHICH_READ_CONDITIONS.contains(source) || flowsLeaving
                    .get(sourceRef)
                    .size() < 2) {
                return;
            }
            if (!flow
                    .getAttribute("id")
                    .equals(elements
                            .get(sourceRef)
                            .getAttribute("default"))) {
                runDifferently.add(line(flow,
                        "sequenceFlow without a condition leaving %s with more flows, which the engine never takes: give it a condition or make it the default flow"
                                .formatted(withArticle(source))));
            }
        }

        private void checkSubProcess(final Element subProcess) {
            final var startEvents = bpmnChildren(subProcess, "startEvent");
            if (startEvents.size() > 1) {
                refusedByTheEngine.add(line(subProcess, "subProcess with %d start events".formatted(startEvents
                        .size())));
            }
            for (final var startEvent : startEvents) {
                if (!ZenBpmBooleans.valueOf(startEvent, "isInterrupting", true) && !bpmnChildren(startEvent,
                        "errorEventDefinition").isEmpty()) {
                    refusedByTheEngine.add(line(startEvent, "non-interrupting error startEvent"));
                }
            }
        }

        private void checkEventBasedGateway(final Element gateway) {
            if (ZenBpmBooleans.valueOf(gateway, "instantiate", false)) {
                runDifferently.add(line(gateway,
                        "eventBasedGateway with instantiate=\"true\", which the engine never uses to start a workflow"));
            }
            for (final var flow : flowsLeaving.getOrDefault(gateway.getAttribute("id"), List.of())) {
                final var target = flow.getAttribute("targetRef");
                if (!"intermediateCatchEvent".equals(kindOf(target))) {
                    refusedByTheEngine
                            .add(line(gateway, "eventBasedGateway leading to '%s', which is no intermediateCatchEvent"
                                    .formatted(target)));
                }
            }
        }

        // the engine starts a workflow only from a receive task of the process itself; one
        // inside a sub-process it never uses to start one
        private void checkReceiveTask(
            final Element receiveTask,
            final Element container,
            final boolean claimed
        ) {
            if (!ZenBpmBooleans.valueOf(receiveTask, "instantiate", false)) {
                return;
            }
            if (container != process) {
                runDifferently.add(line(receiveTask,
                        "receiveTask with instantiate=\"true\" inside a subProcess, which the engine never uses to start a workflow"));
            } else if (claimed) {
                startWithoutAnAggregate.add(line(receiveTask, "receiveTask with instantiate=\"true\""));
            }
        }

        private static boolean hasSequenceFlows(final Element element) {
            return bpmnChildren(element)
                    .stream()
                    .anyMatch(child -> "incoming".equals(child.getLocalName()) || "outgoing".equals(child
                            .getLocalName()));
        }

        private String line(
            final Element element,
            final String construct
        ) {
            return "%s/%s (%s)".formatted(processId, element.getAttribute("id"), construct);
        }

        private static void section(
            final StringBuilder description,
            final String heading,
            final List<String> lines
        ) {
            if (lines.isEmpty()) {
                return;
            }
            description
                    .append("  ")
                    .append(heading)
                    .append('\n');
            lines.forEach(line -> description
                    .append("    - ")
                    .append(line)
                    .append('\n'));
        }

    }

}

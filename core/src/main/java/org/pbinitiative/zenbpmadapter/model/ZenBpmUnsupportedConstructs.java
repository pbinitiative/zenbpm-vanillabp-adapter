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
 * events on intermediate and boundary events, script, manual and plain tasks, complex gateways. The
 * engine would refuse the file with a <code>400</code>; refused here, the message names every
 * element at once.</li>
 * <li>what the engine deploys and then runs differently from the model: it drops the event
 * definition of a signal or conditional start event and of a signal, escalation, compensation or
 * cancel end event and runs a plain one; it reads a condition only on a flow leaving an exclusive
 * or inclusive gateway; it runs an element with a standard loop once; it never reads the completion
 * condition of a multi-instance element; and it fails when a workflow reaches a timer written as an
 * expression (it reads ISO 8601 only) or an intermediate throw event without an event
 * definition.</li>
 * <li>what would start a workflow nobody built a workflow aggregate for: a receive task with
 * <code>instantiate="true"</code>.</li>
 * </ul>
 * The first two kinds are refused in every process of the file, also in one no workflow service
 * claims, because the adapter deploys the whole file and whoever serves that process would get
 * something other than its model. The third is refused only in a claimed process: a process nobody
 * claims belongs to somebody else, who starts it as they please.
 */
public final class ZenBpmUnsupportedConstructs {

    private static final Set<String> EVENT_DEFINITIONS_THE_ENGINE_REFUSES = Set.of("signalEventDefinition",
            "conditionalEventDefinition", "escalationEventDefinition", "compensateEventDefinition",
            "cancelEventDefinition");

    private static final Set<String> ELEMENTS_THE_ENGINE_REFUSES = Set.of("scriptTask", "manualTask", "task",
            "complexGateway");

    private static final Set<String> EVENTS_WHICH_CARRY_DEFINITIONS_THE_ENGINE_REFUSES = Set.of(
            "intermediateCatchEvent", "intermediateThrowEvent", "boundaryEvent");

    private static final Set<String> GATEWAYS_WHICH_READ_CONDITIONS = Set.of("exclusiveGateway",
            "inclusiveGateway");

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
        final var process = processOf(model);
        findings.read(process, claimed);
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

    /**
     * What one walk over a process found, in the order of the file, by the reason it cannot run.
     */
    private static final class Findings {

        private final String processId;

        private final List<String> refusedByTheEngine = new ArrayList<>();

        private final List<String> runDifferently = new ArrayList<>();

        private final List<String> startWithoutAnAggregate = new ArrayList<>();

        // the local name of every element of the process, by its id, to tell what a flow leaves
        private final Map<String, String> kinds = new HashMap<>();

        private Findings(final String processId) {
            this.processId = processId;
        }

        private void read(
            final Element process,
            final boolean claimed
        ) {
            collectKinds(process);
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

        private void collectKinds(final Element container) {
            for (final var element : bpmnChildren(container)) {
                kinds.put(element.getAttribute("id"), element.getLocalName());
                collectKinds(element);
            }
        }

        private void walk(
            final Element container,
            final boolean claimed
        ) {
            for (final var element : bpmnChildren(container)) {
                check(element, claimed);
                if ("subProcess".equals(element.getLocalName())) {
                    walk(element, claimed);
                }
            }
        }

        private void check(
            final Element element,
            final boolean claimed
        ) {
            final var kind = element.getLocalName();
            if (ELEMENTS_THE_ENGINE_REFUSES.contains(kind)) {
                refusedByTheEngine.add(line(element, kind));
            }
            for (final var child : bpmnChildren(element)) {
                checkChild(element, kind, child);
            }
            if ("intermediateThrowEvent".equals(kind) && bpmnChildren(element)
                    .stream()
                    .noneMatch(child -> child
                            .getLocalName()
                            .endsWith("EventDefinition"))) {
                runDifferently.add(line(element,
                        "intermediateThrowEvent without an event definition, which fails when a workflow reaches it"));
            }
            if ("sequenceFlow".equals(kind)) {
                checkCondition(element);
            }
            if (claimed && "receiveTask".equals(kind) && "true".equals(element
                    .getAttribute("instantiate")
                    .trim())) {
                startWithoutAnAggregate.add(line(element, "receiveTask with instantiate=\"true\""));
            }
        }

        private void checkChild(
            final Element element,
            final String kind,
            final Element child
        ) {
            final var childKind = child.getLocalName();
            if (EVENT_DEFINITIONS_THE_ENGINE_REFUSES.contains(childKind)) {
                if (EVENTS_WHICH_CARRY_DEFINITIONS_THE_ENGINE_REFUSES.contains(kind)) {
                    refusedByTheEngine.add(line(element, "%s on %s".formatted(childKind, kind)));
                } else if ("startEvent".equals(kind) || "endEvent".equals(kind)) {
                    runDifferently.add(line(element, "%s on %s, which the engine drops and runs a plain %s"
                            .formatted(childKind, kind, kind)));
                }
            } else if ("standardLoopCharacteristics".equals(childKind)) {
                runDifferently.add(line(element,
                        "standardLoopCharacteristics, which the engine ignores and runs the element once"));
            } else if ("multiInstanceLoopCharacteristics".equals(childKind)) {
                for (final var characteristic : bpmnChildren(child)) {
                    if ("completionCondition".equals(characteristic.getLocalName()) && !characteristic
                            .getTextContent()
                            .isBlank()) {
                        runDifferently.add(line(element, "completionCondition, which the engine never reads"));
                    }
                }
            } else if ("timerEventDefinition".equals(childKind)) {
                for (final var time : bpmnChildren(child)) {
                    if (time
                            .getTextContent()
                            .trim()
                            .startsWith("=")) {
                        runDifferently.add(line(element,
                                "%s written as an expression, which the engine cannot read: it reads ISO 8601 only and fails on it"
                                        .formatted(time.getLocalName())));
                    }
                }
            }
        }

        // the engine reads a condition only where an exclusive or inclusive gateway chooses its
        // way; it refuses one leaving an event-based gateway, and anywhere else it ignores it
        private void checkCondition(final Element flow) {
            final var condition = bpmnChildren(flow)
                    .stream()
                    .filter(child -> "conditionExpression".equals(child.getLocalName()))
                    .findFirst();
            if (condition.isEmpty() || condition
                    .get()
                    .getTextContent()
                    .isBlank()) {
                return;
            }
            final var source = kinds.get(flow
                    .getAttribute("sourceRef")
                    .trim());
            if (source != null && GATEWAYS_WHICH_READ_CONDITIONS.contains(source)) {
                return;
            }
            if (source == null) {
                runDifferently.add(line(flow,
                        "condition on a sequenceFlow leaving no element of the process, which the engine never reads"));
            } else if ("eventBasedGateway".equals(source)) {
                refusedByTheEngine.add(line(flow, "condition on a sequenceFlow leaving an eventBasedGateway"));
            } else {
                runDifferently.add(line(flow,
                        "condition on a sequenceFlow leaving a %s, which the engine ignores: it reads conditions only on the flows leaving an exclusive or inclusive gateway"
                                .formatted(source)));
            }
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

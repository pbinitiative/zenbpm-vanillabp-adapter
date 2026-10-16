/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.model;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;

/**
 * Applies {@link NameClashAvoidance#USE_PREFIX} to a BPMN file of this adapter: every identifier
 * the engine resolves across the files it holds is rewritten through the platform's scoping, so two
 * workflow modules may use the same names on one engine.
 * <p>
 * What is rewritten:
 * <ul>
 * <li>the <code>id</code> of the process, and what points at it in the file: the
 * <code>processRef</code> of a pool and the <code>bpmnElement</code> of the diagram, so the file
 * stays a model a modeller draws;</li>
 * <li>the <code>processId</code> of a <code>calledElement</code>, because a call activity has to
 * address the renamed process;</li>
 * <li>the <code>name</code> of a message, the <code>errorCode</code> of an error, the
 * <code>escalationCode</code> of an escalation and the <code>decisionId</code> of a
 * <code>calledDecision</code>, scoped by the workflow module, because the decisions of the module
 * are deployed under scoped ids and the application correlates and throws by the plain names;</li>
 * <li>the <code>type</code> of a <code>taskDefinition</code>, of a task and of a user task alike,
 * scoped by the workflow module and the process, because job types are what workers subscribe to,
 * engine-wide.</li>
 * </ul>
 * Extension elements are found where the engine reads them, as children of the element's
 * <code>extensionElements</code>, by local name in any namespace. A value which is not there or is
 * empty names nothing and is left, as the engine leaves it; so a user task without a task
 * definition keeps the engine's default type.
 * <p>
 * The platform's scoping answers every identifier unchanged in the modes
 * {@link NameClashAvoidance#NONE} and {@link NameClashAvoidance#BY_ADAPTER}, so a file is only
 * changed under {@link NameClashAvoidance#USE_PREFIX}. It resolves the mode per workflow for the
 * process id and the task definitions, and per workflow module for the rest. The rewrite is not
 * idempotent, so the pipeline applies it once per file.
 * <p>
 * Before it rewrites anything, it refuses a called decision the engine would not find once its id is
 * scoped: the engine reads a decision id holding a <code>.</code> as
 * <code>&lt;DMN definitions id&gt;.&lt;decision id&gt;</code>, and the scoping prefixes the ids of
 * the decisions alone, so a decision named with the id of its DMN definitions, or any decision of a
 * workflow module whose id holds a <code>.</code>, would be looked for in DMN definitions which do
 * not exist. A decision bound by its version tag is looked for by its whole id.
 */
public final class ZenBpmScoping {

    private ZenBpmScoping() {
    }

    /**
     * Rewrites the identifiers of one file in place.
     *
     * @param document The file, holding one process
     * @param workflowModuleId The workflow module of the file
     * @param bpmnProcessId The plain id of the file's process
     * @param scoping The platform's name-clash scoping
     * @param adapterId The adapter id the file is deployed by
     * @throws IllegalStateException Where a called decision would not be found once its id is
     *             scoped, naming every such business rule task
     */
    public static void apply(
        final Document document,
        final String workflowModuleId,
        final String bpmnProcessId,
        final NameClashAvoidanceSupport scoping,
        final String adapterId
    ) {
        final UnaryOperator<String> processId = id -> scoping.scopedProcessId(workflowModuleId, id, adapterId);
        final UnaryOperator<String> identifier = name -> scoping.scopedIdentifier(workflowModuleId, name, adapterId);
        refuseDecisionIdsTheEngineWouldSplit(extensions(document, "calledDecision"), workflowModuleId, bpmnProcessId,
                identifier);
        final var scopedProcessId = processId.apply(bpmnProcessId);
        final UnaryOperator<String> referenceToTheProcess = reference -> reference.equals(bpmnProcessId)
                ? scopedProcessId
                : reference;

        rewrite(bpmnElements(document, "process"), "id", processId);
        rewrite(bpmnElements(document, "participant"), "processRef", referenceToTheProcess);
        rewrite(elements(document, "*", "BPMNPlane"), "bpmnElement", referenceToTheProcess);
        rewrite(bpmnElements(document, "message"), "name", identifier);
        rewrite(bpmnElements(document, "error"), "errorCode", identifier);
        rewrite(bpmnElements(document, "escalation"), "escalationCode", identifier);
        rewrite(extensions(document, "calledElement"), "processId", processId);
        rewrite(extensions(document, "calledDecision"), "decisionId", identifier);
        rewrite(extensions(document, "taskDefinition"), "type", type -> scoping.scopedTaskDefinition(
                workflowModuleId, bpmnProcessId, type, adapterId));
    }

    // the engine reads a decision id holding a '.' as '<id of the DMN definitions>.<id of the
    // decision>' unless the decision is bound by its version tag, and the scoping prefixes the ids
    // of the decisions alone, so such an id is refused once it is scoped
    private static void refuseDecisionIdsTheEngineWouldSplit(
        final List<Element> calledDecisions,
        final String workflowModuleId,
        final String bpmnProcessId,
        final UnaryOperator<String> identifier
    ) {
        final var refused = new ArrayList<String>();
        for (final var calledDecision : calledDecisions) {
            final var decisionId = calledDecision.getAttribute("decisionId");
            if (decisionId.isEmpty() || "versionTag".equals(calledDecision.getAttribute("bindingType"))) {
                continue;
            }
            final var scoped = identifier.apply(decisionId);
            if (!scoped.equals(decisionId) && scoped.contains(".")) {
                refused.add("  - %s/%s: decisionId '%s' becomes '%s'".formatted(bpmnProcessId,
                        ((Element) calledDecision
                                .getParentNode()
                                .getParentNode()).getAttribute("id"),
                        decisionId, scoped));
            }
        }
        if (refused.isEmpty()) {
            return;
        }
        throw new IllegalStateException("""
                Under name-clash-avoidance 'use-prefix', business rule tasks of workflow module '%s' would \
                call decisions ZenBPM does not find:
                %s
                ZenBPM reads a decision id holding a '.' as '<DMN definitions id>.<decision id>', and VanillaBP \
                prefixes the ids of the decisions with the workflow module id but not the ids of the DMN \
                definitions. Name each decision by its own id alone, give the workflow module an id without \
                a '.', or bind the decision by its version tag (bindingType="versionTag"), under which the \
                engine does not split the id.""".formatted(workflowModuleId, String.join("\n", refused)));
    }

    private static void rewrite(
        final List<Element> elements,
        final String attribute,
        final UnaryOperator<String> scoped
    ) {
        for (final var element : elements) {
            final var value = element.getAttribute(attribute);
            if (!value.isEmpty()) {
                element.setAttribute(attribute, scoped.apply(value));
            }
        }
    }

    private static List<Element> bpmnElements(
        final Document document,
        final String localName
    ) {
        return elements(document, ZenBpmModelReader.BPMN, localName);
    }

    // the extension elements of the given local name in any namespace, as children of an
    // element's extensionElements
    private static List<Element> extensions(
        final Document document,
        final String localName
    ) {
        final var extensions = new ArrayList<Element>();
        for (final var extensionElements : bpmnElements(document, "extensionElements")) {
            for (var child = extensionElements.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (child instanceof Element extension && localName.equals(extension.getLocalName())) {
                    extensions.add(extension);
                }
            }
        }
        return extensions;
    }

    private static List<Element> elements(
        final Document document,
        final String namespace,
        final String localName
    ) {
        final var found = document.getElementsByTagNameNS(namespace, localName);
        final var elements = new ArrayList<Element>(found.getLength());
        for (var index = 0; index < found.getLength(); ++index) {
            elements.add((Element) found.item(index));
        }
        return elements;
    }

}

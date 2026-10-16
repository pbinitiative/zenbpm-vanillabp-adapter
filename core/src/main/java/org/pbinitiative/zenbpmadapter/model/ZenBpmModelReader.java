/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.model;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.pbinitiative.zenbpmadapter.model.ZenBpmBpmnModel.CallActivity;
import org.pbinitiative.zenbpmadapter.model.ZenBpmBpmnModel.MessageReference;
import org.pbinitiative.zenbpmadapter.model.ZenBpmBpmnModel.MultiInstanceElement;
import org.pbinitiative.zenbpmadapter.model.ZenBpmBpmnModel.StartEvent;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import io.vanillabp.integration.adapter.spi.BpmnParseException;
import io.vanillabp.integration.adapter.spi.expressions.ExpressionPlace;
import io.vanillabp.integration.adapter.spi.expressions.ModelExpression;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.spi.service.BpmsStartTrigger;

/**
 * Reads a BPMN file into the model of this adapter, without any engine artifact: the file is plain
 * XML, read into a DOM which the pipeline rewrites later.
 * <p>
 * BPMN elements are read in the BPMN namespace. Extension elements are read by their local name in
 * any namespace, as the engine reads them, so a model of the Camunda Modeler (<code>zeebe:</code>)
 * and one of the ZenBPM Modeler (<code>zenbpm:</code>) read alike.
 * <p>
 * The engine reads every <code>process</code> of a file into ONE process definition: the elements
 * of all of them, under the id of the last one, whether a process is marked executable or not. So
 * a file with an executable process and any other process is refused here, instead of being
 * deployed as something nobody modelled. A file without an executable process yields nothing and
 * is not deployed.
 */
public final class ZenBpmModelReader {

    /**
     * The namespace of BPMN 2.0 elements.
     */
    public static final String BPMN = "http://www.omg.org/spec/BPMN/20100524/MODEL";

    /**
     * The job type the engine gives a user task whose task definition names none.
     */
    public static final String DEFAULT_USER_TASK_TYPE = "user-task-type";

    private static final String EXTENSION_ELEMENTS = "extensionElements";

    private static final String MULTI_INSTANCE = "multiInstanceLoopCharacteristics";

    private static final String MESSAGE_DEFINITION = "messageEventDefinition";

    private ZenBpmModelReader() {
    }

    /**
     * Reads one BPMN file.
     *
     * @param workflowModuleId The workflow module the file belongs to, for the messages
     * @param filename The name of the file, for the model and the messages
     * @param bpmn The content of the file; it is read to its end and NOT closed, because it
     *            belongs to the caller
     * @return The model of the file's executable process, or nothing where the file has none
     * @throws BpmnParseException Where the file is no BPMN, declares a DOCTYPE, or holds more
     *             than one process although one of them is executable
     */
    public static List<ZenBpmBpmnModel> read(
        final String workflowModuleId,
        final String filename,
        final InputStream bpmn
    ) {
        final var file = "BPMN file '%s' of workflow module '%s'".formatted(filename, workflowModuleId);
        final var document = parse(file, bpmn);
        final var definitions = document.getDocumentElement();
        if (!isBpmn(definitions, "definitions")) {
            throw new BpmnParseException(
                    "%s cannot be read: its root element is '%s' of %s, not 'definitions' of the namespace %s."
                            .formatted(file, definitions.getLocalName(), definitions.getNamespaceURI() == null
                                    ? "no namespace"
                                    : "the namespace %s".formatted(definitions.getNamespaceURI()), BPMN));
        }
        final var processes = bpmnChildren(definitions, "process");
        for (final var process : processes) {
            if (ZenBpmBooleans.isUnreadable(process, "isExecutable")) {
                throw new BpmnParseException("""
                        %s cannot be deployed: process '%s' has isExecutable="%s", which is no \
                        boolean, and ZenBPM refuses the file over it. Write true or false."""
                        .formatted(file, process.getAttribute("id"), process.getAttribute("isExecutable")));
            }
        }
        final var executable = processes
                .stream()
                .filter(ZenBpmModelReader::isExecutable)
                .toList();
        if (executable.isEmpty()) {
            return List.of();
        }
        if (processes.size() > 1) {
            throw new BpmnParseException("""
                    %s holds the processes %s, but ZenBPM reads every process of a file \
                    as one process with the elements of all of them, under the id of the last one, \
                    whether a process is executable or not. Give every process a file of its own; a \
                    pool which only shows a partner needs no process inside."""
                    .formatted(file, quoted(processes
                            .stream()
                            .map(process -> process.getAttribute("id"))
                            .toList())));
        }
        final var process = executable.getFirst();
        if (attribute(process, "id") == null) {
            throw new BpmnParseException("%s cannot be deployed: its executable process has no id."
                    .formatted(file));
        }
        return List.of(new ProcessReading(filename, document, definitions, process).model());
    }

    /**
     * The extension elements of the given local name, in any namespace, below the element's own
     * <code>extensionElements</code> and not below those of its children.
     *
     * @param element The BPMN element
     * @param localName The local name of the extension elements
     * @return The extension elements, in the order of the file
     */
    static List<Element> extensions(
        final Element element,
        final String localName
    ) {
        final var found = new ArrayList<Element>();
        for (final var extensionElements : bpmnChildren(element, EXTENSION_ELEMENTS)) {
            for (var child = extensionElements.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (child instanceof Element extension && localName.equals(extension.getLocalName())) {
                    found.add(extension);
                }
            }
        }
        return found;
    }

    /**
     * The first extension element of the given local name, see {@link #extensions}.
     *
     * @param element The BPMN element
     * @param localName The local name of the extension element
     * @return The extension element, empty where the element has none
     */
    static Optional<Element> extension(
        final Element element,
        final String localName
    ) {
        return extensions(element, localName)
                .stream()
                .findFirst();
    }

    /**
     * The text of a condition as the engine reads it: the element's content with its markup, so a
     * CDATA section or a comment stays part of the text and no condition starts with <code>=</code>
     * then. The reader gives the text only where the content is plain text.
     *
     * @param condition The <code>conditionExpression</code> element
     * @return The text, empty where the content holds markup
     */
    static Optional<String> plainTextOf(final Element condition) {
        for (var child = condition.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() != Node.TEXT_NODE) {
                return Optional.empty();
            }
        }
        return Optional.of(condition.getTextContent());
    }

    // a hardened parser: no DOCTYPE at all, so no entity can be declared, and nothing external
    // is ever fetched. The bytes are read first, because a parser closes the stream it reads
    private static Document parse(
        final String file,
        final InputStream bpmn
    ) {
        try {
            final var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            final var builder = factory.newDocumentBuilder();
            // the parser reports to standard error unless told otherwise; this handler throws a
            // fatal error, which the message names, and writes nothing
            builder.setErrorHandler(new DefaultHandler());
            return builder.parse(new ByteArrayInputStream(bpmn.readAllBytes()));
        } catch (final SAXException | IOException | ParserConfigurationException e) {
            throw new BpmnParseException("%s cannot be read: %s".formatted(file, e.getMessage()), e);
        }
    }

    private static boolean isExecutable(final Element process) {
        return ZenBpmBooleans.valueOf(process, "isExecutable", false);
    }

    private static boolean isBpmn(
        final Node node,
        final String localName
    ) {
        return node instanceof Element && BPMN.equals(node.getNamespaceURI()) && localName.equals(node
                .getLocalName());
    }

    private static List<Element> bpmnChildren(
        final Element parent,
        final String localName
    ) {
        final var children = new ArrayList<Element>();
        for (var child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (isBpmn(child, localName)) {
                children.add((Element) child);
            }
        }
        return children;
    }

    private static Optional<Element> bpmnChild(
        final Element parent,
        final String localName
    ) {
        return bpmnChildren(parent, localName)
                .stream()
                .findFirst();
    }

    private static List<Element> children(final Element parent) {
        final var children = new ArrayList<Element>();
        for (var child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element) {
                children.add(element);
            }
        }
        return children;
    }

    // an attribute's value as written, whitespace included, because the engine keeps it: a job
    // type ' charge ' is not 'charge'. Null where the element has none or an empty one
    private static String attribute(
        final Element element,
        final String name
    ) {
        final var value = element.getAttribute(name);
        return value.isEmpty() ? null : value;
    }

    // two or more values, quoted and joined: 'a', 'b' and 'c'
    private static String quoted(final List<String> values) {
        final var quoted = values
                .stream()
                .map("'%s'"::formatted)
                .toList();
        return "%s and %s".formatted(String.join(", ", quoted.subList(0, quoted.size() - 1)), quoted.getLast());
    }

    /**
     * One walk over the executable process of a file, in document order, collecting what the
     * model holds.
     */
    private static final class ProcessReading {

        private final String filename;

        private final Document document;

        private final Element process;

        // the name of every message of the file, by its id; null where a message has no name
        private final Map<String, String> messageNames = new HashMap<>();

        // the correlation key of every message of the file which carries one, by its id
        private final Map<String, String> correlationKeys = new HashMap<>();

        // the sources of the sequence flows of the process, to count the outgoing flows of a
        // gateway
        private final Map<String, Integer> outgoingFlows = new HashMap<>();

        private final List<BpmnTaskSpec> tasks = new ArrayList<>();

        private final List<BpmnTaskSpec> userTasks = new ArrayList<>();

        private final Set<String> catchMessages = new LinkedHashSet<>();

        private final Set<String> startMessages = new LinkedHashSet<>();

        private boolean startMessagesAreKnown = true;

        private final List<StartEvent> startEvents = new ArrayList<>();

        private final List<String> endEventIds = new ArrayList<>();

        private final List<MultiInstanceElement> multiInstanceElements = new ArrayList<>();

        private final List<CallActivity> callActivities = new ArrayList<>();

        private final Set<String> concurrentTokenElements = new LinkedHashSet<>();

        private final List<ModelExpression> expressions = new ArrayList<>();

        private final List<MessageReference> messageStarts = new ArrayList<>();

        private final List<MessageReference> messageCatches = new ArrayList<>();

        private final Map<String, List<String>> enclosingMultiInstances = new LinkedHashMap<>();

        private ProcessReading(
            final String filename,
            final Document document,
            final Element definitions,
            final Element process
        ) {
            this.filename = filename;
            this.document = document;
            this.process = process;
            for (final var message : bpmnChildren(definitions, "message")) {
                final var id = attribute(message, "id");
                messageNames.put(id, attribute(message, "name"));
                extension(message, "subscription")
                        .map(subscription -> attribute(subscription, "correlationKey"))
                        .ifPresent(correlationKey -> correlationKeys.put(id, correlationKey));
            }
        }

        private ZenBpmBpmnModel model() {
            countOutgoingFlows(process);
            for (final var element : children(process)) {
                readTopLevel(element);
            }
            walk(process, List.of());
            return new ZenBpmBpmnModel(
                    filename, document, attribute(process,
                            "id"), tasks, userTasks, catchMessages, startMessages, startMessagesAreKnown, startEvents, endEventIds, multiInstanceElements, callActivities, concurrentTokenElements, expressions, messageStarts, messageCatches, enclosingMultiInstances);
        }

        private void countOutgoingFlows(final Element container) {
            for (final var element : children(container)) {
                if (isBpmn(element, "sequenceFlow")) {
                    outgoingFlows.merge(attribute(element, "sourceRef"), 1, Integer::sum);
                } else if (!isBpmn(element, EXTENSION_ELEMENTS)) {
                    countOutgoingFlows(element);
                }
            }
        }

        // what only the elements of the process itself count for: its start and end events
        private void readTopLevel(final Element element) {
            if (isBpmn(element, "startEvent")) {
                final var id = attribute(element, "id");
                startEvents.add(new StartEvent(id, triggerOf(element)));
                bpmnChild(element, MESSAGE_DEFINITION).ifPresent(definition -> readStartMessage(id, definition));
            } else if (isBpmn(element, "endEvent")) {
                endEventIds.add(attribute(element, "id"));
            }
        }

        private void readStartMessage(
            final String elementId,
            final Element definition
        ) {
            final var messageRef = attribute(definition, "messageRef");
            final var name = Optional
                    .ofNullable(messageRef)
                    .map(messageNames::get);
            if (name.isPresent()) {
                startMessages.add(name.get());
            } else {
                startMessagesAreKnown = false;
            }
            if (messageRef != null) {
                messageStarts.add(new MessageReference(elementId, messageRef, name.orElse(null)));
            }
        }

        // every element below the container, in document order. The chain holds the ids of the
        // multi-instance elements around the container, the outermost first
        private void walk(
            final Element container,
            final List<String> chain
        ) {
            for (final var element : children(container)) {
                if (!BPMN.equals(element.getNamespaceURI()) || isBpmn(element, EXTENSION_ELEMENTS)) {
                    continue;
                }
                read(element, container == process);
                // the loop characteristics of a sub-process are a child of it as well, and have no id
                if (!chain.isEmpty() && element.hasAttribute("id")) {
                    enclosingMultiInstances.put(attribute(element, "id"), chain);
                }
                final var multiInstance = bpmnChild(element, MULTI_INSTANCE);
                if (multiInstance.isPresent()) {
                    readMultiInstance(element, multiInstance.get(), chain);
                }
                final var innerChain = multiInstance.isPresent() ? append(chain, attribute(element, "id")) : chain;
                if (isBpmn(element, "subProcess")) {
                    walk(element, innerChain);
                }
            }
        }

        private void read(
            final Element element,
            final boolean ofTheProcessItself
        ) {
            final var id = attribute(element, "id");
            final var kind = element.getLocalName();
            var job = false;
            switch (kind) {
                case "serviceTask", "sendTask" -> job = true;
                // the engine evaluates the decision itself only where the task names one
                case "businessRuleTask" -> job = extension(element, "calledDecision")
                        .map(calledDecision -> attribute(calledDecision, "decisionId"))
                        .isEmpty();
                case "intermediateThrowEvent", "endEvent" -> job = bpmnChild(element, MESSAGE_DEFINITION).isPresent();
                case "userTask" -> userTasks.add(BpmnTaskSpec.userTask(id, Optional
                        .ofNullable(taskDefinitionOf(element))
                        .orElse(DEFAULT_USER_TASK_TYPE), attribute(element, "name")));
                case "intermediateCatchEvent", "boundaryEvent" -> bpmnChild(element, MESSAGE_DEFINITION)
                        .ifPresent(definition -> readCatchMessage(id, attribute(definition, "messageRef")));
                case "receiveTask" -> readCatchMessage(id, attribute(element, "messageRef"));
                case "startEvent" -> {
                    if (!ofTheProcessItself) {
                        bpmnChild(element, MESSAGE_DEFINITION)
                                .ifPresent(definition -> readCatchMessage(id, attribute(definition, "messageRef")));
                    }
                }
                case "callActivity" -> callActivities.add(new CallActivity(id, extension(element, "calledElement")
                        .map(calledElement -> attribute(calledElement, "processId"))
                        .orElse(null)));
                case "sequenceFlow" -> bpmnChild(element, "conditionExpression")
                        .flatMap(ZenBpmModelReader::plainTextOf)
                        .ifPresent(condition -> addExpression(id, ExpressionPlace.SEQUENCE_FLOW_CONDITION,
                                condition));
                default -> {
                    // nothing to read from it beyond what every element is asked below
                }
            }
            if (job) {
                tasks.add(taskOf(element));
            }
            readConcurrency(element);
            readExtensionExpressions(id, element, job || "userTask".equals(kind));
        }

        private BpmnTaskSpec taskOf(final Element element) {
            return new BpmnTaskSpec(attribute(element, "id"), taskDefinitionOf(element), false, attribute(element,
                    "name"));
        }

        private String taskDefinitionOf(final Element element) {
            return extension(element, "taskDefinition")
                    .map(taskDefinition -> attribute(taskDefinition, "type"))
                    .orElse(null);
        }

        // a message a catch element waits for, and the correlation key the engine evaluates
        // for that element
        private void readCatchMessage(
            final String elementId,
            final String messageRef
        ) {
            if (messageRef == null) {
                return;
            }
            final var name = messageNames.get(messageRef);
            if (name != null) {
                catchMessages.add(name);
            }
            messageCatches.add(new MessageReference(elementId, messageRef, name));
            // the engine evaluates a correlation key only where it starts with '=' as written,
            // before it trims anything
            final var correlationKey = correlationKeys.get(messageRef);
            if (correlationKey != null && correlationKey.startsWith("=")) {
                addExpression(elementId, ExpressionPlace.MESSAGE_CORRELATION_KEY, correlationKey);
            }
        }

        private void readMultiInstance(
            final Element element,
            final Element multiInstance,
            final List<String> chain
        ) {
            final var id = attribute(element, "id");
            final var loop = extension(multiInstance, "loopCharacteristics");
            final var inputCollection = loop
                    .map(characteristics -> attribute(characteristics, "inputCollection"))
                    .orElse(null);
            multiInstanceElements.add(new MultiInstanceElement(id, inputCollection, loop
                    .map(characteristics -> attribute(characteristics, "inputElement"))
                    .orElse(null), chain));
            addExpression(id, ExpressionPlace.MULTI_INSTANCE_COLLECTION, inputCollection);
            loop
                    .map(characteristics -> attribute(characteristics, "outputElement"))
                    .ifPresent(outputElement -> addExpression(id, ExpressionPlace.SOMEWHERE_ELSE, outputElement));
        }

        // a token per outgoing flow wherever the engine forks: at a parallel or inclusive gateway,
        // and at every other element with more than one outgoing flow, which it leaves on all of
        // them; an exclusive and an event-based gateway choose one
        private void readConcurrency(final Element element) {
            final var id = attribute(element, "id");
            final var forks = outgoingFlows.getOrDefault(id, 0) > 1;
            final var concurrent = switch (element.getLocalName()) {
                case "exclusiveGateway", "eventBasedGateway", "sequenceFlow" -> false;
                case "boundaryEvent" -> forks || !ZenBpmBooleans.valueOf(element, "cancelActivity", true);
                case "subProcess" -> forks || ZenBpmBooleans.valueOf(element, "triggeredByEvent",
                        false) && bpmnChildren(element, "startEvent")
                                .stream()
                                .anyMatch(start -> !ZenBpmBooleans.valueOf(start, "isInterrupting", true));
                default -> forks;
            };
            final var parallelMultiInstance = bpmnChild(element, MULTI_INSTANCE)
                    .filter(multiInstance -> !ZenBpmBooleans.valueOf(multiInstance, "isSequential", false))
                    .isPresent();
            if (concurrent || parallelMultiInstance) {
                concurrentTokenElements.add(id);
            }
        }

        // the values of the element's extensions which the engine evaluates as FEEL, where the
        // engine keeps them for the element's kind: the retries and the backoff of a task which
        // creates a job, the sources of the mappings, the assignee of a user task and the business
        // key a call activity or a sub-process hands down
        private void readExtensionExpressions(
            final String elementId,
            final Element element,
            final boolean createsAJob
        ) {
            final var kind = element.getLocalName();
            if (createsAJob) {
                for (final var taskDefinition : extensions(element, "taskDefinition")) {
                    addExpression(elementId, ExpressionPlace.SOMEWHERE_ELSE, attribute(taskDefinition, "retries"));
                    addExpression(elementId, ExpressionPlace.SOMEWHERE_ELSE, attribute(taskDefinition,
                            "retryBackoff"));
                }
            }
            final var inputs = keepsInputMappings(element);
            final var outputs = inputs || "startEvent".equals(kind) || "boundaryEvent".equals(kind);
            for (final var ioMapping : extensions(element, "ioMapping")) {
                for (final var mapping : children(ioMapping)) {
                    final var direction = mapping.getLocalName();
                    if (("input".equals(direction) && inputs) || ("output".equals(direction) && outputs)) {
                        addExpression(elementId, ExpressionPlace.INPUT_OR_OUTPUT_MAPPING, attribute(mapping,
                                "source"));
                    }
                }
            }
            if ("userTask".equals(kind)) {
                for (final var assignment : extensions(element, "assignmentDefinition")) {
                    addExpression(elementId, ExpressionPlace.SOMEWHERE_ELSE, attribute(assignment, "assignee"));
                }
            }
            if ("callActivity".equals(kind) || "subProcess".equals(kind)) {
                for (final var in : extensions(element, "in")) {
                    addExpression(elementId, ExpressionPlace.SOMEWHERE_ELSE, attribute(in, "businessKey"));
                }
            }
        }

        // the activities, the intermediate events, and an end event which sends a message or
        // throws an error; the engine drops the mappings of every other end event
        private static boolean keepsInputMappings(final Element element) {
            return switch (element.getLocalName()) {
                case "serviceTask", "userTask", "businessRuleTask", "sendTask", "receiveTask", "callActivity",
                        "subProcess", "intermediateCatchEvent", "intermediateThrowEvent" ->
                    true;
                case "endEvent" -> bpmnChild(element, MESSAGE_DEFINITION).isPresent() || bpmnChild(element,
                        "errorEventDefinition")
                        .filter(definition -> definition.hasAttribute("id"))
                        .isPresent();
                default -> false;
            };
        }

        // the engine evaluates a value only where it starts with '='; anything else it takes
        // literally, so it is no expression
        private void addExpression(
            final String elementId,
            final ExpressionPlace place,
            final String value
        ) {
            if (value == null) {
                return;
            }
            final var expression = value.trim();
            if (expression.startsWith("=")) {
                expressions.add(new ModelExpression(elementId, place, expression, expression.substring(1)));
            }
        }

        private static List<String> append(
            final List<String> chain,
            final String id
        ) {
            final var longer = new ArrayList<>(chain);
            longer.add(id);
            return List.copyOf(longer);
        }

        private static BpmsStartTrigger.Kind triggerOf(final Element startEvent) {
            if (bpmnChild(startEvent, MESSAGE_DEFINITION).isPresent()) {
                return BpmsStartTrigger.Kind.MESSAGE;
            }
            if (bpmnChild(startEvent, "timerEventDefinition").isPresent()) {
                return BpmsStartTrigger.Kind.TIMER;
            }
            if (bpmnChild(startEvent, "signalEventDefinition").isPresent()) {
                return BpmsStartTrigger.Kind.SIGNAL;
            }
            if (bpmnChild(startEvent, "conditionalEventDefinition").isPresent()) {
                return BpmsStartTrigger.Kind.CONDITIONAL;
            }
            return BpmsStartTrigger.Kind.NONE;
        }

    }

}

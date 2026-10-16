/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.model;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.pbinitiative.zenbpmadapter.model.ZenBpmBpmnModel.MessageReference;
import org.w3c.dom.Element;

/**
 * Gives every message a workflow waits for the correlation key the application correlates with:
 * the aggregate id, so that <code>correlateMessage</code> works without a key in the model, as on
 * Camunda 8. And refuses a workflow module which uses one message name both to start a workflow and
 * as a message a workflow waits for.
 * <p>
 * A message a catch element waits for, and which carries no <code>subscription</code>, gets a
 * <code>zenbpm:subscription correlationKey="=&lt;aggregate id name&gt;"</code>. A subscription the
 * modeller wrote stays as it is, so a key of the modeller's own survives, and a model which carries
 * a key for every message it waits for is left as it is. A message only a start event names gets
 * none: the engine ignores the key of a start event.
 * <p>
 * The engine evaluates the key of an element inside a multi-instance body in the body's scope,
 * which holds the item and nothing else, so the aggregate id is carried into it by an input
 * mapping on every multi-instance sub-process around such an element, see
 * {@link ZenBpmInputMappings}.
 */
public final class ZenBpmCorrelationKeys {

    private static final String SUBSCRIPTION = "subscription";

    private ZenBpmCorrelationKeys() {
    }

    /**
     * Writes the correlation keys into the file of a process a workflow service claims.
     *
     * @param model The model of the file
     * @param aggregateIdName The name of the variable which holds the id of the workflow aggregate,
     *            asked only where a key is written
     */
    public static void apply(
        final ZenBpmBpmnModel model,
        final Supplier<String> aggregateIdName
    ) {
        final var document = model.document();
        final var messages = new HashMap<String, Element>();
        final var declared = document.getElementsByTagNameNS(ZenBpmModelReader.BPMN, "message");
        for (var index = 0; index < declared.getLength(); ++index) {
            final var message = (Element) declared.item(index);
            messages.put(message.getAttribute("id"), message);
        }
        final var withoutKey = new LinkedHashSet<String>();
        for (final var waiting : model.messageCatches()) {
            final var message = messages.get(waiting.messageId());
            if (message != null && ZenBpmModelReader
                    .extension(message, SUBSCRIPTION)
                    .isEmpty()) {
                withoutKey.add(waiting.messageId());
            }
        }
        if (withoutKey.isEmpty()) {
            return;
        }
        final var name = aggregateIdName.get();
        for (final var messageId : withoutKey) {
            ZenBpmInputMappings
                    .addExtension(messages.get(messageId), SUBSCRIPTION)
                    .setAttribute("correlationKey", "=%s".formatted(name));
        }
        final var elements = ZenBpmInputMappings.elementsById(document);
        for (final var waiting : model.messageCatches()) {
            if (!withoutKey.contains(waiting.messageId())) {
                continue;
            }
            for (final var multiInstance : model
                    .enclosingMultiInstances()
                    .getOrDefault(waiting.elementId(), List.of())) {
                ZenBpmInputMappings.carry(elements.get(multiInstance), List.of(name));
            }
        }
    }

    /**
     * Ends the deployment of a workflow module which uses a message name both in a message start
     * event and in an element a claimed workflow waits with. The engine hands a message whose
     * correlation key matches no waiting workflow to a start event of the same name, so such a
     * correlation would start a workflow instead of failing. A process nobody claims starts with
     * its messages like any other, but the messages it waits for are correlated by somebody else.
     *
     * @param workflowModuleId The workflow module
     * @param startingMessages The messages which start a workflow of the module, each with where
     *            it does, as <code>process/element</code>; the file's are added
     * @param awaitedMessages The messages claimed workflows of the module wait for, each with
     *            where; the file's are added where its process is claimed
     * @param model The model of the file
     * @param claimed Whether a workflow service claims the process of the file
     * @throws IllegalStateException Naming every message used both ways, and where
     */
    public static void refuseMessagesWhichStartAndAreAwaited(
        final String workflowModuleId,
        final Map<String, String> startingMessages,
        final Map<String, String> awaitedMessages,
        final ZenBpmBpmnModel model,
        final boolean claimed
    ) {
        record(startingMessages, model.bpmnProcessId(), model.messageStarts());
        if (claimed) {
            record(awaitedMessages, model.bpmnProcessId(), model.messageCatches());
        }
        final var collisions = startingMessages
                .keySet()
                .stream()
                .filter(awaitedMessages::containsKey)
                .map(message -> "  - '%s': starts %s, awaited by %s".formatted(message, startingMessages.get(
                        message), awaitedMessages.get(message)))
                .toList();
        if (collisions.isEmpty()) {
            return;
        }
        throw new IllegalStateException("""
                Workflow module '%s' uses a message both to start a workflow and as a message a workflow \
                waits for:
                %s
                ZenBPM hands a correlated message which no waiting workflow matches to the start event of \
                the same name, so a correlation which arrives while the workflow does not wait, or after it \
                went on, would start another workflow instead of failing. Give the message which starts a \
                workflow and the message a workflow waits for different names.""".formatted(workflowModuleId,
                String.join("\n", collisions)));
    }

    // the first element which names a message is the one a message names
    private static void record(
        final Map<String, String> messages,
        final String bpmnProcessId,
        final List<MessageReference> references
    ) {
        for (final var reference : references) {
            if (reference.messageName() != null) {
                messages.putIfAbsent(reference.messageName(), "%s/%s".formatted(bpmnProcessId, reference
                        .elementId()));
            }
        }
    }

}

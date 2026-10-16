/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.model;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.w3c.dom.Document;

import io.vanillabp.integration.adapter.spi.expressions.ModelExpression;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.spi.service.BpmsStartTrigger;

/**
 * The executable process of one BPMN file as {@link ZenBpmModelReader} read it, the model type of
 * this adapter's deployment pipeline.
 * <p>
 * The document is the whole file, and the pipeline rewrites it before it is deployed. Everything
 * else was read from the file as the modeller wrote it, before the pipeline added anything, so
 * that the core judges only what the modeller wrote. The collections keep the order of the file.
 *
 * @param filename The name of the file the process was read from
 * @param document The file, which the pipeline rewrites and deploys
 * @param bpmnProcessId The id of the process as the modeller wrote it
 * @param tasks The elements which make the engine create a job a worker serves, user tasks
 *            excepted: service, send and business rule tasks and the intermediate throw and end
 *            events which send a message. A business rule task which names a decision
 *            (<code>calledDecision</code> with a <code>decisionId</code>) is left out, because the
 *            engine evaluates the decision itself. An element without a task definition has none
 *            in its spec, so the wiring check names it. Job types are kept as written, whitespace
 *            included, as the engine keeps them.
 * @param userTasks The user tasks, each with its task definition, or the engine's default
 *            <code>user-task-type</code> where it names none or an empty one
 * @param catchMessages The names of the messages the process waits for: in intermediate catch
 *            events, boundary events, receive tasks and the start events of event sub-processes
 * @param startMessages The names of the messages which start the process: in the message start
 *            events of the process itself
 * @param startMessagesAreKnown <code>false</code> where the name of a message start event of the
 *            process cannot be read: it names no message, a message the file does not hold, or a
 *            message without a name. The engine takes every name literally, so a name which
 *            starts with <code>=</code> is known.
 * @param startEvents Every start event of the process itself, with what triggers it. The start
 *            events of embedded and event sub-processes start no workflow and are left out.
 * @param endEventIds The ids of the end events of the process itself, those of sub-processes left
 *            out
 * @param multiInstanceElements The multi-instance elements, wherever they are in the process
 * @param callActivities The call activities, wherever they are in the process
 * @param concurrentTokenElements The ids of the elements which give the workflow more than one
 *            token: every element with more than one outgoing flow except an exclusive and an
 *            event-based gateway (the engine leaves an activity or event on all of its flows),
 *            parallel multi-instance activities, non-interrupting boundary events and
 *            non-interrupting event sub-processes; boolean attributes read as the engine reads
 *            them, so <code>cancelActivity="0"</code> is non-interrupting
 * @param expressions The values the engine evaluates as FEEL and which read the data of the
 *            workflow, where the engine keeps them for the element's kind, as the modeller wrote
 *            them without the surrounding whitespace
 */
public record ZenBpmBpmnModel(
    String filename,
    Document document,
    String bpmnProcessId,
    List<BpmnTaskSpec> tasks,
    List<BpmnTaskSpec> userTasks,
    Set<String> catchMessages,
    Set<String> startMessages,
    boolean startMessagesAreKnown,
    List<StartEvent> startEvents,
    List<String> endEventIds,
    List<MultiInstanceElement> multiInstanceElements,
    List<CallActivity> callActivities,
    Set<String> concurrentTokenElements,
    List<ModelExpression> expressions
) {

    /**
     * Copies every collection, so a model stays what was read.
     */
    public ZenBpmBpmnModel {
        tasks = List.copyOf(tasks);
        userTasks = List.copyOf(userTasks);
        catchMessages = inOrder(catchMessages);
        startMessages = inOrder(startMessages);
        startEvents = List.copyOf(startEvents);
        endEventIds = List.copyOf(endEventIds);
        multiInstanceElements = List.copyOf(multiInstanceElements);
        callActivities = List.copyOf(callActivities);
        concurrentTokenElements = inOrder(concurrentTokenElements);
        expressions = List.copyOf(expressions);
    }

    private static Set<String> inOrder(final Set<String> values) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(values));
    }

    /**
     * A start event of the process itself.
     *
     * @param elementId The id of the start event
     * @param kind What triggers it: <code>NONE</code> where it has no event definition the reader
     *            knows
     */
    public record StartEvent(
        String elementId,
        BpmsStartTrigger.Kind kind
    ) {
    }

    /**
     * An element which runs once per item of a collection.
     *
     * @param elementId The id of the element
     * @param inputCollection The collection as the modeller wrote it, <code>null</code> where it
     *            names none
     * @param inputElement The name under which an instance gets its item, <code>null</code> where
     *            it names none: then the engine hands no item to the instances
     * @param enclosingChain The ids of the multi-instance elements around it, the outermost first,
     *            empty where it is inside none
     */
    public record MultiInstanceElement(
        String elementId,
        String inputCollection,
        String inputElement,
        List<String> enclosingChain
    ) {

        /**
         * Copies the chain, so it stays what was read.
         */
        public MultiInstanceElement {
            enclosingChain = List.copyOf(enclosingChain);
        }

    }

    /**
     * An element which starts an instance of another process.
     *
     * @param elementId The id of the call activity
     * @param calledProcessId The id of the called process as the modeller wrote it, without any
     *            prefix, <code>null</code> where it names none
     */
    public record CallActivity(
        String elementId,
        String calledProcessId
    ) {
    }

}

/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.model;

/**
 * Keeps the aggregate id in a workflow a message starts where the modeller maps the outputs of the
 * message start event.
 * <p>
 * The engine applies the output mappings of a message start event to the variables of the message
 * before it creates the instance, and where the event maps any, the instance gets the mapped ones
 * only. The id variable a start by a message sends would then not reach the instance, and its start
 * marker would hand the core a start which names no aggregate. So every message start event of the
 * process which maps outputs, but none to the id variable, gets an output of the id variable as
 * well, see decision 48 in the repository's DECISIONS.md.
 */
public final class ZenBpmMessageStarts {

    private ZenBpmMessageStarts() {
    }

    /**
     * Passes the id variable on through the outputs of every message start event of the process
     * which maps outputs but none to it.
     *
     * @param model The model of the process's file
     * @param aggregateIdName The name of the variable which carries the aggregate's id
     */
    public static void keepTheAggregateId(
        final ZenBpmBpmnModel model,
        final String aggregateIdName
    ) {
        final var elements = ZenBpmInputMappings.elementsById(model.document());
        model
                .messageStarts()
                .forEach(messageStart -> ZenBpmInputMappings.passOn(elements.get(messageStart.elementId()),
                        aggregateIdName));
    }

}

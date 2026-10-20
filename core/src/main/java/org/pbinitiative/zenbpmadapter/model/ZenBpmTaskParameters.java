/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.pbinitiative.zenbpmadapter.model.ZenBpmBpmnModel.MultiInstanceElement;

import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring;

/**
 * Makes the jobs inside a multi-instance body carry what the methods serving them read: the names
 * of their <code>&#64;TaskParam</code>s, those of the extensions' methods, and the aggregate id.
 * <p>
 * The engine creates the job of an element with a copy of the variables of its scope, so outside a
 * multi-instance body every name reaches the job without anything written into the model. Inside
 * one it does not: the body runs in a scope which starts with the item alone, and the job carries
 * nothing else but what input mappings give it. Those are evaluated in the scope around the element
 * which declares them, so a name reaches the job only where the element itself, if it is a
 * multi-instance element, and every multi-instance sub-process around it map it in, see
 * {@link ZenBpmInputMappings}. An element maps in the items of the multi-instance elements around
 * it, which are variables of the scope around it, but neither its own item, which the engine sets
 * inside its body, nor the items of the elements inside it, which do not exist yet where its
 * mappings are evaluated; a target the modeller maps already is left as well.
 * <p>
 * The names are asked of the platform with the element id and with the task definition, as a method
 * may be wired by either, and of the extensions with both keys, element id first, because the
 * engine hands a job to one client of the job stream, whose job an extension reads as well.
 */
public final class ZenBpmTaskParameters {

    private ZenBpmTaskParameters() {
    }

    /**
     * Writes the input mappings into the file of a process a workflow service claims.
     *
     * @param model The model of the file
     * @param workflowModuleId The workflow module of the file
     * @param wiring The platform's wiring, which knows the names the methods read
     */
    public static void apply(
        final ZenBpmBpmnModel model,
        final String workflowModuleId,
        final WorkflowTaskWiring wiring
    ) {
        final var process = model.bpmnProcessId();
        final var elements = ZenBpmInputMappings.elementsById(model.document());
        String aggregateIdName = null;
        for (final var task : Stream
                .concat(model
                        .tasks()
                        .stream(),
                        model
                                .userTasks()
                                .stream())
                .toList()) {
            final var activityId = task.activityId();
            final var chain = model.multiInstanceChainOf(activityId);
            if (chain.isEmpty()) {
                continue;
            }
            if (aggregateIdName == null) {
                aggregateIdName = wiring.resolveWorkflowAggregateIdName(workflowModuleId, process);
            }
            final var names = new TreeSet<String>(wiring.taskParameterNames(workflowModuleId, process, activityId));
            final var keys = new ArrayList<String>(List.of(activityId));
            if (task.taskDefinition() != null) {
                names.addAll(wiring.taskParameterNames(workflowModuleId, process, task.taskDefinition()));
                keys.add(task.taskDefinition());
            }
            names.addAll(wiring.extensionTaskParameterNames(workflowModuleId, process, keys));
            names.add(aggregateIdName);
            // the chain runs from the outermost element inwards: an element maps in the items of
            // the elements around it, and neither its own nor those of the elements inside it
            for (var position = 0; position < chain.size(); ++position) {
                final var carried = new TreeSet<String>(names);
                carried.removeAll(chain
                        .subList(position, chain.size())
                        .stream()
                        .map(MultiInstanceElement::inputElement)
                        .filter(Objects::nonNull)
                        .toList());
                ZenBpmInputMappings.carry(elements.get(chain
                        .get(position)
                        .elementId()), carried);
            }
        }
    }

    /**
     * The names each multi-instance element of a model maps into its body, those {@link #apply}
     * wrote included: what reaches a job inside the body beside the item.
     *
     * @param model The model, as the engine holds it or as it is deployed
     * @return The names by the id of the multi-instance element, in the order of the model
     */
    public static Map<String, Set<String>> mappedIntoMultiInstanceBodies(final ZenBpmBpmnModel model) {
        final var elements = ZenBpmInputMappings.elementsById(model.document());
        final var mapped = new LinkedHashMap<String, Set<String>>();
        model
                .multiInstanceElements()
                .forEach(multiInstance -> mapped.put(multiInstance.elementId(), ZenBpmInputMappings.inputTargetsOf(
                        elements.get(multiInstance.elementId()))));
        return mapped;
    }

}

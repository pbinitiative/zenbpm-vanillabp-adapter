/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.stream.Stream;

import org.pbinitiative.zenbpmadapter.model.ZenBpmBpmnModel.MultiInstanceElement;

import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring;

/**
 * Refuses a process whose methods want the item of an iteration the engine never hands over.
 * <p>
 * The engine gives the instances of a multi-instance element their item only under the name its
 * <code>inputElement</code> says; an element which names none iterates without one. A method which
 * declares <code>&#64;MultiInstanceElement</code> for such an element would receive
 * <code>null</code> once a job arrives, and nothing would say why. Only the adapter reads the model
 * and only the platform knows the methods, so the two halves meet here, while the application
 * starts.
 * <p>
 * What a method may want is the item of an element it runs in: the multi-instance sub-processes
 * around its task and the task itself, see {@link ZenBpmBpmnModel#multiInstanceChainOf(String)}.
 * The platform is asked by the element id and by the task definition, because a method may be
 * wired by either. An element the platform names which is no element of this model belongs to a
 * process which calls this one, and is not judged here.
 */
public final class ZenBpmMultiInstanceItems {

    private ZenBpmMultiInstanceItems() {
    }

    /**
     * Ends the deployment where a method serving a task of a claimed process wants an item which no
     * element of the model hands over.
     *
     * @param model The model of the process's file
     * @param workflowModuleId The workflow module of the file
     * @param wiring The platform's wiring, which knows the items the methods want
     * @throws IllegalStateException Naming every such task, the elements without an item, and the
     *             two ways out
     */
    public static void refuseItemsNoElementHandsOver(
        final ZenBpmBpmnModel model,
        final String workflowModuleId,
        final WorkflowTaskWiring wiring
    ) {
        final var process = model.bpmnProcessId();
        final var findings = new ArrayList<String>();
        for (final var task : Stream
                .concat(model
                        .tasks()
                        .stream(),
                        model
                                .userTasks()
                                .stream())
                .toList()) {
            final var withoutAnItem = model
                    .multiInstanceChainOf(task.activityId())
                    .stream()
                    .filter(multiInstance -> multiInstance.inputElement() == null)
                    .map(MultiInstanceElement::elementId)
                    .toList();
            if (withoutAnItem.isEmpty()) {
                continue;
            }
            final var wanted = new LinkedHashSet<String>(wiring.multiInstanceElementNames(workflowModuleId, process,
                    task.activityId()));
            if (task.taskDefinition() != null) {
                wanted.addAll(wiring.multiInstanceElementNames(workflowModuleId, process, task.taskDefinition()));
            }
            wanted.retainAll(withoutAnItem);
            if (wanted.isEmpty()) {
                continue;
            }
            findings.add("  - the method serving task '%s' (task definition '%s') wants the item of '%s'".formatted(
                    task.activityId(), task.taskDefinition(), String.join("', '", wanted)));
        }
        if (findings.isEmpty()) {
            return;
        }
        throw new IllegalStateException("""
                A @WorkflowTask method of BPMN process '%s' (workflow module '%s') declares \
                @MultiInstanceElement for an iteration which hands over no item:
                %s
                Each of these multi-instance elements names no 'inputElement', so ZenBPM gives its \
                iterations no item, and the parameter would receive null once a job arrives. Name the \
                item on the element (<zenbpm:loopCharacteristics inputCollection="=items" \
                inputElement="item" />, or 'zeebe:' from the Camunda Modeler), or drop the \
                parameter.""".formatted(process,
                workflowModuleId, String.join("\n", findings)));
    }

}

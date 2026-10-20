/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.deployment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.pbinitiative.zenbpmadapter.model.ZenBpmBpmnModel;
import org.pbinitiative.zenbpmadapter.model.ZenBpmMarkerTasks;
import org.pbinitiative.zenbpmadapter.model.ZenBpmTaskParameters;

/**
 * What the workflows of the versions older than the one a start deployed do not get, read from the
 * models the engine holds. The adapter brings VanillaBP's behaviour to a workflow by writing it into
 * the model it deploys, and a workflow stays on the version it was started on, so whatever the
 * deployed model carries and an older one lacks never reaches the workflows of that older one: the
 * end markers, through which the end of a workflow is reported, the start markers, through which
 * its start is, and the input mappings which carry the values the methods read into a
 * multi-instance body, see decisions 26 and 56 in the repository's DECISIONS.md. Only what the
 * deployed model has is named, so a version which lacks what the deployed one lacks as well is not
 * blamed for it.
 */
final class ZenBpmOlderVersions {

    private ZenBpmOlderVersions() {
    }

    /**
     * What the older versions lack, in words which follow "so those workflows never get it: ".
     *
     * @param deployed The model this start deployed, as the engine holds it
     * @param older The models of the older versions
     * @return What they miss, <code>null</code> where they miss nothing
     */
    static String whatTheyMiss(
        final ZenBpmBpmnModel deployed,
        final List<ZenBpmBpmnModel> older
    ) {
        final var missed = new ArrayList<String>();
        if (lackedByAny(deployed, older, ZenBpmMarkerTasks.ENDED)) {
            missed.add("the end of such a workflow is not reported to a @WorkflowEnded method");
        }
        if (lackedByAny(deployed, older, ZenBpmMarkerTasks.STARTED)) {
            missed.add("""
                    their start was not reported, so a workflow the engine started by a timer or a message has no \
                    workflow aggregate, and no start row holds the instance key of any of them""");
        }
        mappingsLacked(deployed, older).forEach((
            elementId,
            names) -> missed.add("a task inside the multi-instance body of '%s' does not get %s".formatted(elementId,
                    inWords(names
                            .stream()
                            .map("'%s'"::formatted)
                            .toList()))));
        return missed.isEmpty() ? null : inWords(missed);
    }

    // 'a', 'a and b', 'a, b, and c'
    private static String inWords(final List<String> items) {
        if (items.size() < 3) {
            return String.join(" and ", items);
        }
        return "%s, and %s".formatted(String.join(", ", items.subList(0, items.size() - 1)), items.getLast());
    }

    // markers are written for every event of a kind at once, so a version holds them or not
    private static boolean lackedByAny(
        final ZenBpmBpmnModel deployed,
        final List<ZenBpmBpmnModel> older,
        final String markerPrefix
    ) {
        return hasMarkers(deployed, markerPrefix) && older
                .stream()
                .anyMatch(model -> !hasMarkers(model, markerPrefix));
    }

    private static boolean hasMarkers(
        final ZenBpmBpmnModel model,
        final String markerPrefix
    ) {
        return model
                .tasks()
                .stream()
                .anyMatch(task -> task
                        .activityId()
                        .startsWith(markerPrefix));
    }

    // only an element an older version has as well counts: one it does not have runs no task of its
    // workflows
    private static LinkedHashMap<String, Set<String>> mappingsLacked(
        final ZenBpmBpmnModel deployed,
        final List<ZenBpmBpmnModel> older
    ) {
        final var lacked = new LinkedHashMap<String, Set<String>>();
        final var mappedNow = ZenBpmTaskParameters.mappedIntoMultiInstanceBodies(deployed);
        for (final var model : older) {
            final var mappedThen = ZenBpmTaskParameters.mappedIntoMultiInstanceBodies(model);
            mappedNow.forEach((
                elementId,
                names) -> {
                if (!mappedThen.containsKey(elementId)) {
                    return;
                }
                names
                        .stream()
                        .filter(name -> !mappedThen
                                .get(elementId)
                                .contains(name))
                        .forEach(name -> lacked
                                .computeIfAbsent(elementId, unused -> new LinkedHashSet<>())
                                .add(name));
            });
        }
        return lacked;
    }

}

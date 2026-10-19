/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.deployment;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.pbinitiative.zenbpmadapter.client.ZenBpmClientFactory;
import org.pbinitiative.zenbpmadapter.model.ZenBpmMarkerTasks;
import org.pbinitiative.zenbpmadapter.model.ZenBpmModelReader;

import io.vanillabp.integration.adapter.spi.AdapterCollaborators;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.spi.service.BpmsStartTrigger;
import lombok.extern.slf4j.Slf4j;

/**
 * Composes the job types which reach the workflows of a BPMN process a workflow module declares
 * without bringing a model for it, such as the old id of a renamed process: its workflows still run
 * in the engine, on the models an earlier version of the application deployed under that id. Under
 * <code>use-prefix</code> a job type carries the process it was deployed with, so the job types of
 * the deployed processes reach none of those workflows, and nobody notices, because a job nobody
 * takes has not failed.
 * <p>
 * What a job type is composed from is what the platform says the methods serving the id name, the
 * task definition of each, scoped by the id as the deployment of the id scoped it. The engine holds
 * a user task as a job as well, of a type scoped the same way, so a type composed from a task
 * definition may as well be a user task's, which the job stream must never take: a handler would run
 * and complete it as if it were a service task. So the models the engine holds under the id are
 * read, every version, and a composed type is served only where one of them gives it to a task which
 * creates a job for a worker and none gives it to a user task, see decision 41 in the repository's
 * DECISIONS.md. A type one of them gives to a user task is handed to the user-task poller instead,
 * which tells a user task's job by its element type, see decision 12 in the repository's
 * DECISIONS.md. Both are named in the line the start writes, and so are a type none of them gives to
 * any element and every reason why the workflows of the id may stand still: a method wired by
 * element id, which names no task definition, and models which cannot be read.
 * <p>
 * Its tasks are served with what their jobs carry: the variables of their scope, and inside a
 * multi-instance body only what the model held maps into it, which the version of the application
 * that deployed it wrote for the names its methods read then.
 * <p>
 * Where a model held under the id carries start markers, which an earlier version of the
 * application wrote, its start markers are served as well, with the start events of every held
 * model which carries them, see decision 46 in the repository's DECISIONS.md: a workflow started
 * right before the rename may still wait at its marker. A start marker needs no task definition, so
 * the held models are read for them also where no method serving the id names one, and not where
 * the platform hears no start of a workflow of the id.
 */
@Slf4j
final class ZenBpmDeclaredProcessWorkers {

    private final String adapterId;

    private final ZenBpmClientFactory factory;

    private final AdapterCollaborators collaborators;

    /**
     * The composer of one adapter id.
     *
     * @param adapterId The adapter id
     * @param factory The factory of the adapter id, whose engine holds the models
     * @param collaborators What the platform hands to the adapter id
     */
    ZenBpmDeclaredProcessWorkers(
        final String adapterId,
        final ZenBpmClientFactory factory,
        final AdapterCollaborators collaborators
    ) {
        this.adapterId = adapterId;
        this.factory = factory;
        this.collaborators = collaborators;
    }

    /**
     * What serves the workflows of the process ids a workflow module declares without a model.
     * Asks the engine only for an id whose methods name a task definition, or whose start the
     * platform hears.
     *
     * @param workflowModuleId The workflow module, which finished deploying
     * @param declared What the platform says the methods serving each such id name
     * @param subscribedBefore The job types the module's deployed processes subscribe
     * @return Per declared id, what serves its workflows
     */
    List<DeclaredProcess> compose(
        final String workflowModuleId,
        final Map<String, Collection<String>> declared,
        final Set<String> subscribedBefore
    ) {
        final var composed = new ArrayList<DeclaredProcess>();
        declared.forEach((
            bpmnProcessId,
            taskDefinitions) -> composed.add(compose(workflowModuleId, bpmnProcessId, taskDefinitions,
                    subscribedBefore)));
        return composed;
    }

    private DeclaredProcess compose(
        final String workflowModuleId,
        final String bpmnProcessId,
        final Collection<String> taskDefinitions,
        final Set<String> subscribedBefore
    ) {
        final var scoping = collaborators.scoping();
        final var scopedProcessId = scoping.scopedProcessId(workflowModuleId, bpmnProcessId, adapterId);
        final var startsAreHeard = collaborators
                .bpmsInitiatedStartInvoker()
                .map(invoker -> invoker.startsAWorkflowOfItsOwn(workflowModuleId, bpmnProcessId))
                .orElse(false);
        if (taskDefinitions.isEmpty()) {
            log.warn("""
                    ZenBPM[{}]: workflow module '{}' declares BPMN process '{}' without deploying a model under it, \
                    and no @WorkflowTask method serving that id names a task definition: every one of them is wired \
                    to a BPMN element id instead. A job type is composed from a task definition, which the model of \
                    that process would name, and this application does not bring it any more. The workflows still \
                    running under that id therefore stand still at their next task, without an incident, because a \
                    job nobody takes has not failed. Either wire those methods by task definition \
                    ('@WorkflowTask(taskDefinition = ...)', which is what the model's 'zenbpm:taskDefinition' \
                    carries), or keep deploying the old model under its old id until those workflows have ended.{}""",
                    adapterId, workflowModuleId, bpmnProcessId, startsAreHeard ? """
                             The start markers of the models the engine holds under that id are served all the \
                            same, because they need no task definition.""" : "");
            return new DeclaredProcess(bpmnProcessId, scopedProcessId, Map.of(), startsAreHeard ? startMarkersHeld(
                    workflowModuleId, scopedProcessId) : Map.of(), Map.of());
        }
        final HeldTypes types;
        try {
            types = heldTypes(workflowModuleId, scopedProcessId, startsAreHeard);
        } catch (final RuntimeException e) {
            log.debug("ZenBPM[{}]: the models the engine holds as '{}' could not be read", adapterId, scopedProcessId,
                    e);
            log.warn("""
                    ZenBPM[{}]: workflow module '{}' declares BPMN process '{}' without deploying a model under it, \
                    and the models the engine holds as '{}' could not be read ({}), so nothing serves the workflows \
                    still running under that id: a task definition alone cannot tell the job of a task from a user \
                    task, which the engine hands out alike. The next start of the application reads them again.""",
                    adapterId, workflowModuleId, bpmnProcessId, scopedProcessId, Objects.requireNonNullElse(e
                            .getMessage(), e.toString()));
            return new DeclaredProcess(bpmnProcessId, scopedProcessId, Map.of(), Map.of(), Map.of());
        }
        final var served = new LinkedHashMap<String, String>();
        final var userTasks = new LinkedHashMap<String, String>();
        final var heldNowhere = new TreeSet<String>();
        final var sharedWithATask = new TreeSet<String>();
        for (final var taskDefinition : new TreeSet<>(taskDefinitions)) {
            final var jobType = scoping.scopedTaskDefinition(workflowModuleId, bpmnProcessId, taskDefinition,
                    adapterId);
            // a type a user task carries in any version is never taken from the job stream, where
            // its job cannot be told from a task's, even where a task of another version carries it;
            // it is polled, and the poller tells a user task's job by its element type
            final var userTaskTypes = userTaskTypesOf(taskDefinition, jobType, types);
            if (!userTaskTypes.isEmpty()) {
                userTaskTypes.forEach(userTaskType -> userTasks.put(userTaskType, taskDefinition));
                // a task of another version which carries the type is served by nobody: the job
                // stream does not take the type, and the poller passes a task's job over
                if (userTaskTypes
                        .stream()
                        .anyMatch(types.taskTypes()::contains)) {
                    sharedWithATask.add(taskDefinition);
                }
                continue;
            }
            if (types
                    .taskTypes()
                    .contains(jobType)) {
                served.put(taskDefinition, jobType);
                continue;
            }
            heldNowhere.add(taskDefinition);
        }
        report(workflowModuleId, bpmnProcessId, scopedProcessId, served, types.startMarkers(), subscribedBefore,
                userTasks, heldNowhere, sharedWithATask);
        return new DeclaredProcess(bpmnProcessId, scopedProcessId, served, types.startMarkers(), userTasks);
    }

    // see decision 46 in the repository's DECISIONS.md: the start markers of an id whose methods
    // name no task definition
    private Map<String, BpmsStartTrigger.Kind> startMarkersHeld(
        final String workflowModuleId,
        final String scopedProcessId
    ) {
        try {
            return heldTypes(workflowModuleId, scopedProcessId, true).startMarkers();
        } catch (final RuntimeException e) {
            log.debug("ZenBPM[{}]: the models the engine holds as '{}' could not be read", adapterId, scopedProcessId,
                    e);
            log.warn("""
                    ZenBPM[{}]: the models the engine holds as '{}' could not be read ({}), so the start markers \
                    they may carry are not served. The next start of the application reads them again.""",
                    adapterId, scopedProcessId, Objects.requireNonNullElse(e.getMessage(), e.toString()));
            return Map.of();
        }
    }

    // the types the held models give to a user task of the task definition: its scoped type, and
    // the engine's default type where the task definition is that default, because a user task
    // without a task definition keeps it unscoped while one which names it is scoped
    private static List<String> userTaskTypesOf(
        final String taskDefinition,
        final String jobType,
        final HeldTypes types
    ) {
        return Stream
                .of(jobType, ZenBpmModelReader.DEFAULT_USER_TASK_TYPE)
                .distinct()
                .filter(userTaskType -> userTaskType.equals(jobType) || userTaskType.equals(taskDefinition))
                .filter(types.userTaskTypes()::contains)
                .toList();
    }

    // every version, because the workflows still running under the id run on old ones as well; a
    // task which names no task definition is served elsewhere and gives no type
    private HeldTypes heldTypes(
        final String workflowModuleId,
        final String scopedProcessId,
        final boolean startsAreHeard
    ) {
        final var taskTypes = new TreeSet<String>();
        final var userTaskTypes = new TreeSet<String>();
        final var startMarkers = new LinkedHashMap<String, BpmsStartTrigger.Kind>();
        final var startMarkerJobType = ZenBpmMarkerTasks.startMarkerJobType(scopedProcessId);
        final var rest = factory.rest();
        for (final var version : rest.processDefinitions(scopedProcessId, false)) {
            final var bpmn = rest
                    .processDefinition(version.key())
                    .bpmnData();
            ZenBpmModelReader
                    .read(workflowModuleId, "version %d of process '%s'".formatted(version.version(),
                            scopedProcessId), new ByteArrayInputStream(bpmn.getBytes(StandardCharsets.UTF_8)))
                    .forEach(model -> {
                        typesOf(model.tasks(), taskTypes);
                        typesOf(model.userTasks(), userTaskTypes);
                        if (startsAreHeard && model
                                .tasks()
                                .stream()
                                .anyMatch(task -> startMarkerJobType.equals(task.taskDefinition()))) {
                            model
                                    .startEvents()
                                    .forEach(startEvent -> startMarkers.putIfAbsent(startEvent.elementId(), startEvent
                                            .kind()));
                        }
                    });
        }
        return new HeldTypes(taskTypes, userTaskTypes, startMarkers);
    }

    private static void typesOf(
        final Collection<BpmnTaskSpec> tasks,
        final Set<String> types
    ) {
        tasks
                .stream()
                .map(BpmnTaskSpec::taskDefinition)
                .filter(Objects::nonNull)
                .forEach(types::add);
    }

    private void report(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String scopedProcessId,
        final Map<String, String> served,
        final Map<String, BpmsStartTrigger.Kind> startMarkers,
        final Set<String> subscribedBefore,
        final Map<String, String> userTasks,
        final Set<String> heldNowhere,
        final Set<String> sharedWithATask
    ) {
        final var subscribed = new TreeSet<String>();
        final var sharing = new TreeSet<String>();
        served
                .values()
                .forEach(jobType -> (subscribedBefore.contains(jobType) ? sharing : subscribed).add(jobType));
        final var servedBy = new ArrayList<String>();
        if (!subscribed.isEmpty()) {
            servedBy.add("the job types %s subscribed for it".formatted(subscribed));
        }
        if (!sharing.isEmpty()) {
            servedBy.add("the job types %s which the deployed processes subscribe".formatted(sharing));
        }
        if (!startMarkers.isEmpty()) {
            servedBy.add("the job type '%s' of the start markers of %s".formatted(ZenBpmMarkerTasks
                    .startMarkerJobType(scopedProcessId), new TreeSet<>(startMarkers.keySet())));
        }
        if (!userTasks.isEmpty()) {
            servedBy.add("""
                    the user-task poller for the task definition(s) %s, which a user task of a model held under that \
                    id carries, and which the job stream never takes, because it cannot tell a user task's job from \
                    a task's""".formatted(new TreeSet<>(userTasks.values())));
        }
        final var notServed = new ArrayList<String>();
        if (!heldNowhere.isEmpty()) {
            notServed.add("the task definition(s) %s, which no model held under that id names".formatted(
                    heldNowhere));
        }
        if (!sharedWithATask.isEmpty()) {
            notServed.add("""
                    the tasks of the task definition(s) %s in the versions which give its type to a task, because a \
                    user task of another version carries the type, which the job stream never takes, and the \
                    user-task poller passes a task's job over""".formatted(sharedWithATask));
        }
        final var line = """
                workflow module '%s' declares BPMN process '%s' without deploying a model under it, and the engine \
                holds it as '%s': %s.%s""".formatted(workflowModuleId, bpmnProcessId, scopedProcessId, servedBy
                .isEmpty() ? "nothing serves its workflows"
                        : "its workflows are served by %s".formatted(String.join(" and ", servedBy)),
                notServed
                        .isEmpty() ? "" : " Not served: %s.".formatted(String.join("; ", notServed)));
        if (served.isEmpty() && userTasks.isEmpty()) {
            log.warn("ZenBPM[{}]: {}", adapterId, line);
            return;
        }
        log.info("""
                ZenBPM[{}]: {} A job of those workflows carries the variables of its scope, and inside a \
                multi-instance body only what the model held maps into it.""", adapterId, line);
    }

    /**
     * What serves the workflows of one process id a workflow module declares without a model.
     *
     * @param bpmnProcessId The id as the application declares it
     * @param scopedBpmnProcessId The id under which the engine holds it
     * @param jobTypes The job type which serves each task definition the methods serving the id
     *            name, as the engine knows it, empty where nothing does
     * @param startMarkers The start events of the held models which carry start markers, each with
     *            what triggers it, empty where none does
     * @param userTaskTypes The job type of a user task of a held model for each task definition the
     *            methods serving the id name, as the engine knows it, each with that task definition;
     *            empty where no held model gives one to a user task
     */
    record DeclaredProcess(
        String bpmnProcessId,
        String scopedBpmnProcessId,
        Map<String, String> jobTypes,
        Map<String, BpmsStartTrigger.Kind> startMarkers,
        Map<String, String> userTaskTypes
    ) {
    }

    /**
     * The types the models held under one process id give to their elements.
     *
     * @param taskTypes The types of the tasks which create a job for a worker
     * @param userTaskTypes The types of the user tasks
     * @param startMarkers The start events of the models which carry start markers
     */
    private record HeldTypes(
        Set<String> taskTypes,
        Set<String> userTaskTypes,
        Map<String, BpmsStartTrigger.Kind> startMarkers
    ) {
    }

}

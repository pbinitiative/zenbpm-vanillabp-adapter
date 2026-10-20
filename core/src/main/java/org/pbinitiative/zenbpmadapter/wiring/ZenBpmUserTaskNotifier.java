/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.wiring;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.pbinitiative.zenbpmadapter.ZenBpmDeployedProcesses;
import org.pbinitiative.zenbpmadapter.ZenBpmInstanceCache;
import org.pbinitiative.zenbpmadapter.ZenBpmInstanceCache.InstanceFacts;
import org.pbinitiative.zenbpmadapter.ZenBpmProcessingContext;
import org.pbinitiative.zenbpmadapter.ZenBpmProcessingContext.WiredProcess;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientFactory;
import org.pbinitiative.zenbpmadapter.client.ZenBpmRestClient;
import org.pbinitiative.zenbpmadapter.client.api.JobFilter;
import org.pbinitiative.zenbpmadapter.client.api.JobRef;
import org.pbinitiative.zenbpmadapter.client.api.ProcessInstanceRef;

import io.vanillabp.integration.adapter.spi.AdapterCollaborators;
import io.vanillabp.integration.adapter.spi.workflowtask.DeliveryOfAnUnknownWorkflowException;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskKind;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;
import io.vanillabp.spi.service.TaskEvent;
import lombok.extern.slf4j.Slf4j;

/**
 * Tells the application of one workflow module that a user task was created or cancelled, for the
 * {@link ZenBpmUserTaskPoller} of the adapter id, which finds the user tasks and hands each to a
 * handler slot. A ZenBPM user task is a job which nobody pushes, so the notification is built from
 * the job as the engine lists it and from the facts of its instance, the way the
 * {@link ZenBpmJobHandler} of the module builds the delivery of a job, see decision 12 in the
 * repository's DECISIONS.md.
 * <p>
 * Before a notification is built, the platform is asked whether a method of the module serves the
 * user task, by its task definition or by its element id: a task the application marked as served
 * elsewhere, which may share its job type with a served one, is not notified and costs no
 * invocation. The notification names the job key as the task id, which completes the task later,
 * {@link TaskKind#USER_TASK} as its kind, the root instance of the task's own process as the
 * workflow (see decision 28 in the repository's DECISIONS.md), and as its delivery id the job key
 * with the event, so the core's record of a delivery answers a notification which a restarted node
 * hands it again. Once the core took the notification, it is handed the module's
 * {@link ZenBpmOpenTaskProbe} of the process, as after every delivery of a job, so a creation or a
 * cancellation of a user task is a wake-up of its workflow like any other.
 * <p>
 * A user task taken away by the end of its workflow is not notified as cancelled where the end was
 * reported, which the workflow's root instance shows by having completed after its end marker's
 * job: the core reported every user task it believed open in that workflow as cancelled before the
 * end, so a notification would tell the application a second time, see decision 53 in the
 * repository's DECISIONS.md.
 * <p>
 * A notification runs inside the module's {@link ZenBpmDrain}, so the shutdown waits for it, and
 * one which starts while the module stops is not run.
 */
@Slf4j
public final class ZenBpmUserTaskNotifier {

    private final String adapterId;

    private final String workflowModuleId;

    /**
     * The processes of the module which have user tasks to be notified of, by the ids the engine
     * knows them under.
     */
    private final Map<String, WiredProcess> processes = new LinkedHashMap<>();

    private final ZenBpmJobHandler handler;

    private final WorkflowTaskInvoker invoker;

    private final ZenBpmInstanceCache instances;

    private final ZenBpmDeployedProcesses deployedProcesses;

    private final ZenBpmRestClient rest;

    /**
     * The notifier of a workflow module whose processing starts.
     *
     * @param adapterId The adapter id
     * @param factory The clients of the adapter id
     * @param collaborators What the platform hands to the adapter id
     * @param context What the deployment of the module collected
     * @param handler The handler of the module's jobs, whose drain and probes the notifications
     *            share
     */
    public ZenBpmUserTaskNotifier(
        final String adapterId,
        final ZenBpmClientFactory factory,
        final AdapterCollaborators collaborators,
        final ZenBpmProcessingContext context,
        final ZenBpmJobHandler handler
    ) {
        this.adapterId = adapterId;
        this.workflowModuleId = context.workflowModuleId();
        context
                .wiredProcesses()
                .values()
                .stream()
                .filter(process -> !process
                        .userTaskTypes()
                        .isEmpty())
                .forEach(process -> processes.put(process.scopedBpmnProcessId(), process));
        this.handler = handler;
        this.invoker = collaborators.workflowTaskInvoker();
        this.instances = factory.instances();
        this.deployedProcesses = factory.deployedProcesses();
        this.rest = factory.rest();
    }

    /**
     * The workflow module whose user tasks this notifier tells about.
     *
     * @return The workflow module id
     */
    public String workflowModuleId() {
        return workflowModuleId;
    }

    /**
     * The job types of the module's user tasks to be notified of.
     *
     * @return The job types as the engine knows them, each once
     */
    public Set<String> userTaskTypes() {
        final var userTaskTypes = new LinkedHashSet<String>();
        processes
                .values()
                .forEach(process -> userTaskTypes.addAll(process
                        .userTaskTypes()
                        .keySet()));
        return userTaskTypes;
    }

    /**
     * The process of the module a user task's job belongs to, where the module is notified of it.
     *
     * @param scopedProcessId The process of the job's instance, as the engine knows it
     * @param jobType The job's type
     * @return The process, empty where the module holds no such process or polls no such type in
     *         it
     */
    Optional<WiredProcess> processPolling(
        final String scopedProcessId,
        final String jobType
    ) {
        return Optional
                .ofNullable(processes.get(scopedProcessId))
                .filter(process -> process
                        .userTaskTypes()
                        .containsKey(jobType));
    }

    /**
     * The name of the variable which carries the aggregate's id in the instances of a process.
     *
     * @param scopedProcessId The process, as the engine knows it
     * @return The name; <code>null</code> where the module holds no such process
     */
    String idVariableNameOf(final String scopedProcessId) {
        return handler.idVariableNameOf(scopedProcessId);
    }

    /**
     * Tells the application about one user task, on a handler thread.
     *
     * @param job The user task's job, as the engine listed it
     * @param event What happened to the user task
     * @param facts The facts of the job's instance, with the variable of the aggregate's id where
     *            the instance has it
     * @param process The process of the module the job belongs to
     * @param warnedBefore Whether a failure of the method notified was said at WARN before, so a
     *            further one is said at DEBUG
     * @return What became of the notification
     */
    Outcome deliver(
        final JobRef job,
        final TaskEvent.Event event,
        final InstanceFacts facts,
        final WiredProcess process,
        final boolean warnedBefore
    ) {
        final var plainProcessId = process.bpmnProcessId();
        final var taskDefinition = process
                .userTaskTypes()
                .get(job.type());
        if (!invoker.workflowTaskHandlerExists(workflowModuleId, plainProcessId, taskDefinition) && !invoker
                .workflowTaskHandlerExists(workflowModuleId, plainProcessId, job.elementId())) {
            log.trace(
                    "ZenBPM[{}]: no @WorkflowTask method of process '{}' (workflow module '{}') serves user task '{}' (element '{}'), so job {} is {} without a notification",
                    adapterId, plainProcessId, workflowModuleId, taskDefinition, job.elementId(), job.key(), event);
            return Outcome.DONE;
        }
        final var drain = handler.drain();
        // entered before the module's state is read, as a job's handler enters, so either the stop
        // waits for this notification or this notification sees the stop
        final var entered = drain.enter(job.key(), taskDefinition);
        try {
            if (drain.isStopping()) {
                log.debug("ZenBPM[{}]: user task {} ('{}') is not notified as {} while workflow module '{}' shuts down",
                        adapterId, job.key(), taskDefinition, event, workflowModuleId);
                return Outcome.AGAIN;
            }
            final Optional<Notification> notification;
            try {
                notification = notificationOf(job, event, facts, plainProcessId, taskDefinition);
            } catch (final RuntimeException e) {
                log.debug(
                        "ZenBPM[{}]: the workflow of user task {} ('{}') could not be read; the next look at the user tasks hands it over again: {}",
                        adapterId, job.key(), taskDefinition, e.getMessage());
                return Outcome.AGAIN;
            }
            if (notification.isEmpty()) {
                return Outcome.AGAIN;
            }
            final boolean toldByTheEnd;
            try {
                toldByTheEnd = (event == TaskEvent.Event.CANCELED) && theEndOfItsWorkflowToldIt(process, notification
                        .get());
            } catch (final RuntimeException e) {
                log.debug(
                        "ZenBPM[{}]: the end of the workflow of user task {} ('{}') could not be read; the next look at the user tasks hands it over again: {}",
                        adapterId, job.key(), taskDefinition, e.getMessage());
                return Outcome.AGAIN;
            }
            if (toldByTheEnd) {
                log.debug(
                        "ZenBPM[{}]: user task {} ('{}') was taken away by the end of its workflow, whose report told the application already, so it is not notified as {}",
                        adapterId, job.key(), taskDefinition, event);
                return Outcome.DONE;
            }
            if (notification
                    .get()
                    .namesNoAggregate()) {
                warnAboutAnInstanceWithoutAnAggregate(job, event, facts, taskDefinition);
                return Outcome.DONE;
            }
            drain.describe(entered, notification
                    .get()
                    .getWorkflowAggregateId());
            return invoke(plainProcessId, notification.get(), warnedBefore);
        } finally {
            drain.leave(entered);
        }
    }

    /**
     * The delivery id of a notification of a user task: the job key with the event, so the creation
     * and the cancellation of one user task are two deliveries, and no id of a job of the job stream,
     * which is a key alone, is ever one of them.
     *
     * @param jobKey The key of the user task's job
     * @param event What happened to the user task
     * @return E.g. <code>2251799813685251-created</code>
     */
    static String deliveryIdOf(
        final long jobKey,
        final TaskEvent.Event event
    ) {
        return event == TaskEvent.Event.CANCELED ? "%d-canceled".formatted(jobKey) : "%d-created".formatted(jobKey);
    }

    private Outcome invoke(
        final String plainProcessId,
        final Notification notification,
        final boolean warnedBefore
    ) {
        final WorkflowTaskOutcome outcome;
        try {
            outcome = invoker.invokeWorkflowTask(workflowModuleId, plainProcessId, notification);
        } catch (final DeliveryOfAnUnknownWorkflowException e) {
            // no stack trace: the message says everything
            log.warn("""
                    ZenBPM[{}]: user task {} ('{}') is about a workflow this application does not own, so it is \
                    not notified as {}: {}""", adapterId, notification.getTaskId(), notification.getTaskDefinition(),
                    notification.getTaskEvent(), e.getMessage());
            return Outcome.DONE;
        } catch (final RuntimeException e) {
            final var failure = """
                    ZenBPM[{}]: the @WorkflowTask method notified of user task {} ('{}', workflow aggregate '{}') as \
                    {} threw; its transaction was rolled back, and a later look at the user tasks notifies it again: \
                    the next look first, and after each further failure a look which waits twice as many looks, at \
                    most {}. A further failure of this notification is logged at DEBUG.""";
            if (warnedBefore) {
                log.debug(failure, adapterId, notification.getTaskId(), notification.getTaskDefinition(),
                        notification.getWorkflowAggregateId(), notification.getTaskEvent(),
                        ZenBpmUserTaskPoller.LONGEST_WAIT_IN_LOOKS, e);
            } else {
                log.warn(failure, adapterId, notification.getTaskId(), notification.getTaskDefinition(), notification
                        .getWorkflowAggregateId(), notification.getTaskEvent(),
                        ZenBpmUserTaskPoller.LONGEST_WAIT_IN_LOOKS, e);
            }
            return Outcome.THREW;
        }
        if (outcome.kind() == WorkflowTaskOutcome.Kind.BPMN_ERROR) {
            log.warn("""
                    ZenBPM[{}]: the @WorkflowTask method notified of user task {} ('{}', workflow aggregate '{}') as \
                    {} raised the BPMN error '{}', which a notification cannot throw into the workflow: the \
                    aggregate's changes are committed, and the user task waits on. Cancel the user task with \
                    ProcessService#cancelUserTask to route the error.""", adapterId, notification.getTaskId(),
                    notification.getTaskDefinition(), notification.getWorkflowAggregateId(), notification
                            .getTaskEvent(),
                    outcome.errorCode());
        }
        reportTheTasksTheEngineNoLongerHas(plainProcessId, notification);
        return Outcome.DONE;
    }

    // after the core took the notification, as after a job's outcome reached the engine; nothing of
    // it may cost the notification, which the core committed
    private void reportTheTasksTheEngineNoLongerHas(
        final String plainProcessId,
        final Notification wakeUp
    ) {
        try {
            invoker.reportTasksTheBpmsNoLongerHas(workflowModuleId, plainProcessId, wakeUp, handler.probeOf(
                    plainProcessId));
        } catch (final RuntimeException e) {
            log.debug(
                    "ZenBPM[{}]: the other open tasks of the workflow of user task {} ('{}') could not be looked at; the next delivery of the workflow looks again",
                    adapterId, wakeUp.getTaskId(), wakeUp.getTaskDefinition(), e);
        }
    }

    // see decision 53 in the repository's DECISIONS.md: the end marker's job is completed only after
    // the core committed the end, which reported every user task it believed open in the workflow
    // as cancelled; a workflow which ends where a branch ends completes markers of its other branches
    // without a report, so the workflow has to have ended as well
    private boolean theEndOfItsWorkflowToldIt(
        final WiredProcess process,
        final Notification notification
    ) {
        final var endMarkerJobType = process.endMarkerJobType();
        if (endMarkerJobType.isEmpty()) {
            return false;
        }
        final var workflowId = notification.workflowId;
        final var ended = rest
                .instance(workflowId)
                .map(ProcessInstanceRef::state)
                .filter(ProcessInstanceRef.State.COMPLETED::equals)
                .isPresent();
        return ended && !rest
                .jobs(JobFilter
                        .anyJob()
                        .withJobType(endMarkerJobType.get())
                        .withState(JobRef.State.COMPLETED)
                        .withProcessInstanceKey(workflowId)
                        .withSize(1))
                .items()
                .isEmpty();
    }

    // an instance which names no aggregate was started past VanillaBP; a user task's job is not
    // failed, because nobody waits for it but a person, so it is said once per event
    private void warnAboutAnInstanceWithoutAnAggregate(
        final JobRef job,
        final TaskEvent.Event event,
        final InstanceFacts facts,
        final String taskDefinition
    ) {
        final var idVariableName = idVariableNameOf(facts.scopedProcessId());
        log.warn("""
                ZenBPM[{}]: user task {} ('{}') of process instance {} of process '{}' is not notified as {}: the \
                instance names no workflow aggregate, it has no business key and no variable '{}'. An instance \
                started through VanillaBP carries both, so this one was started past it. Set the variable '{}' to \
                the id of the workflow aggregate, or cancel the instance.""", adapterId, job.key(), taskDefinition,
                facts.key(), facts.scopedProcessId(), event, idVariableName, idVariableName);
    }

    // empty where an instance above the job's own is not known to the engine node asked, which may
    // lag behind; the next look hands the user task over again
    private Optional<Notification> notificationOf(
        final JobRef job,
        final TaskEvent.Event event,
        final InstanceFacts facts,
        final String plainProcessId,
        final String taskDefinition
    ) {
        final var path = instances.instancesUpToTheWorkflow(facts.key());
        if (path.isEmpty()) {
            log.debug(
                    "ZenBPM[{}]: an instance above instance {} of user task {} is not known to the engine node asked, which may lag behind; the next look at the user tasks hands it over again",
                    adapterId, facts.key(), job.key());
            return Optional.empty();
        }
        final var workflowId = path
                .get()
                .getLast();
        final var idVariableName = idVariableNameOf(facts.scopedProcessId());
        final var aggregate = aggregateNamedIn(facts, idVariableName, workflowId);
        return Optional
                .of(new Notification(adapterId, job, event, taskDefinition, aggregate.orElse(null), workflowId, facts
                        .version(), deployedProcesses
                                .versionOf(workflowModuleId, plainProcessId)
                                .map(deployed -> facts.version() < deployed)
                                .orElse(false)));
    }

    // an instance of a sub-process takes both from its parent when it starts, so a workflow the
    // engine started and VanillaBP named only later has them in its root alone
    private Optional<NamedAggregate> aggregateNamedIn(
        final InstanceFacts facts,
        final String idVariableName,
        final long workflowId
    ) {
        final var named = NamedAggregate.in(facts, idVariableName);
        if (named.isPresent() || (facts.key() == workflowId)) {
            return named;
        }
        return instances
                .facts(workflowId, idVariableName)
                .flatMap(root -> NamedAggregate.in(root, idVariableName));
    }

    /**
     * What became of a notification of a user task.
     */
    enum Outcome {

        /**
         * Nothing is left to do: the core took the notification, or nobody is to be notified.
         */
        DONE,

        /**
         * It was not told, because the engine did not answer for its workflow or the module stops;
         * a later look hands it over again.
         */
        AGAIN,

        /**
         * The method notified threw, and the core rolled its transaction back; a later look hands it
         * over again.
         */
        THREW

    }

    /**
     * The invocation context of one notification of a user task, built from the job and the facts
     * of its instance; it asks the engine nothing.
     */
    private static final class Notification implements TaskInvocationContext {

        private final String adapterId;

        private final JobRef job;

        private final TaskEvent.Event event;

        private final String taskDefinition;

        private final NamedAggregate aggregate;

        private final long workflowId;

        private final int version;

        private final boolean predatesDeployedVersion;

        private Notification(
            final String adapterId,
            final JobRef job,
            final TaskEvent.Event event,
            final String taskDefinition,
            final NamedAggregate aggregate,
            final long workflowId,
            final int version,
            final boolean predatesDeployedVersion
        ) {
            this.adapterId = adapterId;
            this.job = job;
            this.event = event;
            this.taskDefinition = taskDefinition;
            this.aggregate = aggregate;
            this.workflowId = workflowId;
            this.version = version;
            this.predatesDeployedVersion = predatesDeployedVersion;
        }

        @Override
        public String getTaskDefinition() {
            return taskDefinition;
        }

        @Override
        public String getWorkflowAggregateId() {
            return aggregate.id();
        }

        @Override
        public String getBusinessKey() {
            return aggregate.businessKey();
        }

        @Override
        public String getBpmnElementId() {
            // the core finds the method by the task definition or by the element id, and an
            // element id is never scoped
            return job.elementId();
        }

        @Override
        public String getWorkflowId() {
            // the root instance of the task's own process, see decision 28 in the repository's
            // DECISIONS.md
            return String.valueOf(workflowId);
        }

        @Override
        public String getTaskId() {
            return String.valueOf(job.key());
        }

        @Override
        public TaskKind getTaskKind() {
            return TaskKind.USER_TASK;
        }

        @Override
        public TaskEvent.Event getTaskEvent() {
            return event;
        }

        @Override
        public String getProcessVersion() {
            return String.valueOf(version);
        }

        @Override
        public String getAdapterId() {
            return adapterId;
        }

        @Override
        public String getDeliveryId() {
            return deliveryIdOf(job.key(), event);
        }

        @Override
        public String getActivationId() {
            // the job key differs between two activations of one element, as the contract asks,
            // and is what the job handler names as well
            return getTaskId();
        }

        @Override
        public Object getTaskParameter(final String name) {
            return job
                    .inputVariables()
                    .get(name);
        }

        @Override
        public boolean predatesDeployedVersion() {
            return predatesDeployedVersion;
        }

        private boolean namesNoAggregate() {
            return aggregate == null;
        }

    }

}

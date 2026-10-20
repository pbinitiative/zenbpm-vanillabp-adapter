/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter;

import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientFactory.WorkflowModuleShutdownRegistration;
import org.pbinitiative.zenbpmadapter.model.ZenBpmBpmnModel;
import org.pbinitiative.zenbpmadapter.model.ZenBpmCorrelationKeys;
import org.pbinitiative.zenbpmadapter.model.ZenBpmMarkerTasks;

import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport.ModelIdentifier;
import io.vanillabp.spi.service.BpmsStartTrigger;

/**
 * What the deployment pipeline of this adapter collects about one workflow module, from its first
 * BPMN file to the start of its workflow processing. It names the type the platform and the
 * extensions match the pipeline by.
 * <p>
 * It holds the files it prepared, the processes of the workflow module, the messages they start
 * with and wait for, and its DMN files as they are deployed; what it hands out cannot be changed.
 * The adapter SPI prepares every process of a file, and all processes of a file share one model,
 * so what is done to a file is done once: the context remembers the files it prepared. This
 * adapter reads one process per file, because the engine deploys no more, so today every file is
 * prepared once anyway; the guard keeps the SPI's contract should that ever change.
 * <p>
 * Each field has an accessor of its own and no Lombok getter on the class, see decision 13 in the
 * repository's DECISIONS.md.
 */
@SuppressWarnings("LombokGetterMayBeUsed")
public final class ZenBpmProcessingContext {

    private final String workflowModuleId;

    private final Set<String> preparedFiles = new HashSet<>();

    // the processes of the workflow module, each with the file declaring it
    private final Map<String, String> processes = new LinkedHashMap<>();

    private final Map<String, byte[]> decisions = new LinkedHashMap<>();

    private final Map<String, String> startingMessages = new LinkedHashMap<>();

    private final Map<String, String> awaitedMessages = new LinkedHashMap<>();

    private final Map<String, WiredProcess> wiredProcesses = new LinkedHashMap<>();

    private final Map<String, JobTimeout> jobTimeouts = new LinkedHashMap<>();

    private final Map<String, ZenBpmBpmnModel> bpmnFiles = new LinkedHashMap<>();

    private final Set<ModelIdentifier> declaredIdentifiers = new LinkedHashSet<>();

    // guarded by this: the platform stops a module on the thread which stops the application, and
    // the client factory on the thread which closes it
    private StartedProcessing startedProcessing;

    /**
     * The context of one workflow module, built for its first BPMN process.
     *
     * @param workflowModuleId The workflow module
     */
    public ZenBpmProcessingContext(final String workflowModuleId) {
        this.workflowModuleId = workflowModuleId;
    }

    /**
     * The workflow module this context collects about.
     *
     * @return The workflow module id
     */
    public String workflowModuleId() {
        return workflowModuleId;
    }

    /**
     * Remembers that a file is prepared, and says whether it is the first time.
     *
     * @param filename The name of the BPMN file
     * @return <code>true</code> the first time a file is named, <code>false</code> afterwards
     */
    public boolean firstPreparationOf(final String filename) {
        return preparedFiles.add(filename);
    }

    /**
     * Remembers a process of the workflow module, by its plain id, with the file declaring it.
     * The engine keeps one definition per process id and deploys every file on its own, so a
     * second file declaring the same process would replace the first as a new version of it,
     * and the platform's check for colliding process ids takes one process of one module handed
     * over twice for the same process: two files declaring one process id end the deployment
     * here.
     *
     * @param bpmnProcessId The id of the process as the modeller wrote it
     * @param filename The name of the file declaring it
     * @throws IllegalStateException Where another file of the workflow module declares the same
     *             process id, naming both files
     */
    public void recordProcess(
        final String bpmnProcessId,
        final String filename
    ) {
        final var declaredBefore = processes.putIfAbsent(bpmnProcessId, filename);
        if (declaredBefore == null || declaredBefore.equals(filename)) {
            return;
        }
        throw new IllegalStateException("""
                Workflow module '%s' declares BPMN process '%s' in two files, '%s' and '%s'. ZenBPM keeps \
                one definition per process id, so the later file would replace the earlier one as a new \
                version of it. Keep the process in one file, or give one of the two processes another id."""
                .formatted(workflowModuleId, bpmnProcessId, declaredBefore, filename));
    }

    /**
     * The processes of the workflow module, by their plain ids, in the order they were prepared.
     *
     * @return The ids, each once
     */
    public List<String> processes() {
        return List.copyOf(processes.keySet());
    }

    /**
     * Keeps a BPMN file of the workflow module for its deployment, with the model the pipeline
     * rewrites. A file is kept once, whatever number of processes it holds.
     *
     * @param model The model of the file
     */
    public void addBpmnFile(final ZenBpmBpmnModel model) {
        bpmnFiles.putIfAbsent(model.filename(), model);
    }

    /**
     * The BPMN files of the workflow module, in the order they were prepared.
     *
     * @return The model of each file, by the name of the file
     */
    public Map<String, ZenBpmBpmnModel> bpmnFiles() {
        return Collections.unmodifiableMap(bpmnFiles);
    }

    /**
     * Adds identifiers the files of the workflow module declare, see
     * {@link #declaredIdentifiers()}.
     *
     * @param identifiers The identifiers, plain
     */
    public void addDeclaredIdentifiers(final Collection<ModelIdentifier> identifiers) {
        declaredIdentifiers.addAll(identifiers);
    }

    /**
     * The identifiers the BPMN and DMN files of the workflow module declare, in their plain form:
     * message names, error and escalation codes, task definitions with their process, and decision
     * ids. The platform warns where two workflow modules end up under one scoped form.
     *
     * @return The identifiers, each once, in the order they were read
     */
    public Set<ModelIdentifier> declaredIdentifiers() {
        return Collections.unmodifiableSet(declaredIdentifiers);
    }

    /**
     * Keeps a DMN file of the workflow module as it is deployed.
     *
     * @param filename The name of the file
     * @param dmn The content of the file, with its decision ids scoped where the mode asks for it
     */
    public void addDecisions(
        final String filename,
        final byte[] dmn
    ) {
        decisions.put(filename, dmn);
    }

    /**
     * The DMN files of the workflow module as they are deployed, in the order they were read.
     *
     * @return The content of each file, by its name
     */
    public Map<String, byte[]> decisions() {
        return Collections.unmodifiableMap(decisions);
    }

    /**
     * Adds the messages of a file to those of the workflow module, and ends the deployment where a
     * message then both starts a workflow of the module and is one a claimed workflow waits for, see
     * {@link ZenBpmCorrelationKeys#refuseMessagesWhichStartAndAreAwaited}.
     *
     * @param model The model of the file
     * @param claimed Whether a workflow service claims the process of the file
     * @throws IllegalStateException Naming every message used both ways, and where
     */
    public void addMessagesOf(
        final ZenBpmBpmnModel model,
        final boolean claimed
    ) {
        ZenBpmCorrelationKeys.refuseMessagesWhichStartAndAreAwaited(workflowModuleId, startingMessages,
                awaitedMessages, model, claimed);
    }

    /**
     * The names of the messages which start a workflow of the module, each with the first start
     * event naming it as <code>process/element</code>.
     *
     * @return The messages, as far as the files were prepared
     */
    public Map<String, String> startingMessages() {
        return Collections.unmodifiableMap(startingMessages);
    }

    /**
     * The names of the messages the claimed workflows of the module wait for, each with the first
     * element waiting for it as <code>process/element</code>.
     *
     * @return The messages, as far as the files were prepared
     */
    public Map<String, String> awaitedMessages() {
        return Collections.unmodifiableMap(awaitedMessages);
    }

    /**
     * Remembers what wiring found out about a claimed process of the workflow module.
     *
     * @param wiredProcess What was found
     */
    public void recordWiring(final WiredProcess wiredProcess) {
        wiredProcesses.put(wiredProcess.bpmnProcessId(), wiredProcess);
    }

    /**
     * The claimed processes of the workflow module as far as they were wired, by their plain ids,
     * in the order they were wired, followed by the process ids it declares without a model once its
     * processing started and found something to serve them with. A process nobody claims is not
     * wired and not among them.
     *
     * @return What wiring found out about each of them
     */
    public Map<String, WiredProcess> wiredProcesses() {
        return Collections.unmodifiableMap(wiredProcesses);
    }

    /**
     * The job types of the workflow module's claimed processes, those of their start and end
     * markers included, and of the process ids it declares without a model, each once, in the
     * order the processes were wired.
     * <p>
     * The end markers' type of every process is among them wherever the module subscribes a type
     * anyway, also where the deployed model gives no end event a marker: an instance of a version
     * deployed while somebody listened to the end of its workflows waits at its end marker after
     * nobody does any more, and a job nobody takes stops it there for good, see decision 52 in the
     * repository's DECISIONS.md. A module which subscribes nothing else opens no job stream for
     * them, so an adapter id without a job stream still starts.
     *
     * @return The job types as the engine knows them
     */
    public Set<String> jobTypes() {
        final var jobTypes = new LinkedHashSet<String>();
        wiredProcesses
                .values()
                .forEach(wiredProcess -> {
                    jobTypes.addAll(wiredProcess.jobTypes());
                    wiredProcess
                            .startMarkerJobType()
                            .ifPresent(jobTypes::add);
                    if (!wiredProcess
                            .endMarkers()
                            .isEmpty()) {
                        jobTypes.add(wiredProcess.endMarkerJobType());
                    }
                });
        if (!jobTypes.isEmpty()) {
            wiredProcesses
                    .values()
                    .forEach(wiredProcess -> jobTypes.add(wiredProcess.endMarkerJobType()));
        }
        return Collections.unmodifiableSet(jobTypes);
    }

    /**
     * The job types of the user tasks of the workflow module's claimed processes, and of the process
     * ids it declares without a model, whose notifications the application wants, each once, in the
     * order the processes were wired. The job stream never subscribes them: they are polled, see
     * decision 12 in the repository's DECISIONS.md.
     *
     * @return The job types as the engine knows them
     */
    public Set<String> userTaskTypes() {
        final var userTaskTypes = new LinkedHashSet<String>();
        wiredProcesses
                .values()
                .forEach(wiredProcess -> userTaskTypes.addAll(wiredProcess
                        .userTaskTypes()
                        .keySet()));
        return Collections.unmodifiableSet(userTaskTypes);
    }

    /**
     * Remembers the <code>job-timeout</code> of a task a claimed process of the module serves, by
     * the job type the task's jobs come under, and ends the deployment where another task of the
     * module comes under the same job type with another one: the engine locks the jobs of a type for
     * one duration per subscription.
     *
     * @param jobType The job type as the engine knows it
     * @param jobTimeout The <code>job-timeout</code> resolved for the task
     * @param bpmnProcessId The plain id of the task's process
     * @param taskDefinition The plain task definition of the task
     * @param adapterId The adapter id, for the keys the message names
     * @throws IllegalStateException Where another task of the job type has another
     *             <code>job-timeout</code>, naming both and the keys which set them
     */
    public void recordJobTimeout(
        final String jobType,
        final Duration jobTimeout,
        final String bpmnProcessId,
        final String taskDefinition,
        final String adapterId
    ) {
        final var recorded = jobTimeouts.putIfAbsent(jobType,
                new JobTimeout(jobTimeout, bpmnProcessId, taskDefinition));
        if ((recorded == null) || recorded.duration.equals(jobTimeout)) {
            return;
        }
        throw new IllegalStateException("""
                Job type '%s' of workflow module '%s' is served by two tasks with different job-timeouts: %s for \
                task '%s' of process '%s' and %s for task '%s' of process '%s'. The engine locks the jobs of a type \
                for one duration, so give both tasks the same job-timeout, for example:
                  vanillabp.workflow-modules.%s.workflows.%s.tasks.%s.adapters.%s.job-timeout
                  vanillabp.workflow-modules.%s.workflows.%s.tasks.%s.adapters.%s.job-timeout"""
                .formatted(jobType, workflowModuleId, recorded.duration, recorded.taskDefinition,
                        recorded.bpmnProcessId, jobTimeout, taskDefinition, bpmnProcessId, workflowModuleId,
                        recorded.bpmnProcessId, recorded.taskDefinition, adapterId, workflowModuleId, bpmnProcessId,
                        taskDefinition, adapterId));
    }

    /**
     * The <code>job-timeout</code> of each job type of the workflow module's claimed processes, the
     * duration its subscription asks the engine to lock a job for. A type no task recorded one for
     * has the default, {@value ZenBpmAdapterConfiguration#DEFAULT_JOB_TIMEOUT_ISO}.
     *
     * @return The durations by job type, in the order of {@link #jobTypes()}
     */
    public Map<String, Duration> jobTimeouts() {
        final var byJobType = new LinkedHashMap<String, Duration>();
        jobTypes().forEach(jobType -> byJobType.put(jobType, Optional
                .ofNullable(jobTimeouts.get(jobType))
                .map(JobTimeout::duration)
                .orElse(ZenBpmAdapterConfiguration.DEFAULT_JOB_TIMEOUT)));
        return Collections.unmodifiableMap(byJobType);
    }

    /**
     * Remembers that the workflow processing of the module started: the job types it subscribes
     * and the registration of the hook which stops it where the client factory closes first.
     *
     * @param started What started
     */
    public synchronized void processingStarted(final StartedProcessing started) {
        startedProcessing = started;
    }

    /**
     * Forgets that the workflow processing of the module started, and says what has to be stopped.
     * Only the first call after a start answers, so a module is stopped once, whoever stops it.
     *
     * @return What started, empty where nothing started or it was stopped already
     */
    public synchronized Optional<StartedProcessing> processingStopped() {
        final var started = Optional.ofNullable(startedProcessing);
        startedProcessing = null;
        return started;
    }

    /**
     * What the start of the workflow processing of a module opened.
     *
     * @param jobTypes The job types the module subscribes, as the engine knows them
     * @param registration What removes the hook which stops the module where the client factory
     *            closes before the module stopped
     */
    public record StartedProcessing(
        Set<String> jobTypes,
        WorkflowModuleShutdownRegistration registration
    ) {

        /**
         * Copies the job types, so what started stays what it was.
         */
        public StartedProcessing {
            jobTypes = Collections.unmodifiableSet(new LinkedHashSet<>(jobTypes));
        }

    }

    /**
     * The <code>job-timeout</code> of a job type, with the first task it was resolved for.
     */
    private record JobTimeout(
        Duration duration,
        String bpmnProcessId,
        String taskDefinition
    ) {
    }

    /**
     * What wiring found out about one claimed process, or what serves the workflows of a process id
     * a workflow service declares without bringing a model for it.
     *
     * @param bpmnProcessId The id of the process as the modeller wrote it
     * @param scopedBpmnProcessId The id under which the engine holds it
     * @param jobTypes The job types of its tasks which this application serves, as the engine knows
     *            them; user tasks and the tasks the application says something else serves are left
     *            out
     * @param reportsItsStart Whether the start of one of its instances is the start of a workflow
     *            somebody listens to: <code>false</code> for a called process
     * @param reportsItsEnd Whether the application wants to hear about the end of its workflows
     * @param startMarkers The start events the deployed model gives a start marker, each with what
     *            triggers it, see {@link ZenBpmMarkerTasks}; empty where the model gives none, which
     *            it does where the process starts no workflow of its own, or where the module
     *            brings no model for it
     * @param endMarkers The end events the deployed model gives an end marker, each with how much of
     *            the workflow it ends, see {@link ZenBpmMarkerTasks}; empty where the model gives
     *            none, which it does where nobody listens to the end of its workflows, or where the
     *            module brings no model for it
     * @param userTaskTypes The job types of its user tasks whose notifications the application
     *            wants, as the engine knows them, each with the task definition the application knows
     *            it by; a user task nobody is notified about is left out, and so is every one where the
     *            module brings no model and no method names its task definition
     */
    public record WiredProcess(
        String bpmnProcessId,
        String scopedBpmnProcessId,
        Set<String> jobTypes,
        boolean reportsItsStart,
        boolean reportsItsEnd,
        Map<String, BpmsStartTrigger.Kind> startMarkers,
        Map<String, ZenBpmMarkerTasks.Ending> endMarkers,
        Map<String, String> userTaskTypes
    ) {

        /**
         * Copies the job types, the markers and the user-task types, so what was found stays what
         * it was.
         */
        public WiredProcess {
            jobTypes = Collections.unmodifiableSet(new LinkedHashSet<>(jobTypes));
            startMarkers = Collections.unmodifiableMap(new LinkedHashMap<>(startMarkers));
            endMarkers = Collections.unmodifiableMap(new LinkedHashMap<>(endMarkers));
            userTaskTypes = Collections.unmodifiableMap(new LinkedHashMap<>(userTaskTypes));
        }

        /**
         * The job type of the process's start markers.
         *
         * @return The job type as the engine knows it, empty where the model gives no start event a
         *         marker
         */
        public Optional<String> startMarkerJobType() {
            if (startMarkers.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(ZenBpmMarkerTasks.startMarkerJobType(scopedBpmnProcessId));
        }

        /**
         * The job type of the process's end markers, whatever the deployed model gives a marker, see
         * {@link ZenBpmProcessingContext#jobTypes()}.
         *
         * @return The job type as the engine knows it
         */
        public String endMarkerJobType() {
            return ZenBpmMarkerTasks.endMarkerJobType(scopedBpmnProcessId);
        }

    }

}

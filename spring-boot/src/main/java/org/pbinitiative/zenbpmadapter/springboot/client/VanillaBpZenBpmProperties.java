/*
 * Copyright 2026 Phactum Softwareentwicklung GmbH
 * Adapted for zenbpm-vanillabp-adapter from vanillabp/camunda8-adapter, see NOTICE.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.pbinitiative.zenbpmadapter.springboot.client;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

import org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The ZenBPM adapter's overlay of the shared <code>vanillabp.*</code> configuration tree: a second
 * view of the tree beside the platform's own binding, which reads only the keys of this adapter.
 * <p>
 * The keys of an adapter id live at <code>vanillabp.adapters.&lt;id&gt;.*</code>
 * ({@link ZenBpmAdapterKeys}). Three of them may also be set below the adapter level, and the most
 * specific level wins for a task (task &gt; workflow &gt; workflow module &gt; adapter):
 * <ul>
 * <li><code>retry-backoff</code> and <code>max-redeliveries</code> at
 * <code>vanillabp.workflow-modules.&lt;module&gt;.adapters.&lt;id&gt;.*</code>,
 * <code>vanillabp.workflow-modules.&lt;module&gt;.workflows.&lt;process&gt;.adapters.&lt;id&gt;.*</code>
 * and
 * <code>vanillabp.workflow-modules.&lt;module&gt;.workflows.&lt;process&gt;.tasks.&lt;task&gt;.adapters.&lt;id&gt;.*</code>;</li>
 * <li><code>history-time-to-live</code> at the workflow module and the workflow, because it is sent
 * with the start of a workflow and no task has one of its own.</li>
 * </ul>
 * Spring Boot ignores a key neither view knows, so a typo under
 * <code>vanillabp.adapters.&lt;id&gt;.</code> changes nothing and fails nothing: a misspelt
 * <code>rest-adress</code> leaves the adapter id unconfigured, and the start warns about the
 * missing <code>rest-address</code>. That is the difference to Quarkus, where every key under
 * <code>vanillabp.</code> is modelled and an unknown one fails the start, as long as no extension
 * of the application declares a build-time configuration root under <code>vanillabp</code>; the
 * Camunda 8 adapter declares one.
 * <p>
 * The adapter ids are NEVER taken from this overlay's maps. They come from the platform's core
 * properties, and the overlay is only asked about an id the core knows: an environment variable can
 * create a map entry here for an id which does not exist.
 */
@ConfigurationProperties("vanillabp")
public class VanillaBpZenBpmProperties {

    private Map<String, ZenBpmAdapterKeys> adapters = Map.of();

    private Map<String, ModuleOverlay> workflowModules = Map.of();

    /**
     * Bound by Spring Boot, which builds the overlay with this constructor and then calls the
     * setters.
     */
    public VanillaBpZenBpmProperties() {
    }

    /**
     * The adapter sections of the shared tree.
     *
     * @return The sections, keyed by adapter id, never <code>null</code>
     */
    public Map<String, ZenBpmAdapterKeys> getAdapters() {
        return adapters;
    }

    /**
     * The adapter sections of the shared tree.
     *
     * @param adapters The sections, keyed by adapter id
     */
    public void setAdapters(final Map<String, ZenBpmAdapterKeys> adapters) {
        this.adapters = adapters;
    }

    /**
     * The workflow-module sections of the shared tree.
     *
     * @return The sections, keyed by workflow module id, never <code>null</code>
     */
    public Map<String, ModuleOverlay> getWorkflowModules() {
        return workflowModules;
    }

    /**
     * The workflow-module sections of the shared tree.
     *
     * @param workflowModules The sections, keyed by workflow module id
     */
    public void setWorkflowModules(final Map<String, ModuleOverlay> workflowModules) {
        this.workflowModules = workflowModules;
    }

    /**
     * The core configuration of one adapter id: its keys at the adapter level, and every value it
     * sets below that level handed in for the validation at startup.
     *
     * @param adapterId An adapter id the platform's core properties name
     * @return A new configuration, with every key unset where the application wrote nothing
     */
    public ZenBpmAdapterConfiguration configurationOf(final String adapterId) {
        final var configuration = new ZenBpmAdapterConfiguration();
        final var adapterKeys = adapters.get(adapterId);
        if (adapterKeys != null) {
            adapterKeys.applyTo(configuration);
        }
        workflowModules.forEach((
            moduleId,
            module) -> {
            final var modulePrefix = "vanillabp.workflow-modules.%s".formatted(moduleId);
            addScopedValues(configuration, "%s.adapters.%s".formatted(modulePrefix, adapterId), module
                    .getAdapters()
                    .get(adapterId));
            module
                    .getWorkflows()
                    .forEach((
                        bpmnProcessId,
                        workflow) -> {
                        final var workflowPrefix = "%s.workflows.%s".formatted(modulePrefix, bpmnProcessId);
                        addScopedValues(configuration, "%s.adapters.%s".formatted(workflowPrefix, adapterId), workflow
                                .getAdapters()
                                .get(adapterId));
                        workflow
                                .getTasks()
                                .forEach((
                                    taskId,
                                    task) -> addScopedValues(configuration, "%s.tasks.%s.adapters.%s".formatted(
                                            workflowPrefix, taskId, adapterId),
                                            task
                                                    .getAdapters()
                                                    .get(adapterId)));
                    });
        });
        return configuration;
    }

    /**
     * The first wait after a failed handler of a task: the most specific level which sets
     * <code>retry-backoff</code>, else the adapter's, else the default.
     *
     * @param workflowModuleId The workflow module the task belongs to
     * @param bpmnProcessId The BPMN process the task belongs to
     * @param taskId The task, as the BPMN model names its job type
     * @param adapterId The adapter id whose keys are read
     * @return The wait, never <code>null</code>
     */
    public Duration retryBackoffFor(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String taskId,
        final String adapterId
    ) {
        return mostSpecific(workflowModuleId, bpmnProcessId, taskId, adapterId, ZenBpmScopedKeys::getRetryBackoff)
                .or(() -> adapterLevel(adapterId, ZenBpmAdapterKeys::getRetryBackoff))
                .orElse(ZenBpmAdapterConfiguration.DEFAULT_RETRY_BACKOFF);
    }

    /**
     * After how many redeliveries a failing job of a task becomes an incident: the most specific
     * level which sets <code>max-redeliveries</code>, else the adapter's, else the default.
     *
     * @param workflowModuleId The workflow module the task belongs to
     * @param bpmnProcessId The BPMN process the task belongs to
     * @param taskId The task, as the BPMN model names its job type
     * @param adapterId The adapter id whose keys are read
     * @return The number
     */
    public int maxRedeliveriesFor(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String taskId,
        final String adapterId
    ) {
        return mostSpecific(workflowModuleId, bpmnProcessId, taskId, adapterId, ZenBpmScopedKeys::getMaxRedeliveries)
                .or(() -> adapterLevel(adapterId, ZenBpmAdapterKeys::getMaxRedeliveries))
                .orElse(ZenBpmAdapterConfiguration.DEFAULT_MAX_REDELIVERIES);
    }

    /**
     * How long the engine keeps the history of a workflow started for a process: the workflow's
     * <code>history-time-to-live</code>, else the workflow module's, else the adapter's.
     *
     * @param workflowModuleId The workflow module the process belongs to
     * @param bpmnProcessId The BPMN process
     * @param adapterId The adapter id whose keys are read
     * @return The time, or <code>null</code> where no level sets it and the engine's own default
     *         applies
     */
    public Duration historyTimeToLiveFor(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String adapterId
    ) {
        return workflowAndModuleLevel(workflowModuleId, bpmnProcessId, adapterId)
                .map(ZenBpmWorkflowScopedKeys::getHistoryTimeToLive)
                .filter(Objects::nonNull)
                .findFirst()
                .or(() -> adapterLevel(adapterId, ZenBpmAdapterKeys::getHistoryTimeToLive))
                .orElse(null);
    }

    private static void addScopedValues(
        final ZenBpmAdapterConfiguration configuration,
        final String prefix,
        final ZenBpmScopedKeys keys
    ) {
        if (keys == null) {
            return;
        }
        configuration.addScopedValue("%s.retry-backoff".formatted(prefix), keys.getRetryBackoff());
        configuration.addScopedValue("%s.max-redeliveries".formatted(prefix), keys.getMaxRedeliveries());
        if (keys instanceof ZenBpmWorkflowScopedKeys workflowKeys) {
            configuration.addScopedValue("%s.history-time-to-live".formatted(prefix), workflowKeys
                    .getHistoryTimeToLive());
        }
    }

    private <T> Optional<T> adapterLevel(
        final String adapterId,
        final Function<ZenBpmAdapterKeys, T> key
    ) {
        return Optional
                .ofNullable(adapters.get(adapterId))
                .map(key);
    }

    // task, workflow, workflow module: the first level which sets the key wins
    private <T> Optional<T> mostSpecific(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String taskId,
        final String adapterId,
        final Function<ZenBpmScopedKeys, T> key
    ) {
        return Stream
                .concat(Stream.ofNullable(taskLevel(workflowModuleId, bpmnProcessId, taskId, adapterId)),
                        workflowAndModuleLevel(workflowModuleId, bpmnProcessId, adapterId))
                .map(key)
                .filter(Objects::nonNull)
                .findFirst();
    }

    private ZenBpmScopedKeys taskLevel(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String taskId,
        final String adapterId
    ) {
        return Optional
                .ofNullable(workflowModules.get(workflowModuleId))
                .map(module -> module
                        .getWorkflows()
                        .get(bpmnProcessId))
                .map(workflow -> workflow
                        .getTasks()
                        .get(taskId))
                .map(task -> task
                        .getAdapters()
                        .get(adapterId))
                .orElse(null);
    }

    // the workflow first, then its module
    private Stream<ZenBpmWorkflowScopedKeys> workflowAndModuleLevel(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String adapterId
    ) {
        final var module = workflowModules.get(workflowModuleId);
        if (module == null) {
            return Stream.empty();
        }
        final var workflow = module
                .getWorkflows()
                .get(bpmnProcessId);
        return Stream
                .of(workflow == null ? null : workflow
                        .getAdapters()
                        .get(adapterId), module
                                .getAdapters()
                                .get(adapterId))
                .filter(Objects::nonNull);
    }

    /**
     * The keys of an adapter id which a task may set for itself.
     */
    public static class ZenBpmScopedKeys {

        private Duration retryBackoff;

        private Integer maxRedeliveries;

        /**
         * Bound by Spring Boot.
         */
        public ZenBpmScopedKeys() {
        }

        /**
         * <code>retry-backoff</code> at this level.
         *
         * @return The wait, or <code>null</code> where this level does not set it
         */
        public Duration getRetryBackoff() {
            return retryBackoff;
        }

        /**
         * <code>retry-backoff</code> at this level.
         *
         * @param retryBackoff The wait as bound
         */
        public void setRetryBackoff(final Duration retryBackoff) {
            this.retryBackoff = retryBackoff;
        }

        /**
         * <code>max-redeliveries</code> at this level.
         *
         * @return The number, or <code>null</code> where this level does not set it
         */
        public Integer getMaxRedeliveries() {
            return maxRedeliveries;
        }

        /**
         * <code>max-redeliveries</code> at this level.
         *
         * @param maxRedeliveries The number as bound
         */
        public void setMaxRedeliveries(final Integer maxRedeliveries) {
            this.maxRedeliveries = maxRedeliveries;
        }

    }

    /**
     * The keys of an adapter id which a workflow module or a workflow may set for itself: those of a
     * task, and <code>history-time-to-live</code>.
     */
    public static class ZenBpmWorkflowScopedKeys extends ZenBpmScopedKeys {

        private Duration historyTimeToLive;

        /**
         * Bound by Spring Boot.
         */
        public ZenBpmWorkflowScopedKeys() {
        }

        /**
         * <code>history-time-to-live</code> at this level.
         *
         * @return The time, or <code>null</code> where this level does not set it
         */
        public Duration getHistoryTimeToLive() {
            return historyTimeToLive;
        }

        /**
         * <code>history-time-to-live</code> at this level.
         *
         * @param historyTimeToLive The time as bound
         */
        public void setHistoryTimeToLive(final Duration historyTimeToLive) {
            this.historyTimeToLive = historyTimeToLive;
        }

    }

    /**
     * One section <code>vanillabp.workflow-modules.&lt;module&gt;</code>.
     */
    public static class ModuleOverlay {

        private Map<String, ZenBpmWorkflowScopedKeys> adapters = Map.of();

        private Map<String, WorkflowOverlay> workflows = Map.of();

        /**
         * Bound by Spring Boot.
         */
        public ModuleOverlay() {
        }

        /**
         * The keys of the workflow module per adapter id.
         *
         * @return The keys, keyed by adapter id, never <code>null</code>
         */
        public Map<String, ZenBpmWorkflowScopedKeys> getAdapters() {
            return adapters;
        }

        /**
         * The keys of the workflow module per adapter id.
         *
         * @param adapters The keys, keyed by adapter id
         */
        public void setAdapters(final Map<String, ZenBpmWorkflowScopedKeys> adapters) {
            this.adapters = adapters;
        }

        /**
         * The workflows of the workflow module.
         *
         * @return The sections, keyed by BPMN process id, never <code>null</code>
         */
        public Map<String, WorkflowOverlay> getWorkflows() {
            return workflows;
        }

        /**
         * The workflows of the workflow module.
         *
         * @param workflows The sections, keyed by BPMN process id
         */
        public void setWorkflows(final Map<String, WorkflowOverlay> workflows) {
            this.workflows = workflows;
        }

    }

    /**
     * One section <code>vanillabp.workflow-modules.&lt;module&gt;.workflows.&lt;process&gt;</code>.
     */
    public static class WorkflowOverlay {

        private Map<String, ZenBpmWorkflowScopedKeys> adapters = Map.of();

        private Map<String, TaskOverlay> tasks = Map.of();

        /**
         * Bound by Spring Boot.
         */
        public WorkflowOverlay() {
        }

        /**
         * The keys of the workflow per adapter id.
         *
         * @return The keys, keyed by adapter id, never <code>null</code>
         */
        public Map<String, ZenBpmWorkflowScopedKeys> getAdapters() {
            return adapters;
        }

        /**
         * The keys of the workflow per adapter id.
         *
         * @param adapters The keys, keyed by adapter id
         */
        public void setAdapters(final Map<String, ZenBpmWorkflowScopedKeys> adapters) {
            this.adapters = adapters;
        }

        /**
         * The tasks of the workflow.
         *
         * @return The sections, keyed by task id, never <code>null</code>
         */
        public Map<String, TaskOverlay> getTasks() {
            return tasks;
        }

        /**
         * The tasks of the workflow.
         *
         * @param tasks The sections, keyed by task id
         */
        public void setTasks(final Map<String, TaskOverlay> tasks) {
            this.tasks = tasks;
        }

    }

    /**
     * One section
     * <code>vanillabp.workflow-modules.&lt;module&gt;.workflows.&lt;process&gt;.tasks.&lt;task&gt;</code>.
     */
    public static class TaskOverlay {

        private Map<String, ZenBpmScopedKeys> adapters = Map.of();

        /**
         * Bound by Spring Boot.
         */
        public TaskOverlay() {
        }

        /**
         * The keys of the task per adapter id.
         *
         * @return The keys, keyed by adapter id, never <code>null</code>
         */
        public Map<String, ZenBpmScopedKeys> getAdapters() {
            return adapters;
        }

        /**
         * The keys of the task per adapter id.
         *
         * @param adapters The keys, keyed by adapter id
         */
        public void setAdapters(final Map<String, ZenBpmScopedKeys> adapters) {
            this.adapters = adapters;
        }

    }

}

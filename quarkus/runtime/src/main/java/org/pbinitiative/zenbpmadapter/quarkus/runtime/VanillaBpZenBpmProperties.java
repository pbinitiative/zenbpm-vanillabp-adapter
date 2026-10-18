/*
 * Copyright 2026 Phactum Softwareentwicklung GmbH
 * Adapted for zenbpm-vanillabp-adapter from vanillabp/camunda8-adapter, see NOTICE.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.pbinitiative.zenbpmadapter.quarkus.runtime;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

import org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration;
import org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration.MessageStartLookup;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.quarkus.runtime.annotations.StaticInitSafe;
import io.smallrye.config.ConfigMapping;

/**
 * The ZenBPM adapter's overlay of the shared <code>vanillabp.*</code> configuration tree: a second
 * mapping of the tree beside the platform's own, which models only the keys of this adapter.
 * <p>
 * The keys of an adapter id live at <code>vanillabp.adapters.&lt;id&gt;.*</code>
 * ({@link ZenBpmAdapterKeys}). Three of them may also be set below the adapter level, and the most
 * specific level wins for a task (task &gt; workflow &gt; workflow module &gt; adapter):
 * <ul>
 * <li><code>job-timeout</code> and <code>retry-backoff</code> at
 * <code>vanillabp.workflow-modules.&lt;module&gt;.adapters.&lt;id&gt;.*</code>,
 * <code>vanillabp.workflow-modules.&lt;module&gt;.workflows.&lt;process&gt;.adapters.&lt;id&gt;.*</code>
 * and
 * <code>vanillabp.workflow-modules.&lt;module&gt;.workflows.&lt;process&gt;.tasks.&lt;task&gt;.adapters.&lt;id&gt;.*</code>;</li>
 * <li><code>history-time-to-live</code> at the workflow module and the workflow, because it is sent
 * with the start of a workflow and no task has one of its own.</li>
 * </ul>
 * <code>accept-unscoped-identifiers</code> may also be set at the workflow module, which wins over
 * the adapter level: the platform reports <code>name-clash-avoidance: none</code> once per workflow
 * module, so no level below it would mean anything.
 * SmallRye Config refuses a key under <code>vanillabp.</code> which no mapping knows, so a typo
 * under <code>vanillabp.adapters.&lt;id&gt;.</code> fails the start and names the key. That is
 * the difference to Spring Boot, which ignores such a key; this mapping is what makes the keys of
 * this adapter known to that check. The check is off for the whole <code>vanillabp.*</code> tree
 * where an extension of the application declares a build-time configuration root under
 * <code>vanillabp</code>, and the Camunda 8 adapter declares an empty one. Beside it, Quarkus only
 * warns that it ignores the key, and the start warns about the key the adapter misses, as on Spring
 * Boot.
 * <p>
 * The mapping is never injected: an injected mapping makes Quarkus check the
 * <code>vanillabp.*</code> tree while it initializes its static configuration, before the
 * platform's own mappings are registered, so every start fails with the platform's keys, such as
 * <code>vanillabp.prioritized-adapters</code>, named as unknown. It is read through
 * <code>ConfigProvider.getConfig().unwrap(SmallRyeConfig.class).getConfigMapping(...)</code>.
 * <p>
 * The adapter ids are NEVER taken from this mapping's maps. They come from the platform's core
 * properties, and the mapping is only asked about an id the core knows: an environment variable
 * can create a map entry here for an id which does not exist.
 */
@StaticInitSafe
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
@ConfigMapping(prefix = "vanillabp")
public interface VanillaBpZenBpmProperties {

    /**
     * The adapter sections of the shared tree.
     *
     * @return The sections, keyed by adapter id, empty where the application wrote none
     */
    Map<String, ZenBpmAdapterKeys> adapters();

    /**
     * The workflow-module sections of the shared tree.
     *
     * @return The sections, keyed by workflow module id, empty where the application wrote none
     */
    Map<String, ModuleOverlay> workflowModules();

    /**
     * The core configuration of one adapter id: its keys at the adapter level, and every value it
     * sets below that level handed in for the validation at startup.
     *
     * @param adapterId An adapter id the platform's core properties name
     * @return A new configuration, with every key unset where the application wrote nothing
     */
    default ZenBpmAdapterConfiguration configurationOf(final String adapterId) {
        final var configuration = new ZenBpmAdapterConfiguration();
        final var adapterKeys = adapters().get(adapterId);
        if (adapterKeys != null) {
            adapterKeys.applyTo(configuration);
        }
        workflowModules().forEach((
            moduleId,
            module) -> {
            final var modulePrefix = "vanillabp.workflow-modules.%s".formatted(moduleId);
            final var moduleKeys = module
                    .adapters()
                    .get(adapterId);
            addScopedValues(configuration, "%s.adapters.%s".formatted(modulePrefix, adapterId), moduleKeys);
            if (moduleKeys != null) {
                configuration.setAcceptUnscopedIdentifiers(moduleId, moduleKeys
                        .acceptUnscopedIdentifiers()
                        .orElse(null));
            }
            module
                    .workflows()
                    .forEach((
                        bpmnProcessId,
                        workflow) -> {
                        final var workflowPrefix = "%s.workflows.%s".formatted(modulePrefix, bpmnProcessId);
                        addScopedValues(configuration, "%s.adapters.%s".formatted(workflowPrefix, adapterId), workflow
                                .adapters()
                                .get(adapterId));
                        workflow
                                .tasks()
                                .forEach((
                                    taskId,
                                    task) -> addScopedValues(configuration, "%s.tasks.%s.adapters.%s".formatted(
                                            workflowPrefix, taskId, adapterId),
                                            task
                                                    .adapters()
                                                    .get(adapterId)));
                    });
        });
        return configuration;
    }

    /**
     * How long a job of a task stays locked to the node which received it: the most specific level
     * which sets <code>job-timeout</code>, else the adapter's, else the default.
     *
     * @param workflowModuleId The workflow module the task belongs to
     * @param bpmnProcessId The BPMN process the task belongs to
     * @param taskId The task, as the BPMN model names its job type
     * @param adapterId The adapter id whose keys are read
     * @return The duration, never <code>null</code>
     */
    default Duration jobTimeoutFor(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String taskId,
        final String adapterId
    ) {
        return mostSpecific(workflowModuleId, bpmnProcessId, taskId, adapterId, ZenBpmScopedKeys::jobTimeout)
                .or(() -> adapterLevel(adapterId, ZenBpmAdapterKeys::jobTimeout))
                .orElse(ZenBpmAdapterConfiguration.DEFAULT_JOB_TIMEOUT);
    }

    /**
     * How long the engine waits before it hands the job of a task out again after its handler
     * threw: the most specific level which sets <code>retry-backoff</code>, else the adapter's.
     *
     * @param workflowModuleId The workflow module the task belongs to
     * @param bpmnProcessId The BPMN process the task belongs to
     * @param taskId The task, as the BPMN model names its job type
     * @param adapterId The adapter id whose keys are read
     * @return The wait; empty where no level sets it, so the <code>retryBackoff</code> of the task's
     *         model applies, else the engine's default
     */
    default Optional<Duration> retryBackoffFor(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String taskId,
        final String adapterId
    ) {
        return mostSpecific(workflowModuleId, bpmnProcessId, taskId, adapterId, ZenBpmScopedKeys::retryBackoff)
                .or(() -> adapterLevel(adapterId, ZenBpmAdapterKeys::retryBackoff));
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
    default Duration historyTimeToLiveFor(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String adapterId
    ) {
        return workflowAndModuleLevel(workflowModuleId, bpmnProcessId, adapterId)
                .map(ZenBpmWorkflowScopedKeys::historyTimeToLive)
                .flatMap(Optional::stream)
                .findFirst()
                .or(() -> adapterLevel(adapterId, ZenBpmAdapterKeys::historyTimeToLive))
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
        configuration.addScopedValue("%s.job-timeout".formatted(prefix), keys
                .jobTimeout()
                .orElse(null));
        configuration.addScopedValue("%s.retry-backoff".formatted(prefix), keys
                .retryBackoff()
                .orElse(null));
        if (keys instanceof ZenBpmWorkflowScopedKeys workflowKeys) {
            configuration.addScopedValue("%s.history-time-to-live".formatted(prefix), workflowKeys
                    .historyTimeToLive()
                    .orElse(null));
        }
    }

    private <T> Optional<T> adapterLevel(
        final String adapterId,
        final Function<ZenBpmAdapterKeys, Optional<T>> key
    ) {
        return Optional
                .ofNullable(adapters().get(adapterId))
                .flatMap(key);
    }

    // task, workflow, workflow module: the first level which sets the key wins
    private <T> Optional<T> mostSpecific(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String taskId,
        final String adapterId,
        final Function<ZenBpmScopedKeys, Optional<T>> key
    ) {
        return Stream
                .concat(Stream.ofNullable(taskLevel(workflowModuleId, bpmnProcessId, taskId, adapterId)),
                        workflowAndModuleLevel(workflowModuleId, bpmnProcessId, adapterId))
                .map(key)
                .flatMap(Optional::stream)
                .findFirst();
    }

    private ZenBpmScopedKeys taskLevel(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String taskId,
        final String adapterId
    ) {
        return Optional
                .ofNullable(workflowModules().get(workflowModuleId))
                .map(module -> module
                        .workflows()
                        .get(bpmnProcessId))
                .map(workflow -> workflow
                        .tasks()
                        .get(taskId))
                .map(task -> task
                        .adapters()
                        .get(adapterId))
                .orElse(null);
    }

    // the workflow first, then its module
    private Stream<ZenBpmWorkflowScopedKeys> workflowAndModuleLevel(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String adapterId
    ) {
        final var module = workflowModules().get(workflowModuleId);
        if (module == null) {
            return Stream.empty();
        }
        final var workflow = module
                .workflows()
                .get(bpmnProcessId);
        return Stream.concat(Stream.ofNullable(workflow == null ? null : workflow
                .adapters()
                .get(adapterId)), Stream.ofNullable(module
                        .adapters()
                        .get(adapterId)));
    }

    /**
     * The keys of one ZenBPM adapter id at <code>vanillabp.adapters.&lt;id&gt;.*</code>: every key
     * empty where the application did not write it, so {@link #applyTo(ZenBpmAdapterConfiguration)}
     * hands the defaults to the core configuration instead of repeating them.
     * {@link ZenBpmAdapterConfiguration} documents what each key means, its default and how it is
     * validated.
     * <p>
     * The keys of the platform in the same section, such as <code>type</code> or
     * <code>deployment-failure</code>, are not modelled here; the platform's own mapping knows
     * them.
     */
    interface ZenBpmAdapterKeys {

        /**
         * <code>rest-address</code>: the address of the engine's REST API.
         *
         * @return The value, empty where the application did not set the key
         */
        Optional<String> restAddress();

        /**
         * <code>grpc-address</code>: <code>host:port</code> of the job stream.
         *
         * @return The value, empty where the application did not set the key
         */
        Optional<String> grpcAddress();

        /**
         * <code>grpc-plaintext</code>: whether the job stream goes without TLS.
         *
         * @return The value, empty where the application did not set the key
         */
        Optional<Boolean> grpcPlaintext();

        /**
         * <code>client-id</code>: the job stream's client id.
         *
         * @return The value, empty where the application did not set the key
         */
        Optional<String> clientId();

        /**
         * <code>request-timeout</code>: the deadline of every REST call.
         *
         * @return The value, empty where the application did not set the key
         */
        Optional<Duration> requestTimeout();

        /**
         * <code>startup-wait</code>: how long the first deployment waits for the engine to report
         * ready.
         *
         * @return The value, empty where the application did not set the key
         */
        Optional<Duration> startupWait();

        /**
         * <code>worker-threads</code>: how many platform threads run the handlers, or
         * <code>virtual</code>.
         *
         * @return The value, empty where the application did not set the key
         */
        Optional<String> workerThreads();

        /**
         * <code>worker-threads-bound</code>: how many handlers run at the same time on virtual
         * threads.
         *
         * @return The value, empty where the application did not set the key
         */
        Optional<Integer> workerThreadsBound();

        /**
         * <code>job-timeout</code>: how long a job stays locked to the node which received it, at
         * the adapter level.
         *
         * @return The value, empty where the application did not set the key
         */
        Optional<Duration> jobTimeout();

        /**
         * <code>async-task-lock-renewal</code>: how long the job of a task left open for its
         * asynchronous completion stays locked once its handler returned.
         *
         * @return The value, empty where the application did not set the key
         */
        Optional<Duration> asyncTaskLockRenewal();

        /**
         * <code>async-task-max-age-action</code>: what the adapter does with a task left open
         * longer than the platform's maximum age, mapped as written so the validation names a value
         * which is no action.
         *
         * @return The value, empty where the application did not set the key
         */
        Optional<String> asyncTaskMaxAgeAction();

        /**
         * <code>retry-backoff</code>: how long the engine waits before it hands the job of a
         * handler which threw out again, at the adapter level.
         *
         * @return The value, empty where the application did not set the key
         */
        Optional<Duration> retryBackoff();

        /**
         * <code>shutdown-grace</code>: how long a shutdown waits for the handlers in flight.
         *
         * @return The value, empty where the application did not set the key
         */
        Optional<Duration> shutdownGrace();

        /**
         * <code>workflow-visibility-timeout</code>: how long a started workflow may stay invisible
         * to a query.
         *
         * @return The value, empty where the application did not set the key
         */
        Optional<Duration> workflowVisibilityTimeout();

        /**
         * <code>user-task-poll-interval</code>: how often user tasks are looked for.
         *
         * @return The value, empty where the application did not set the key
         */
        Optional<Duration> userTaskPollInterval();

        /**
         * <code>history-time-to-live</code>: how long the engine keeps the history of a started
         * workflow, at the adapter level.
         *
         * @return The value, empty where the application did not set the key
         */
        Optional<Duration> historyTimeToLive();

        /**
         * <code>instance-cache-max-entries</code>: the bound of the cache of process instance
         * facts.
         *
         * @return The value, empty where the application did not set the key
         */
        Optional<Integer> instanceCacheMaxEntries();

        /**
         * <code>message-start-lookup</code>: how a workflow <code>startWorkflowByMessage</code>
         * started is found, <code>start-row</code>, <code>scan</code> or <code>refuse</code>.
         *
         * @return The value, empty where the application did not set the key
         */
        Optional<MessageStartLookup> messageStartLookup();

        /**
         * <code>message-start-lookup-max-instances</code>: how many instances one lookup of
         * <code>message-start-lookup: scan</code> reads at most.
         *
         * @return The value, empty where the application did not set the key
         */
        Optional<Integer> messageStartLookupMaxInstances();

        /**
         * <code>accept-unscoped-identifiers</code>: whether the identifiers of the workflow modules
         * are unique, so <code>name-clash-avoidance: none</code> is no reason to warn.
         *
         * @return The value, empty where the application did not set the key
         */
        Optional<Boolean> acceptUnscopedIdentifiers();

        /**
         * Copies every key into the core configuration of the adapter id. A key the application did
         * not write is handed on as <code>null</code>, which restores the core's default.
         *
         * @param configuration The core configuration of the adapter id
         */
        default void applyTo(final ZenBpmAdapterConfiguration configuration) {
            configuration.setRestAddress(restAddress().orElse(null));
            configuration.setGrpcAddress(grpcAddress().orElse(null));
            configuration.setGrpcPlaintext(grpcPlaintext().orElse(null));
            configuration.setClientId(clientId().orElse(null));
            configuration.setRequestTimeout(requestTimeout().orElse(null));
            configuration.setStartupWait(startupWait().orElse(null));
            configuration.setWorkerThreads(workerThreads().orElse(null));
            configuration.setWorkerThreadsBound(workerThreadsBound().orElse(null));
            configuration.setJobTimeout(jobTimeout().orElse(null));
            configuration.setAsyncTaskLockRenewal(asyncTaskLockRenewal().orElse(null));
            configuration.setAsyncTaskMaxAgeAction(asyncTaskMaxAgeAction().orElse(null));
            configuration.setRetryBackoff(retryBackoff().orElse(null));
            configuration.setShutdownGrace(shutdownGrace().orElse(null));
            configuration.setWorkflowVisibilityTimeout(workflowVisibilityTimeout().orElse(null));
            configuration.setUserTaskPollInterval(userTaskPollInterval().orElse(null));
            configuration.setHistoryTimeToLive(historyTimeToLive().orElse(null));
            configuration.setInstanceCacheMaxEntries(instanceCacheMaxEntries().orElse(null));
            configuration.setMessageStartLookup(messageStartLookup().orElse(null));
            configuration.setMessageStartLookupMaxInstances(messageStartLookupMaxInstances().orElse(null));
            configuration.setAcceptUnscopedIdentifiers(acceptUnscopedIdentifiers().orElse(null));
        }

    }

    /**
     * The keys of an adapter id which a task may set for itself.
     */
    interface ZenBpmScopedKeys {

        /**
         * <code>job-timeout</code> at this level.
         *
         * @return The duration, empty where this level does not set it
         */
        Optional<Duration> jobTimeout();

        /**
         * <code>retry-backoff</code> at this level.
         *
         * @return The wait, empty where this level does not set it
         */
        Optional<Duration> retryBackoff();

    }

    /**
     * The keys of an adapter id which a workflow module or a workflow may set for itself: those of a
     * task, and <code>history-time-to-live</code>.
     */
    interface ZenBpmWorkflowScopedKeys extends ZenBpmScopedKeys {

        /**
         * <code>history-time-to-live</code> at this level.
         *
         * @return The time, empty where this level does not set it
         */
        Optional<Duration> historyTimeToLive();

    }

    /**
     * The keys of an adapter id which a workflow module may set for itself: those of a workflow, and
     * <code>accept-unscoped-identifiers</code>.
     */
    interface ZenBpmModuleScopedKeys extends ZenBpmWorkflowScopedKeys {

        /**
         * <code>accept-unscoped-identifiers</code> at this level.
         *
         * @return The value, empty where this level does not set it
         */
        Optional<Boolean> acceptUnscopedIdentifiers();

    }

    /**
     * One section <code>vanillabp.workflow-modules.&lt;module&gt;</code>.
     */
    interface ModuleOverlay {

        /**
         * The keys of the workflow module per adapter id.
         *
         * @return The keys, keyed by adapter id
         */
        Map<String, ZenBpmModuleScopedKeys> adapters();

        /**
         * The workflows of the workflow module.
         *
         * @return The sections, keyed by BPMN process id
         */
        Map<String, WorkflowOverlay> workflows();

    }

    /**
     * One section <code>vanillabp.workflow-modules.&lt;module&gt;.workflows.&lt;process&gt;</code>.
     */
    interface WorkflowOverlay {

        /**
         * The keys of the workflow per adapter id.
         *
         * @return The keys, keyed by adapter id
         */
        Map<String, ZenBpmWorkflowScopedKeys> adapters();

        /**
         * The tasks of the workflow.
         *
         * @return The sections, keyed by task id
         */
        Map<String, TaskOverlay> tasks();

    }

    /**
     * One section
     * <code>vanillabp.workflow-modules.&lt;module&gt;.workflows.&lt;process&gt;.tasks.&lt;task&gt;</code>.
     */
    interface TaskOverlay {

        /**
         * The keys of the task per adapter id.
         *
         * @return The keys, keyed by adapter id
         */
        Map<String, ZenBpmScopedKeys> adapters();

    }

}

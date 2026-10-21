/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.client;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * The configuration of one ZenBPM adapter instance, keyed by adapter id. It does not depend on
 * a platform: the Spring Boot and the Quarkus module read their own configuration source and
 * fill one object per configured id through the setters. A setter given <code>null</code>
 * restores the default of its key, so a platform hands on what the application did not write
 * without knowing the defaults.
 * <p>
 * Every key lives at <code>vanillabp.adapters.&lt;adapter-id&gt;.*</code>:
 * <ul>
 * <li><code>rest-address</code> (required) - the engine's REST API, e.g.
 * <code>http://zenbpm:8080</code>; the adapter appends <code>/v1</code></li>
 * <li><code>grpc-address</code> (default: the host of <code>rest-address</code>, port
 * {@value #DEFAULT_GRPC_PORT}) - <code>host:port</code> of the job stream</li>
 * <li><code>grpc-plaintext</code> (default <code>true</code>) - <code>false</code> for TLS
 * through a proxy</li>
 * <li><code>client-id</code> (default <code>&lt;application name&gt;-&lt;adapter
 * id&gt;-&lt;8 random hex digits&gt;</code>) - the job stream's client id, unique per
 * node</li>
 * <li><code>request-timeout</code> (default {@value #DEFAULT_REQUEST_TIMEOUT_ISO}) - the
 * deadline of every REST call</li>
 * <li><code>startup-wait</code> (default {@value #DEFAULT_STARTUP_WAIT_ISO}) - how long the
 * first deployment waits for the engine to report ready; a request under way when it runs out
 * still ends at its own deadline, <code>request-timeout</code></li>
 * <li><code>worker-threads</code> (default {@value #DEFAULT_WORKER_THREADS}) - how many platform
 * threads run the handlers, or <code>virtual</code> for a virtual thread per handler</li>
 * <li><code>worker-threads-bound</code> (default {@value #DEFAULT_WORKER_THREADS}, only with
 * <code>virtual</code>) - how many handlers run at the same time on virtual threads</li>
 * <li><code>job-timeout</code> (default {@value #DEFAULT_JOB_TIMEOUT_ISO}) - how long a job stays
 * locked to the node which received it; the lock is renewed while its handler runs, and a job type
 * is subscribed with it</li>
 * <li><code>async-task-lock-renewal</code> (default {@value #DEFAULT_ASYNC_TASK_LOCK_RENEWAL_ISO}) -
 * how long the job of a task left open for its asynchronous completion stays locked once its
 * handler returned; it has to stay below <code>vanillabp.delivery.retention</code></li>
 * <li><code>async-task-max-age-action</code> (default <code>report</code>) - <code>incident</code>
 * fails the job of a task open longer than <code>vanillabp.delivery.max-task-age</code> without
 * retries, so the engine raises an incident</li>
 * <li><code>retry-backoff</code> (default: none) - how long the engine waits before it hands the
 * job of a handler which threw out again, sent with the failure in whole seconds; without the key
 * the <code>retryBackoff</code> of the task's model applies, else the engine's own default</li>
 * <li><code>shutdown-grace</code> (default {@value #DEFAULT_SHUTDOWN_GRACE_ISO}) - how long a
 * shutdown waits for the handlers in flight, all workflow modules of the adapter id together; one
 * above thirty seconds is warned about, because the runtime's shutdown budget has to grow with
 * it</li>
 * <li><code>workflow-visibility-timeout</code> (default
 * {@value #DEFAULT_WORKFLOW_VISIBILITY_TIMEOUT_ISO}) - how long a started workflow may stay
 * invisible to a query</li>
 * <li><code>user-task-poll-interval</code> (default
 * {@value #DEFAULT_USER_TASK_POLL_INTERVAL_ISO}) - how often user tasks are looked for;
 * <code>PT0S</code> switches the notifications of user tasks off, and both it and an interval below
 * one second are warned about, see decision 12 in the repository's DECISIONS.md</li>
 * <li><code>history-time-to-live</code> (default: none, the engine's own) - sent with every
 * start in whole seconds, so a value with a fraction of a second is refused, and so is one above
 * {@link ZenBpmTimeToLive#LONGEST}; <code>PT0S</code> has the engine remove an ended workflow at
 * its next clean-up, see decision 31 in the repository's DECISIONS.md</li>
 * <li><code>instance-cache-max-entries</code> (default
 * {@value #DEFAULT_INSTANCE_CACHE_MAX_ENTRIES}) - the bound of the cache of process instance
 * facts, and of the iterations of multi-instance bodies each workflow module keeps; <code>0</code>
 * keeps none</li>
 * <li><code>message-start-lookup</code> (default <code>start-row</code>) - how a workflow which
 * <code>startWorkflowByMessage</code> started is found: <code>start-row</code> through the instance
 * key its start marker wrote into the start row, <code>scan</code> in addition by reading the
 * running instances of its process, and <code>refuse</code> starts no workflow by a message</li>
 * <li><code>message-start-lookup-max-instances</code> (default
 * {@value #DEFAULT_MESSAGE_START_LOOKUP_MAX_INSTANCES}) - how many instances one lookup of
 * <code>scan</code> reads at most; at least one</li>
 * <li><code>accept-unscoped-identifiers</code> (default <code>false</code>) - <code>true</code>
 * says that the identifiers of the workflow modules are unique, so the warning about
 * <code>name-clash-avoidance: none</code> becomes a DEBUG line</li>
 * </ul>
 * <code>job-timeout</code> and <code>retry-backoff</code> may also be set per workflow module,
 * workflow or task, <code>history-time-to-live</code> per workflow module or workflow, and
 * <code>accept-unscoped-identifiers</code> per workflow module, which
 * {@link #setAcceptUnscopedIdentifiers(String, Boolean)} hands in.
 * This class holds their values at the adapter level. A value set at a lower level is handed in
 * through {@link #addScopedValue(String, Duration)}, so the validation checks it at startup like
 * the adapter's own; which level wins for a task is the platform's to resolve.
 * <p>
 * The platforms copy their own configuration classes into this one; it is not bound directly.
 * Some getters answer a primitive where the setter takes <code>null</code> for the default, so a
 * JavaBeans binder would see read-only properties.
 * <p>
 * {@link #validate(String, boolean, boolean)} checks every key at startup. The derivations
 * ({@link #grpcAddressInUse()}, {@link #clientIdInUse(String, Supplier)},
 * {@link #restBaseUri()}, {@link #instanceIdentity()}, {@link #workerThreadsBoundInUse()}) are
 * defined for a configuration it found complete.
 * <p>
 * Each field has an accessor of its own and no Lombok getter on the class, see decision 13 in
 * the repository's DECISIONS.md.
 */
@SuppressWarnings("LombokGetterMayBeUsed")
public class ZenBpmAdapterConfiguration {

    /**
     * The prefix every key of an adapter instance lives under, followed by the adapter id.
     */
    public static final String CONFIGURATION_PREFIX = "vanillabp.adapters";

    /**
     * The port of the job stream where <code>grpc-address</code> is not set: the engine's
     * default gRPC port.
     */
    public static final int DEFAULT_GRPC_PORT = 9090;

    /**
     * The path of the engine's API version, appended to <code>rest-address</code>.
     */
    public static final String API_VERSION_PATH = "/v1";

    /**
     * What a finding of the validation calls the adapter id, which the startup report shows as the
     * finding's scope, see decision 60 in the repository's DECISIONS.md.
     */
    static final String THE_ADAPTER_IN_A_FINDING = "This adapter";

    /**
     * The default of <code>request-timeout</code> as written in a configuration.
     */
    public static final String DEFAULT_REQUEST_TIMEOUT_ISO = "PT10S";

    /**
     * The default of <code>request-timeout</code>.
     */
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.parse(DEFAULT_REQUEST_TIMEOUT_ISO);

    /**
     * The shortest <code>request-timeout</code> accepted. Below it a healthy engine answers too
     * late often enough to look like a network problem.
     */
    public static final Duration MINIMUM_REQUEST_TIMEOUT = Duration.ofSeconds(1);

    /**
     * The default of <code>startup-wait</code> as written in a configuration.
     */
    public static final String DEFAULT_STARTUP_WAIT_ISO = "PT10M";

    /**
     * The default of <code>startup-wait</code>.
     */
    public static final Duration DEFAULT_STARTUP_WAIT = Duration.parse(DEFAULT_STARTUP_WAIT_ISO);

    /**
     * The default of <code>worker-threads</code>: four platform threads. Every running handler
     * holds a database connection, so the number is sized against the usual connection pools,
     * not the CPU.
     */
    public static final String DEFAULT_WORKER_THREADS = "4";

    /**
     * The literal <code>worker-threads</code> accepts besides a positive number.
     */
    public static final String VIRTUAL_WORKER_THREADS = "virtual";

    /**
     * The default of <code>job-timeout</code> as written in a configuration.
     */
    public static final String DEFAULT_JOB_TIMEOUT_ISO = "PT5M";

    /**
     * The default of <code>job-timeout</code>.
     */
    public static final Duration DEFAULT_JOB_TIMEOUT = Duration.parse(DEFAULT_JOB_TIMEOUT_ISO);

    /**
     * The default of <code>async-task-lock-renewal</code> as written in a configuration.
     */
    public static final String DEFAULT_ASYNC_TASK_LOCK_RENEWAL_ISO = "PT1H";

    /**
     * The default of <code>async-task-lock-renewal</code>.
     */
    public static final Duration DEFAULT_ASYNC_TASK_LOCK_RENEWAL = Duration.parse(
            DEFAULT_ASYNC_TASK_LOCK_RENEWAL_ISO);

    /**
     * The default of <code>shutdown-grace</code> as written in a configuration.
     */
    public static final String DEFAULT_SHUTDOWN_GRACE_ISO = "PT20S";

    /**
     * The default of <code>shutdown-grace</code>.
     */
    public static final Duration DEFAULT_SHUTDOWN_GRACE = Duration.parse(DEFAULT_SHUTDOWN_GRACE_ISO);

    /**
     * The longest <code>shutdown-grace</code> which fits into the time Kubernetes grants a pod to stop
     * by default, <code>terminationGracePeriodSeconds</code> of thirty seconds. A longer one is warned
     * about at startup.
     */
    public static final Duration LONGEST_SHUTDOWN_GRACE_WITHIN_THE_USUAL_BUDGETS = Duration.ofSeconds(30);

    /**
     * The default of <code>workflow-visibility-timeout</code> as written in a configuration.
     */
    public static final String DEFAULT_WORKFLOW_VISIBILITY_TIMEOUT_ISO = "PT2S";

    /**
     * The default of <code>workflow-visibility-timeout</code>.
     */
    public static final Duration DEFAULT_WORKFLOW_VISIBILITY_TIMEOUT = Duration
            .parse(DEFAULT_WORKFLOW_VISIBILITY_TIMEOUT_ISO);

    /**
     * The default of <code>user-task-poll-interval</code> as written in a configuration.
     */
    public static final String DEFAULT_USER_TASK_POLL_INTERVAL_ISO = "PT5S";

    /**
     * The default of <code>user-task-poll-interval</code>.
     */
    public static final Duration DEFAULT_USER_TASK_POLL_INTERVAL = Duration.parse(DEFAULT_USER_TASK_POLL_INTERVAL_ISO);

    /**
     * The shortest <code>user-task-poll-interval</code> above zero which the start does not warn
     * about: a shorter one asks the engine almost without pause.
     */
    public static final Duration SHORTEST_USER_TASK_POLL_INTERVAL_WITHOUT_A_WARNING = Duration.ofSeconds(1);

    /**
     * The default of <code>instance-cache-max-entries</code>.
     */
    public static final int DEFAULT_INSTANCE_CACHE_MAX_ENTRIES = 10_000;

    /**
     * The default of <code>message-start-lookup-max-instances</code>.
     */
    public static final int DEFAULT_MESSAGE_START_LOOKUP_MAX_INSTANCES = 1_000;

    /**
     * The application name a derived <code>client-id</code> starts with where the platform
     * knows none.
     */
    public static final String DEFAULT_APPLICATION_NAME = "application";

    /**
     * How a workflow <code>startWorkflowByMessage</code> started is found. The engine gives such
     * a workflow no business key, so no search by the aggregate's id finds it.
     */
    public enum MessageStartLookup {

        /**
         * A workflow is started by a message, and found through the instance key its start
         * marker writes into the start row of its aggregate, and through nothing else. The
         * default.
         */
        START_ROW,

        /**
         * As {@link #START_ROW}, and where the start row does not know the workflow, the probes
         * read the running instances of its process to find the one whose id variable names the
         * aggregate, at most <code>message-start-lookup-max-instances</code> of them.
         */
        SCAN,

        /**
         * No workflow is started by a message: <code>startWorkflowByMessage</code> is refused
         * with a message naming the key which allows it.
         */
        REFUSE;

        /**
         * The value as a configuration writes it.
         *
         * @return E.g. <code>start-row</code>
         */
        public String configured() {
            return name()
                    .toLowerCase(Locale.ROOT)
                    .replace('_', '-');
        }

    }

    /**
     * What the adapter does with a task left open for its asynchronous completion longer than
     * <code>vanillabp.delivery.max-task-age</code> allows, beside the line the platform's core
     * logs about it.
     */
    public enum AsyncTaskMaxAgeAction {

        /**
         * Nothing beyond the core's line: the job stays locked and is renewed as before. The
         * default.
         */
        REPORT,

        /**
         * The job is failed without retries, so the engine raises an incident which names the
         * aggregate and the age.
         */
        INCIDENT;

        /**
         * The value as a configuration writes it.
         *
         * @return E.g. <code>report</code>
         */
        public String configured() {
            return name().toLowerCase(Locale.ROOT);
        }

    }

    /**
     * What {@link #validate(String, boolean, boolean)} found.
     *
     * @param kind Whether the adapter instance is unconfigured, inconsistent or complete
     * @param message The guiding message, naming the adapter id and the keys concerned, which every
     *          use of an adapter id without clients fails with; empty where complete
     * @param finding The same message as the startup report shows it under the adapter id's scope:
     *          without the adapter id as its subject; empty where complete
     * @param endsTheBoot Whether the platform has to end the boot with the message instead of
     *          logging it
     * @param notices Findings worth a notice at startup, whatever the kind, without the adapter id
     *          as their subject
     * @param warnings Findings worth a warning at startup, whatever the kind: values which work but
     *          hold a risk the application has to deal with
     */
    public record ValidationResult(
        Kind kind,
        String message,
        String finding,
        boolean endsTheBoot,
        List<String> notices,
        List<String> warnings
    ) {

        /**
         * The three outcomes of a validation.
         */
        public enum Kind {

            /**
             * No <code>rest-address</code>, no other key without a default, and nothing wrong. The
             * platform lets the application boot and logs the message as a warning.
             */
            UNCONFIGURED,

            /**
             * At least one key holds a value which cannot work, or the configuration was started
             * without <code>rest-address</code>. {@link ValidationResult#endsTheBoot()} says whether
             * the platform ends the boot or boots degraded.
             */
            INCONSISTENT,

            /**
             * Every key is usable. The client may be built.
             */
            COMPLETE

        }

        /**
         * Copies the notices and the warnings, so a result cannot change after it was handed out.
         *
         * @param kind Whether the adapter instance is unconfigured, inconsistent or complete
         * @param message The guiding message, naming the adapter id and the keys concerned
         * @param finding The same message without the adapter id as its subject
         * @param endsTheBoot Whether the platform has to end the boot with the message
         * @param notices Findings worth a notice at startup
         * @param warnings Findings worth a warning at startup
         */
        public ValidationResult {
            notices = List.copyOf(notices);
            warnings = List.copyOf(warnings);
        }

    }

    private String restAddress;

    private String grpcAddress;

    private boolean grpcPlaintext = true;

    private String clientId;

    private Duration requestTimeout = DEFAULT_REQUEST_TIMEOUT;

    private Duration startupWait = DEFAULT_STARTUP_WAIT;

    private String workerThreads = DEFAULT_WORKER_THREADS;

    private Integer workerThreadsBound;

    private Duration jobTimeout = DEFAULT_JOB_TIMEOUT;

    private Duration asyncTaskLockRenewal = DEFAULT_ASYNC_TASK_LOCK_RENEWAL;

    /**
     * As the application wrote it, so a value which names no action is refused by the validation
     * with the values there are; <code>null</code> for the default.
     */
    private String asyncTaskMaxAgeAction;

    private Duration retryBackoff;

    private Duration shutdownGrace = DEFAULT_SHUTDOWN_GRACE;

    private Duration workflowVisibilityTimeout = DEFAULT_WORKFLOW_VISIBILITY_TIMEOUT;

    private Duration userTaskPollInterval = DEFAULT_USER_TASK_POLL_INTERVAL;

    private Duration historyTimeToLive;

    private int instanceCacheMaxEntries = DEFAULT_INSTANCE_CACHE_MAX_ENTRIES;

    private MessageStartLookup messageStartLookup = MessageStartLookup.START_ROW;

    private int messageStartLookupMaxInstances = DEFAULT_MESSAGE_START_LOOKUP_MAX_INSTANCES;

    private boolean acceptUnscopedIdentifiers;

    /**
     * <code>accept-unscoped-identifiers</code> of the workflow modules which set it, by workflow
     * module id.
     */
    private final Map<String, Boolean> acceptUnscopedIdentifiersOfModules = new LinkedHashMap<>();

    /**
     * The durations set below the adapter level, keyed by their full property key, in the order
     * they were handed in.
     */
    private final Map<String, Duration> scopedDurations = new LinkedHashMap<>();

    // drawn once, so every call of clientIdInUse answers the same id for this adapter instance.
    // It keeps two nodes of one application apart, which is all it is for: it guards nothing
    private final String clientIdSuffix = HexFormat
            .of()
            .toHexDigits(ThreadLocalRandom.current().nextInt());

    /**
     * Opens an adapter section with the default of every key.
     */
    public ZenBpmAdapterConfiguration() {
    }

    /**
     * The full property key of one key of an adapter instance, as messages name it.
     *
     * @param adapterId The adapter id
     * @param key The key relative to the adapter's section, e.g. <code>rest-address</code>
     * @return E.g. <code>vanillabp.adapters.zenbpm.rest-address</code>
     */
    public static String propertyKey(
        final String adapterId,
        final String key
    ) {
        return "%s.%s.%s".formatted(CONFIGURATION_PREFIX, adapterId, key);
    }

    /**
     * The address of the engine's REST API.
     *
     * @return What the application wrote, or <code>null</code>
     */
    public String getRestAddress() {
        return restAddress;
    }

    /**
     * The address of the engine's REST API.
     *
     * @param restAddress E.g. <code>http://zenbpm:8080</code>, or <code>null</code>
     */
    public void setRestAddress(final String restAddress) {
        this.restAddress = restAddress;
    }

    /**
     * The address of the engine's job stream as written, see {@link #grpcAddressInUse()} for
     * the one used.
     *
     * @return <code>host:port</code>, or <code>null</code> where it is derived
     */
    public String getGrpcAddress() {
        return grpcAddress;
    }

    /**
     * The address of the engine's job stream.
     *
     * @param grpcAddress <code>host:port</code>, or <code>null</code> to derive it
     */
    public void setGrpcAddress(final String grpcAddress) {
        this.grpcAddress = grpcAddress;
    }

    /**
     * Whether the job stream is opened without TLS.
     *
     * @return <code>true</code> by default
     */
    public boolean isGrpcPlaintext() {
        return grpcPlaintext;
    }

    /**
     * Whether the job stream is opened without TLS.
     *
     * @param grpcPlaintext <code>false</code> for TLS, <code>null</code> for the default
     */
    public void setGrpcPlaintext(final Boolean grpcPlaintext) {
        this.grpcPlaintext = grpcPlaintext == null || grpcPlaintext;
    }

    /**
     * The client id of the job stream as written, see {@link #clientIdInUse(String, Supplier)}
     * for the one used.
     *
     * @return What the application wrote, or <code>null</code> where it is derived
     */
    public String getClientId() {
        return clientId;
    }

    /**
     * The client id of the job stream.
     *
     * @param clientId An id unique per node, or <code>null</code> to derive it
     */
    public void setClientId(final String clientId) {
        this.clientId = clientId;
    }

    /**
     * The deadline of every REST call.
     *
     * @return Never <code>null</code>
     */
    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    /**
     * The deadline of every REST call.
     *
     * @param requestTimeout The deadline, or <code>null</code> for the default
     */
    public void setRequestTimeout(final Duration requestTimeout) {
        this.requestTimeout = requestTimeout == null ? DEFAULT_REQUEST_TIMEOUT : requestTimeout;
    }

    /**
     * How long the first deployment waits for the engine to report ready.
     *
     * @return Never <code>null</code>
     */
    public Duration getStartupWait() {
        return startupWait;
    }

    /**
     * How long the first deployment waits for the engine to report ready.
     *
     * @param startupWait The wait, or <code>null</code> for the default
     */
    public void setStartupWait(final Duration startupWait) {
        this.startupWait = startupWait == null ? DEFAULT_STARTUP_WAIT : startupWait;
    }

    /**
     * How many platform threads run the handlers, or <code>virtual</code>.
     *
     * @return What the application wrote, or the default
     */
    public String getWorkerThreads() {
        return workerThreads;
    }

    /**
     * How many platform threads run the handlers, or <code>virtual</code>.
     *
     * @param workerThreads A positive number or <code>virtual</code>; <code>null</code> or blank
     *          for the default, as an empty key binds to an empty text
     */
    public void setWorkerThreads(final String workerThreads) {
        this.workerThreads = isBlank(workerThreads) ? DEFAULT_WORKER_THREADS : workerThreads;
    }

    /**
     * How many handlers may run at the same time on virtual threads, as written. See
     * {@link #workerThreadsBoundInUse()} for the bound in use.
     *
     * @return The bound, or <code>null</code> where the key is not set
     */
    public Integer getWorkerThreadsBound() {
        return workerThreadsBound;
    }

    /**
     * How many handlers may run at the same time. With platform threads it is their number, and
     * <code>worker-threads-bound</code> may not be set. Virtual threads have no limit of their
     * own, so there it is the key, or {@value #DEFAULT_WORKER_THREADS} where it is not set.
     *
     * @return The bound in use
     */
    public int workerThreadsBoundInUse() {
        if (workerThreadsBound != null) {
            return workerThreadsBound;
        }
        final var threads = workerThreadsAsNumber();
        return threads == null ? Integer.parseInt(DEFAULT_WORKER_THREADS) : threads;
    }

    /**
     * How many handlers may run at the same time on virtual threads.
     *
     * @param workerThreadsBound The bound, or <code>null</code> for the default
     */
    public void setWorkerThreadsBound(final Integer workerThreadsBound) {
        this.workerThreadsBound = workerThreadsBound;
    }

    /**
     * How long a job stays locked to the node which received it, at the adapter level.
     *
     * @return Never <code>null</code>
     */
    public Duration getJobTimeout() {
        return jobTimeout;
    }

    /**
     * How long a job stays locked to the node which received it, at the adapter level.
     *
     * @param jobTimeout The duration, or <code>null</code> for the default
     */
    public void setJobTimeout(final Duration jobTimeout) {
        this.jobTimeout = jobTimeout == null ? DEFAULT_JOB_TIMEOUT : jobTimeout;
    }

    /**
     * How long the job of a task left open for its asynchronous completion stays locked once its
     * handler returned.
     *
     * @return Never <code>null</code>
     */
    public Duration getAsyncTaskLockRenewal() {
        return asyncTaskLockRenewal;
    }

    /**
     * How long the job of a task left open for its asynchronous completion stays locked once its
     * handler returned.
     *
     * @param asyncTaskLockRenewal The duration, or <code>null</code> for the default
     */
    public void setAsyncTaskLockRenewal(final Duration asyncTaskLockRenewal) {
        this.asyncTaskLockRenewal = asyncTaskLockRenewal == null ? DEFAULT_ASYNC_TASK_LOCK_RENEWAL
                : asyncTaskLockRenewal;
    }

    /**
     * What the adapter does with a task left open longer than the platform's maximum age, as the
     * application wrote it.
     *
     * @return The value, or <code>null</code> where the application did not set the key
     */
    public String getAsyncTaskMaxAgeAction() {
        return asyncTaskMaxAgeAction;
    }

    /**
     * What the adapter does with a task left open longer than the platform's maximum age.
     *
     * @param asyncTaskMaxAgeAction <code>report</code> or <code>incident</code>, in any case, or
     *            <code>null</code> for the default
     */
    public void setAsyncTaskMaxAgeAction(final String asyncTaskMaxAgeAction) {
        this.asyncTaskMaxAgeAction = asyncTaskMaxAgeAction;
    }

    /**
     * What the adapter does with a task left open longer than the platform's maximum age. Defined
     * for a configuration {@link #validate(String, boolean, boolean, Duration)} found no defect
     * in.
     *
     * @return The action, {@link AsyncTaskMaxAgeAction#REPORT} where the key is not set
     */
    public AsyncTaskMaxAgeAction asyncTaskMaxAgeActionInUse() {
        return asyncTaskMaxAgeActionOf(asyncTaskMaxAgeAction).orElse(AsyncTaskMaxAgeAction.REPORT);
    }

    /**
     * How long the engine waits before it hands the job of a handler which threw out again, at the
     * adapter level.
     *
     * @return The wait; <code>null</code> where the key is not set, so the model's or the engine's
     *         backoff applies
     */
    public Duration getRetryBackoff() {
        return retryBackoff;
    }

    /**
     * How long the engine waits before it hands the job of a handler which threw out again, at the
     * adapter level.
     *
     * @param retryBackoff The wait, or <code>null</code> where the key is not set
     */
    public void setRetryBackoff(final Duration retryBackoff) {
        this.retryBackoff = retryBackoff;
    }

    /**
     * How long a shutdown waits for the handlers in flight.
     *
     * @return Never <code>null</code>
     */
    public Duration getShutdownGrace() {
        return shutdownGrace;
    }

    /**
     * How long a shutdown waits for the handlers in flight.
     *
     * @param shutdownGrace The grace, or <code>null</code> for the default
     */
    public void setShutdownGrace(final Duration shutdownGrace) {
        this.shutdownGrace = shutdownGrace == null ? DEFAULT_SHUTDOWN_GRACE : shutdownGrace;
    }

    /**
     * How long a started workflow may stay invisible to a query.
     *
     * @return Never <code>null</code>
     */
    public Duration getWorkflowVisibilityTimeout() {
        return workflowVisibilityTimeout;
    }

    /**
     * How long a started workflow may stay invisible to a query.
     *
     * @param workflowVisibilityTimeout The timeout, or <code>null</code> for the default
     */
    public void setWorkflowVisibilityTimeout(final Duration workflowVisibilityTimeout) {
        this.workflowVisibilityTimeout = workflowVisibilityTimeout == null
                ? DEFAULT_WORKFLOW_VISIBILITY_TIMEOUT
                : workflowVisibilityTimeout;
    }

    /**
     * How often user tasks are looked for.
     *
     * @return Never <code>null</code>; zero means the notifications are off
     */
    public Duration getUserTaskPollInterval() {
        return userTaskPollInterval;
    }

    /**
     * How often user tasks are looked for.
     *
     * @param userTaskPollInterval The interval, zero to switch the notifications off, or
     *          <code>null</code> for the default
     */
    public void setUserTaskPollInterval(final Duration userTaskPollInterval) {
        this.userTaskPollInterval = userTaskPollInterval == null
                ? DEFAULT_USER_TASK_POLL_INTERVAL
                : userTaskPollInterval;
    }

    /**
     * How long the engine keeps the history of a started workflow, at the adapter level.
     *
     * @return The time, or <code>null</code> where the engine's own default applies
     */
    public Duration getHistoryTimeToLive() {
        return historyTimeToLive;
    }

    /**
     * How long the engine keeps the history of a started workflow, at the adapter level.
     *
     * @param historyTimeToLive The time, or <code>null</code> for the engine's own default
     */
    public void setHistoryTimeToLive(final Duration historyTimeToLive) {
        this.historyTimeToLive = historyTimeToLive;
    }

    /**
     * The bound of the cache of process instance facts, and of the iterations of multi-instance
     * bodies each workflow module keeps; <code>0</code> keeps none.
     *
     * @return The number of entries
     */
    public int getInstanceCacheMaxEntries() {
        return instanceCacheMaxEntries;
    }

    /**
     * The bound of the cache of process instance facts, and of the iterations of multi-instance
     * bodies each workflow module keeps; <code>0</code> keeps none.
     *
     * @param instanceCacheMaxEntries The number of entries, or <code>null</code> for the default
     */
    public void setInstanceCacheMaxEntries(final Integer instanceCacheMaxEntries) {
        this.instanceCacheMaxEntries = instanceCacheMaxEntries == null
                ? DEFAULT_INSTANCE_CACHE_MAX_ENTRIES
                : instanceCacheMaxEntries;
    }

    /**
     * How a workflow <code>startWorkflowByMessage</code> started is found.
     *
     * @return Never <code>null</code>
     */
    public MessageStartLookup getMessageStartLookup() {
        return messageStartLookup;
    }

    /**
     * How a workflow <code>startWorkflowByMessage</code> started is found.
     *
     * @param messageStartLookup The choice, or <code>null</code> for the default
     */
    public void setMessageStartLookup(final MessageStartLookup messageStartLookup) {
        this.messageStartLookup = messageStartLookup == null ? MessageStartLookup.START_ROW : messageStartLookup;
    }

    /**
     * How many instances one lookup of <code>message-start-lookup: scan</code> reads at most.
     *
     * @return The number
     */
    public int getMessageStartLookupMaxInstances() {
        return messageStartLookupMaxInstances;
    }

    /**
     * How many instances one lookup of <code>message-start-lookup: scan</code> reads at most.
     *
     * @param messageStartLookupMaxInstances The number, or <code>null</code> for the default
     */
    public void setMessageStartLookupMaxInstances(final Integer messageStartLookupMaxInstances) {
        this.messageStartLookupMaxInstances = messageStartLookupMaxInstances == null
                ? DEFAULT_MESSAGE_START_LOOKUP_MAX_INSTANCES
                : messageStartLookupMaxInstances;
    }

    /**
     * Whether the application says that the identifiers of its workflow modules are unique, at the
     * adapter level.
     *
     * @return The value of the adapter level, <code>false</code> where it is not set
     */
    public boolean isAcceptUnscopedIdentifiers() {
        return acceptUnscopedIdentifiers;
    }

    /**
     * Whether the application says that the identifiers of its workflow modules are unique, at the
     * adapter level.
     *
     * @param acceptUnscopedIdentifiers The value, or <code>null</code> for the default
     */
    public void setAcceptUnscopedIdentifiers(final Boolean acceptUnscopedIdentifiers) {
        this.acceptUnscopedIdentifiers = Boolean.TRUE.equals(acceptUnscopedIdentifiers);
    }

    /**
     * Whether the application says that the identifiers of one workflow module are unique:
     * <code>accept-unscoped-identifiers</code> set for the workflow module, which wins over the
     * adapter level.
     *
     * @param workflowModuleId The workflow module
     * @param accepted The value; <code>null</code>, where the workflow module does not set the
     *            key, is ignored
     */
    public void setAcceptUnscopedIdentifiers(
        final String workflowModuleId,
        final Boolean accepted
    ) {
        if (accepted != null) {
            acceptUnscopedIdentifiersOfModules.put(workflowModuleId, accepted);
        }
    }

    /**
     * The key which says that a workflow module's identifiers are unique, so that
     * <code>name-clash-avoidance: none</code> is no reason to warn: the module's
     * <code>accept-unscoped-identifiers</code> where it sets the key, otherwise the adapter's.
     *
     * @param adapterId The adapter id
     * @param workflowModuleId The workflow module
     * @return The full key, empty where the level which decides does not accept them
     */
    public Optional<String> keyAcceptingUnscopedIdentifiers(
        final String adapterId,
        final String workflowModuleId
    ) {
        final var ofTheModule = acceptUnscopedIdentifiersOfModules.get(workflowModuleId);
        if (ofTheModule != null) {
            return ofTheModule ? Optional.of("vanillabp.workflow-modules.%s.adapters.%s.accept-unscoped-identifiers"
                    .formatted(workflowModuleId, adapterId)) : Optional.empty();
        }
        return acceptUnscopedIdentifiers ? Optional.of(propertyKey(adapterId, "accept-unscoped-identifiers"))
                : Optional.empty();
    }

    /**
     * Hands a duration set below the adapter level to the validation: a <code>job-timeout</code>,
     * a <code>retry-backoff</code> or a <code>history-time-to-live</code> of a workflow module, a
     * workflow or a task. Like the adapter's own, it must not be negative, a
     * <code>job-timeout</code> must not be zero either, a <code>retry-backoff</code> and a
     * <code>history-time-to-live</code> must be whole seconds the engine takes, and both, which have
     * no default, count as a started configuration where they are handed in here, as the adapter's
     * own do.
     *
     * @param propertyKey The full key as the application wrote it, e.g.
     *            <code>vanillabp.workflow-modules.orders.adapters.zenbpm.retry-backoff</code>
     * @param value The duration; <code>null</code>, where the level does not set the key, is
     *            ignored
     */
    public void addScopedValue(
        final String propertyKey,
        final Duration value
    ) {
        if (value != null) {
            scopedDurations.put(propertyKey, value);
        }
    }

    /**
     * Checks every key of this adapter instance. Called once per adapter id while the application
     * starts, on both platforms, before anything contacts the engine.
     * <p>
     * Only a defect in how the engine is reached (<code>rest-address</code>,
     * <code>grpc-address</code>, <code>client-id</code>) may let the application boot degraded,
     * and only where the adapter is nowhere first priority and its <code>deployment-failure</code>
     * policy is <code>warn</code>: that is the old engine of a migration, which may be away for a
     * while. A number or duration which cannot work is a typo, so it ends the boot for every
     * adapter id.
     * <p>
     * The messages of a validation name keys, and values only where a value cannot carry anything
     * somebody keeps to themselves: numbers and durations. They never repeat an address or the
     * client id; an address may carry a user and password, a client id a host name somebody
     * considers internal.
     *
     * @param adapterId The adapter id
     * @param nowhereFirst Whether the adapter is first priority in no prioritized-adapters list
     *          (globally, per workflow module, per workflow)
     * @param deploymentFailureWarns Whether the adapter's <code>deployment-failure</code> policy
     *          is <code>warn</code>
     * @return The outcome and the message the platform logs or ends the boot with
     */
    public ValidationResult validate(
        final String adapterId,
        final boolean nowhereFirst,
        final boolean deploymentFailureWarns
    ) {
        return validate(adapterId, nowhereFirst, deploymentFailureWarns, null);
    }

    /**
     * The same, with the platform's <code>vanillabp.delivery.retention</code>, which
     * <code>async-task-lock-renewal</code> has to stay below: the redelivery of a task left open is
     * answered from the record of its delivery, and a record removed before the lock lapses lets
     * the <code>&#64;WorkflowTask</code> method run a second time.
     *
     * @param adapterId The adapter id
     * @param nowhereFirst Whether the adapter is first priority in no prioritized-adapters list
     * @param deploymentFailureWarns Whether the adapter's <code>deployment-failure</code> policy
     *          is <code>warn</code>
     * @param deliveryRetention How long the platform keeps the record of a delivery;
     *          <code>null</code> where the caller knows none, which checks nothing against it
     * @return The outcome and the message the platform logs or ends the boot with
     */
    public ValidationResult validate(
        final String adapterId,
        final boolean nowhereFirst,
        final boolean deploymentFailureWarns,
        final Duration deliveryRetention
    ) {
        final var connectionDefects = new ArrayList<String>();
        final var valueDefects = new ArrayList<String>();
        final var notices = new ArrayList<String>();
        final var warnings = new ArrayList<String>();

        final var restAddressMissing = isBlank(restAddress);
        if (!restAddressMissing) {
            final var restAddressDefect = restAddressDefect();
            if (restAddressDefect != null) {
                connectionDefects.add("%s %s".formatted(propertyKey(adapterId, "rest-address"), restAddressDefect));
            } else {
                addRestAddressNotices(adapterId, notices);
            }
        }

        if (grpcAddress != null) {
            final var grpcAddressDefect = grpcAddressDefect(grpcAddress.trim());
            if (grpcAddressDefect != null) {
                connectionDefects.add("%s %s".formatted(propertyKey(adapterId, "grpc-address"), grpcAddressDefect));
            }
        }

        if ((clientId != null) && clientId.isBlank()) {
            connectionDefects.add(
                    "%s is blank; remove the key to have the id derived as <application name>-%s-<random>"
                            .formatted(propertyKey(adapterId, "client-id"), adapterId));
        }

        if (requestTimeout.compareTo(MINIMUM_REQUEST_TIMEOUT) < 0) {
            valueDefects.add("%s is %s and has to be at least %s; the default is %s"
                    .formatted(
                            propertyKey(adapterId, "request-timeout"),
                            requestTimeout,
                            MINIMUM_REQUEST_TIMEOUT,
                            DEFAULT_REQUEST_TIMEOUT_ISO));
        }

        addWorkerThreadsDefects(adapterId, valueDefects);

        addNegativeDurationDefect(adapterId, "startup-wait", startupWait, DEFAULT_STARTUP_WAIT_ISO, valueDefects);
        addNegativeDurationDefect(adapterId, "job-timeout", jobTimeout, DEFAULT_JOB_TIMEOUT_ISO, valueDefects);
        addJobTimeoutDefect(propertyKey(adapterId, "job-timeout"), jobTimeout, "the default is %s".formatted(
                DEFAULT_JOB_TIMEOUT_ISO), valueDefects);
        addAsyncTaskDefects(adapterId, deliveryRetention, valueDefects);
        addAsyncTaskMaxAgeActionDefect(adapterId, valueDefects);
        if ((retryBackoff != null) && retryBackoff.isNegative()) {
            valueDefects.add(
                    "%s is %s and must not be negative; without the key the retryBackoff of the task's model applies, else the engine's own default"
                            .formatted(propertyKey(adapterId, "retry-backoff"), retryBackoff));
        }
        addRetryBackoffDefect(propertyKey(adapterId, "retry-backoff"),
                retryBackoff, "the retryBackoff of the task's model applies, else the engine's own default",
                valueDefects);
        addNegativeDurationDefect(adapterId, "shutdown-grace", shutdownGrace, DEFAULT_SHUTDOWN_GRACE_ISO, valueDefects);
        addShutdownGraceWarning(adapterId, warnings);
        addNegativeDurationDefect(
                adapterId,
                "workflow-visibility-timeout",
                workflowVisibilityTimeout,
                DEFAULT_WORKFLOW_VISIBILITY_TIMEOUT_ISO,
                valueDefects);
        addNegativeDurationDefect(
                adapterId,
                "user-task-poll-interval",
                userTaskPollInterval,
                DEFAULT_USER_TASK_POLL_INTERVAL_ISO,
                valueDefects);
        addUserTaskPollIntervalWarning(adapterId, warnings);
        if ((historyTimeToLive != null) && historyTimeToLive.isNegative()) {
            valueDefects.add("%s is %s and must not be negative; without the key the engine's own default applies"
                    .formatted(propertyKey(adapterId, "history-time-to-live"), historyTimeToLive));
        }
        addTimeToLiveDefect(propertyKey(adapterId, "history-time-to-live"), historyTimeToLive,
                "the engine's own default applies", valueDefects);

        if (instanceCacheMaxEntries < 0) {
            valueDefects.add("%s is %d and must not be negative; the default is %d"
                    .formatted(
                            propertyKey(adapterId, "instance-cache-max-entries"),
                            instanceCacheMaxEntries,
                            DEFAULT_INSTANCE_CACHE_MAX_ENTRIES));
        }
        if (messageStartLookupMaxInstances < 1) {
            valueDefects.add("%s is %d and has to be at least 1; the default is %d"
                    .formatted(
                            propertyKey(adapterId, "message-start-lookup-max-instances"),
                            messageStartLookupMaxInstances,
                            DEFAULT_MESSAGE_START_LOOKUP_MAX_INSTANCES));
        }
        scopedDurations.forEach((
            propertyKey,
            value) -> {
            if (value.isNegative()) {
                valueDefects.add("%s is %s and must not be negative; without the key the level above applies"
                        .formatted(propertyKey, value));
            }
            if (propertyKey.endsWith(".history-time-to-live")) {
                addTimeToLiveDefect(propertyKey, value, "the level above applies", valueDefects);
            }
            if (propertyKey.endsWith(".job-timeout")) {
                addJobTimeoutDefect(propertyKey, value, "without the key the level above applies", valueDefects);
            }
            if (propertyKey.endsWith(".retry-backoff")) {
                addRetryBackoffDefect(propertyKey, value, "the level above applies", valueDefects);
            }
        });

        // a key without a default, at the adapter level or below it, is set only by somebody who
        // configures this adapter. Beside one, or beside a value which cannot work, a missing address
        // is no longer "not configured yet": the configuration was started and has to be finished.
        // job-timeout below the adapter level falls back to a default, so it does not count
        final var keyWithoutADefaultBelow = scopedDurations
                .keySet()
                .stream()
                .anyMatch(propertyKey -> propertyKey.endsWith(".history-time-to-live") || propertyKey.endsWith(
                        ".retry-backoff"));
        final var partlyConfigured = (grpcAddress != null) || (clientId != null) || (historyTimeToLive != null) || (retryBackoff != null) || keyWithoutADefaultBelow;
        if (restAddressMissing && (partlyConfigured || !valueDefects.isEmpty())) {
            connectionDefects.addFirst(
                    "%s is missing, but other keys of this adapter are set; it is the address of the engine's REST API, e.g. http://localhost:8080"
                            .formatted(propertyKey(adapterId, "rest-address")));
        }

        if (connectionDefects.isEmpty() && valueDefects.isEmpty()) {
            if (restAddressMissing) {
                return new ValidationResult(ValidationResult.Kind.UNCONFIGURED, unconfiguredMessage(adapterId,
                        subjectOf(adapterId)), unconfiguredMessage(adapterId,
                                THE_ADAPTER_IN_A_FINDING), false, notices, warnings);
            }
            return new ValidationResult(ValidationResult.Kind.COMPLETE, "", "", false, notices, warnings);
        }

        final var defects = new ArrayList<String>(connectionDefects);
        defects.addAll(valueDefects);
        final var degrades = nowhereFirst && deploymentFailureWarns && valueDefects.isEmpty();
        return new ValidationResult(
                ValidationResult.Kind.INCONSISTENT, inconsistentMessage(adapterId, subjectOf(adapterId), defects,
                        nowhereFirst, deploymentFailureWarns, degrades), inconsistentMessage(adapterId,
                                THE_ADAPTER_IN_A_FINDING, defects, nowhereFirst, deploymentFailureWarns,
                                degrades), !degrades, notices, warnings);
    }

    /**
     * The address of the job stream in use: <code>grpc-address</code>, or the host of
     * <code>rest-address</code> with port {@value #DEFAULT_GRPC_PORT}.
     *
     * @return <code>host:port</code>
     * @throws IllegalStateException If <code>rest-address</code> is missing or malformed and
     *           <code>grpc-address</code> is not set
     */
    public String grpcAddressInUse() {
        if (grpcAddress != null) {
            return grpcAddress.trim();
        }
        return "%s:%d".formatted(parsedRestAddress().getHost(), DEFAULT_GRPC_PORT);
    }

    /**
     * The client id of the job stream in use: <code>client-id</code>, or
     * <code>&lt;application name&gt;-&lt;adapter id&gt;-&lt;8 random hex digits&gt;</code>. The
     * random part is drawn once per configuration, so every call answers the same id.
     *
     * @param adapterId The adapter id
     * @param applicationName The application's name as the platform knows it
     * @return The id
     */
    public String clientIdInUse(
        final String adapterId,
        final Supplier<String> applicationName
    ) {
        if (clientId != null) {
            return clientId.trim();
        }
        final var name = applicationName.get();
        return "%s-%s-%s".formatted(isBlank(name) ? DEFAULT_APPLICATION_NAME : name.trim(), adapterId, clientIdSuffix);
    }

    /**
     * The engine's address: <code>rest-address</code> normalised, without a <code>/v1</code> at
     * its end. Normalised means a lower-cased scheme and host, an explicit port, a path without
     * repeated or trailing slashes, and an escaped letter, digit or <code>-._~</code> written as
     * itself (<code>%76%31</code> is <code>v1</code>); every other escape stays, so
     * <code>%2F</code> never becomes a separator. The REST calls go below it under
     * <code>/v1</code>, its operational endpoints such as the readiness probe under
     * <code>/system</code>, and messages name the engine by it.
     *
     * @return E.g. <code>http://zenbpm:8080</code>
     * @throws IllegalStateException If <code>rest-address</code> is missing or malformed
     */
    public URI engineAddress() {
        final var address = parsedRestAddress();
        final var scheme = address
                .getScheme()
                .toLowerCase(Locale.ROOT);
        final var port = address.getPort() != -1
                ? address.getPort()
                : "https".equals(scheme) ? 443 : 80;
        final var path = normalisedPath(address.getRawPath());
        return URI.create("%s://%s:%d%s".formatted(
                scheme,
                address
                        .getHost()
                        .toLowerCase(Locale.ROOT),
                port,
                path.endsWith(API_VERSION_PATH) ? path.substring(0, path.length() - API_VERSION_PATH.length()) : path));
    }

    /**
     * The base of every REST call: {@link #engineAddress()} with <code>/v1</code> appended, so
     * <code>rest-address</code> may name it or leave it out.
     *
     * @return E.g. <code>http://zenbpm:8080/v1</code>
     * @throws IllegalStateException If <code>rest-address</code> is missing or malformed
     */
    public URI restBaseUri() {
        return URI.create(engineAddress() + API_VERSION_PATH);
    }

    /**
     * Whether <code>rest-address</code> names an engine, so that {@link #instanceIdentity()} and
     * the addresses derived from it can be asked. It may, where the configuration as a whole is not
     * complete: an adapter id which boots degraded because of another key still names its engine.
     *
     * @return <code>true</code> where <code>rest-address</code> is set and well-formed
     */
    public boolean hasValidRestAddress() {
        return !isBlank(restAddress) && (restAddressDefect() == null);
    }

    /**
     * Which engine this adapter instance talks to. Two adapter ids with equal identities address
     * one engine, however their <code>rest-address</code> is spelled.
     *
     * @return The normalised base of every REST call, see {@link #restBaseUri()}
     * @throws IllegalStateException If <code>rest-address</code> is missing or malformed
     */
    public String instanceIdentity() {
        return restBaseUri().toString();
    }

    private void addRestAddressNotices(
        final String adapterId,
        final List<String> notices
    ) {
        final var address = parsedRestAddress();
        if (normalisedPath(address.getRawPath()).endsWith(API_VERSION_PATH)) {
            notices.add(
                    """
                            '%s' already ends with '%s'. The adapter appends it itself, so the key may name the \
                            engine's address without it. No second '%s' is appended."""
                            .formatted(propertyKey(adapterId, "rest-address"), API_VERSION_PATH, API_VERSION_PATH));
        }
        if ("https".equalsIgnoreCase(address.getScheme()) && (grpcAddress == null) && grpcPlaintext) {
            notices.add(
                    """
                            '%s' uses https, but the job stream goes to port %d of the same host without TLS. \
                            Where a proxy terminates TLS for both, set '%s' and '%s: false'."""
                            .formatted(
                                    propertyKey(adapterId, "rest-address"),
                                    DEFAULT_GRPC_PORT,
                                    propertyKey(adapterId, "grpc-address"),
                                    propertyKey(adapterId, "grpc-plaintext")));
        }
    }

    private URI parsedRestAddress() {
        if (isBlank(restAddress) || (restAddressDefect() != null)) {
            throw new IllegalStateException(
                    "The address of the engine is derived from a valid rest-address only; validate the configuration first");
        }
        return URI.create(restAddress.trim());
    }

    private String restAddressDefect() {
        final URI address;
        try {
            address = new URI(restAddress.trim());
        } catch (URISyntaxException e) {
            return "is not a URL; write it as http://host:port";
        }
        final var scheme = address.getScheme();
        if ((scheme == null) || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            return "needs the scheme http or https, as in http://localhost:8080";
        }
        if (address.getRawUserInfo() != null) {
            return "carries a user before '@', which the adapter never sends; remove it";
        }
        // java.net.URI answers no host where the authority is not a valid host and port, e.g. an
        // underscore in the host or a port too large for a number
        if (address.getHost() == null) {
            return "names no valid host and port; write it as http://host:port (a host name may not contain '_')";
        }
        if ((address.getPort() == 0) || (address.getPort() > 65_535)) {
            return "names a port outside 1 to 65535; write it as http://host:port, e.g. http://localhost:8080";
        }
        if ((address.getRawQuery() != null) || (address.getRawFragment() != null)) {
            return "carries a query or a fragment; it is the engine's address and nothing else";
        }
        return null;
    }

    private static String grpcAddressDefect(final String address) {
        final var example = "e.g. localhost:%d or [::1]:%d".formatted(DEFAULT_GRPC_PORT, DEFAULT_GRPC_PORT);
        if (address.isEmpty()) {
            return "is blank; remove the key to have it derived from the host of rest-address and port %d"
                    .formatted(DEFAULT_GRPC_PORT);
        }
        if (address.contains("://")) {
            return "is host:port without a scheme, %s".formatted(example);
        }
        // parsed as the authority of a URI, so a host, an IPv6 address in brackets and a port are
        // checked the way the gRPC channel will read them
        final URI authority;
        try {
            authority = new URI("grpc://"
                    + address);
        } catch (URISyntaxException e) {
            return "is not host:port, %s".formatted(example);
        }
        if (authority.getRawUserInfo() != null) {
            return "carries a user before '@', which the adapter never sends; write it as host:port, %s"
                    .formatted(example);
        }
        if (!authority
                .getRawPath()
                .isEmpty() || (authority.getRawQuery() != null) || (authority.getRawFragment() != null)) {
            return "carries more than host:port, %s".formatted(example);
        }
        if (authority.getHost() == null) {
            return "names no valid host and port, %s (a host name may not contain '_')".formatted(example);
        }
        if (authority.getPort() == -1) {
            return "names no port; write it as host:port, %s".formatted(example);
        }
        if ((authority.getPort() == 0) || (authority.getPort() > 65_535)) {
            return "names a port outside 1 to 65535, %s".formatted(example);
        }
        return null;
    }

    private void addWorkerThreadsDefects(
        final String adapterId,
        final List<String> defects
    ) {
        final var configured = workerThreads.trim();
        final var threads = workerThreadsAsNumber();
        if ((threads == null) && configured.matches("[+-]?\\d+")) {
            defects.add("%s is too large a number; size it against the database connection pool, the default is %s"
                    .formatted(propertyKey(adapterId, "worker-threads"), DEFAULT_WORKER_THREADS));
        } else if ((threads == null) && !VIRTUAL_WORKER_THREADS.equalsIgnoreCase(configured)) {
            defects.add("%s is neither a number nor '%s'; e.g. '%s' platform threads, or '%s' bounded by %s"
                    .formatted(
                            propertyKey(adapterId, "worker-threads"),
                            VIRTUAL_WORKER_THREADS,
                            DEFAULT_WORKER_THREADS,
                            VIRTUAL_WORKER_THREADS,
                            propertyKey(adapterId, "worker-threads-bound")));
        } else if ((threads != null) && (threads < 1)) {
            defects.add("%s is %d; without a thread the adapter runs no handler, the default is %s"
                    .formatted(propertyKey(adapterId, "worker-threads"), threads, DEFAULT_WORKER_THREADS));
        }
        if (workerThreadsBound == null) {
            return;
        }
        // with platform threads the number of threads is the bound, so a second number would either
        // repeat it or be ignored. A key which changes nothing is refused rather than ignored
        if (threads != null) {
            defects.add(
                    "%s is set, but %s is a number of platform threads, which is the bound itself; set '%s' or remove the bound"
                            .formatted(
                                    propertyKey(adapterId, "worker-threads-bound"),
                                    propertyKey(adapterId, "worker-threads"),
                                    VIRTUAL_WORKER_THREADS));
        } else if (workerThreadsBound < 1) {
            defects
                    .add("%s is %d and has to be at least 1; every running handler holds a database connection, the default is %s"
                            .formatted(propertyKey(adapterId, "worker-threads-bound"), workerThreadsBound,
                                    DEFAULT_WORKER_THREADS));
        }
    }

    private Integer workerThreadsAsNumber() {
        try {
            return Integer.valueOf(workerThreads.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void addNegativeDurationDefect(
        final String adapterId,
        final String key,
        final Duration configured,
        final String defaultIso,
        final List<String> defects
    ) {
        if (configured.isNegative()) {
            defects.add("%s is %s and must not be negative; the default is %s"
                    .formatted(propertyKey(adapterId, key), configured, defaultIso));
        }
    }

    // a negative duration has a defect of its own, so only zero is left
    private static void addJobTimeoutDefect(
        final String propertyKey,
        final Duration configured,
        final String withoutTheKey,
        final List<String> defects
    ) {
        if (configured.isZero()) {
            defects.add("%s is PT0S, but a job has to stay locked for a while to the node which runs it; %s"
                    .formatted(propertyKey, withoutTheKey));
        }
    }

    private void addShutdownGraceWarning(
        final String adapterId,
        final List<String> warnings
    ) {
        if (shutdownGrace.compareTo(LONGEST_SHUTDOWN_GRACE_WITHIN_THE_USUAL_BUDGETS) <= 0) {
            return;
        }
        warnings.add(
                "%s is %s, above the %s which Kubernetes grants a pod to stop by default: raise terminationGracePeriodSeconds, or the stop timeout of whatever else runs the application, with it, or the application is killed before the handlers had their grace"
                        .formatted(propertyKey(adapterId, "shutdown-grace"), shutdownGrace,
                                LONGEST_SHUTDOWN_GRACE_WITHIN_THE_USUAL_BUDGETS));
    }

    // the validation refuses only a negative interval, so the two which work against the application
    // are said once at startup: one which switches the notifications off, and one which asks the engine
    // almost without pause, because every look reads every page of the jobs of every user-task type
    private void addUserTaskPollIntervalWarning(
        final String adapterId,
        final List<String> warnings
    ) {
        final var key = propertyKey(adapterId, "user-task-poll-interval");
        if (userTaskPollInterval.isZero()) {
            warnings.add(
                    "%s is %s, which switches the notifications of user tasks off: no @WorkflowTask method hears that a user task was created or cancelled. Remove the key for the default %s"
                            .formatted(key, userTaskPollInterval, DEFAULT_USER_TASK_POLL_INTERVAL_ISO));
            return;
        }
        final var belowASecond = userTaskPollInterval.compareTo(SHORTEST_USER_TASK_POLL_INTERVAL_WITHOUT_A_WARNING) < 0;
        if (userTaskPollInterval.isNegative() || !belowASecond) {
            return;
        }
        warnings.add(
                "%s is %s, below %s: every look lists the active and the terminated jobs of every user-task type, every page of them, so the engine is asked almost without pause. Raise it to a second or more; the default is %s"
                        .formatted(key, userTaskPollInterval, SHORTEST_USER_TASK_POLL_INTERVAL_WITHOUT_A_WARNING,
                                DEFAULT_USER_TASK_POLL_INTERVAL_ISO));
    }

    // the engine reads a backoff in whole seconds and refuses a fraction of one; a negative backoff
    // has a defect of its own, so it is left out here
    private static void addRetryBackoffDefect(
        final String propertyKey,
        final Duration configured,
        final String withoutTheKey,
        final List<String> defects
    ) {
        if ((configured == null) || configured.isNegative() || (configured.getNano() == 0)) {
            return;
        }
        defects.add(
                "%s is %s, but the engine takes a backoff in whole seconds; write whole seconds, or remove the key, so %s"
                        .formatted(propertyKey, configured, withoutTheKey));
    }

    private void addAsyncTaskDefects(
        final String adapterId,
        final Duration deliveryRetention,
        final List<String> defects
    ) {
        final var renewalKey = propertyKey(adapterId, "async-task-lock-renewal");
        if (asyncTaskLockRenewal.isNegative() || asyncTaskLockRenewal.isZero()) {
            defects.add("""
                    %s is %s, but the job of a task left open for its asynchronous completion has to stay locked \
                    for a while, or the engine hands it out again at once; the default is %s"""
                    .formatted(renewalKey, asyncTaskLockRenewal, DEFAULT_ASYNC_TASK_LOCK_RENEWAL_ISO));
            return;
        }
        if ((deliveryRetention != null) && (asyncTaskLockRenewal.compareTo(deliveryRetention) >= 0)) {
            defects.add("""
                    %s is %s, which is not shorter than vanillabp.delivery.retention (%s). When the lock of a task \
                    left open lapses, the engine hands its job out again, and the redelivery is answered from the \
                    record of the delivery; a record removed before that lets the @WorkflowTask method run a second \
                    time. Set %s well below the retention, at most a tenth of it (%s here), or raise \
                    vanillabp.delivery.retention"""
                    .formatted(renewalKey, asyncTaskLockRenewal, deliveryRetention, renewalKey, deliveryRetention
                            .dividedBy(10)));
        }
    }

    private void addAsyncTaskMaxAgeActionDefect(
        final String adapterId,
        final List<String> defects
    ) {
        if ((asyncTaskMaxAgeAction != null) && asyncTaskMaxAgeActionOf(asyncTaskMaxAgeAction).isEmpty()) {
            defects.add("%s is '%s'; it is '%s', the default, or '%s'".formatted(propertyKey(adapterId,
                    "async-task-max-age-action"), asyncTaskMaxAgeAction.trim(),
                    AsyncTaskMaxAgeAction.REPORT
                            .configured(),
                    AsyncTaskMaxAgeAction.INCIDENT.configured()));
        }
    }

    private static Optional<AsyncTaskMaxAgeAction> asyncTaskMaxAgeActionOf(final String configured) {
        if (configured == null) {
            return Optional.empty();
        }
        for (final var action : AsyncTaskMaxAgeAction.values()) {
            if (action
                    .configured()
                    .equalsIgnoreCase(configured.trim())) {
                return Optional.of(action);
            }
        }
        return Optional.empty();
    }

    // a negative time to live has a defect of its own, so it is left out here
    private static void addTimeToLiveDefect(
        final String propertyKey,
        final Duration configured,
        final String withoutTheKey,
        final List<String> defects
    ) {
        if ((configured == null) || configured.isNegative()) {
            return;
        }
        ZenBpmTimeToLive
                .defectOf(configured)
                .ifPresent(defect -> defects.add("%s is %s, %s, or remove the key, so %s".formatted(propertyKey,
                        configured, defect, withoutTheKey)));
    }

    // the adapter id is the subject of the message every use of an adapter id without clients
    // fails with, and the scope of a finding, whose text names it as this adapter
    private static String subjectOf(final String adapterId) {
        return "ZenBPM adapter '%s'".formatted(adapterId);
    }

    private static String unconfiguredMessage(
        final String adapterId,
        final String subject
    ) {
        return """
                %s is not configured: '%s' is missing. The application boots, and the adapter refuses \
                work with this message until the address of the engine's REST API is added, e.g.:
                  vanillabp:
                    adapters:
                      %s:
                        rest-address: http://localhost:8080
                The adapter appends '%s' itself. The job stream's address and client id are derived \
                unless '%s' and '%s' are set."""
                .formatted(
                        subject,
                        propertyKey(adapterId, "rest-address"),
                        adapterId,
                        API_VERSION_PATH,
                        propertyKey(adapterId, "grpc-address"),
                        propertyKey(adapterId, "client-id"));
    }

    private static String inconsistentMessage(
        final String adapterId,
        final String subject,
        final List<String> defects,
        final boolean nowhereFirst,
        final boolean deploymentFailureWarns,
        final boolean degrades
    ) {
        final var listed = defects
                .stream()
                .map("  - %s"::formatted)
                .collect(Collectors.joining("\n"));
        final String consequence;
        if (degrades) {
            consequence = """
                    The adapter is nowhere first priority and '%s' is 'warn', so the application boots \
                    DEGRADED: the adapter refuses work with this message until the keys are corrected."""
                    .formatted(propertyKey(adapterId, "deployment-failure"));
        } else if (!nowhereFirst) {
            consequence = "Correct the keys. The adapter is first priority somewhere, so it cannot boot degraded.";
        } else if (deploymentFailureWarns) {
            consequence = """
                    Correct the keys. A number or a duration which cannot work ends the boot even where '%s' \
                    is 'warn': only an engine which cannot be reached may leave the adapter degraded."""
                    .formatted(propertyKey(adapterId, "deployment-failure"));
        } else {
            consequence = """
                    Correct the keys. Where only the engine's address or the client id is wrong, an adapter \
                    which is nowhere first priority, like the old engine of a migration, may instead set \
                    '%s: warn' to boot degraded."""
                    .formatted(propertyKey(adapterId, "deployment-failure"));
        }
        return "%s is configured inconsistently:\n%s\n%s".formatted(subject, listed, consequence);
    }

    // RFC 3986 normalisation of a raw path (section 6.2.2.2: an escaped unreserved character is
    // the character itself), plus repeated and trailing slashes removed, so the '/v1' check and
    // the identity of two spellings of one address agree
    private static String normalisedPath(final String rawPath) {
        final var path = new StringBuilder();
        for (var i = 0; i < rawPath.length(); ++i) {
            final var character = rawPath.charAt(i);
            if (character == '%') {
                // java.net.URI has checked that two hex digits follow
                final var hex = rawPath.substring(i + 1, i + 3);
                final var decoded = (char) Integer.parseInt(hex, 16);
                if (isUnreserved(decoded)) {
                    path.append(decoded);
                } else {
                    path
                            .append('%')
                            .append(hex.toUpperCase(Locale.ROOT));
                }
                i += 2;
            } else if ((character != '/') || path.isEmpty() || (path.charAt(path.length() - 1) != '/')) {
                path.append(character);
            }
        }
        if (!path.isEmpty() && (path.charAt(path.length() - 1) == '/')) {
            path.setLength(path.length() - 1);
        }
        return path.toString();
    }

    private static boolean isUnreserved(final char character) {
        // the ASCII letters and digits only: Character.isLetterOrDigit alone accepts every alphabet
        return ((character < 128) && Character.isLetterOrDigit(character)) || ("-._~".indexOf(character) >= 0);
    }

    private static boolean isBlank(final String text) {
        return (text == null) || text.isBlank();
    }

}

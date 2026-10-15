/*
 * Copyright 2026 Phactum Softwareentwicklung GmbH
 * Adapted for zenbpm-vanillabp-adapter from vanillabp/camunda8-adapter, see NOTICE.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.pbinitiative.zenbpmadapter.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import org.pbinitiative.zenbpmadapter.client.api.EngineStatus;

import lombok.extern.slf4j.Slf4j;

/**
 * Everything one adapter id needs to talk to its engine: the REST client, the job stream and the
 * executor the handlers run on. The platform builds one factory per configured adapter id while
 * the application starts, and closes it when the application stops.
 * <p>
 * A factory of a complete configuration builds its clients at once, so a client which cannot be
 * built fails the start and not the first call. Building them contacts nothing: the REST client
 * connects with its first call, and the job stream with its first subscription. A factory of an
 * adapter id which is not configured, or which boots degraded, builds nothing and keeps the
 * message of the validation: every client it is asked for throws that message, so the adapter
 * refuses its work with the words which say what to configure.
 * <p>
 * The workflow modules register a hook while their job types are subscribed. On every path of the
 * platforms the module stops itself before the factory is closed. Where that did not happen,
 * {@link #close()} runs the hooks first and says so at WARN, because a stream closed under a
 * module's open subscriptions hands its jobs to the next client at once, while the module's
 * handlers may still be running them. The hooks run without a lock of the factory held, so a hook
 * may wait for the module's own stop, which removes its hook meanwhile. A module which starts once
 * {@link #close()} began is refused, because nothing would stop it.
 * <p>
 * Each field has an accessor of its own and no Lombok getter on the class, see decision 13 in the
 * repository's DECISIONS.md.
 */
@Slf4j
@SuppressWarnings("LombokGetterMayBeUsed")
public class ZenBpmClientFactory implements AutoCloseable {

    private final String adapterId;

    private final ZenBpmAdapterConfiguration configuration;

    /**
     * Why this factory has no clients; <code>null</code> where it has them.
     */
    private final String unconfiguredMessage;

    private final String clientId;

    private final ZenBpmRestClient rest;

    private final ZenBpmJobStream stream;

    private final ZenBpmExecutor executor;

    /**
     * The workflow modules whose job types are subscribed right now, and per module the hooks
     * which stop them, in the order they were registered. Guarded by <code>this</code>.
     */
    private final Map<String, List<WorkflowModuleShutdown>> openWorkflowModules = new LinkedHashMap<>();

    /**
     * What the engine wait of this adapter id found. A lock of its own, not <code>this</code>, so a
     * wait does not hold up {@link #close()}.
     */
    private final ZenBpmEngineWait.Memory engineWait = new ZenBpmEngineWait.Memory();

    /**
     * Whether the engine's version and commit were logged, or are being logged.
     */
    private final AtomicBoolean environmentLogged = new AtomicBoolean();

    /**
     * What the engine reported about itself when it was asked at startup.
     */
    private volatile EngineStatus engineStatusAtStart;

    /**
     * Whether {@link #close()} began. Guarded by <code>this</code>.
     */
    private boolean closed;

    /**
     * The factory of an adapter id whose configuration is complete. Builds the REST client, the
     * job stream and the executor, and contacts nothing.
     *
     * @param adapterId The adapter id
     * @param configuration A configuration which {@link ZenBpmAdapterConfiguration#validate} found
     *            complete
     * @param applicationName The application's name as the platform knows it, the first part of
     *            a derived client id
     * @throws IllegalStateException Where the application's protobuf runtime cannot run the job
     *             stream, see {@link ProtobufRuntimeCheck}
     */
    public ZenBpmClientFactory(
        final String adapterId,
        final ZenBpmAdapterConfiguration configuration,
        final Supplier<String> applicationName
    ) {
        this(adapterId, configuration, configuration.clientIdInUse(adapterId, applicationName));
    }

    private ZenBpmClientFactory(
        final String adapterId,
        final ZenBpmAdapterConfiguration configuration,
        final String clientId
    ) {
        this(adapterId, configuration, clientId, ZenBpmExecutor.of(adapterId, ZenBpmExecutionModel
                .resolve(adapterId, configuration)));
    }

    // the stream before the REST client: the stream is what refuses an unfit protobuf runtime, and
    // the HTTP client starts a thread of its own as soon as it is built
    private ZenBpmClientFactory(
        final String adapterId,
        final ZenBpmAdapterConfiguration configuration,
        final String clientId,
        final ZenBpmExecutor executor
    ) {
        this(adapterId, configuration, clientId, executor, new ZenBpmJobStream(adapterId, configuration, clientId, executor
                .timing()));
    }

    private ZenBpmClientFactory(
        final String adapterId,
        final ZenBpmAdapterConfiguration configuration,
        final String clientId,
        final ZenBpmExecutor executor,
        final ZenBpmJobStream stream
    ) {
        this(adapterId, configuration, clientId, new ZenBpmRestClient(adapterId, configuration), stream, executor);
    }

    /**
     * A factory of clients which were built elsewhere, so a test can watch what the factory does
     * with them.
     */
    ZenBpmClientFactory(
        final String adapterId,
        final ZenBpmAdapterConfiguration configuration,
        final String clientId,
        final ZenBpmRestClient rest,
        final ZenBpmJobStream stream,
        final ZenBpmExecutor executor
    ) {
        this(adapterId, configuration, null, clientId, rest, stream, executor);
    }

    private ZenBpmClientFactory(
        final String adapterId,
        final ZenBpmAdapterConfiguration configuration,
        final String unconfiguredMessage,
        final String clientId,
        final ZenBpmRestClient rest,
        final ZenBpmJobStream stream,
        final ZenBpmExecutor executor
    ) {
        this.adapterId = adapterId;
        this.configuration = configuration;
        this.unconfiguredMessage = unconfiguredMessage;
        this.clientId = clientId;
        this.rest = rest;
        this.stream = stream;
        this.executor = executor;
    }

    /**
     * The factory of an adapter id which is not configured, or whose configuration lets the
     * application boot degraded. It builds no client, and every client it is asked for throws the
     * message.
     *
     * @param adapterId The adapter id
     * @param configuration The configuration as the platform read it
     * @param message The message of the validation, which names the keys to set or correct
     * @return The factory
     */
    public static ZenBpmClientFactory unconfigured(
        final String adapterId,
        final ZenBpmAdapterConfiguration configuration,
        final String message
    ) {
        return new ZenBpmClientFactory(adapterId, configuration, Objects.requireNonNull(message,
                "message"), null, null, null, null);
    }

    /**
     * The adapter id this factory belongs to.
     *
     * @return The adapter id
     */
    public String getAdapterId() {
        return adapterId;
    }

    /**
     * The configuration of the adapter id at the adapter level.
     *
     * @return The configuration
     */
    public ZenBpmAdapterConfiguration configuration() {
        return configuration;
    }

    /**
     * Whether this factory has clients. Where it has none, {@link #unconfiguredMessage()} says
     * why.
     *
     * @return <code>false</code> for an adapter id which is not configured or boots degraded
     */
    public boolean isConfigured() {
        return unconfiguredMessage == null;
    }

    /**
     * Why this factory has no clients.
     *
     * @return The message of the validation, empty where the factory has clients
     */
    public Optional<String> unconfiguredMessage() {
        return Optional.ofNullable(unconfiguredMessage);
    }

    /**
     * The client id the engine knows this adapter id's job stream by.
     *
     * @return The client id
     * @throws IllegalStateException With the message of the validation where the factory has no
     *             clients
     */
    public String clientId() {
        requireConfigured();
        return clientId;
    }

    /**
     * The REST client of this adapter id.
     *
     * @return The client
     * @throws IllegalStateException With the message of the validation where the factory has no
     *             clients
     */
    public ZenBpmRestClient rest() {
        requireConfigured();
        return rest;
    }

    /**
     * The job stream of this adapter id.
     *
     * @return The stream
     * @throws IllegalStateException With the message of the validation where the factory has no
     *             clients
     */
    public ZenBpmJobStream stream() {
        requireConfigured();
        return stream;
    }

    /**
     * The threads of this adapter id: the handler slots and the timing threads the job stream
     * runs on.
     *
     * @return The executor
     * @throws IllegalStateException With the message of the validation where the factory has no
     *             clients
     */
    public ZenBpmExecutor executor() {
        requireConfigured();
        return executor;
    }

    /**
     * What the engine reported about itself when {@link ZenBpmEnvironmentInfo} asked it at
     * startup. It is not asked again: the health check reports these values beside its own
     * answer, which may be younger.
     *
     * @return Version, commit and nodes; empty before the engine was asked or where it did not
     *         answer
     */
    public Optional<EngineStatus> engineStatusAtStart() {
        return Optional.ofNullable(engineStatusAtStart);
    }

    /**
     * Registers the hook which stops a workflow module whose job types are subscribed now. The
     * module registers it before it subscribes, so a refusal leaves nothing subscribed.
     *
     * @param workflowModuleId The workflow module
     * @param shutdown How to stop it where the factory is closed before it stopped itself
     * @return What removes this hook again once the module stopped
     * @throws IllegalStateException Where {@link #close()} began: nothing would run the hook
     */
    public synchronized WorkflowModuleShutdownRegistration workflowModuleStarted(
        final String workflowModuleId,
        final WorkflowModuleShutdown shutdown
    ) {
        Objects.requireNonNull(workflowModuleId, "workflowModuleId");
        Objects.requireNonNull(shutdown, "shutdown");
        if (closed) {
            throw new IllegalStateException(
                    "ZenBPM adapter '%s': workflow module '%s' cannot start, because the clients of the adapter id are closed or being closed while the application stops."
                            .formatted(adapterId, workflowModuleId));
        }
        openWorkflowModules
                .computeIfAbsent(workflowModuleId, module -> new ArrayList<>())
                .add(shutdown);
        return () -> removeShutdownHook(workflowModuleId, shutdown);
    }

    /**
     * The workflow modules of this adapter id which registered a hook and did not remove it.
     *
     * @return The workflow module ids
     */
    public synchronized Set<String> getOpenWorkflowModules() {
        return Set.copyOf(openWorkflowModules.keySet());
    }

    /**
     * Stops what the factory built, in this order: the workflow modules which did not stop
     * themselves (logged at WARN), the job stream, the executor and the REST client. The executor
     * takes no new handler and does not wait for the running ones: the platform's drain waited
     * <code>shutdown-grace</code> for them already. Where one of them fails to close, the others
     * are closed all the same, and the first failure is thrown with the later ones suppressed.
     * Closing twice does nothing the second time.
     */
    @Override
    public void close() {
        final Map<String, List<WorkflowModuleShutdown>> notStopped;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            notStopped = new LinkedHashMap<>(openWorkflowModules);
            openWorkflowModules.clear();
        }
        stopWorkflowModulesWhichDidNotStop(notStopped);
        if (!isConfigured()) {
            return;
        }
        closeEach(stream::close, executor::shutdown, () -> {
            log.info("ZenBPM[{}]: closing the REST client of the engine at {}", adapterId, configuration
                    .engineAddress());
            rest.close();
        });
    }

    /**
     * Whether {@link #close()} began.
     *
     * @return <code>true</code> from the start of the first {@link #close()} on
     */
    synchronized boolean isClosed() {
        return closed;
    }

    /**
     * What the engine wait of this adapter id found, kept for its later workflow modules.
     *
     * @return The memory of {@link ZenBpmEngineWait}
     */
    ZenBpmEngineWait.Memory engineWait() {
        return engineWait;
    }

    /**
     * Runs the logging of the engine's version and commit once per adapter id.
     *
     * @param logging Answers what the engine reported, or <code>null</code> where it did not
     *            answer
     */
    void onceTheEnvironmentIsLogged(final Supplier<EngineStatus> logging) {
        if (environmentLogged.compareAndSet(false, true)) {
            engineStatusAtStart = logging.get();
        }
    }

    private void requireConfigured() {
        if (unconfiguredMessage != null) {
            throw new IllegalStateException(unconfiguredMessage);
        }
    }

    private synchronized void removeShutdownHook(
        final String workflowModuleId,
        final WorkflowModuleShutdown shutdown
    ) {
        final var hooks = openWorkflowModules.get(workflowModuleId);
        if (hooks == null) {
            return;
        }
        hooks.remove(shutdown);
        if (hooks.isEmpty()) {
            openWorkflowModules.remove(workflowModuleId);
        }
    }

    // the backstop: a module the platform did not stop is stopped here, while the stream and the
    // REST client are still open, so its handlers can still complete what they run
    private void stopWorkflowModulesWhichDidNotStop(final Map<String, List<WorkflowModuleShutdown>> pending) {
        if (pending.isEmpty()) {
            return;
        }
        log.warn(
                "ZenBPM[{}]: the clients are being closed while the workflow module(s) '{}' still subscribe their job types, so the shutdown of the application did not stop their workflow processing. They are stopped now, before the job stream: a stream closed under them hands their jobs to other clients while their handlers may still run them. Report this as a defect of the platform wiring: on both platforms the lifecycle stops the modules first.",
                adapterId, String.join("', '", pending.keySet()));
        pending.forEach((
            module,
            registered) -> {
            // what was registered last stops first, so an extension's subscriptions end before
            // the adapter's own
            final var hooks = new ArrayList<>(registered);
            Collections.reverse(hooks);
            hooks.forEach(hook -> {
                try {
                    hook.stopWorkflowProcessing();
                } catch (final Exception e) {
                    log.warn(
                            "ZenBPM[{}]: stopping workflow module '{}' failed while the clients were being closed; they are closed anyway",
                            adapterId, module, e);
                }
            });
        });
    }

    // each step runs whether or not the one before it failed, so a stream which fails to close
    // does not keep the executor and the HTTP client open
    private static void closeEach(final Runnable... steps) {
        RuntimeException first = null;
        for (final var step : steps) {
            try {
                step.run();
            } catch (final RuntimeException e) {
                if (first == null) {
                    first = e;
                } else {
                    first.addSuppressed(e);
                }
            }
        }
        if (first != null) {
            throw first;
        }
    }

    /**
     * How the factory stops a workflow module on a path which did not reach
     * <code>stopWorkflowProcessing</code>. The deployment service implements it, because it owns
     * the module's subscriptions and its drain.
     */
    @FunctionalInterface
    public interface WorkflowModuleShutdown {

        /**
         * Stops the module's workflow processing and waits for it, as the ordinary shutdown does.
         * It has to be idempotent, because the module may stop itself a moment later.
         */
        void stopWorkflowProcessing();

    }

    /**
     * What removes a registered hook. Closing it removes that hook and no other, so several hooks
     * can share one workflow module.
     */
    @FunctionalInterface
    public interface WorkflowModuleShutdownRegistration extends AutoCloseable {

        /**
         * Removes the hook this registration was handed out for. Idempotent.
         */
        @Override
        void close();

    }

}

/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.client;

import java.time.Duration;

import org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration;
import org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration.MessageStartLookup;

/**
 * The keys of one ZenBPM adapter id at <code>vanillabp.adapters.&lt;id&gt;.*</code>, as Spring Boot
 * binds them: every key <code>null</code> where the application did not write it, so
 * {@link #applyTo(ZenBpmAdapterConfiguration)} hands the defaults to the core configuration
 * instead of repeating them. {@link ZenBpmAdapterConfiguration} documents what each key means, its
 * default and how it is validated.
 * <p>
 * The keys of the platform in the same section, such as <code>type</code> or
 * <code>deployment-failure</code>, are not modelled here; the platform's own binding reads them.
 */
public class ZenBpmAdapterKeys {

    private String restAddress;

    private String grpcAddress;

    private Boolean grpcPlaintext;

    private String clientId;

    private Duration requestTimeout;

    private Duration startupWait;

    private String workerThreads;

    private Integer workerThreadsBound;

    private Duration retryBackoff;

    private Integer maxRedeliveries;

    private Duration shutdownGrace;

    private Duration workflowVisibilityTimeout;

    private Duration userTaskPollInterval;

    private Duration historyTimeToLive;

    private Integer instanceCacheMaxEntries;

    private MessageStartLookup messageStartLookup;

    private Boolean acceptUnscopedIdentifiers;

    /**
     * Bound by Spring Boot, which builds the section with this constructor and then calls the
     * setters.
     */
    public ZenBpmAdapterKeys() {
    }

    /**
     * <code>rest-address</code>: the address of the engine's REST API.
     *
     * @return The value, or <code>null</code> where the application did not set the key
     */
    public String getRestAddress() {
        return restAddress;
    }

    /**
     * <code>rest-address</code>: the address of the engine's REST API.
     *
     * @param restAddress The value as bound
     */
    public void setRestAddress(final String restAddress) {
        this.restAddress = restAddress;
    }

    /**
     * <code>grpc-address</code>: <code>host:port</code> of the job stream.
     *
     * @return The value, or <code>null</code> where the application did not set the key
     */
    public String getGrpcAddress() {
        return grpcAddress;
    }

    /**
     * <code>grpc-address</code>: <code>host:port</code> of the job stream.
     *
     * @param grpcAddress The value as bound
     */
    public void setGrpcAddress(final String grpcAddress) {
        this.grpcAddress = grpcAddress;
    }

    /**
     * <code>grpc-plaintext</code>: whether the job stream goes without TLS.
     *
     * @return The value, or <code>null</code> where the application did not set the key
     */
    public Boolean getGrpcPlaintext() {
        return grpcPlaintext;
    }

    /**
     * <code>grpc-plaintext</code>: whether the job stream goes without TLS.
     *
     * @param grpcPlaintext The value as bound
     */
    public void setGrpcPlaintext(final Boolean grpcPlaintext) {
        this.grpcPlaintext = grpcPlaintext;
    }

    /**
     * <code>client-id</code>: the job stream's client id.
     *
     * @return The value, or <code>null</code> where the application did not set the key
     */
    public String getClientId() {
        return clientId;
    }

    /**
     * <code>client-id</code>: the job stream's client id.
     *
     * @param clientId The value as bound
     */
    public void setClientId(final String clientId) {
        this.clientId = clientId;
    }

    /**
     * <code>request-timeout</code>: the deadline of every REST call.
     *
     * @return The value, or <code>null</code> where the application did not set the key
     */
    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    /**
     * <code>request-timeout</code>: the deadline of every REST call.
     *
     * @param requestTimeout The value as bound
     */
    public void setRequestTimeout(final Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    /**
     * <code>startup-wait</code>: how long the first deployment waits for the engine to report ready.
     *
     * @return The value, or <code>null</code> where the application did not set the key
     */
    public Duration getStartupWait() {
        return startupWait;
    }

    /**
     * <code>startup-wait</code>: how long the first deployment waits for the engine to report ready.
     *
     * @param startupWait The value as bound
     */
    public void setStartupWait(final Duration startupWait) {
        this.startupWait = startupWait;
    }

    /**
     * <code>worker-threads</code>: how many platform threads run the handlers, or <code>virtual</code>.
     *
     * @return The value, or <code>null</code> where the application did not set the key
     */
    public String getWorkerThreads() {
        return workerThreads;
    }

    /**
     * <code>worker-threads</code>: how many platform threads run the handlers, or <code>virtual</code>.
     *
     * @param workerThreads The value as bound
     */
    public void setWorkerThreads(final String workerThreads) {
        this.workerThreads = workerThreads;
    }

    /**
     * <code>worker-threads-bound</code>: how many handlers run at the same time on virtual threads.
     *
     * @return The value, or <code>null</code> where the application did not set the key
     */
    public Integer getWorkerThreadsBound() {
        return workerThreadsBound;
    }

    /**
     * <code>worker-threads-bound</code>: how many handlers run at the same time on virtual threads.
     *
     * @param workerThreadsBound The value as bound
     */
    public void setWorkerThreadsBound(final Integer workerThreadsBound) {
        this.workerThreadsBound = workerThreadsBound;
    }

    /**
     * <code>retry-backoff</code>: the first wait after a failed handler, at the adapter level.
     *
     * @return The value, or <code>null</code> where the application did not set the key
     */
    public Duration getRetryBackoff() {
        return retryBackoff;
    }

    /**
     * <code>retry-backoff</code>: the first wait after a failed handler, at the adapter level.
     *
     * @param retryBackoff The value as bound
     */
    public void setRetryBackoff(final Duration retryBackoff) {
        this.retryBackoff = retryBackoff;
    }

    /**
     * <code>max-redeliveries</code>: after how many redeliveries a failing job becomes an incident, at the adapter level.
     *
     * @return The value, or <code>null</code> where the application did not set the key
     */
    public Integer getMaxRedeliveries() {
        return maxRedeliveries;
    }

    /**
     * <code>max-redeliveries</code>: after how many redeliveries a failing job becomes an incident, at the adapter level.
     *
     * @param maxRedeliveries The value as bound
     */
    public void setMaxRedeliveries(final Integer maxRedeliveries) {
        this.maxRedeliveries = maxRedeliveries;
    }

    /**
     * <code>shutdown-grace</code>: how long a shutdown waits for the handlers in flight.
     *
     * @return The value, or <code>null</code> where the application did not set the key
     */
    public Duration getShutdownGrace() {
        return shutdownGrace;
    }

    /**
     * <code>shutdown-grace</code>: how long a shutdown waits for the handlers in flight.
     *
     * @param shutdownGrace The value as bound
     */
    public void setShutdownGrace(final Duration shutdownGrace) {
        this.shutdownGrace = shutdownGrace;
    }

    /**
     * <code>workflow-visibility-timeout</code>: how long a started workflow may stay invisible to a query.
     *
     * @return The value, or <code>null</code> where the application did not set the key
     */
    public Duration getWorkflowVisibilityTimeout() {
        return workflowVisibilityTimeout;
    }

    /**
     * <code>workflow-visibility-timeout</code>: how long a started workflow may stay invisible to a query.
     *
     * @param workflowVisibilityTimeout The value as bound
     */
    public void setWorkflowVisibilityTimeout(final Duration workflowVisibilityTimeout) {
        this.workflowVisibilityTimeout = workflowVisibilityTimeout;
    }

    /**
     * <code>user-task-poll-interval</code>: how often user tasks are looked for.
     *
     * @return The value, or <code>null</code> where the application did not set the key
     */
    public Duration getUserTaskPollInterval() {
        return userTaskPollInterval;
    }

    /**
     * <code>user-task-poll-interval</code>: how often user tasks are looked for.
     *
     * @param userTaskPollInterval The value as bound
     */
    public void setUserTaskPollInterval(final Duration userTaskPollInterval) {
        this.userTaskPollInterval = userTaskPollInterval;
    }

    /**
     * <code>history-time-to-live</code>: how long the engine keeps the history of a started workflow, at the adapter level.
     *
     * @return The value, or <code>null</code> where the application did not set the key
     */
    public Duration getHistoryTimeToLive() {
        return historyTimeToLive;
    }

    /**
     * <code>history-time-to-live</code>: how long the engine keeps the history of a started workflow, at the adapter level.
     *
     * @param historyTimeToLive The value as bound
     */
    public void setHistoryTimeToLive(final Duration historyTimeToLive) {
        this.historyTimeToLive = historyTimeToLive;
    }

    /**
     * <code>instance-cache-max-entries</code>: the bound of the cache of process instance facts.
     *
     * @return The value, or <code>null</code> where the application did not set the key
     */
    public Integer getInstanceCacheMaxEntries() {
        return instanceCacheMaxEntries;
    }

    /**
     * <code>instance-cache-max-entries</code>: the bound of the cache of process instance facts.
     *
     * @param instanceCacheMaxEntries The value as bound
     */
    public void setInstanceCacheMaxEntries(final Integer instanceCacheMaxEntries) {
        this.instanceCacheMaxEntries = instanceCacheMaxEntries;
    }

    /**
     * <code>message-start-lookup</code>: what the adapter does when it is asked for workflows a message started.
     *
     * @return The value, or <code>null</code> where the application did not set the key
     */
    public MessageStartLookup getMessageStartLookup() {
        return messageStartLookup;
    }

    /**
     * <code>message-start-lookup</code>: what the adapter does when it is asked for workflows a message started.
     *
     * @param messageStartLookup The value as bound
     */
    public void setMessageStartLookup(final MessageStartLookup messageStartLookup) {
        this.messageStartLookup = messageStartLookup;
    }

    /**
     * <code>accept-unscoped-identifiers</code>: whether the identifiers of the workflow modules are
     * unique, so <code>name-clash-avoidance: none</code> is no reason to warn.
     *
     * @return The value, or <code>null</code> where the application did not set the key
     */
    public Boolean getAcceptUnscopedIdentifiers() {
        return acceptUnscopedIdentifiers;
    }

    /**
     * <code>accept-unscoped-identifiers</code>: whether the identifiers of the workflow modules are
     * unique, so <code>name-clash-avoidance: none</code> is no reason to warn.
     *
     * @param acceptUnscopedIdentifiers The value as bound
     */
    public void setAcceptUnscopedIdentifiers(final Boolean acceptUnscopedIdentifiers) {
        this.acceptUnscopedIdentifiers = acceptUnscopedIdentifiers;
    }

    /**
     * Copies every key into the core configuration of the adapter id. A key the application did not
     * write is handed on as <code>null</code>, which restores the core's default.
     *
     * @param configuration The core configuration of the adapter id
     */
    public void applyTo(final ZenBpmAdapterConfiguration configuration) {
        configuration.setRestAddress(restAddress);
        configuration.setGrpcAddress(grpcAddress);
        configuration.setGrpcPlaintext(grpcPlaintext);
        configuration.setClientId(clientId);
        configuration.setRequestTimeout(requestTimeout);
        configuration.setStartupWait(startupWait);
        configuration.setWorkerThreads(workerThreads);
        configuration.setWorkerThreadsBound(workerThreadsBound);
        configuration.setRetryBackoff(retryBackoff);
        configuration.setMaxRedeliveries(maxRedeliveries);
        configuration.setShutdownGrace(shutdownGrace);
        configuration.setWorkflowVisibilityTimeout(workflowVisibilityTimeout);
        configuration.setUserTaskPollInterval(userTaskPollInterval);
        configuration.setHistoryTimeToLive(historyTimeToLive);
        configuration.setInstanceCacheMaxEntries(instanceCacheMaxEntries);
        configuration.setMessageStartLookup(messageStartLookup);
        configuration.setAcceptUnscopedIdentifiers(acceptUnscopedIdentifiers);
    }

}

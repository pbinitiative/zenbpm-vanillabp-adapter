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
import java.util.List;
import java.util.Locale;
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
 * first deployment waits for the engine to report ready</li>
 * <li><code>worker-threads</code> (default {@value #DEFAULT_WORKER_THREADS}) and
 * <code>worker-threads-bound</code> (default: the same number) - how many handlers run at the
 * same time, <code>virtual</code> allowed</li>
 * <li><code>retry-backoff</code> (default {@value #DEFAULT_RETRY_BACKOFF_ISO}) - the first wait
 * after a failed handler</li>
 * <li><code>max-redeliveries</code> (default {@value #DEFAULT_MAX_REDELIVERIES}) - after which
 * a failing job becomes an incident</li>
 * <li><code>shutdown-grace</code> (default {@value #DEFAULT_SHUTDOWN_GRACE_ISO}) - how long a
 * shutdown waits for the handlers in flight</li>
 * <li><code>workflow-visibility-timeout</code> (default
 * {@value #DEFAULT_WORKFLOW_VISIBILITY_TIMEOUT_ISO}) - how long a started workflow may stay
 * invisible to a query</li>
 * <li><code>user-task-poll-interval</code> (default
 * {@value #DEFAULT_USER_TASK_POLL_INTERVAL_ISO}) - how often user tasks are looked for;
 * <code>PT0S</code> switches the notifications off</li>
 * <li><code>history-time-to-live</code> (default: none, the engine's own) - sent with every
 * start</li>
 * <li><code>instance-cache-max-entries</code> (default
 * {@value #DEFAULT_INSTANCE_CACHE_MAX_ENTRIES}) - the bound of the cache of process instance
 * facts</li>
 * <li><code>message-start-lookup</code> (default <code>refuse</code>) - <code>scan</code>
 * allows the bounded lookup of workflows a message started</li>
 * </ul>
 * <code>retry-backoff</code>, <code>max-redeliveries</code> and
 * <code>history-time-to-live</code> may also be set per workflow module, workflow or task;
 * this class holds the value of the adapter level only.
 * <p>
 * {@link #validate(String, boolean, boolean)} checks every key at startup. The derivations
 * ({@link #grpcAddressInUse()}, {@link #clientIdInUse(String, Supplier)},
 * {@link #restBaseUri()}, {@link #instanceIdentity()}) are defined for a configuration it
 * found complete.
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
   * The default of <code>retry-backoff</code> as written in a configuration.
   */
  public static final String DEFAULT_RETRY_BACKOFF_ISO = "PT10S";

  /**
   * The default of <code>retry-backoff</code>.
   */
  public static final Duration DEFAULT_RETRY_BACKOFF = Duration.parse(DEFAULT_RETRY_BACKOFF_ISO);

  /**
   * The default of <code>max-redeliveries</code>.
   */
  public static final int DEFAULT_MAX_REDELIVERIES = 10;

  /**
   * The default of <code>shutdown-grace</code> as written in a configuration.
   */
  public static final String DEFAULT_SHUTDOWN_GRACE_ISO = "PT20S";

  /**
   * The default of <code>shutdown-grace</code>.
   */
  public static final Duration DEFAULT_SHUTDOWN_GRACE = Duration.parse(DEFAULT_SHUTDOWN_GRACE_ISO);

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
   * The default of <code>instance-cache-max-entries</code>.
   */
  public static final int DEFAULT_INSTANCE_CACHE_MAX_ENTRIES = 10_000;

  /**
   * The application name a derived <code>client-id</code> starts with where the platform
   * knows none.
   */
  public static final String DEFAULT_APPLICATION_NAME = "application";

  /**
   * What the adapter does when it is asked for workflows a message started. The engine stores
   * no business key on a message start, so finding one means reading the instances.
   */
  public enum MessageStartLookup {

    /**
     * The lookup is refused with a message naming the key which allows it. The default.
     */
    REFUSE,

    /**
     * The lookup reads the instances of the workflow, bounded in number.
     */
    SCAN

  }

  /**
   * What {@link #validate(String, boolean, boolean)} found.
   *
   * @param kind Whether the adapter instance is unconfigured, inconsistent or complete
   * @param message The guiding message, naming the keys concerned; empty where complete
   * @param endsTheBoot Whether the platform has to end the boot with the message instead of
   *          logging it
   * @param notices Lines worth one INFO at startup, which the platform logs whatever the kind
   */
  public record ValidationResult(
                                 Kind kind,
                                 String message,
                                 boolean endsTheBoot,
                                 List<String> notices) {

    /**
     * The three outcomes of a validation.
     */
    public enum Kind {

      /**
       * No <code>rest-address</code> and nothing else wrong. The application boots with a
       * warning, and the adapter refuses work with the same message.
       */
      UNCONFIGURED,

      /**
       * At least one key holds a value which cannot work. The boot ends, unless the adapter is
       * nowhere first priority and its <code>deployment-failure</code> policy is
       * <code>warn</code>: then the application boots degraded.
       */
      INCONSISTENT,

      /**
       * Every key is usable. The client may be built.
       */
      COMPLETE

    }

    /**
     * Copies the notices, so a result cannot change after it was handed out.
     *
     * @param kind Whether the adapter instance is unconfigured, inconsistent or complete
     * @param message The guiding message, naming the keys concerned; empty where complete
     * @param endsTheBoot Whether the platform has to end the boot with the message
     * @param notices Lines worth one INFO at startup
     */
    public ValidationResult {

      notices = List.copyOf(notices);

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

  private Duration retryBackoff = DEFAULT_RETRY_BACKOFF;

  private int maxRedeliveries = DEFAULT_MAX_REDELIVERIES;

  private Duration shutdownGrace = DEFAULT_SHUTDOWN_GRACE;

  private Duration workflowVisibilityTimeout = DEFAULT_WORKFLOW_VISIBILITY_TIMEOUT;

  private Duration userTaskPollInterval = DEFAULT_USER_TASK_POLL_INTERVAL;

  private Duration historyTimeToLive;

  private int instanceCacheMaxEntries = DEFAULT_INSTANCE_CACHE_MAX_ENTRIES;

  private MessageStartLookup messageStartLookup = MessageStartLookup.REFUSE;

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
      final String key) {

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
  public void setRestAddress(
      final String restAddress) {

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
  public void setGrpcAddress(
      final String grpcAddress) {

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
  public void setGrpcPlaintext(
      final Boolean grpcPlaintext) {

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
  public void setClientId(
      final String clientId) {

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
  public void setRequestTimeout(
      final Duration requestTimeout) {

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
  public void setStartupWait(
      final Duration startupWait) {

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
   * @param workerThreads A positive number or <code>virtual</code>, or <code>null</code> for
   *          the default
   */
  public void setWorkerThreads(
      final String workerThreads) {

    this.workerThreads = workerThreads == null ? DEFAULT_WORKER_THREADS : workerThreads;

  }

  /**
   * How many handlers may run at the same time. Where it is not set, it is the number of
   * <code>worker-threads</code>, and {@value #DEFAULT_WORKER_THREADS} for virtual threads, which
   * have no limit of their own.
   *
   * @return The bound in use
   */
  public int getWorkerThreadsBound() {

    if (workerThreadsBound != null) {
      return workerThreadsBound;
    }
    final var threads = workerThreadsAsNumber();
    return threads == null ? Integer.parseInt(DEFAULT_WORKER_THREADS) : threads;

  }

  /**
   * How many handlers may run at the same time.
   *
   * @param workerThreadsBound The bound, or <code>null</code> for the default
   */
  public void setWorkerThreadsBound(
      final Integer workerThreadsBound) {

    this.workerThreadsBound = workerThreadsBound;

  }

  /**
   * The first wait after a failed handler, at the adapter level.
   *
   * @return Never <code>null</code>
   */
  public Duration getRetryBackoff() {

    return retryBackoff;

  }

  /**
   * The first wait after a failed handler, at the adapter level.
   *
   * @param retryBackoff The wait, or <code>null</code> for the default
   */
  public void setRetryBackoff(
      final Duration retryBackoff) {

    this.retryBackoff = retryBackoff == null ? DEFAULT_RETRY_BACKOFF : retryBackoff;

  }

  /**
   * After how many redeliveries a failing job becomes an incident, at the adapter level.
   *
   * @return The number
   */
  public int getMaxRedeliveries() {

    return maxRedeliveries;

  }

  /**
   * After how many redeliveries a failing job becomes an incident, at the adapter level.
   *
   * @param maxRedeliveries The number, or <code>null</code> for the default
   */
  public void setMaxRedeliveries(
      final Integer maxRedeliveries) {

    this.maxRedeliveries = maxRedeliveries == null ? DEFAULT_MAX_REDELIVERIES : maxRedeliveries;

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
  public void setShutdownGrace(
      final Duration shutdownGrace) {

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
  public void setWorkflowVisibilityTimeout(
      final Duration workflowVisibilityTimeout) {

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
  public void setUserTaskPollInterval(
      final Duration userTaskPollInterval) {

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
  public void setHistoryTimeToLive(
      final Duration historyTimeToLive) {

    this.historyTimeToLive = historyTimeToLive;

  }

  /**
   * The bound of the cache of process instance facts.
   *
   * @return The number of entries
   */
  public int getInstanceCacheMaxEntries() {

    return instanceCacheMaxEntries;

  }

  /**
   * The bound of the cache of process instance facts.
   *
   * @param instanceCacheMaxEntries The number of entries, or <code>null</code> for the default
   */
  public void setInstanceCacheMaxEntries(
      final Integer instanceCacheMaxEntries) {

    this.instanceCacheMaxEntries = instanceCacheMaxEntries == null
        ? DEFAULT_INSTANCE_CACHE_MAX_ENTRIES
        : instanceCacheMaxEntries;

  }

  /**
   * What the adapter does when it is asked for workflows a message started.
   *
   * @return Never <code>null</code>
   */
  public MessageStartLookup getMessageStartLookup() {

    return messageStartLookup;

  }

  /**
   * What the adapter does when it is asked for workflows a message started.
   *
   * @param messageStartLookup The choice, or <code>null</code> for the default
   */
  public void setMessageStartLookup(
      final MessageStartLookup messageStartLookup) {

    this.messageStartLookup = messageStartLookup == null ? MessageStartLookup.REFUSE : messageStartLookup;

  }

  /**
   * Checks every key of this adapter instance. Called once per adapter id at startup (Spring
   * Boot) or build time (Quarkus), before anything contacts the engine.
   * <p>
   * The messages name keys, and values only where a value cannot carry anything somebody
   * keeps to themselves: numbers and durations. Addresses and the client id are never
   * repeated; an address may carry a user and password, a client id a host name somebody
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
      final boolean deploymentFailureWarns) {

    final var defects = new ArrayList<String>();
    final var notices = new ArrayList<String>();

    final var restAddressMissing = isBlank(restAddress);
    if (!restAddressMissing) {
      final var restAddressDefect = restAddressDefect();
      if (restAddressDefect != null) {
        defects.add("%s %s".formatted(propertyKey(adapterId, "rest-address"), restAddressDefect));
      } else if (endsWithApiVersion(parsedRestAddress().getPath())) {
        notices.add(
            """
                ZenBPM adapter '%s': '%s' already ends with '%s'. The adapter appends it itself, so \
                the key may name the engine's address without it. It is used as written."""
                .formatted(adapterId, propertyKey(adapterId, "rest-address"), API_VERSION_PATH));
      }
    }

    if (grpcAddress != null) {
      final var grpcAddressDefect = grpcAddressDefect(grpcAddress.trim());
      if (grpcAddressDefect != null) {
        defects.add("%s %s".formatted(propertyKey(adapterId, "grpc-address"), grpcAddressDefect));
      }
    }

    if ((clientId != null) && clientId.isBlank()) {
      defects.add(
          "%s is blank; remove the key to have the id derived as <application name>-%s-<random>"
              .formatted(propertyKey(adapterId, "client-id"), adapterId));
    }

    if (requestTimeout.compareTo(MINIMUM_REQUEST_TIMEOUT) < 0) {
      defects.add("%s is %s and has to be at least %s; the default is %s"
          .formatted(
              propertyKey(adapterId, "request-timeout"),
              requestTimeout,
              MINIMUM_REQUEST_TIMEOUT,
              DEFAULT_REQUEST_TIMEOUT_ISO));
    }

    addWorkerThreadsDefects(adapterId, defects);

    addNegativeDurationDefect(adapterId, "startup-wait", startupWait, defects);
    addNegativeDurationDefect(adapterId, "retry-backoff", retryBackoff, defects);
    addNegativeDurationDefect(adapterId, "shutdown-grace", shutdownGrace, defects);
    addNegativeDurationDefect(adapterId, "workflow-visibility-timeout", workflowVisibilityTimeout, defects);
    addNegativeDurationDefect(adapterId, "user-task-poll-interval", userTaskPollInterval, defects);
    if (historyTimeToLive != null) {
      addNegativeDurationDefect(adapterId, "history-time-to-live", historyTimeToLive, defects);
    }

    if (maxRedeliveries < 0) {
      defects.add("%s is %d and must not be negative; 0 turns the first failure into an incident"
          .formatted(propertyKey(adapterId, "max-redeliveries"), maxRedeliveries));
    }
    if (instanceCacheMaxEntries < 0) {
      defects.add("%s is %d and must not be negative"
          .formatted(propertyKey(adapterId, "instance-cache-max-entries"), instanceCacheMaxEntries));
    }

    if (defects.isEmpty()) {
      return restAddressMissing
          ? new ValidationResult(ValidationResult.Kind.UNCONFIGURED, unconfiguredMessage(adapterId), false, notices)
          : new ValidationResult(ValidationResult.Kind.COMPLETE, "", false, notices);
    }
    // a missing address beside a value which cannot work is no longer "not configured yet":
    // somebody configured this adapter and has to finish it
    if (restAddressMissing) {
      defects.addFirst("%s is missing; it is the address of the engine's REST API, e.g. http://localhost:8080"
          .formatted(propertyKey(adapterId, "rest-address")));
    }

    final var degrades = nowhereFirst && deploymentFailureWarns;
    return new ValidationResult(
        ValidationResult.Kind.INCONSISTENT, inconsistentMessage(adapterId, defects, nowhereFirst,
            degrades), !degrades, notices);

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
      final Supplier<String> applicationName) {

    if (clientId != null) {
      return clientId.trim();
    }
    final var name = applicationName.get();
    return "%s-%s-%s".formatted(isBlank(name) ? DEFAULT_APPLICATION_NAME : name.trim(), adapterId, clientIdSuffix);

  }

  /**
   * The base of every REST call: <code>rest-address</code> normalised (lower-cased scheme and
   * host, explicit port, no trailing slash) with <code>/v1</code> appended unless it already
   * ends with it.
   *
   * @return E.g. <code>http://zenbpm:8080/v1</code>
   * @throws IllegalStateException If <code>rest-address</code> is missing or malformed
   */
  public URI restBaseUri() {

    final var address = parsedRestAddress();
    final var scheme = address
        .getScheme()
        .toLowerCase(Locale.ROOT);
    final var port = address.getPort() != -1
        ? address.getPort()
        : "https".equals(scheme) ? 443 : 80;
    final var path = withoutTrailingSlashes(address.getRawPath());
    return URI.create("%s://%s:%d%s".formatted(
        scheme,
        address
            .getHost()
            .toLowerCase(Locale.ROOT),
        port,
        endsWithApiVersion(path) ? path : path + API_VERSION_PATH));

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
    if (address.getHost() == null) {
      return "names no host the JDK's HTTP client accepts (a host name may not contain '_')";
    }
    if ((address.getPort() == 0) || (address.getPort() > 65_535)) {
      return "names a port outside 1 to 65535";
    }
    if ((address.getRawQuery() != null) || (address.getRawFragment() != null)) {
      return "carries a query or a fragment; it is the engine's address and nothing else";
    }
    return null;

  }

  private static String grpcAddressDefect(
      final String address) {

    if (address.contains("://")) {
      return "is host:port, without a scheme, e.g. localhost:%d".formatted(DEFAULT_GRPC_PORT);
    }
    final var colon = address.lastIndexOf(':');
    if ((colon == -1) || address.endsWith("]")) {
      return "names no port; write it as host:port, e.g. localhost:%d".formatted(DEFAULT_GRPC_PORT);
    }
    if (colon == 0) {
      return "names no host; write it as host:port, e.g. localhost:%d".formatted(DEFAULT_GRPC_PORT);
    }
    final int port;
    try {
      port = Integer.parseInt(address.substring(colon + 1));
    } catch (NumberFormatException e) {
      return "names no port; write it as host:port, e.g. localhost:%d".formatted(DEFAULT_GRPC_PORT);
    }
    if ((port < 1) || (port > 65_535)) {
      return "names a port outside 1 to 65535";
    }
    return null;

  }

  private void addWorkerThreadsDefects(
      final String adapterId,
      final List<String> defects) {

    final var configured = workerThreads.trim();
    final var threads = workerThreadsAsNumber();
    if ((threads == null) && !VIRTUAL_WORKER_THREADS.equalsIgnoreCase(configured)) {
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
    if (workerThreadsBound < 1) {
      defects.add("%s is %d and has to be at least 1"
          .formatted(propertyKey(adapterId, "worker-threads-bound"), workerThreadsBound));
    } else if ((threads != null) && (threads > workerThreadsBound)) {
      defects.add("%s is %d, above %s of %d, which caps how many handlers run at the same time"
          .formatted(
              propertyKey(adapterId, "worker-threads"),
              threads,
              propertyKey(adapterId, "worker-threads-bound"),
              workerThreadsBound));
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
      final List<String> defects) {

    if (configured.isNegative()) {
      defects.add("%s is %s and must not be negative".formatted(propertyKey(adapterId, key), configured));
    }

  }

  private static String unconfiguredMessage(
      final String adapterId) {

    return """
        ZenBPM adapter '%s' is not configured: '%s' is missing. The application boots, and the \
        adapter refuses work with this message until the address of the engine's REST API is \
        added, e.g.:
          vanillabp:
            adapters:
              %s:
                rest-address: http://localhost:8080
        The adapter appends '%s' itself. The job stream's address and client id are derived \
        unless '%s' and '%s' are set."""
        .formatted(
            adapterId,
            propertyKey(adapterId, "rest-address"),
            adapterId,
            API_VERSION_PATH,
            propertyKey(adapterId, "grpc-address"),
            propertyKey(adapterId, "client-id"));

  }

  private static String inconsistentMessage(
      final String adapterId,
      final List<String> defects,
      final boolean nowhereFirst,
      final boolean degrades) {

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
    } else if (nowhereFirst) {
      consequence = """
          Correct the keys. An adapter which is nowhere first priority, like the old engine of a \
          migration, may instead set '%s: warn' to boot degraded."""
          .formatted(propertyKey(adapterId, "deployment-failure"));
    } else {
      consequence = "Correct the keys. The adapter is first priority somewhere, so it cannot boot degraded.";
    }
    return "ZenBPM adapter '%s' is configured inconsistently:\n%s\n%s".formatted(adapterId, listed, consequence);

  }

  private static boolean endsWithApiVersion(
      final String path) {

    return withoutTrailingSlashes(path).endsWith(API_VERSION_PATH);

  }

  private static String withoutTrailingSlashes(
      final String path) {

    var trimmed = path == null ? "" : path;
    while (trimmed.endsWith("/")) {
      trimmed = trimmed.substring(0, trimmed.length() - 1);
    }
    return trimmed;

  }

  private static boolean isBlank(
      final String text) {

    return (text == null) || text.isBlank();

  }

}

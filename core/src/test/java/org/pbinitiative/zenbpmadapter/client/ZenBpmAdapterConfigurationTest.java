/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration.MessageStartLookup;
import org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration.ValidationResult.Kind;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What the startup validation of one adapter instance reports, and what the configuration
 * derives from a complete one.
 */
@ExtendWith(SuppressOutputExtension.class)
public class ZenBpmAdapterConfigurationTest {

  private static final String ADAPTER_ID = "orders";

  @Test
  @DisplayName("A configuration without a REST address is unconfigured and tells which YAML to add")
  public void anUnconfiguredAdapterIsToldWhatToAdd() {

    final var validation = new ZenBpmAdapterConfiguration().validate(ADAPTER_ID, false, false);

    assertEquals(Kind.UNCONFIGURED, validation.kind());
    assertFalse(validation.endsTheBoot());
    assertTrue(validation
        .message()
        .contains("'vanillabp.adapters.orders.rest-address' is missing"), validation.message());
    assertTrue(validation
        .message()
        .contains("""
              vanillabp:
                adapters:
                  orders:
                    rest-address: http://localhost:8080
            """), validation.message());

  }

  @Test
  @DisplayName("A blank REST address counts as none")
  public void aBlankRestAddressIsUnconfigured() {

    final var configuration = new ZenBpmAdapterConfiguration();
    configuration.setRestAddress("  ");

    assertEquals(Kind.UNCONFIGURED, configuration
        .validate(ADAPTER_ID, false, false)
        .kind());

  }

  @Test
  @DisplayName("A REST address and nothing else is a complete configuration with every default")
  public void aRestAddressAloneIsComplete() {

    final var configuration = configured("http://zenbpm:8080");

    final var validation = configuration.validate(ADAPTER_ID, false, false);

    assertEquals(Kind.COMPLETE, validation.kind());
    assertEquals("", validation.message());
    assertFalse(validation.endsTheBoot());
    assertEquals(List.of(), validation.notices());
    assertNull(configuration.getGrpcAddress());
    assertTrue(configuration.isGrpcPlaintext());
    assertNull(configuration.getClientId());
    assertEquals(Duration.ofSeconds(10), configuration.getRequestTimeout());
    assertEquals(Duration.ofMinutes(10), configuration.getStartupWait());
    assertEquals("4", configuration.getWorkerThreads());
    assertEquals(4, configuration.getWorkerThreadsBound());
    assertEquals(Duration.ofSeconds(10), configuration.getRetryBackoff());
    assertEquals(10, configuration.getMaxRedeliveries());
    assertEquals(Duration.ofSeconds(20), configuration.getShutdownGrace());
    assertEquals(Duration.ofSeconds(2), configuration.getWorkflowVisibilityTimeout());
    assertEquals(Duration.ofSeconds(5), configuration.getUserTaskPollInterval());
    assertNull(configuration.getHistoryTimeToLive());
    assertEquals(10_000, configuration.getInstanceCacheMaxEntries());
    assertEquals(MessageStartLookup.REFUSE, configuration.getMessageStartLookup());

  }

  @Test
  @DisplayName("Every key holds what was set, and null restores its default")
  public void everySetterTakesAValueAndNullRestoresTheDefault() {

    final var configuration = configured("https://zenbpm");
    configuration.setGrpcAddress("zenbpm-grpc:443");
    configuration.setGrpcPlaintext(false);
    configuration.setClientId("orders-node-1");
    configuration.setRequestTimeout(Duration.ofSeconds(3));
    configuration.setStartupWait(Duration.ofMinutes(1));
    configuration.setWorkerThreads("virtual");
    configuration.setWorkerThreadsBound(16);
    configuration.setRetryBackoff(Duration.ofSeconds(1));
    configuration.setMaxRedeliveries(0);
    configuration.setShutdownGrace(Duration.ZERO);
    configuration.setWorkflowVisibilityTimeout(Duration.ofSeconds(5));
    configuration.setUserTaskPollInterval(Duration.ZERO);
    configuration.setHistoryTimeToLive(Duration.ofDays(30));
    configuration.setInstanceCacheMaxEntries(0);
    configuration.setMessageStartLookup(MessageStartLookup.SCAN);

    assertEquals(Kind.COMPLETE, configuration
        .validate(ADAPTER_ID, false, false)
        .kind());
    assertEquals("https://zenbpm", configuration.getRestAddress());
    assertEquals("zenbpm-grpc:443", configuration.getGrpcAddress());
    assertFalse(configuration.isGrpcPlaintext());
    assertEquals("orders-node-1", configuration.getClientId());
    assertEquals(Duration.ofSeconds(3), configuration.getRequestTimeout());
    assertEquals(Duration.ofMinutes(1), configuration.getStartupWait());
    assertEquals("virtual", configuration.getWorkerThreads());
    assertEquals(16, configuration.getWorkerThreadsBound());
    assertEquals(Duration.ofSeconds(1), configuration.getRetryBackoff());
    assertEquals(0, configuration.getMaxRedeliveries());
    assertEquals(Duration.ZERO, configuration.getShutdownGrace());
    assertEquals(Duration.ofSeconds(5), configuration.getWorkflowVisibilityTimeout());
    assertEquals(Duration.ZERO, configuration.getUserTaskPollInterval());
    assertEquals(Duration.ofDays(30), configuration.getHistoryTimeToLive());
    assertEquals(0, configuration.getInstanceCacheMaxEntries());
    assertEquals(MessageStartLookup.SCAN, configuration.getMessageStartLookup());

    configuration.setGrpcPlaintext(null);
    configuration.setRequestTimeout(null);
    configuration.setStartupWait(null);
    configuration.setWorkerThreads(null);
    configuration.setWorkerThreadsBound(null);
    configuration.setRetryBackoff(null);
    configuration.setMaxRedeliveries(null);
    configuration.setShutdownGrace(null);
    configuration.setWorkflowVisibilityTimeout(null);
    configuration.setUserTaskPollInterval(null);
    configuration.setInstanceCacheMaxEntries(null);
    configuration.setMessageStartLookup(null);

    final var defaults = configured("https://zenbpm");
    assertEquals(defaults.isGrpcPlaintext(), configuration.isGrpcPlaintext());
    assertEquals(defaults.getRequestTimeout(), configuration.getRequestTimeout());
    assertEquals(defaults.getStartupWait(), configuration.getStartupWait());
    assertEquals(defaults.getWorkerThreads(), configuration.getWorkerThreads());
    assertEquals(defaults.getWorkerThreadsBound(), configuration.getWorkerThreadsBound());
    assertEquals(defaults.getRetryBackoff(), configuration.getRetryBackoff());
    assertEquals(defaults.getMaxRedeliveries(), configuration.getMaxRedeliveries());
    assertEquals(defaults.getShutdownGrace(), configuration.getShutdownGrace());
    assertEquals(defaults.getWorkflowVisibilityTimeout(), configuration.getWorkflowVisibilityTimeout());
    assertEquals(defaults.getUserTaskPollInterval(), configuration.getUserTaskPollInterval());
    assertEquals(defaults.getInstanceCacheMaxEntries(), configuration.getInstanceCacheMaxEntries());
    assertEquals(defaults.getMessageStartLookup(), configuration.getMessageStartLookup());

  }

  static Stream<Arguments> defects() {

    return Stream.of(
        defect("rest-address", "localhost:8080", c -> c.setRestAddress("localhost:8080")),
        defect("rest-address", "a URL with spaces", c -> c.setRestAddress("http://zen bpm:8080")),
        defect("rest-address", "another scheme", c -> c.setRestAddress("grpc://zenbpm:9090")),
        defect("rest-address", "an underscore in the host", c -> c.setRestAddress("http://zen_bpm:8080")),
        defect("rest-address", "a port of 0", c -> c.setRestAddress("http://zenbpm:0")),
        defect("rest-address", "a port above 65535", c -> c.setRestAddress("http://zenbpm:65536")),
        defect("rest-address", "a query", c -> c.setRestAddress("http://zenbpm:8080?tenant=a")),
        defect("rest-address", "a fragment", c -> c.setRestAddress("http://zenbpm:8080#v1")),
        defect("grpc-address", "no port", c -> c.setGrpcAddress("zenbpm")),
        defect("grpc-address", "an empty port", c -> c.setGrpcAddress("zenbpm:")),
        defect("grpc-address", "an IPv6 host without a port", c -> c.setGrpcAddress("[::1]")),
        defect("grpc-address", "a scheme", c -> c.setGrpcAddress("http://zenbpm:9090")),
        defect("grpc-address", "no host", c -> c.setGrpcAddress(":9090")),
        defect("grpc-address", "a port which is no number", c -> c.setGrpcAddress("zenbpm:grpc")),
        defect("grpc-address", "a port of 0", c -> c.setGrpcAddress("zenbpm:0")),
        defect("grpc-address", "a port above 65535", c -> c.setGrpcAddress("zenbpm:65536")),
        defect("grpc-address", "a blank value", c -> c.setGrpcAddress(" ")),
        defect("client-id", "a blank value", c -> c.setClientId(" ")),
        defect("request-timeout", "half a second", c -> c.setRequestTimeout(Duration.ofMillis(500))),
        defect("request-timeout", "a negative duration", c -> c.setRequestTimeout(Duration.ofSeconds(-1))),
        defect("worker-threads", "zero", c -> c.setWorkerThreads("0")),
        defect("worker-threads", "a word", c -> c.setWorkerThreads("many")),
        defect("worker-threads-bound", "zero beside virtual threads", c -> {
          c.setWorkerThreads("virtual");
          c.setWorkerThreadsBound(0);
        }),
        defect("worker-threads-bound", "a value beside the default platform threads",
            c -> c.setWorkerThreadsBound(16)),
        defect("startup-wait", "a negative duration", c -> c.setStartupWait(Duration.ofSeconds(-1))),
        defect("retry-backoff", "a negative duration", c -> c.setRetryBackoff(Duration.ofSeconds(-1))),
        defect("shutdown-grace", "a negative duration", c -> c.setShutdownGrace(Duration.ofSeconds(-1))),
        defect("workflow-visibility-timeout", "a negative duration",
            c -> c.setWorkflowVisibilityTimeout(Duration.ofSeconds(-1))),
        defect("user-task-poll-interval", "a negative duration",
            c -> c.setUserTaskPollInterval(Duration.ofSeconds(-1))),
        defect("history-time-to-live", "a negative duration",
            c -> c.setHistoryTimeToLive(Duration.ofDays(-1))),
        defect("max-redeliveries", "a negative number", c -> c.setMaxRedeliveries(-1)),
        defect("instance-cache-max-entries", "a negative number", c -> c.setInstanceCacheMaxEntries(-1)));

  }

  @ParameterizedTest(name = "{0} with {1}")
  @MethodSource("defects")
  @DisplayName("Each defect ends the boot and names its key")
  public void eachDefectIsNamedWithItsKey(
      final String key,
      final String description,
      final Consumer<ZenBpmAdapterConfiguration> breakIt) {

    final var configuration = configured("http://zenbpm:8080");
    breakIt.accept(configuration);

    final var validation = configuration.validate(ADAPTER_ID, false, false);

    assertEquals(Kind.INCONSISTENT, validation.kind());
    assertTrue(validation.endsTheBoot());
    assertTrue(validation
        .message()
        .contains("  - vanillabp.adapters.orders.%s ".formatted(key)), validation.message());

  }

  @Test
  @DisplayName("Two defects are reported in one message")
  public void twoDefectsAreReportedTogether() {

    final var configuration = configured("zenbpm:8080");
    configuration.setRequestTimeout(Duration.ZERO);

    final var message = configuration
        .validate(ADAPTER_ID, false, false)
        .message();

    assertTrue(message.contains("  - vanillabp.adapters.orders.rest-address "), message);
    assertTrue(message.contains("  - vanillabp.adapters.orders.request-timeout "), message);

  }

  @Test
  @DisplayName("A defect beside a missing REST address is inconsistent and names both keys")
  public void aDefectBesideAMissingAddressIsInconsistent() {

    final var configuration = new ZenBpmAdapterConfiguration();
    configuration.setWorkerThreads("0");

    final var validation = configuration.validate(ADAPTER_ID, false, false);

    assertEquals(Kind.INCONSISTENT, validation.kind());
    assertTrue(validation
        .message()
        .contains("  - vanillabp.adapters.orders.rest-address is missing"), validation.message());
    assertTrue(validation
        .message()
        .contains("  - vanillabp.adapters.orders.worker-threads "), validation.message());

  }

  @Test
  @DisplayName("Neither the client id nor a password in the REST address is ever repeated")
  public void valuesWhichMayBeSecretAreNeverRepeated() {

    final var configuration = configured("http://operator:s3cret@zenbpm.internal:8080");
    configuration.setClientId("billing-host-17.internal");
    configuration.setGrpcAddress("zenbpm.internal");

    final var message = configuration
        .validate(ADAPTER_ID, false, false)
        .message();

    assertTrue(message.contains("vanillabp.adapters.orders.rest-address "), message);
    assertTrue(message.contains("vanillabp.adapters.orders.grpc-address "), message);
    assertFalse(message.contains("s3cret"), message);
    assertFalse(message.contains("operator"), message);
    assertFalse(message.contains("zenbpm.internal"), message);
    assertFalse(message.contains("billing-host-17"), message);

  }

  @Test
  @DisplayName("An adapter which is nowhere first and warns on deployment failures boots degraded")
  public void aNowhereFirstAdapterWithWarnBootsDegraded() {

    final var validation = configured("zenbpm:8080").validate(ADAPTER_ID, true, true);

    assertEquals(Kind.INCONSISTENT, validation.kind());
    assertFalse(validation.endsTheBoot());
    assertTrue(validation
        .message()
        .contains("'vanillabp.adapters.orders.deployment-failure' is 'warn', so the application boots DEGRADED"),
        validation.message());

  }

  @Test
  @DisplayName("An adapter which is nowhere first but fails on deployment failures is shown the way to degrade")
  public void aNowhereFirstAdapterWithFailIsShownTheWarnPolicy() {

    final var validation = configured("zenbpm:8080").validate(ADAPTER_ID, true, false);

    assertTrue(validation.endsTheBoot());
    assertTrue(validation
        .message()
        .contains("may instead set 'vanillabp.adapters.orders.deployment-failure: warn'"), validation.message());

  }

  @Test
  @DisplayName("An adapter which is first priority somewhere cannot boot degraded, whatever its policy")
  public void aFirstPriorityAdapterNeverDegrades() {

    final var validation = configured("zenbpm:8080").validate(ADAPTER_ID, false, true);

    assertTrue(validation.endsTheBoot());
    assertTrue(validation
        .message()
        .contains("first priority somewhere, so it cannot boot degraded"), validation.message());

  }

  @Test
  @DisplayName("An unconfigured adapter never ends the boot, whatever its priority and policy")
  public void anUnconfiguredAdapterNeverEndsTheBoot() {

    final var configuration = new ZenBpmAdapterConfiguration();

    assertFalse(configuration
        .validate(ADAPTER_ID, false, false)
        .endsTheBoot());
    assertFalse(configuration
        .validate(ADAPTER_ID, true, true)
        .endsTheBoot());

  }

  @ParameterizedTest
  @ValueSource(strings = {
      "virtual", "VIRTUAL", " virtual "
  })
  @DisplayName("Virtual worker threads are accepted, bounded by four unless a bound is set")
  public void virtualWorkerThreadsAreAccepted(
      final String workerThreads) {

    final var configuration = configured("http://zenbpm:8080");
    configuration.setWorkerThreads(workerThreads);

    assertEquals(Kind.COMPLETE, configuration
        .validate(ADAPTER_ID, false, false)
        .kind());
    assertEquals(4, configuration.getWorkerThreadsBound());

    configuration.setWorkerThreadsBound(32);

    assertEquals(Kind.COMPLETE, configuration
        .validate(ADAPTER_ID, false, false)
        .kind());
    assertEquals(32, configuration.getWorkerThreadsBound());

  }

  @ParameterizedTest
  @ValueSource(ints = {
      4, 12, 20
  })
  @DisplayName("Platform threads are their own bound, so a bound beside them is refused whatever its size")
  public void platformThreadsAreTheirOwnBound(
      final int bound) {

    final var configuration = configured("http://zenbpm:8080");
    configuration.setWorkerThreads("12");

    assertEquals(12, configuration.getWorkerThreadsBound());

    configuration.setWorkerThreadsBound(bound);
    final var validation = configuration.validate(ADAPTER_ID, false, false);

    assertEquals(Kind.INCONSISTENT, validation.kind());
    assertTrue(validation
        .message()
        .contains(
            "  - vanillabp.adapters.orders.worker-threads-bound is set, but vanillabp.adapters.orders.worker-threads is a number of platform threads"),
        validation.message());
    assertTrue(validation
        .message()
        .contains("set 'virtual' or remove the bound"), validation.message());

  }

  @Test
  @DisplayName("A misspelt 'virtual' beside a bound is reported once, as the worker threads it is")
  public void aMisspeltVirtualIsReportedOnce() {

    final var configuration = configured("http://zenbpm:8080");
    configuration.setWorkerThreads("virtul");
    configuration.setWorkerThreadsBound(16);

    final var message = configuration
        .validate(ADAPTER_ID, false, false)
        .message();

    assertTrue(message.contains("  - vanillabp.adapters.orders.worker-threads is neither a number nor 'virtual'"),
        message);
    assertFalse(message.contains("  - vanillabp.adapters.orders.worker-threads-bound "), message);

  }

  @Test
  @DisplayName("The gRPC address defaults to the REST host and port 9090, and a configured one is used as written")
  public void theGrpcAddressIsDerivedFromTheRestHost() {

    final var configuration = configured("https://ZenBPM.example:8443/");

    assertEquals("ZenBPM.example:9090", configuration.grpcAddressInUse());

    configuration.setGrpcAddress(" zenbpm-grpc.example:443 ");

    assertEquals("zenbpm-grpc.example:443", configuration.grpcAddressInUse());
    assertEquals(Kind.COMPLETE, configuration
        .validate(ADAPTER_ID, false, false)
        .kind());

  }

  @Test
  @DisplayName("An IPv6 REST host gives an IPv6 gRPC address, which validates")
  public void anIpv6HostIsDerivedInBrackets() {

    final var configuration = configured("http://[::1]:8080");

    assertEquals("[::1]:9090", configuration.grpcAddressInUse());

    configuration.setGrpcAddress(configuration.grpcAddressInUse());

    assertEquals(Kind.COMPLETE, configuration
        .validate(ADAPTER_ID, false, false)
        .kind());

  }

  @Test
  @DisplayName("The client id is derived from application name, adapter id and a random part drawn once")
  public void theClientIdIsDerived() {

    final var configuration = configured("http://zenbpm:8080");

    final var clientId = configuration.clientIdInUse(ADAPTER_ID, () -> "billing");

    assertTrue(clientId.matches("billing-orders-[0-9a-f]{8}"), clientId);
    assertEquals(clientId, configuration.clientIdInUse(ADAPTER_ID, () -> "billing"));
    assertNotEquals(clientId, configured("http://zenbpm:8080").clientIdInUse(ADAPTER_ID, () -> "billing"));
    assertTrue(configuration
        .clientIdInUse(ADAPTER_ID, () -> null)
        .matches("application-orders-[0-9a-f]{8}"));
    assertTrue(configuration
        .clientIdInUse(ADAPTER_ID, () -> " ")
        .matches("application-orders-[0-9a-f]{8}"));

    configuration.setClientId(" billing-node-1 ");

    assertEquals("billing-node-1", configuration.clientIdInUse(ADAPTER_ID, () -> "billing"));

  }

  @Test
  @DisplayName("'/v1' is appended to the REST address once, and an address carrying it already is noticed")
  public void theApiVersionIsAppendedOnce() {

    assertEquals(URI.create("http://zenbpm:8080/v1"), configured("http://zenbpm:8080").restBaseUri());
    assertEquals(URI.create("http://zenbpm:8080/v1"), configured("http://zenbpm:8080/").restBaseUri());
    assertEquals(URI.create("http://zenbpm:80/engine/v1"), configured("http://zenbpm/engine/").restBaseUri());
    assertEquals(URI.create("https://zenbpm:443/v1"), configured("https://zenbpm").restBaseUri());

    final var configuration = configured("http://zenbpm:8080/v1/");

    assertEquals(URI.create("http://zenbpm:8080/v1"), configuration.restBaseUri());
    final var validation = configuration.validate(ADAPTER_ID, false, false);
    assertEquals(Kind.COMPLETE, validation.kind());
    assertEquals(1, validation
        .notices()
        .size());
    assertTrue(validation
        .notices()
        .getFirst()
        .contains("'vanillabp.adapters.orders.rest-address' already ends with '/v1'"), validation.notices().getFirst());

  }

  @Test
  @DisplayName("Two spellings of one engine's address have one identity, another port another")
  public void theIdentityIsNormalised() {

    final var identity = configured("http://host:8080").instanceIdentity();

    assertEquals(identity, configured("HTTP://Host:8080/").instanceIdentity());
    assertEquals(identity, configured(" http://HOST:8080/v1 ").instanceIdentity());
    assertEquals(configured("http://host").instanceIdentity(), configured("http://host:80/").instanceIdentity());
    assertNotEquals(identity, configured("http://host:8081").instanceIdentity());
    assertNotEquals(identity, configured("https://host:8080").instanceIdentity());

  }

  @ParameterizedTest
  @ValueSource(strings = {
      "", "zenbpm:8080"
  })
  @DisplayName("Nothing is derived from a missing or malformed REST address")
  public void nothingIsDerivedFromAnUnusableAddress(
      final String restAddress) {

    final var configuration = configured(restAddress);

    assertThrows(IllegalStateException.class, configuration::restBaseUri);
    assertThrows(IllegalStateException.class, configuration::instanceIdentity);
    assertThrows(IllegalStateException.class, configuration::grpcAddressInUse);

  }

  @Test
  @DisplayName("The notices of a validation cannot be changed afterwards")
  public void theNoticesAreACopy() {

    final var notices = new ArrayList<String>();
    notices.add("one");
    final var validation = new ZenBpmAdapterConfiguration.ValidationResult(Kind.COMPLETE, "", false, notices);
    notices.add("two");

    assertEquals(List.of("one"), validation.notices());
    assertThrows(UnsupportedOperationException.class, () -> validation
        .notices()
        .add("three"));

  }

  private static ZenBpmAdapterConfiguration configured(
      final String restAddress) {

    final var configuration = new ZenBpmAdapterConfiguration();
    configuration.setRestAddress(restAddress);
    return configuration;

  }

  private static Arguments defect(
      final String key,
      final String description,
      final Consumer<ZenBpmAdapterConfiguration> breakIt) {

    return Arguments.of(key, description, breakIt);

  }

}

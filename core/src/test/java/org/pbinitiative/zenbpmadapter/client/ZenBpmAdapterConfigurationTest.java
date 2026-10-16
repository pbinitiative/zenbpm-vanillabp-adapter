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
class ZenBpmAdapterConfigurationTest {

    private static final String ADAPTER_ID = "orders";

    @Test
    @DisplayName("A configuration without a REST address is unconfigured and tells which YAML to add")
    void anUnconfiguredAdapterIsToldWhatToAdd() {
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
    void aBlankRestAddressIsUnconfigured() {
        final var configuration = new ZenBpmAdapterConfiguration();
        configuration.setRestAddress("  ");

        assertEquals(Kind.UNCONFIGURED, configuration
                .validate(ADAPTER_ID, false, false)
                .kind());
    }

    @Test
    @DisplayName("A REST address and nothing else is a complete configuration with every default")
    void aRestAddressAloneIsComplete() {
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
        assertNull(configuration.getWorkerThreadsBound());
        assertEquals(4, configuration.workerThreadsBoundInUse());
        assertEquals(Duration.ofSeconds(10), configuration.getRetryBackoff());
        assertEquals(10, configuration.getMaxRedeliveries());
        assertEquals(Duration.ofSeconds(20), configuration.getShutdownGrace());
        assertEquals(Duration.ofSeconds(2), configuration.getWorkflowVisibilityTimeout());
        assertEquals(Duration.ofSeconds(5), configuration.getUserTaskPollInterval());
        assertNull(configuration.getHistoryTimeToLive());
        assertEquals(10_000, configuration.getInstanceCacheMaxEntries());
        assertEquals(MessageStartLookup.REFUSE, configuration.getMessageStartLookup());
        assertFalse(configuration.isAcceptUnscopedIdentifiers());
    }

    @Test
    @DisplayName("Every key holds what was set, and null restores its default")
    void everySetterTakesAValueAndNullRestoresTheDefault() {
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
        configuration.setAcceptUnscopedIdentifiers(true);

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
        assertEquals(Integer.valueOf(16), configuration.getWorkerThreadsBound());
        assertEquals(16, configuration.workerThreadsBoundInUse());
        assertEquals(Duration.ofSeconds(1), configuration.getRetryBackoff());
        assertEquals(0, configuration.getMaxRedeliveries());
        assertEquals(Duration.ZERO, configuration.getShutdownGrace());
        assertEquals(Duration.ofSeconds(5), configuration.getWorkflowVisibilityTimeout());
        assertEquals(Duration.ZERO, configuration.getUserTaskPollInterval());
        assertEquals(Duration.ofDays(30), configuration.getHistoryTimeToLive());
        assertEquals(0, configuration.getInstanceCacheMaxEntries());
        assertEquals(MessageStartLookup.SCAN, configuration.getMessageStartLookup());
        assertTrue(configuration.isAcceptUnscopedIdentifiers());

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
        configuration.setAcceptUnscopedIdentifiers(null);

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
        assertEquals(defaults.isAcceptUnscopedIdentifiers(), configuration.isAcceptUnscopedIdentifiers());
    }

    @ParameterizedTest(name = "{0} with {1}")
    @MethodSource("defects")
    @DisplayName("Each defect ends the boot of a first-priority adapter, names its key and says how to fix it")
    void eachDefectIsNamedWithItsKeyAndItsFix(
        final String key,
        final String description,
        final String fix,
        final Consumer<ZenBpmAdapterConfiguration> breakIt
    ) {
        final var configuration = configured("http://zenbpm:8080");
        breakIt.accept(configuration);

        final var validation = configuration.validate(ADAPTER_ID, false, false);

        assertEquals(Kind.INCONSISTENT, validation.kind());
        assertTrue(validation.endsTheBoot());
        final var line = validation
                .message()
                .lines()
                .filter(defect -> defect.startsWith("  - vanillabp.adapters.orders.%s ".formatted(key)))
                .findFirst();
        assertTrue(line.isPresent(), validation.message());
        assertTrue(line
                .get()
                .contains(fix), line.get());
    }

    @Test
    @DisplayName("Two defects are reported in one message")
    void twoDefectsAreReportedTogether() {
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
    void aDefectBesideAMissingAddressIsInconsistent() {
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

    @ParameterizedTest(name = "{0}")
    @MethodSource("keysWithoutADefault")
    @DisplayName("A key without a default, set without a REST address, makes the configuration inconsistent")
    void aStartedConfigurationWithoutAnAddressIsInconsistent(
        final String key,
        final Consumer<ZenBpmAdapterConfiguration> configure
    ) {
        final var configuration = new ZenBpmAdapterConfiguration();
        configure.accept(configuration);

        final var validation = configuration.validate(ADAPTER_ID, false, false);

        assertEquals(Kind.INCONSISTENT, validation.kind());
        assertTrue(validation.endsTheBoot());
        assertTrue(validation
                .message()
                .contains(
                        "  - vanillabp.adapters.orders.rest-address is missing, but other keys of this adapter are set"),
                validation.message());
    }

    /**
     * The client id has no defect but a blank one, which has no value to repeat, so the part of
     * this test about the client id cannot fail today. It is there for a later check of the client
     * id, whose message must not repeat it either.
     */
    @Test
    @DisplayName("Neither the client id nor a password in the REST address is ever repeated")
    void valuesWhichMayBeSecretAreNeverRepeated() {
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

    @ParameterizedTest(name = "{0}")
    @MethodSource("connectionDefects")
    @DisplayName("An adapter which is nowhere first and warns on deployment failures boots degraded where the engine cannot be reached")
    void aNowhereFirstAdapterWithWarnBootsDegraded(
        final String description,
        final Consumer<ZenBpmAdapterConfiguration> breakIt
    ) {
        final var configuration = configured("http://zenbpm:8080");
        breakIt.accept(configuration);

        final var validation = configuration.validate(ADAPTER_ID, true, true);

        assertEquals(Kind.INCONSISTENT, validation.kind());
        assertFalse(validation.endsTheBoot());
        assertTrue(validation
                .message()
                .contains(
                        "'vanillabp.adapters.orders.deployment-failure' is 'warn', so the application boots DEGRADED"),
                validation.message());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://zenbpm:8080", "zenbpm:8080"
    })
    @DisplayName("A number or duration which cannot work ends the boot even where the adapter may boot degraded")
    void aValueWhichCannotWorkAlwaysEndsTheBoot(final String restAddress) {
        final var configuration = configured(restAddress);
        configuration.setWorkerThreads("-1");

        final var validation = configuration.validate(ADAPTER_ID, true, true);

        assertEquals(Kind.INCONSISTENT, validation.kind());
        assertTrue(validation.endsTheBoot());
        assertTrue(validation
                .message()
                .contains("ends the boot even where 'vanillabp.adapters.orders.deployment-failure' is 'warn'"),
                validation.message());
        assertFalse(validation
                .message()
                .contains("DEGRADED"), validation.message());
    }

    @Test
    @DisplayName("An adapter which is nowhere first but fails on deployment failures is shown the way to degrade")
    void aNowhereFirstAdapterWithFailIsShownTheWarnPolicy() {
        final var validation = configured("zenbpm:8080").validate(ADAPTER_ID, true, false);

        assertTrue(validation.endsTheBoot());
        assertTrue(validation
                .message()
                .contains("may instead set 'vanillabp.adapters.orders.deployment-failure: warn'"),
                validation.message());
    }

    @Test
    @DisplayName("An adapter which is first priority somewhere cannot boot degraded, whatever its policy")
    void aFirstPriorityAdapterNeverDegrades() {
        final var validation = configured("zenbpm:8080").validate(ADAPTER_ID, false, true);

        assertTrue(validation.endsTheBoot());
        assertTrue(validation
                .message()
                .contains("first priority somewhere, so it cannot boot degraded"), validation.message());
    }

    @Test
    @DisplayName("An unconfigured adapter never ends the boot, whatever its priority and policy")
    void anUnconfiguredAdapterNeverEndsTheBoot() {
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
    void virtualWorkerThreadsAreAccepted(final String workerThreads) {
        final var configuration = configured("http://zenbpm:8080");
        configuration.setWorkerThreads(workerThreads);

        assertEquals(Kind.COMPLETE, configuration
                .validate(ADAPTER_ID, false, false)
                .kind());
        assertEquals(4, configuration.workerThreadsBoundInUse());

        configuration.setWorkerThreadsBound(32);

        assertEquals(Kind.COMPLETE, configuration
                .validate(ADAPTER_ID, false, false)
                .kind());
        assertEquals(32, configuration.workerThreadsBoundInUse());
    }

    @ParameterizedTest
    @ValueSource(ints = {
            4, 12, 20
    })
    @DisplayName("Platform threads are their own bound, so a bound beside them is refused whatever its size")
    void platformThreadsAreTheirOwnBound(final int bound) {
        final var configuration = configured("http://zenbpm:8080");
        configuration.setWorkerThreads("12");

        assertEquals(12, configuration.workerThreadsBoundInUse());

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
    void aMisspeltVirtualIsReportedOnce() {
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
    void theGrpcAddressIsDerivedFromTheRestHost() {
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
    void anIpv6HostIsDerivedInBrackets() {
        final var configuration = configured("http://[::1]:8080");

        assertEquals("[::1]:9090", configuration.grpcAddressInUse());

        configuration.setGrpcAddress(configuration.grpcAddressInUse());

        assertEquals(Kind.COMPLETE, configuration
                .validate(ADAPTER_ID, false, false)
                .kind());
    }

    @Test
    @DisplayName("The client id is derived from application name, adapter id and a random part drawn once")
    void theClientIdIsDerived() {
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
    void theApiVersionIsAppendedOnce() {
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
                .contains("'vanillabp.adapters.orders.rest-address' already ends with '/v1'"),
                validation.notices().getFirst());
    }

    @Test
    @DisplayName("The engine's address is the REST address normalised, without '/v1'")
    void theEngineAddressLeavesTheApiVersionOut() {
        assertEquals(URI.create("http://zenbpm:8080"), configured("http://zenbpm:8080/v1/").engineAddress());
        assertEquals(URI.create("http://zenbpm:80/engine"), configured("HTTP://ZenBPM/engine/").engineAddress());
        assertEquals(URI.create("https://zenbpm:443"), configured("https://zenbpm").engineAddress());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/%76%31", "/v%31", "/v1/", "//v1//", "/proxy/%76%31/"
    })
    @DisplayName("An address ending with an escaped or doubled '/v1' gets no second one, and the notice says so")
    void theNoticeAndTheBaseAgreeOnTheApiVersion(final String path) {
        final var configuration = configured("http://zenbpm:8080"
                + path);

        final var validation = configuration.validate(ADAPTER_ID, false, false);

        assertEquals(1, validation
                .notices()
                .size(), validation.notices()::toString);
        assertTrue(configuration
                .restBaseUri()
                .getRawPath()
                .matches("(/proxy)?/v1"), configuration.restBaseUri()::toString);
    }

    @Test
    @DisplayName("Escaped ASCII letters, digits and '-._~' are written as themselves, every other escape stays")
    void onlyUnreservedEscapesAreDecoded() {
        assertEquals(URI.create("http://zenbpm:8080/Az09-._~/%C3%A4%20/v1"),
                configured("http://zenbpm:8080/%41%7a%30%39%2d%2e%5f%7e/%c3%a4%20").restBaseUri());
    }

    @Test
    @DisplayName("An escaped slash stays escaped, so it never becomes a separator in front of '/v1'")
    void anEscapedSlashStaysEscaped() {
        final var configuration = configured("http://zenbpm:8080/%2fv1");

        assertEquals(URI.create("http://zenbpm:8080/%2Fv1/v1"), configuration.restBaseUri());
        assertEquals(List.of(), configuration
                .validate(ADAPTER_ID, false, false)
                .notices());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "  "
    })
    @DisplayName("Blank worker threads are the default, as an empty key binds to an empty text")
    void blankWorkerThreadsAreTheDefault(final String workerThreads) {
        final var configuration = configured("http://zenbpm:8080");
        configuration.setWorkerThreads(workerThreads);

        assertEquals("4", configuration.getWorkerThreads());
        assertEquals(Kind.COMPLETE, configuration
                .validate(ADAPTER_ID, false, false)
                .kind());
    }

    @Test
    @DisplayName("An https REST address with a derived plaintext job stream is noticed, and not where either is set")
    void aTlsRestAddressBesideAPlaintextJobStreamIsNoticed() {
        final var configuration = configured("https://zenbpm");

        assertEquals(1, configuration
                .validate(ADAPTER_ID, false, false)
                .notices()
                .size());
        assertTrue(configuration
                .validate(ADAPTER_ID, false, false)
                .notices()
                .getFirst()
                .contains(
                        "uses https, but the job stream goes to port 9090 of the same host without TLS. Where a proxy terminates TLS for both, set 'vanillabp.adapters.orders.grpc-address' and 'vanillabp.adapters.orders.grpc-plaintext: false'"));

        configuration.setGrpcPlaintext(false);

        assertEquals(List.of(), configuration
                .validate(ADAPTER_ID, false, false)
                .notices());

        configuration.setGrpcPlaintext(true);
        configuration.setGrpcAddress("zenbpm:9090");

        assertEquals(List.of(), configuration
                .validate(ADAPTER_ID, false, false)
                .notices());
    }

    @Test
    @DisplayName("Two spellings of one engine's address have one identity, another port another")
    void theIdentityIsNormalised() {
        final var identity = configured("http://host:8080").instanceIdentity();

        assertTrue(configured("http://host:8080").hasValidRestAddress());
        assertEquals(identity, configured("HTTP://Host:8080/").instanceIdentity());
        assertEquals(identity, configured(" http://HOST:8080/v1 ").instanceIdentity());
        assertEquals(identity, configured("http://host:8080/%76%31").instanceIdentity());
        assertEquals(identity, configured("http://host:8080//v1//").instanceIdentity());
        assertEquals(configured("http://host").instanceIdentity(), configured("http://host:80/").instanceIdentity());
        assertNotEquals(identity, configured("http://host:8081").instanceIdentity());
        assertNotEquals(identity, configured("https://host:8080").instanceIdentity());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "zenbpm:8080"
    })
    @DisplayName("Nothing is derived from a missing or malformed REST address")
    void nothingIsDerivedFromAnUnusableAddress(final String restAddress) {
        final var configuration = configured(restAddress);

        assertFalse(configuration.hasValidRestAddress());
        assertThrows(IllegalStateException.class, configuration::engineAddress);
        assertThrows(IllegalStateException.class, configuration::restBaseUri);
        assertThrows(IllegalStateException.class, configuration::instanceIdentity);
        assertThrows(IllegalStateException.class, configuration::grpcAddressInUse);
    }

    @Test
    @DisplayName("The notices of a validation cannot be changed afterwards")
    void theNoticesAreACopy() {
        final var notices = new ArrayList<String>();
        notices.add("one");
        final var validation = new ZenBpmAdapterConfiguration.ValidationResult(Kind.COMPLETE, "", false, notices);
        notices.add("two");

        assertEquals(List.of("one"), validation.notices());
        assertThrows(UnsupportedOperationException.class, () -> validation
                .notices()
                .add("three"));
    }

    @Test
    @DisplayName("A negative value below the adapter level ends the boot naming its full key, also where the adapter id could degrade")
    void aNegativeScopedValueIsAValueDefect() {
        final var configuration = configured("http://localhost:8080");
        configuration.addScopedValue("vanillabp.workflow-modules.shop.adapters.orders.retry-backoff",
                Duration.ofSeconds(-1));
        configuration.addScopedValue(
                "vanillabp.workflow-modules.shop.workflows.Order.tasks.ship.adapters.orders.max-redeliveries", -1);

        final var validation = configuration.validate(ADAPTER_ID, true, true);

        assertEquals(Kind.INCONSISTENT, validation.kind());
        assertTrue(validation.endsTheBoot());
        assertTrue(validation
                .message()
                .contains(
                        "  - vanillabp.workflow-modules.shop.adapters.orders.retry-backoff is PT-1S and must not be negative; without the key the level above applies\n"
                                + "  - vanillabp.workflow-modules.shop.workflows.Order.tasks.ship.adapters.orders.max-redeliveries is -1 and must not be negative; without the key the level above applies\n"),
                validation.message());
    }

    @Test
    @DisplayName("A retry-backoff or max-redeliveries below the adapter level, without a REST address, leaves the adapter id not configured yet: both fall back to a default")
    void aScopedKeyWithADefaultDoesNotStartTheConfiguration() {
        final var configuration = new ZenBpmAdapterConfiguration();
        configuration.addScopedValue(
                "vanillabp.workflow-modules.shop.workflows.Order.tasks.ship.adapters.orders.retry-backoff",
                Duration.ofSeconds(5));
        configuration.addScopedValue(
                "vanillabp.workflow-modules.shop.workflows.Order.tasks.ship.adapters.orders.max-redeliveries", 3);

        final var validation = configuration.validate(ADAPTER_ID, false, false);

        assertEquals(Kind.UNCONFIGURED, validation.kind());
        assertFalse(validation.endsTheBoot());
    }

    @Test
    @DisplayName("A level which does not set a key, or sets zero, is no defect")
    void anUnsetOrZeroScopedValueIsNoDefect() {
        final var configuration = configured("http://localhost:8080");
        configuration.addScopedValue("vanillabp.workflow-modules.shop.adapters.orders.retry-backoff", (Duration) null);
        configuration.addScopedValue("vanillabp.workflow-modules.shop.adapters.orders.max-redeliveries",
                (Integer) null);
        configuration.addScopedValue(
                "vanillabp.workflow-modules.shop.workflows.Order.adapters.orders.history-time-to-live", Duration.ZERO);
        configuration.addScopedValue("vanillabp.workflow-modules.shop.workflows.Order.adapters.orders.max-redeliveries",
                0);

        assertEquals(Kind.COMPLETE, configuration
                .validate(ADAPTER_ID, false, false)
                .kind());
    }

    static Stream<Arguments> defects() {
        return Stream.of(
                defect("rest-address", "localhost:8080", "needs the scheme http or https, as in http://localhost:8080",
                        c -> c.setRestAddress("localhost:8080")),
                defect("rest-address", "no scheme", "needs the scheme http or https, as in http://localhost:8080",
                        c -> c.setRestAddress("//zenbpm:8080")),
                defect("rest-address", "a URL with spaces", "is not a URL; write it as http://host:port",
                        c -> c.setRestAddress("http://zen bpm:8080")),
                defect("rest-address", "another scheme", "needs the scheme http or https",
                        c -> c.setRestAddress("grpc://zenbpm:9090")),
                defect("rest-address", "an underscore in the host",
                        "names no valid host and port; write it as http://host:port (a host name may not contain '_')",
                        c -> c.setRestAddress("http://zen_bpm:8080")),
                defect("rest-address", "no authority", "names no valid host and port; write it as http://host:port",
                        c -> c.setRestAddress("http:zenbpm")),
                defect("rest-address", "an empty host", "names no valid host and port; write it as http://host:port",
                        c -> c.setRestAddress("http:///v1")),
                defect("rest-address", "a port too large for a number",
                        "names no valid host and port; write it as http://host:port",
                        c -> c.setRestAddress("http://zenbpm:99999999999")),
                defect("rest-address", "a port of 0", "names a port outside 1 to 65535; write it as http://host:port",
                        c -> c.setRestAddress("http://zenbpm:0")),
                defect("rest-address", "a port above 65535",
                        "names a port outside 1 to 65535; write it as http://host:port",
                        c -> c.setRestAddress("http://zenbpm:65536")),
                defect("rest-address", "a user", "carries a user before '@', which the adapter never sends; remove it",
                        c -> c.setRestAddress("http://operator@zenbpm:8080")),
                defect("rest-address", "a query", "carries a query or a fragment",
                        c -> c.setRestAddress("http://zenbpm:8080?tenant=a")),
                defect("rest-address", "a fragment", "carries a query or a fragment",
                        c -> c.setRestAddress("http://zenbpm:8080#v1")),
                defect("grpc-address", "no port", "names no port; write it as host:port, e.g. localhost:9090",
                        c -> c.setGrpcAddress("zenbpm")),
                defect("grpc-address", "an empty port", "names no port; write it as host:port",
                        c -> c.setGrpcAddress("zenbpm:")),
                defect("grpc-address", "an IPv6 host without a port", "names no port; write it as host:port",
                        c -> c.setGrpcAddress("[::1]")),
                defect("grpc-address", "a scheme", "is host:port without a scheme, e.g. localhost:9090",
                        c -> c.setGrpcAddress("http://zenbpm:9090")),
                defect("grpc-address", "no host", "names no valid host and port, e.g. localhost:9090 or [::1]:9090",
                        c -> c.setGrpcAddress(":9090")),
                defect("grpc-address", "an underscore in the host", "(a host name may not contain '_')",
                        c -> c.setGrpcAddress("zen_bpm:9090")),
                defect("grpc-address", "a port which is no number", "names no valid host and port",
                        c -> c.setGrpcAddress("zenbpm:grpc")),
                defect("grpc-address", "a port with a sign", "names no valid host and port",
                        c -> c.setGrpcAddress("zenbpm:+9090")),
                defect("grpc-address", "a port of 0", "names a port outside 1 to 65535, e.g. localhost:9090",
                        c -> c.setGrpcAddress("zenbpm:0")),
                defect("grpc-address", "a port above 65535", "names a port outside 1 to 65535",
                        c -> c.setGrpcAddress("zenbpm:65536")),
                defect("grpc-address", "an IPv6 address without brackets",
                        "names no valid host and port, e.g. localhost:9090 or [::1]:9090",
                        c -> c.setGrpcAddress("2001:db8::1")),
                defect("grpc-address", "an IPv6 address and port without brackets", "names no valid host and port",
                        c -> c.setGrpcAddress("::1:9090")),
                defect("grpc-address", "an unclosed bracket", "is not host:port, e.g. localhost:9090 or [::1]:9090",
                        c -> c.setGrpcAddress("[::1")),
                defect("grpc-address", "brackets around no IPv6 address", "is not host:port",
                        c -> c.setGrpcAddress("[not-ipv6]:9090")),
                defect("grpc-address", "a space in the host", "is not host:port",
                        c -> c.setGrpcAddress("zen bpm:9090")),
                defect("grpc-address", "a path", "carries more than host:port",
                        c -> c.setGrpcAddress("zenbpm/path:9090")),
                defect("grpc-address", "a query", "carries more than host:port",
                        c -> c.setGrpcAddress("zenbpm?a:9090")),
                defect("grpc-address", "a fragment", "carries more than host:port",
                        c -> c.setGrpcAddress("zenbpm:9090#a")),
                defect("grpc-address", "a user", "carries a user before '@', which the adapter never sends",
                        c -> c.setGrpcAddress("operator:s3cret@zenbpm:9090")),
                defect("grpc-address", "a blank value",
                        "is blank; remove the key to have it derived from the host of rest-address and port 9090",
                        c -> c.setGrpcAddress(" ")),
                defect("client-id", "a blank value",
                        "is blank; remove the key to have the id derived as <application name>-orders-<random>",
                        c -> c.setClientId(" ")),
                defect("request-timeout", "half a second", "has to be at least PT1S; the default is PT10S",
                        c -> c.setRequestTimeout(Duration.ofMillis(500))),
                defect("request-timeout", "a negative duration", "has to be at least PT1S; the default is PT10S",
                        c -> c.setRequestTimeout(Duration.ofSeconds(-1))),
                defect("worker-threads", "zero", "without a thread the adapter runs no handler, the default is 4",
                        c -> c.setWorkerThreads("0")),
                defect("worker-threads", "a word",
                        "is neither a number nor 'virtual'; e.g. '4' platform threads, or 'virtual' bounded by vanillabp.adapters.orders.worker-threads-bound",
                        c -> c.setWorkerThreads("many")),
                defect("worker-threads", "a number too large for an int",
                        "is too large a number; size it against the database connection pool, the default is 4",
                        c -> c.setWorkerThreads("2147483648")),
                defect("worker-threads-bound", "zero beside virtual threads",
                        "has to be at least 1; every running handler holds a database connection, the default is 4",
                        c -> {
                            c.setWorkerThreads("virtual");
                            c.setWorkerThreadsBound(0);
                        }),
                defect("worker-threads-bound", "a value beside the default platform threads",
                        "set 'virtual' or remove the bound",
                        c -> c.setWorkerThreadsBound(16)),
                defect("startup-wait", "a negative duration", "must not be negative; the default is PT10M",
                        c -> c.setStartupWait(Duration.ofSeconds(-1))),
                defect("retry-backoff", "a negative duration", "must not be negative; the default is PT10S",
                        c -> c.setRetryBackoff(Duration.ofSeconds(-1))),
                defect("shutdown-grace", "a negative duration", "must not be negative; the default is PT20S",
                        c -> c.setShutdownGrace(Duration.ofSeconds(-1))),
                defect("workflow-visibility-timeout", "a negative duration",
                        "must not be negative; the default is PT2S",
                        c -> c.setWorkflowVisibilityTimeout(Duration.ofSeconds(-1))),
                defect("user-task-poll-interval", "a negative duration", "must not be negative; the default is PT5S",
                        c -> c.setUserTaskPollInterval(Duration.ofSeconds(-1))),
                defect("history-time-to-live", "a negative duration",
                        "must not be negative; without the key the engine's own default applies",
                        c -> c.setHistoryTimeToLive(Duration.ofDays(-1))),
                defect("max-redeliveries", "a negative number",
                        "0 turns the first failure into an incident, the default is 10",
                        c -> c.setMaxRedeliveries(-1)),
                defect("instance-cache-max-entries", "a negative number", "must not be negative; the default is 10000",
                        c -> c.setInstanceCacheMaxEntries(-1)));
    }

    static Stream<Arguments> keysWithoutADefault() {
        return Stream.of(
                Arguments.of("grpc-address",
                        (Consumer<ZenBpmAdapterConfiguration>) c -> c.setGrpcAddress("zenbpm:9090")),
                Arguments.of("client-id", (Consumer<ZenBpmAdapterConfiguration>) c -> c.setClientId("orders-node-1")),
                Arguments.of("history-time-to-live",
                        (Consumer<ZenBpmAdapterConfiguration>) c -> c.setHistoryTimeToLive(Duration.ofDays(30))),
                Arguments.of("history-time-to-live of a workflow module",
                        (Consumer<ZenBpmAdapterConfiguration>) c -> c.addScopedValue(
                                "vanillabp.workflow-modules.shop.adapters.orders.history-time-to-live",
                                Duration.ofDays(30))),
                Arguments.of("history-time-to-live of a workflow",
                        (Consumer<ZenBpmAdapterConfiguration>) c -> c.addScopedValue(
                                "vanillabp.workflow-modules.shop.workflows.Order.adapters.orders.history-time-to-live",
                                Duration.ofDays(30))));
    }

    static Stream<Arguments> connectionDefects() {
        return Stream.of(
                Arguments.of("a REST address without a scheme",
                        (Consumer<ZenBpmAdapterConfiguration>) c -> c.setRestAddress("zenbpm:8080")),
                Arguments.of("a gRPC address which is not host:port",
                        (Consumer<ZenBpmAdapterConfiguration>) c -> c.setGrpcAddress("zen bpm:9090")),
                Arguments.of("a blank client id", (Consumer<ZenBpmAdapterConfiguration>) c -> c.setClientId(" ")),
                Arguments.of("a gRPC address without a REST address", (Consumer<ZenBpmAdapterConfiguration>) c -> {
                    c.setRestAddress(null);
                    c.setGrpcAddress("zenbpm:9090");
                }));
    }

    private static ZenBpmAdapterConfiguration configured(final String restAddress) {
        final var configuration = new ZenBpmAdapterConfiguration();
        configuration.setRestAddress(restAddress);
        return configuration;
    }

    private static Arguments defect(
        final String key,
        final String description,
        final String fix,
        final Consumer<ZenBpmAdapterConfiguration> breakIt
    ) {
        return Arguments.of(key, description, fix, breakIt);
    }

}

/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.health;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pbinitiative.zenbpmadapter.ZenBpmAdapter;
import org.pbinitiative.zenbpmadapter.client.FakeEngine;
import org.pbinitiative.zenbpmadapter.client.ZenBpmAdapterConfiguration;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientFactory;
import org.pbinitiative.zenbpmadapter.client.ZenBpmEnvironmentInfo;
import org.pbinitiative.zenbpmadapter.client.ZenBpmFindings;

import io.vanillabp.integration.adapter.spi.health.AdapterHealth;
import io.vanillabp.integration.spi.startup.StartupReport;
import io.vanillabp.integration.spi.startup.StartupTopic;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The four answers of the health check, against an engine a test plays.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmHealthTest {

    private static final String ADAPTER_ID = "orders";

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(1);

    private FakeEngine engine;

    @BeforeEach
    void startTheEngine() {
        engine = FakeEngine.started();
    }

    @AfterEach
    void stopTheEngine() {
        engine.close();
    }

    @Test
    @DisplayName("An adapter id without clients is UNKNOWN, with the message which names the key to set")
    void anAdapterIdWithoutClientsIsUnknown() {
        final var configuration = new ZenBpmAdapterConfiguration();
        final var message = configuration
                .validate(ADAPTER_ID, true, true)
                .message();
        final var factory = ZenBpmClientFactory.unconfigured(ADAPTER_ID, configuration, message);

        final var health = ZenBpmHealth.check(factory);

        assertAll(
                () -> assertEquals(AdapterHealth.Status.UNKNOWN, health.status()),
                () -> assertEquals(message, health.description()),
                () -> assertTrue(health
                        .description()
                        .contains("'vanillabp.adapters.orders.rest-address' is missing"), health.description()),
                () -> assertEquals(ADAPTER_ID, health.adapterId()),
                () -> assertEquals(ZenBpmAdapter.ADAPTER_TYPE, health.adapterType()),
                () -> assertEquals(Map.of(), health.details()));
    }

    @Test
    @DisplayName("An adapter id without clients names its address where 'rest-address' is valid, and leaves out one which is not")
    void anAdapterIdWithoutClientsNamesAValidAddress() {
        final var valid = new ZenBpmAdapterConfiguration();
        valid.setRestAddress("HTTP://ZenBPM:8080/v1");
        final var withAUser = new ZenBpmAdapterConfiguration();
        withAUser.setRestAddress("http://admin:secret@zenbpm:8080");

        assertAll(
                () -> assertEquals(Map.of("address", "http://zenbpm:8080"), ZenBpmHealth
                        .check(ZenBpmClientFactory.unconfigured(ADAPTER_ID, valid, "boots degraded"))
                        .details()),
                () -> assertEquals(Map.of(), ZenBpmHealth
                        .check(ZenBpmClientFactory.unconfigured(ADAPTER_ID, withAUser, "carries a user"))
                        .details()));
    }

    @Test
    @DisplayName("A ready engine is UP, with the version and commit it reported at startup")
    void aReadyEngineIsUp() {
        engine.answers("GET", "/system/health/ready", 200, "{\"status\":\"UP\",\"reasons\":[]}");
        engine.answers("GET", "/system/status", 200, """
                {"git":{"commitId":"0123456789ab"},"build":{"version":"1.2.0"},"nodes":{"node-1":{}}}""");

        try (var factory = factory(engine.address())) {
            ZenBpmEnvironmentInfo.logOnce(factory, new ZenBpmFindings(new IgnoredStartupReport()));

            final var health = ZenBpmHealth.check(factory);

            assertAll(
                    () -> assertEquals(AdapterHealth.Status.UP, health.status()),
                    () -> assertEquals("The engine reports ready", health.description()),
                    () -> assertEquals(List.of("address", "versionAtStart", "commitAtStart", "jobStream"), List
                            .copyOf(health
                                    .details()
                                    .keySet())),
                    () -> assertEquals(engine.address(), health
                            .details()
                            .get("address")),
                    () -> assertEquals("1.2.0", health
                            .details()
                            .get("versionAtStart")),
                    () -> assertEquals("0123456789ab", health
                            .details()
                            .get("commitAtStart")));
        }
    }

    @Test
    @DisplayName("A 'rest-address' changed into an invalid one after the factory was built does not make the check throw")
    void anAddressMadeInvalidLaterDoesNotThrow() {
        engine.answers("GET", "/system/health/ready", 200, "{\"status\":\"UP\",\"reasons\":[]}");

        try (var factory = factory(engine.address())) {
            // a configuration bean is mutable, and a platform may bind it again while the
            // application runs; the clients keep the address they were built with
            factory
                    .configuration()
                    .setRestAddress("not an address");

            final var health = ZenBpmHealth.check(factory);

            assertAll(
                    () -> assertEquals(AdapterHealth.Status.UP, health.status()),
                    () -> assertEquals(Map.of("jobStream", "not opened yet"), health.details()));
        }
    }

    @Test
    @DisplayName("A ready engine whose version was not read at startup is UP without it")
    void aReadyEngineWithoutAVersionIsUp() {
        engine.answers("GET", "/system/health/ready", 200, "{\"status\":\"UP\",\"reasons\":[]}");

        try (var factory = factory(engine.address())) {
            final var health = ZenBpmHealth.check(factory);

            assertAll(
                    () -> assertEquals(AdapterHealth.Status.UP, health.status()),
                    () -> assertEquals(Map.of("address", engine.address(), "jobStream", "not opened yet"), health
                            .details()),
                    () -> assertEquals(1, engine
                            .requests()
                            .size()));
        }
    }

    @Test
    @DisplayName("An engine which is not ready is DOWN, with the reasons it gives")
    void anEngineWhichIsNotReadyIsDown() {
        engine.answers("GET", "/system/health/ready", 503,
                "{\"status\":\"DOWN\",\"reasons\":[\"no cluster leader elected\",\"partition 2 is not initialized\"]}");

        try (var factory = factory(engine.address())) {
            final var health = ZenBpmHealth.check(factory);

            assertAll(
                    () -> assertEquals(AdapterHealth.Status.DOWN, health.status()),
                    () -> assertEquals(
                            "The engine reports not ready: no cluster leader elected; partition 2 is not initialized",
                            health.description()),
                    () -> assertEquals(Map.of("address", engine.address(), "jobStream", "not opened yet"), health
                            .details()));
        }
    }

    @Test
    @DisplayName("An engine which is not ready and gives no reason is DOWN")
    void anEngineWhichIsNotReadyWithoutAReasonIsDown() {
        engine.answers("GET", "/system/health/ready", 503, "{\"status\":\"DOWN\",\"reasons\":[]}");

        try (var factory = factory(engine.address())) {
            final var health = ZenBpmHealth.check(factory);

            assertAll(
                    () -> assertEquals(AdapterHealth.Status.DOWN, health.status()),
                    () -> assertEquals("The engine reports not ready", health.description()));
        }
    }

    @Test
    @DisplayName("An engine out of reach is DOWN, with what the HTTP client reported")
    void anEngineOutOfReachIsDown() throws IOException {
        try (var factory = factory(closedPortAddress())) {
            final var health = ZenBpmHealth.check(factory);

            assertAll(
                    () -> assertEquals(AdapterHealth.Status.DOWN, health.status()),
                    () -> assertTrue(health
                            .description()
                            .startsWith(
                                    "GET /system/health/ready got no answer (java.net.ConnectException"),
                            health.description()),
                    () -> assertEquals(List.of("address", "requestTimeout", "jobStream"), List.copyOf(health
                            .details()
                            .keySet())),
                    () -> assertEquals("PT1S", health
                            .details()
                            .get("requestTimeout")));
        }
    }

    @Test
    @DisplayName("An answer the call does not expect is DOWN, with the status")
    void anUnexpectedAnswerIsDown() {
        engine.answers("GET", "/system/health/ready", 500, "{\"code\":\"X\",\"message\":\"boom\"}");

        try (var factory = factory(engine.address())) {
            final var health = ZenBpmHealth.check(factory);

            assertAll(
                    () -> assertEquals(AdapterHealth.Status.DOWN, health.status()),
                    () -> assertEquals(
                            "GET /system/health/ready was answered with status 500, X: boom",
                            health.description()));
        }
    }

    @Test
    @DisplayName("An engine which takes the connection and never answers is DOWN within request-timeout and one second")
    void anEngineWhichNeverAnswersIsDownInTime() throws IOException {
        try (var blackHole = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            final var held = new CopyOnWriteArrayList<Socket>();
            Thread
                    .ofVirtual()
                    .start(() -> {
                        while (!blackHole.isClosed()) {
                            try {
                                held.add(blackHole.accept());
                            } catch (final IOException e) {
                                // closed
                            }
                        }
                    });

            try (var factory = factory("http://127.0.0.1:%d".formatted(blackHole.getLocalPort()))) {
                final var health = assertTimeoutPreemptively(REQUEST_TIMEOUT.plusSeconds(1), () -> ZenBpmHealth.check(
                        factory));

                assertAll(
                        () -> assertEquals(AdapterHealth.Status.DOWN, health.status()),
                        () -> assertTrue(health
                                .description()
                                .contains("got no answer within PT1S"), health.description()));
            } finally {
                for (final var socket : held) {
                    socket.close();
                }
            }
        }
    }

    @Test
    @DisplayName("A factory which was closed is DOWN, and the check does not throw")
    void aClosedFactoryIsDown() {
        final var factory = factory(engine.address());
        factory.close();

        final var health = ZenBpmHealth.check(factory);

        assertAll(
                () -> assertEquals(AdapterHealth.Status.DOWN, health.status()),
                () -> assertEquals("closed", health
                        .details()
                        .get("jobStream")));
    }

    @Test
    @DisplayName("A ready engine whose job stream cannot connect is UP however often the stream tries again, and says that the stream is down, as the README's section 'Health' promises")
    void aReadyEngineWithAStreamDownSaysSo() throws IOException {
        engine.answers("GET", "/system/health/ready", 200, "{\"status\":\"UP\",\"reasons\":[]}");
        final var configuration = configuration(engine.address());
        configuration.setGrpcAddress(closedPortAddress().substring("http://".length()));

        try (var factory = new ZenBpmClientFactory(ADAPTER_ID, configuration, () -> "shop")) {
            factory
                    .stream()
                    .subscribe("module", "type", Duration.ofMinutes(1), 1);

            final var stream = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
                while (true) {
                    final var state = ZenBpmHealth
                            .check(factory)
                            .details()
                            .get("jobStream");
                    // a stream which failed again and again, not one which just lost its first call
                    if (state.startsWith("down since ") && (factory
                            .stream()
                            .reconnects() >= 3)) {
                        return state;
                    }
                    Thread.sleep(50);
                }
            });
            assertAll(
                    () -> assertEquals(AdapterHealth.Status.UP, ZenBpmHealth
                            .check(factory)
                            .status()),
                    () -> assertTrue(
                            stream.matches(
                                    "down since \\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\dZ, \\d+ attempt\\(s\\)"),
                            stream));
        }
    }

    private static ZenBpmClientFactory factory(final String restAddress) {
        return new ZenBpmClientFactory(ADAPTER_ID, configuration(restAddress), () -> "shop");
    }

    private static ZenBpmAdapterConfiguration configuration(final String restAddress) {
        final var configuration = new ZenBpmAdapterConfiguration();
        configuration.setRestAddress(restAddress);
        configuration.setRequestTimeout(REQUEST_TIMEOUT);
        return configuration;
    }

    private static String closedPortAddress() throws IOException {
        try (var socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return "http://127.0.0.1:%d".formatted(socket.getLocalPort());
        }
    }

    /**
     * A startup report nobody reads: these tests are about the health check.
     */
    private static final class IgnoredStartupReport implements StartupReport {

        @Override
        public void notice(
            final StartupTopic topic,
            final String scope,
            final String message
        ) {
        }

        @Override
        public void warn(
            final StartupTopic topic,
            final String scope,
            final String message
        ) {
        }

        @Override
        public void error(
            final StartupTopic topic,
            final String scope,
            final String message
        ) {
        }

        @Override
        public void refuse(
            final StartupTopic topic,
            final String scope,
            final String message
        ) {
        }

    }

}

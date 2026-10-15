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
    @DisplayName("A ready engine is UP, with the version and commit it reported at startup")
    void aReadyEngineIsUp() {
        engine.answers("GET", "/system/health/ready", 200, "{\"status\":\"UP\",\"reasons\":[]}");
        engine.answers("GET", "/system/status", 200, """
                {"git":{"commitId":"0123456789ab"},"build":{"version":"1.2.0"},"nodes":{"node-1":{}}}""");

        try (var factory = factory(engine.address())) {
            ZenBpmEnvironmentInfo.logOnce(factory, new IgnoredStartupReport());

            final var health = ZenBpmHealth.check(factory);

            assertAll(
                    () -> assertEquals(AdapterHealth.Status.UP, health.status()),
                    () -> assertEquals("The engine reports ready", health.description()),
                    () -> assertEquals(List.of("address", "versionAtStart", "commitAtStart"), List.copyOf(health
                            .details()
                            .keySet())),
                    () -> assertEquals("%s/v1".formatted(engine.address()), health
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
    @DisplayName("A ready engine whose version was not read at startup is UP without it")
    void aReadyEngineWithoutAVersionIsUp() {
        engine.answers("GET", "/system/health/ready", 200, "{\"status\":\"UP\",\"reasons\":[]}");

        try (var factory = factory(engine.address())) {
            final var health = ZenBpmHealth.check(factory);

            assertAll(
                    () -> assertEquals(AdapterHealth.Status.UP, health.status()),
                    () -> assertEquals(Map.of("address", "%s/v1".formatted(engine.address())), health.details()),
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
                    () -> assertEquals(Map.of("address", "%s/v1".formatted(engine.address())), health.details()));
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
                                    "ZenBPM adapter 'orders': GET /system/health/ready got no answer (java.net.ConnectException"),
                            health.description()),
                    () -> assertEquals(List.of("address", "requestTimeout"), List.copyOf(health
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
                            "ZenBPM adapter 'orders': GET /system/health/ready was answered with status 500, X: boom",
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

        assertEquals(AdapterHealth.Status.DOWN, health.status());
    }

    private static ZenBpmClientFactory factory(final String restAddress) {
        final var configuration = new ZenBpmAdapterConfiguration();
        configuration.setRestAddress(restAddress);
        configuration.setRequestTimeout(REQUEST_TIMEOUT);
        return new ZenBpmClientFactory(ADAPTER_ID, configuration, () -> "shop");
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

/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.client;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pbinitiative.zenbpmadapter.test.EngineUnderTest;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The engine wait against the pinned engine, started after the wait began, as an engine which
 * starts together with the application is.
 * <p>
 * The engine's port is mapped, and known only once its container runs, while the wait needs an
 * address before that. So the wait asks a relay on a port of its own, which refuses connections
 * until the engine runs and passes them on afterwards.
 */
@ExtendWith(SuppressOutputExtension.class)
@Testcontainers(disabledWithoutDocker = true)
class ZenBpmEngineWaitIT {

    private static final String ADAPTER_ID = "wait";

    /**
     * How long after the wait began the engine is started.
     */
    private static final Duration LATE = Duration.ofSeconds(3);

    @Test
    @DisplayName("The wait returns once an engine started after it began reports ready")
    void theWaitReturnsForAnEngineStartedLate() throws Exception {
        try (var engine = new EngineUnderTest(); var relay = TcpRelay.to("localhost:1")) {
            relay.cut();
            final var configuration = new ZenBpmAdapterConfiguration();
            configuration.setRestAddress("http://%s".formatted(relay.address()));
            final var startedLate = CompletableFuture.runAsync(() -> {
                engine.start();
                relay.restoreTo(engine
                        .restAddress()
                        .substring("http://".length()));
            }, CompletableFuture.delayedExecutor(LATE.toMillis(), TimeUnit.MILLISECONDS));

            try (var factory = new ZenBpmClientFactory(ADAPTER_ID, configuration, () -> "wait-it")) {
                final var waitStarted = System.nanoTime();
                ZenBpmEngineWait.awaitReady(factory);
                final var waited = Duration.ofNanos(System.nanoTime() - waitStarted);

                startedLate.get(10, TimeUnit.SECONDS);
                assertTrue(waited.compareTo(LATE) >= 0, "the wait returned after %s, before the engine was started"
                        .formatted(waited));
                assertTrue(factory
                        .rest()
                        .ready()
                        .ready());
            }
        }
    }

    @Test
    @DisplayName("A wait of two seconds against a closed port fails naming the key to raise")
    void aShortWaitAgainstAClosedPortFails() throws IOException {
        final var configuration = new ZenBpmAdapterConfiguration();
        configuration.setRestAddress(closedPortAddress());
        configuration.setStartupWait(Duration.ofSeconds(2));

        try (var factory = new ZenBpmClientFactory(ADAPTER_ID, configuration, () -> "wait-it")) {
            final var failure = assertThrows(IllegalStateException.class, () -> ZenBpmEngineWait.awaitReady(factory));

            assertTrue(failure
                    .getMessage()
                    .contains("'vanillabp.adapters.wait.startup-wait: PT2S'"), failure.getMessage());
        }
    }

    private static String closedPortAddress() throws IOException {
        try (var socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return "http://127.0.0.1:%d".formatted(socket.getLocalPort());
        }
    }

}

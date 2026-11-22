/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The relay the tests of the job stream and of the wait for the engine cut and restore. A test
 * restores it right after it cut it, so the cut has to leave the port free. It did not always:
 * closing the listening socket returns while the thread which accepts still holds it, and on a busy
 * machine that thread wakes up late enough for the next listener to find the address in use.
 */
@ExtendWith(SuppressOutputExtension.class)
class TcpRelayTest {

    // enough for the unfixed relay to fail on every machine it was tried on, within a second
    private static final int ROUNDS = 2_000;

    @Test
    @DisplayName("A relay which was cut is restored on its port at once, also while every processor is busy, and relays again")
    void aRelayWhichWasCutIsRestoredAtOnce() throws IOException {
        final var busy = new AtomicBoolean(true);
        keepEveryProcessorBusy(busy);
        try (var engine = new ServerSocket(0, 50, InetAddress.getLoopbackAddress()); var relay = TcpRelay
                .to(addressOf(engine))) {
            for (var round = 0; round < ROUNDS; round++) {
                relay.cut();
                // fails with "Address already in use" where the cut returned too early
                relay.restoreTo(addressOf(engine));
            }

            try (var client = new Socket(InetAddress.getLoopbackAddress(), portOf(relay)); var arrived = engine
                    .accept()) {
                client
                        .getOutputStream()
                        .write(42);
                assertEquals(42, arrived
                        .getInputStream()
                        .read());
            }
        } finally {
            busy.set(false);
        }
    }

    // twice as many threads as processors, so the relay's thread has to wait for its turn
    private static void keepEveryProcessorBusy(final AtomicBoolean busy) {
        IntStream
                .range(0, 2 * Runtime
                        .getRuntime()
                        .availableProcessors())
                .forEach(number -> Thread
                        .ofPlatform()
                        .daemon()
                        .start(() -> {
                            while (busy.get()) {
                                Thread.onSpinWait();
                            }
                        }));
    }

    private static String addressOf(final ServerSocket engine) {
        return "localhost:%d".formatted(engine.getLocalPort());
    }

    private static int portOf(final TcpRelay relay) {
        final var address = relay.address();
        return Integer.parseInt(address.substring(address.lastIndexOf(':') + 1));
    }

}

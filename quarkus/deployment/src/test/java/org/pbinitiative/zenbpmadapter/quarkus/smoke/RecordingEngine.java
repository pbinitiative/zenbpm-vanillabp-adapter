/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.smoke;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;

/**
 * An engine which answers nothing useful and counts every request it gets, so a test can show that
 * the adapter asked it nothing. It listens on a port the system picks.
 */
final class RecordingEngine implements AutoCloseable {

    private final AtomicInteger requests = new AtomicInteger();

    private final HttpServer server;

    private RecordingEngine(final HttpServer server) {
        this.server = server;
    }

    /**
     * Starts an engine on a free port.
     *
     * @return The engine, which counts from now on
     */
    static RecordingEngine start() {
        try {
            final var engine = new RecordingEngine(HttpServer.create(new InetSocketAddress("localhost", 0), 0));
            engine.server.createContext("/", exchange -> {
                engine.requests.incrementAndGet();
                exchange.sendResponseHeaders(503, -1);
                exchange.close();
            });
            engine.server.start();
            return engine;
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Where the adapter finds this engine.
     *
     * @return The value of <code>rest-address</code>
     */
    String address() {
        return "http://localhost:%d".formatted(server
                .getAddress()
                .getPort());
    }

    /**
     * How many requests the engine got.
     *
     * @return The count
     */
    int requests() {
        return requests.get();
    }

    @Override
    public void close() {
        server.stop(0);
    }

}

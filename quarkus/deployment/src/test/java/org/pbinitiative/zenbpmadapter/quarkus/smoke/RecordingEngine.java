/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.smoke;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpServer;

/**
 * An engine which answers every request with <code>503</code> and writes down its method and path,
 * so a test can show what the adapter asked it. It listens on a port the system picks.
 */
final class RecordingEngine implements AutoCloseable {

    private final List<String> requests = new CopyOnWriteArrayList<>();

    private final HttpServer server;

    private RecordingEngine(final HttpServer server) {
        this.server = server;
    }

    /**
     * Starts an engine on a free port.
     *
     * @return The engine, which writes down from now on
     */
    static RecordingEngine start() {
        try {
            final var engine = new RecordingEngine(HttpServer.create(new InetSocketAddress("localhost", 0), 0));
            engine.server.createContext("/", exchange -> {
                engine.requests.add("%s %s".formatted(exchange.getRequestMethod(), exchange
                        .getRequestURI()
                        .getPath()));
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
     * The requests the engine got, in the order they came.
     *
     * @return Method and path of each
     */
    List<String> requests() {
        return List.copyOf(requests);
    }

    @Override
    public void close() {
        server.stop(0);
    }

}

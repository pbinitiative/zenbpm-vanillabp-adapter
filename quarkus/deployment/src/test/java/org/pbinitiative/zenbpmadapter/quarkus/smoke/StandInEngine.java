/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.quarkus.smoke;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * An engine which answers what a deployment asks, without being one: it is ready, names the engine
 * commit this adapter is tested with, takes or refuses every BPMN file with the status it was
 * given, and holds every definition as version 1. It writes down every request as method and path.
 * It listens on a port the system picks.
 */
final class StandInEngine implements AutoCloseable {

    private static final String TESTED_COMMIT = "bdd795a399b6";

    private static final String SHARED_ADDRESS = "zenbpm.stand-in-engine.address";

    private final HttpServer server;

    private final List<String> requests = new CopyOnWriteArrayList<>();

    private StandInEngine(final HttpServer server) {
        this.server = server;
    }

    /**
     * Starts an engine which takes every file.
     *
     * @return The engine
     */
    static StandInEngine takingEveryFile() {
        return start(201, "{\"processDefinitionKey\":7}");
    }

    /**
     * Where an engine which takes every file answers, started once per JVM. A Quarkus test which
     * boots runs its class a second time, in the application's own class loader, and that second
     * run finds the engine the first one started through a system property, instead of starting an
     * engine nobody configured. The engine runs until the JVM ends.
     *
     * @return The value of <code>rest-address</code>
     */
    static synchronized String addressOfOneTakingEveryFile() {
        final var running = System.getProperty(SHARED_ADDRESS);
        if (running != null) {
            return running;
        }
        final var address = takingEveryFile().address();
        System.setProperty(SHARED_ADDRESS, address);
        return address;
    }

    /**
     * Starts an engine which refuses every BPMN file with the given reason, as the engine does with
     * a file it cannot read.
     *
     * @param reason What the engine says
     * @return The engine
     */
    static StandInEngine refusingEveryFile(final String reason) {
        return start(400, "{\"code\":\"BAD_REQUEST\",\"message\":\"%s\"}".formatted(reason));
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
     * Every request received, as method and path.
     *
     * @return The requests
     */
    List<String> requests() {
        return List.copyOf(requests);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private static StandInEngine start(
        final int deployStatus,
        final String deployAnswer
    ) {
        try {
            final var engine = new StandInEngine(HttpServer.create(new InetSocketAddress("localhost", 0), 0));
            engine.server.createContext("/", exchange -> engine.answer(exchange, deployStatus, deployAnswer));
            engine.server.start();
            return engine;
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void answer(
        final HttpExchange exchange,
        final int deployStatus,
        final String deployAnswer
    ) throws IOException {
        final var request = "%s %s".formatted(exchange.getRequestMethod(), exchange
                .getRequestURI()
                .getPath());
        requests.add(request);
        exchange
                .getRequestBody()
                .readAllBytes();
        if (request.equals("GET /system/health/ready")) {
            respond(exchange, 200, "{\"status\":\"UP\",\"reasons\":[]}");
        } else if (request.equals("GET /system/status")) {
            respond(exchange, 200,
                    "{\"git\":{\"commitId\":\"%s\"},\"build\":{\"version\":\"1.2.0\"},\"nodes\":{\"node-1\":{}}}"
                            .formatted(TESTED_COMMIT));
        } else if (request.equals("POST /v1/process-definitions")) {
            respond(exchange, deployStatus, deployAnswer);
        } else if (request.startsWith("GET /v1/process-definitions/")) {
            respond(exchange, 200, "{\"key\":7,\"version\":1,\"bpmnProcessId\":\"stand-in\",\"bpmnData\":\"<xml/>\"}");
        } else {
            respond(exchange, 404, "{\"code\":\"NOT_FOUND\",\"message\":\"%s\"}".formatted(request));
        }
    }

    private static void respond(
        final HttpExchange exchange,
        final int status,
        final String body
    ) throws IOException {
        final var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange
                .getResponseHeaders()
                .add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var answer = exchange.getResponseBody()) {
            answer.write(bytes);
        }
    }

}

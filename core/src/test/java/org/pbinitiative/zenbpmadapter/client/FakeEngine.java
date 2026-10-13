/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.client;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * An HTTP server standing in for the engine: it answers what a test told it to answer and
 * remembers every request. It binds a port the operating system picks, so it never collides with
 * another test.
 * <p>
 * A request nothing was told for is answered with status 418, which no call of the client
 * expects, so it fails the test.
 */
final class FakeEngine implements AutoCloseable {

    private final HttpServer server;

    private final ExecutorService handlers;

    private final Map<String, Handler> answers = new ConcurrentHashMap<>();

    private final List<Request> requests = new CopyOnWriteArrayList<>();

    private FakeEngine(
        final HttpServer server,
        final ExecutorService handlers
    ) {

        this.server = server;
        this.handlers = handlers;

    }

    /**
     * A started server.
     *
     * @return The server
     */
    static FakeEngine started() {

        try {
            final var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            // one thread per request, so an answer which takes its time does not hold up the next
            final var handlers = Executors.newCachedThreadPool();
            server.setExecutor(handlers);
            final var engine = new FakeEngine(server, handlers);
            server.createContext("/", engine::handle);
            server.start();
            return engine;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

    }

    /**
     * Where the server answers.
     *
     * @return The loopback address as a literal, e.g. <code>http://127.0.0.1:41234</code>
     */
    String address() {

        return "http://%s:%d".formatted(
                server
                        .getAddress()
                        .getAddress()
                        .getHostAddress(),
                server
                        .getAddress()
                        .getPort());

    }

    /**
     * Answers every request of a method and a path with a status and a JSON body.
     *
     * @param method The HTTP method
     * @param path The path without a query
     * @param status The status
     * @param body The body, <code>null</code> for none
     */
    void answers(
        final String method,
        final String path,
        final int status,
        final String body
    ) {

        answers(method, path, exchange -> respond(exchange, status, body));

    }

    /**
     * Answers every request of a method and a path the way a handler does.
     *
     * @param method The HTTP method
     * @param path The path without a query
     * @param handler What to do with the request
     */
    void answers(
        final String method,
        final String path,
        final Handler handler
    ) {

        answers.put("%s %s".formatted(method, path), handler);

    }

    /**
     * Every request received, in the order received.
     *
     * @return The requests
     */
    List<Request> requests() {

        return List.copyOf(requests);

    }

    /**
     * The one request received.
     *
     * @return The request
     * @throws AssertionError Where not exactly one request was received
     */
    Request onlyRequest() {

        if (requests.size() != 1) {
            throw new AssertionError("Expected one request, received %s".formatted(requests));
        }
        return requests.getFirst();

    }

    /**
     * Stops the server at once, interrupting the answers still running.
     */
    @Override
    public void close() {

        server.stop(0);
        handlers.shutdownNow();

    }

    /**
     * Writes an answer.
     *
     * @param exchange The exchange
     * @param status The status
     * @param body The JSON body, <code>null</code> for none
     * @throws IOException Where the connection broke
     */
    static void respond(
        final HttpExchange exchange,
        final int status,
        final String body
    ) throws IOException {

        if (body == null) {
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
            return;
        }
        final var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange
                .getResponseHeaders()
                .add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var responseBody = exchange.getResponseBody()) {
            responseBody.write(bytes);
        }

    }

    private void handle(
        final HttpExchange exchange
    ) throws IOException {

        final var method = exchange.getRequestMethod();
        final var path = exchange
                .getRequestURI()
                .getRawPath();
        final String body;
        try (var requestBody = exchange.getRequestBody()) {
            body = new String(requestBody.readAllBytes(), StandardCharsets.UTF_8);
        }
        requests.add(new Request(
                method, path, exchange
                        .getRequestURI()
                        .getRawQuery(), exchange
                                .getRequestHeaders()
                                .getFirst("Accept"), exchange
                                        .getRequestHeaders()
                                        .getFirst("Content-Type"), body));
        final var handler = answers.get("%s %s".formatted(method, path));
        if (handler == null) {
            respond(exchange, 418, "{\"code\":\"NOT_STUBBED\",\"message\":\"%s %s\"}".formatted(method, path));
            return;
        }
        handler.handle(exchange);

    }

    /**
     * What to do with a request.
     */
    @FunctionalInterface
    interface Handler {

        void handle(
            HttpExchange exchange
        ) throws IOException;

    }

    /**
     * One request as the server received it.
     *
     * @param method The HTTP method
     * @param path The raw path, without a query
     * @param query The raw query, <code>null</code> where there is none
     * @param accept The <code>Accept</code> header
     * @param contentType The <code>Content-Type</code> header, <code>null</code> where there is
     *            none
     * @param body The body, empty where there is none
     */
    record Request(
        String method,
        String path,
        String query,
        String accept,
        String contentType,
        String body
    ) {
    }

}

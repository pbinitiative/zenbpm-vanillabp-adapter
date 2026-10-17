/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.it;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A proxy in front of the engine's REST API which passes every request on, but answers the read of
 * one job (<code>GET /v1/jobs/{key}</code>) with <code>502</code>, as an engine without a leader for
 * the job's partition does. It stands for an engine which cannot be asked, for exactly the question
 * the probe of the open tasks asks, while everything else the adapter sends reaches the engine.
 */
final class JobReadsRefusingProxy implements AutoCloseable {

    private static final Pattern JOB_READ = Pattern.compile("/v1/jobs/\\d+");

    // the headers the JDK's client sets itself and refuses to be given
    private static final Set<String> RESTRICTED_HEADERS = Set.of("connection", "content-length", "expect", "host",
            "upgrade");

    private final Supplier<String> engineAddress;

    private final List<String> refused = new CopyOnWriteArrayList<>();

    private HttpServer server;

    private ExecutorService handlers;

    private HttpClient client;

    /**
     * A proxy which starts when its address is first asked for.
     *
     * @param engineAddress The address of the engine's REST API, asked when the proxy starts
     */
    JobReadsRefusingProxy(final Supplier<String> engineAddress) {
        this.engineAddress = engineAddress;
    }

    /**
     * Where the proxy answers, which starts it.
     *
     * @return E.g. <code>http://127.0.0.1:41234</code>
     */
    synchronized String address() {
        if (server == null) {
            start();
        }
        return "http://127.0.0.1:%d".formatted(server
                .getAddress()
                .getPort());
    }

    /**
     * The reads of a job the proxy answered with <code>502</code>.
     *
     * @return Their paths, in the order received
     */
    List<String> refusedReads() {
        return List.copyOf(refused);
    }

    @Override
    public synchronized void close() {
        if (server == null) {
            return;
        }
        server.stop(0);
        handlers.shutdownNow();
        client.close();
    }

    private void start() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        handlers = Executors.newCachedThreadPool();
        client = HttpClient.newHttpClient();
        server.setExecutor(handlers);
        server.createContext("/", this::handle);
        server.start();
    }

    private void handle(final HttpExchange exchange) throws IOException {
        try (exchange) {
            final var path = exchange
                    .getRequestURI()
                    .getRawPath();
            if ("GET".equals(exchange.getRequestMethod()) && JOB_READ
                    .matcher(path)
                    .matches()) {
                refused.add(path);
                respond(exchange, 502, "application/json",
                        "{\"code\":\"BAD_GATEWAY\",\"message\":\"refused by the test\"}"
                                .getBytes(StandardCharsets.UTF_8));
                return;
            }
            final var answer = passedOn(exchange);
            respond(exchange, answer.statusCode(), answer
                    .headers()
                    .firstValue("Content-Type")
                    .orElse(null), answer.body());
        }
    }

    private HttpResponse<byte[]> passedOn(final HttpExchange exchange) throws IOException {
        final var query = exchange
                .getRequestURI()
                .getRawQuery();
        final var request = HttpRequest.newBuilder(URI.create("%s%s%s".formatted(engineAddress.get(), exchange
                .getRequestURI()
                .getRawPath(), query == null ? "" : "?%s".formatted(query))));
        exchange
                .getRequestHeaders()
                .forEach((
                    name,
                    values) -> {
                    if (!RESTRICTED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                        values.forEach(value -> request.header(name, value));
                    }
                });
        final var body = exchange
                .getRequestBody()
                .readAllBytes();
        request.method(exchange.getRequestMethod(), body.length == 0 ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body));
        try {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (final InterruptedException e) {
            Thread
                    .currentThread()
                    .interrupt();
            throw new IOException(e);
        }
    }

    private static void respond(
        final HttpExchange exchange,
        final int status,
        final String contentType,
        final byte[] body
    ) throws IOException {
        if (contentType != null) {
            exchange
                    .getResponseHeaders()
                    .set("Content-Type", contentType);
        }
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            exchange
                    .getResponseBody()
                    .write(body);
        }
    }

}

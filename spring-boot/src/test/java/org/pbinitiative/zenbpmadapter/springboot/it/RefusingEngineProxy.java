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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A proxy in front of the engine's REST API which passes every request on, except those a test
 * told it to refuse: it answers them itself, with the status the test chose, as an engine without
 * a leader for the job's partition does with <code>502</code>, or one which failed with
 * <code>500</code>. So a test makes exactly the call of the adapter fail which its case is about,
 * while everything else the adapter sends reaches the engine, and no switch in the adapter's code
 * is needed for it.
 */
final class RefusingEngineProxy implements AutoCloseable {

    /**
     * How often a refusal applies where it applies to every matching request.
     */
    static final int ALWAYS = Integer.MAX_VALUE;

    /**
     * The read of one job.
     */
    static final Pattern JOB_READ = Pattern.compile("GET /v1/jobs/\\d+");

    /**
     * The extension of a job's lock.
     */
    static final Pattern LOCK_EXTENSION = Pattern.compile("POST /v1/jobs/\\d+/extend-lock");

    /**
     * The completion of a job.
     */
    static final Pattern COMPLETION = Pattern.compile("POST /v1/jobs/\\d+/complete");

    // the headers the JDK's client sets itself and refuses to be given
    private static final Set<String> RESTRICTED_HEADERS = Set.of("connection", "content-length", "expect", "host",
            "upgrade");

    private final Supplier<String> engineAddress;

    private final List<Refusal> refusals = new CopyOnWriteArrayList<>();

    private final List<String> refused = new CopyOnWriteArrayList<>();

    private HttpServer server;

    private ExecutorService handlers;

    private HttpClient client;

    /**
     * A proxy which starts when its address is first asked for, and refuses nothing until it is
     * told to.
     *
     * @param engineAddress The address of the engine's REST API, asked when the proxy starts
     */
    RefusingEngineProxy(final Supplier<String> engineAddress) {
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
     * Refuses the requests a pattern matches from now on.
     *
     * @param request What is refused: the method, a blank and the path, e.g. {@link #COMPLETION}
     * @param status The status the proxy answers with, e.g. <code>502</code>
     * @param times How many of those requests are refused, {@link #ALWAYS} for every one
     */
    void refuse(
        final Pattern request,
        final int status,
        final int times
    ) {
        refusals.add(new Refusal(request, status, new AtomicInteger(times)));
    }

    /**
     * Passes every request on again, as before the first refusal.
     */
    void refuseNothing() {
        refusals.clear();
    }

    /**
     * Passes every request on again and forgets the requests refused so far, so that the next test
     * of a class sharing the proxy reads only what was refused in its own case.
     */
    void startAfresh() {
        refuseNothing();
        refused.clear();
    }

    /**
     * The requests the proxy refused.
     *
     * @return Each as its method, a blank and its path, in the order received
     */
    List<String> refused() {
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
            final var request = "%s %s".formatted(exchange.getRequestMethod(), exchange
                    .getRequestURI()
                    .getRawPath());
            final var refusal = refusals
                    .stream()
                    .filter(candidate -> candidate.takes(request))
                    .findFirst();
            if (refusal.isPresent()) {
                refused.add(request);
                respond(exchange, refusal
                        .get()
                        .status(), "application/json",
                        "{\"code\":\"REFUSED_BY_THE_TEST\",\"message\":\"refused by the test\"}"
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

    private record Refusal(
        Pattern request,
        int status,
        AtomicInteger left
    ) {

        // a refusal which applies takes one of the requests it may refuse
        private boolean takes(final String candidate) {
            return request
                    .matcher(candidate)
                    .matches() && (left.getAndUpdate(times -> times == ALWAYS ? ALWAYS : Math.max(0, times - 1)) > 0);
        }

    }

}

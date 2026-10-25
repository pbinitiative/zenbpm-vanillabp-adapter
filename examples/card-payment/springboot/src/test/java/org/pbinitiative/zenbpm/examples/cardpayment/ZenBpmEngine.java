/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.cardpayment;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import com.jayway.jsonpath.JsonPath;

/**
 * A ZenBPM engine for the integration test, started from the image the build names in the
 * property <code>zenbpm.image</code>, with the settings of the README's <code>docker run</code>.
 * It also does what an operator does in zenbpm-ui: find the incidents of a workflow, and resolve
 * one.
 */
final class ZenBpmEngine {

    private static final int REST_PORT = 8080;

    private static final int GRPC_PORT = 9090;

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private ZenBpmEngine() {
    }

    static GenericContainer<?> container() {
        final var image = System.getProperty("zenbpm.image");
        if (image == null || image.isBlank()) {
            throw new IllegalStateException(
                    "No engine image: run the test with 'mvn verify', which passes the property zenbpm.image of the POM");
        }
        return new GenericContainer<>(DockerImageName.parse(image))
                .withExposedPorts(REST_PORT, GRPC_PORT)
                // a timer is looked at every second instead of every ten
                .withEnv("POLL_TIMER_DELAY_SECONDS", "1")
                // 503 until the node is the leader and its partitions are ready
                .waitingFor(Wait
                        .forHttp("/system/health/ready")
                        .forPort(REST_PORT)
                        .forStatusCode(200)
                        .withStartupTimeout(Duration.ofMinutes(2)));
    }

    static String restAddress(final GenericContainer<?> engine) {
        return "http://%s:%d".formatted(engine.getHost(), engine.getMappedPort(REST_PORT));
    }

    static String grpcAddress(final GenericContainer<?> engine) {
        return "%s:%d".formatted(engine.getHost(), engine.getMappedPort(GRPC_PORT));
    }

    /**
     * The incidents nobody resolved of the workflow whose business key is given, the id of its
     * workflow aggregate.
     */
    static List<Long> unresolvedIncidents(
        final GenericContainer<?> engine,
        final String businessKey
    ) {
        final List<Number> instances = JsonPath.read(get(engine, "/v1/process-instances?businessKey=%s"
                .formatted(businessKey)), "$.partitions[*].items[*].key");
        return instances
                .stream()
                .flatMap(instance -> JsonPath
                        .<List<Number>>read(get(engine, "/v1/process-instances/%s/incidents?state=unresolved"
                                .formatted(instance)), "$.items[*].key")
                        .stream())
                .map(Number::longValue)
                .toList();
    }

    /**
     * Resolves an incident, which gives its task a fresh series of attempts.
     */
    static void resolve(
        final GenericContainer<?> engine,
        final long incident
    ) {
        send(engine, HttpRequest
                .newBuilder(URI.create("%s/v1/incidents/%d/resolve".formatted(restAddress(engine), incident)))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build());
    }

    private static String get(
        final GenericContainer<?> engine,
        final String path
    ) {
        return send(engine, HttpRequest
                .newBuilder(URI.create("%s%s".formatted(restAddress(engine), path)))
                .GET()
                .build());
    }

    private static String send(
        final GenericContainer<?> engine,
        final HttpRequest request
    ) {
        try {
            final var response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new IllegalStateException("%s %s answered %d: %s".formatted(request.method(), request.uri(),
                        response.statusCode(), response.body()));
            }
            return response.body();
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        } catch (final InterruptedException e) {
            Thread
                    .currentThread()
                    .interrupt();
            throw new IllegalStateException(e);
        }
    }

}

/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.banktransferorder;

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
 * It also tells where the workflow of an order stands, as zenbpm-ui shows it.
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
     * The states of the workflows whose business key is given, the id of their workflow aggregate:
     * <code>active</code>, <code>completed</code>, <code>terminated</code> or <code>failed</code>.
     */
    static List<String> workflowStates(
        final GenericContainer<?> engine,
        final String businessKey
    ) {
        final var request = HttpRequest
                .newBuilder(URI.create("%s/v1/process-instances?businessKey=%s".formatted(restAddress(engine),
                        businessKey)))
                .GET()
                .build();
        try {
            final var response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new IllegalStateException("GET %s answered %d: %s".formatted(request.uri(), response
                        .statusCode(), response.body()));
            }
            return JsonPath.read(response.body(), "$.partitions[*].items[*].state");
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

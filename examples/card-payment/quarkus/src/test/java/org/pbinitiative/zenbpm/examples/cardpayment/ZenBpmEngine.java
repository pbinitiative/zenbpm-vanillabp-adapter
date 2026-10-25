/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.cardpayment;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.lessThan;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

/**
 * A ZenBPM engine for the integration test, started from the image the build names in the
 * property <code>zenbpm.image</code>, with the settings of the README's <code>docker run</code>.
 * Quarkus starts it before the application and hands the application its addresses. It also does
 * what an operator does in zenbpm-ui: find the incidents of a workflow, and resolve one.
 */
public class ZenBpmEngine implements QuarkusTestResourceLifecycleManager {

    private static final int REST_PORT = 8080;

    private static final int GRPC_PORT = 9090;

    private static volatile String restAddress;

    private GenericContainer<?> engine;

    @Override
    public Map<String, String> start() {
        final var image = System.getProperty("zenbpm.image");
        if (image == null || image.isBlank()) {
            throw new IllegalStateException(
                    "No engine image: run the test with 'mvn verify', which passes the property zenbpm.image of the POM");
        }
        engine = new GenericContainer<>(DockerImageName.parse(image))
                .withExposedPorts(REST_PORT, GRPC_PORT)
                // a timer is looked at every second instead of every ten
                .withEnv("POLL_TIMER_DELAY_SECONDS", "1")
                // 503 until the node is the leader and its partitions are ready
                .waitingFor(Wait
                        .forHttp("/system/health/ready")
                        .forPort(REST_PORT)
                        .forStatusCode(200)
                        .withStartupTimeout(Duration.ofMinutes(2)));
        engine.start();
        restAddress = "http://%s:%d".formatted(engine.getHost(), engine.getMappedPort(REST_PORT));
        return Map.of(
                "vanillabp.adapters.zenbpm.rest-address",
                restAddress,
                "vanillabp.adapters.zenbpm.grpc-address",
                "%s:%d".formatted(engine.getHost(), engine.getMappedPort(GRPC_PORT)));
    }

    @Override
    public void stop() {
        if (engine != null) {
            engine.stop();
        }
    }

    /**
     * The incidents nobody resolved of the workflow whose business key is given, the id of its
     * workflow aggregate.
     */
    static List<Long> unresolvedIncidents(final String businessKey) {
        final List<Number> instances = given()
                .baseUri(restAddress)
                .queryParam("businessKey", businessKey)
                .get("/v1/process-instances")
                .then()
                .statusCode(200)
                .extract()
                .path("partitions.items.flatten().key");
        return instances
                .stream()
                .flatMap(instance -> given()
                        .baseUri(restAddress)
                        .queryParam("state", "unresolved")
                        .get("/v1/process-instances/{key}/incidents", instance.toString())
                        .then()
                        .statusCode(200)
                        .extract()
                        .<List<Number>>path("items.key")
                        .stream())
                .map(Number::longValue)
                .toList();
    }

    /**
     * Resolves an incident, which gives its task a fresh series of attempts.
     */
    static void resolve(final long incident) {
        given()
                .baseUri(restAddress)
                .post("/v1/incidents/{key}/resolve", Long.toString(incident))
                .then()
                .statusCode(lessThan(300));
    }

}

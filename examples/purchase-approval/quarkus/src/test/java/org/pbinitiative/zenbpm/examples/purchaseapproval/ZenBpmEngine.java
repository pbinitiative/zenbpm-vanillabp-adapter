/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpm.examples.purchaseapproval;

import java.time.Duration;
import java.util.Map;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

/**
 * A ZenBPM engine for the integration test, started from the image the build names in the
 * property <code>zenbpm.image</code>, with the settings of the README's <code>docker run</code>.
 * Quarkus starts it before the application and hands the application its addresses.
 */
public class ZenBpmEngine implements QuarkusTestResourceLifecycleManager {

    private static final int REST_PORT = 8080;

    private static final int GRPC_PORT = 9090;

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
        return Map.of(
                "vanillabp.adapters.zenbpm.rest-address",
                "http://%s:%d".formatted(engine.getHost(), engine.getMappedPort(REST_PORT)),
                "vanillabp.adapters.zenbpm.grpc-address",
                "%s:%d".formatted(engine.getHost(), engine.getMappedPort(GRPC_PORT)));
    }

    @Override
    public void stop() {
        if (engine != null) {
            engine.stop();
        }
    }

}

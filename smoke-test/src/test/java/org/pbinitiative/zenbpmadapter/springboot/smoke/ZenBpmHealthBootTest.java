/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.smoke;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.context.ConfigurableApplicationContext;

import io.vanillabp.integration.health.VanillaBpHealthIndicator;
import io.vanillabp.integration.test.utils.OneFreePortPerJvm;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What the health endpoint of a Spring Boot application says about a ZenBPM adapter id, without an
 * engine: an adapter id which is not configured is UNKNOWN, and an engine nothing answers for is
 * DOWN within the request timeout.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmHealthBootTest {

    @Test
    @DisplayName("An adapter id without an address is UNKNOWN, with the message which names the key")
    void anUnconfiguredAdapterIdIsUnknown() {
        try (var context = run("vanillabp.prioritized-adapters=zenbpm")) {
            final var detail = detailOf(context
                    .getBean(VanillaBpHealthIndicator.class)
                    .health(), "zenbpm");

            assertAll(
                    () -> assertEquals(Status.UNKNOWN.getCode(), detail.get("status")),
                    () -> assertEquals("zenbpm", detail.get("type")),
                    () -> assertTrue(String
                            .valueOf(detail.get("description"))
                            .contains("'vanillabp.adapters.zenbpm.rest-address' is missing"), String.valueOf(detail)));
        }
    }

    @Test
    @DisplayName("An engine nothing answers for is DOWN within the request timeout, naming the address and the job stream")
    void anEngineNothingAnswersForIsDown() {
        final var address = "http://localhost:%d".formatted(OneFreePortPerJvm.getPort());
        try (var context = run("vanillabp.prioritized-adapters=zenbpm", "vanillabp.adapters.zenbpm.rest-address="
                + address, "vanillabp.adapters.zenbpm.request-timeout=PT1S")) {
            final var indicator = context.getBean(VanillaBpHealthIndicator.class);

            final Health health = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> indicator.health());
            final var detail = detailOf(health, "zenbpm");

            assertAll(
                    () -> assertEquals(Status.DOWN, health.getStatus()),
                    () -> assertEquals(Status.DOWN.getCode(), detail.get("status")),
                    () -> assertEquals(address, detail.get("address")),
                    () -> assertEquals("PT1S", detail.get("requestTimeout")),
                    () -> assertNotNull(detail.get("jobStream"), String.valueOf(detail)));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> detailOf(
        final Health health,
        final String adapterId
    ) {
        final var detail = (Map<String, Object>) health
                .getDetails()
                .get(adapterId);
        assertNotNull(detail, "the detail is named after the adapter id: %s".formatted(health.getDetails()));
        return detail;
    }

    private static ConfigurableApplicationContext run(final String... properties) {
        return new SpringApplicationBuilder(SmokeTestApplication.class)
                .web(WebApplicationType.NONE)
                .run(Stream
                        .of(properties)
                        .map("--%s"::formatted)
                        .toArray(String[]::new));
    }

}

/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.smoke;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientRegistry;
import org.pbinitiative.zenbpmadapter.deployment.ZenBpmDeploymentService;
import org.pbinitiative.zenbpmadapter.processservice.ZenBpmProcessService;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * An application with the ZenBPM adapter on its classpath which configures no adapter id of type
 * <code>zenbpm</code>: its only adapter is the platform's BPMS double. It boots, the adapter has
 * no beans and an empty registry, and it says nothing. The application starts inside the test, so
 * the output of its start is always part of what the test captured.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmWithoutAdapterIdsBootTest {

    @Test
    @DisplayName("Without an adapter id of type zenbpm the application boots, and the adapter has nothing and says nothing")
    void noAdapterIdMeansNothing(final CapturedOutput output) {
        try (var context = run("vanillabp.prioritized-adapters=dummy", "vanillabp.adapters.dummy.type=dummy")) {
            final var log = output.getAllOfThisTest();

            assertAll(
                    () -> assertEquals(List.of(), context
                            .getBean(ZenBpmClientRegistry.class)
                            .factories()),
                    () -> assertEquals(0, context.getBeanNamesForType(ZenBpmDeploymentService.class).length),
                    () -> assertEquals(0, context.getBeanNamesForType(ZenBpmProcessService.class).length),
                    () -> assertFalse(log.contains("zenbpm adapter '"), log));
        }
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

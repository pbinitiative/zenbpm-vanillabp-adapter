/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.smoke;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientFactory;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientRegistry;
import org.pbinitiative.zenbpmadapter.deployment.ZenBpmDeploymentService;
import org.pbinitiative.zenbpmadapter.processservice.ZenBpmProcessService;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * An adapter id of type <code>zenbpm</code> beside one of another type, the setup of a migration
 * from another BPMS to ZenBPM: the platform's BPMS double stands in for the other one, and both
 * write a key of their own under <code>vanillabp.adapters.</code>. The platform finds the services
 * of both, and the ZenBPM adapter holds nothing of the other id. The application starts inside the
 * test, so the output of its start is always part of what the test captured.
 */
@ExtendWith(SuppressOutputExtension.class)
class ZenBpmBesideAnotherAdapterTypeBootTest {

    @Test
    @DisplayName("Beside an adapter id of another type, the platform finds both, and the ZenBPM adapter has only its own")
    void bothAdapterTypesAreFound(final CapturedOutput output) {
        try (var context = run("vanillabp.prioritized-adapters=zenbpm,dummy", "vanillabp.adapters.zenbpm.type=zenbpm",
                "vanillabp.adapters.zenbpm.rest-address=http://localhost:8080", "vanillabp.adapters.dummy.type=dummy",
                "vanillabp.adapters.dummy.test=7")) {
            final var log = output.getAllOfThisTest();

            assertAll(
                    () -> assertEquals(List.of("dummy", "zenbpm"), context
                            .getBeanProvider(AdapterDeploymentService.class)
                            .orderedStream()
                            .map(AdapterDeploymentService::getAdapterId)
                            .sorted()
                            .toList()),
                    () -> assertEquals(List.of("dummy", "zenbpm"), context
                            .getBeanProvider(MigratableProcessService.class)
                            .orderedStream()
                            .map(MigratableProcessService::getAdapterId)
                            .sorted()
                            .toList()),
                    () -> assertEquals(List.of("zenbpm"), context
                            .getBeansOfType(ZenBpmDeploymentService.class)
                            .values()
                            .stream()
                            .map(ZenBpmDeploymentService::getAdapterId)
                            .toList()),
                    () -> assertEquals(List.of("zenbpm"), context
                            .getBeansOfType(ZenBpmProcessService.class)
                            .values()
                            .stream()
                            .map(ZenBpmProcessService::getAdapterId)
                            .toList()),
                    () -> assertEquals(List.of("zenbpm"), context
                            .getBean(ZenBpmClientRegistry.class)
                            .factories()
                            .stream()
                            .map(ZenBpmClientFactory::getAdapterId)
                            .toList()),
                    () -> assertTrue(StartupBlock.isWritten(log), log),
                    () -> assertEquals("", StartupBlock.configuration(log), log));
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

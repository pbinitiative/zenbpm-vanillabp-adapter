/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.springboot.smoke;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientFactory;
import org.pbinitiative.zenbpmadapter.client.ZenBpmClientRegistry;
import org.pbinitiative.zenbpmadapter.deployment.ZenBpmDeploymentService;
import org.pbinitiative.zenbpmadapter.processservice.ZenBpmProcessService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Two adapter ids of type <code>zenbpm</code>, the setup of a migration from one engine to another:
 * each id gets its own process service, deployment service and factory, named after the id.
 */
@ExtendWith(SuppressOutputExtension.class)
@SpringBootTest(classes = SmokeTestApplication.class, properties = {
        "vanillabp.prioritized-adapters=zenbpm-new,zenbpm-old", "vanillabp.adapters.zenbpm-new.type=zenbpm", "vanillabp.adapters.zenbpm-new.rest-address=http://new-engine:8080", "vanillabp.adapters.zenbpm-old.type=zenbpm", "vanillabp.adapters.zenbpm-old.rest-address=http://old-engine:8080"
})
class ZenBpmTwoAdapterIdsDiscoveryTest {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("Each adapter id has its own two services, named after it")
    void eachAdapterIdHasItsOwnServices() {
        assertAll(
                () -> assertEquals("zenbpm-new", context
                        .getBean("ZenBpm_DeploymentService_zenbpm-new", ZenBpmDeploymentService.class)
                        .getAdapterId()),
                () -> assertEquals("zenbpm-old", context
                        .getBean("ZenBpm_DeploymentService_zenbpm-old", ZenBpmDeploymentService.class)
                        .getAdapterId()),
                () -> assertEquals("zenbpm-new", context
                        .getBean("ZenBpm_ProcessService_zenbpm-new", ZenBpmProcessService.class)
                        .getAdapterId()),
                () -> assertEquals("zenbpm-old", context
                        .getBean("ZenBpm_ProcessService_zenbpm-old", ZenBpmProcessService.class)
                        .getAdapterId()));
    }

    @Test
    @DisplayName("The platform's streams find both of each, and the registry holds a factory with clients per id")
    void thePlatformFindsBothOfEach() {
        final var factories = context
                .getBean(ZenBpmClientRegistry.class)
                .factories();

        assertAll(
                () -> assertEquals(List.of("zenbpm-new", "zenbpm-old"), context
                        .getBeanProvider(AdapterDeploymentService.class)
                        .orderedStream()
                        .map(AdapterDeploymentService::getAdapterId)
                        .sorted()
                        .toList()),
                () -> assertEquals(List.of("zenbpm-new", "zenbpm-old"), context
                        .getBeanProvider(MigratableProcessService.class)
                        .orderedStream()
                        .map(MigratableProcessService::getAdapterId)
                        .sorted()
                        .toList()),
                () -> assertEquals(List.of("zenbpm-new", "zenbpm-old"), factories
                        .stream()
                        .map(ZenBpmClientFactory::getAdapterId)
                        .toList()),
                () -> assertTrue(factories
                        .stream()
                        .allMatch(ZenBpmClientFactory::isConfigured)));
    }

}
